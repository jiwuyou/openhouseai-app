package com.termux.app.openhouse;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import com.termux.app.OpenCodeSettings;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class OpenHouseStatusRepository {

    private static final String LOG_TAG = "OpenHouseStatus";
    private static final String ONBOARDING_PREFS_NAME = "openhouse_onboarding";
    private static final String KEY_CURRENT_STEP = "current_step";
    private static final String KEY_PERMISSIONS_SKIPPED = "permissions_skipped";
    private static final String KEY_KEY_SKIPPED = "key_skipped";
    private static final String KEY_CONFIGURATION_SKIPPED = "configuration_skipped";
    private static final String KEY_KEY_SAVED = "key_saved";
    private static final String KEY_DEEPSEEK_CONFIGURED = "deepseek_configured";
    private static final String KEY_LAUNCH_CONFIRMED = "launch_confirmed";
    private static final String KEY_OVERLAY_STEP = "step";
    private static final String KEY_OVERLAY_BATTERY_SKIPPED = "battery_skipped";
    private static final String KEY_OVERLAY_DEEPSEEK_KEY_SKIPPED = "deepseek_key_skipped";
    private static final String KEY_OVERLAY_DEEPSEEK_CONFIG_SKIPPED = "deepseek_config_skipped";

    private final Context context;

    public OpenHouseStatusRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public OpenHouseStatus loadStatus() {
        String diagnostic = "";
        ShellCheckResult ubuntuCheck = runTermuxCommand("proot-distro login ubuntu -- true", 10);
        if (!ubuntuCheck.isSuccess()) {
            diagnostic = ubuntuCheck.output;
        }
        boolean entryUbuntuConfigured = runTermuxCommand("test \"$(tr -d '[:space:]' < \"$HOME/.openhouseai/entry-mode\" 2>/dev/null || true)\" = ubuntu && test -f \"$HOME/.openhouseai/entry.sh\" && grep -Fq '# OpenHouseAI startup entry' \"$HOME/.bashrc\"", 8).isSuccess();
        boolean openCodeInstalled = runTermuxCommand("proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:$PATH\"; (command -v opencode >/dev/null 2>&1 || test -x \"$HOME/.opencode/bin/opencode\") && test -f \"$HOME/openhouseai-links/docs-path.txt\" && test -f \"$HOME/openhouseai-links/workspace-path.txt\"'", 12).isSuccess();
        boolean codexInstalled = runTermuxCommand("proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH\"; command -v codex >/dev/null 2>&1'", 12).isSuccess();
        boolean claudeCodeInstalled = runTermuxCommand("proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH\"; command -v claude >/dev/null 2>&1'", 12).isSuccess();
        boolean reasonixInstalled = runTermuxCommand("proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH\"; command -v reasonix >/dev/null 2>&1'", 12).isSuccess();
        boolean deepSeekConfigured = runTermuxCommand("proot-distro login ubuntu -- bash -lc 'test -s \"$HOME/.config/openhouseai/deepseek-api-key\" && test -f \"$HOME/.config/opencode/opencode.json\" && test -f \"$HOME/.reasonix/config.json\" && grep -Fq \"ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic\" \"$HOME/.bashrc\"'", 12).isSuccess();
        boolean openCodeReachable = runTermuxCommand("proot-distro login ubuntu -- bash -lc 'curl -fsS --max-time 3 http://127.0.0.1:" + OpenCodeSettings.DEFAULT_OPENCODE_PORT + "/ >/dev/null 2>&1'", 8).isSuccess();
        boolean openCodeRunningInRoot = openCodeReachable && isOpenCodeRunningInRoot();

        return new OpenHouseStatus(
            isBatteryOptimizationIgnored(),
            isProductPrepared(),
            ubuntuCheck.isSuccess(),
            isOfficialDocsSynced(),
            entryUbuntuConfigured,
            openCodeInstalled,
            codexInstalled,
            claudeCodeInstalled,
            reasonixInstalled,
            deepSeekConfigured,
            openCodeReachable,
            hasSavedDeepSeekKey(),
            getOnboardingPrefs().getBoolean(KEY_LAUNCH_CONFIRMED, false),
            openCodeRunningInRoot,
            OpenCodeSettings.DEFAULT_OPENCODE_PORT,
            OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY,
            diagnostic
        );
    }

    public OpenHouseOnboardingState loadOnboardingState() {
        OpenHouseInstallState installState = OpenHouseInstallState.idle();
        try {
            installState = OpenHouseInstallController.getInstance(context).getState();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read one-click install state", e);
        }
        return loadOnboardingState(installState, null);
    }

    public OpenHouseOnboardingState loadOnboardingState(OpenHouseInstallState installState, OpenHouseStatus status) {
        SharedPreferences preferences = getOnboardingPrefs();
        OpenHouseOnboardingState.Step storedStep = readStoredStep(preferences);
        boolean permissionsSkipped = preferences.getBoolean(KEY_PERMISSIONS_SKIPPED, false)
            || preferences.getBoolean(KEY_OVERLAY_BATTERY_SKIPPED, false);
        boolean keySkipped = preferences.getBoolean(KEY_KEY_SKIPPED, false)
            || preferences.getBoolean(KEY_OVERLAY_DEEPSEEK_KEY_SKIPPED, false);
        boolean configurationSkipped = preferences.getBoolean(KEY_CONFIGURATION_SKIPPED, false)
            || preferences.getBoolean(KEY_OVERLAY_DEEPSEEK_CONFIG_SKIPPED, false);
        boolean keySaved = hasSavedDeepSeekKey()
            || (status != null && status.deepSeekKeySaved);
        boolean deepSeekConfigured = preferences.getBoolean(KEY_DEEPSEEK_CONFIGURED, false)
            || (status != null && status.deepSeekConfigured);
        boolean launchConfirmed = preferences.getBoolean(KEY_LAUNCH_CONFIRMED, false)
            || (status != null && status.launchConfirmed);
        if (deepSeekConfigured) {
            keySaved = true;
        }

        OpenHouseOnboardingState.Step effectiveStep = resolveEffectiveStep(
            storedStep,
            permissionsSkipped,
            keySkipped,
            configurationSkipped,
            keySaved,
            deepSeekConfigured,
            launchConfirmed,
            installState,
            status);
        return new OpenHouseOnboardingState(
            effectiveStep,
            permissionsSkipped,
            keySkipped,
            configurationSkipped,
            keySaved,
            deepSeekConfigured,
            launchConfirmed,
            installState,
            status);
    }

    public OpenHouseOnboardingState setCurrentOnboardingStep(OpenHouseOnboardingState.Step step) {
        OpenHouseOnboardingState.Step resolvedStep = step == null
            ? OpenHouseOnboardingState.Step.PERMISSIONS
            : step;
        getOnboardingPrefs().edit()
            .putInt(KEY_CURRENT_STEP, resolvedStep.number)
            .putString(KEY_OVERLAY_STEP, toOverlayStepName(resolvedStep))
            .apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState markPermissionsSkipped(boolean skipped) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_PERMISSIONS_SKIPPED, skipped)
            .putBoolean(KEY_OVERLAY_BATTERY_SKIPPED, skipped);
        if (skipped) {
            putStepAtLeast(editor, OpenHouseOnboardingState.Step.ONE_CLICK_INSTALL);
        }
        editor.apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState skipPermissions() {
        return markPermissionsSkipped(true);
    }

    public OpenHouseOnboardingState markDeepSeekKeySkipped(boolean skipped) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_KEY_SKIPPED, skipped)
            .putBoolean(KEY_OVERLAY_DEEPSEEK_KEY_SKIPPED, skipped);
        if (skipped) {
            putStepAtLeastAfterInstallGate(
                editor,
                OpenHouseOnboardingState.Step.WAITING_INSTALL,
                OpenHouseOnboardingState.Step.DEEPSEEK_CONFIGURATION);
        }
        editor.apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState skipDeepSeekKey() {
        return markDeepSeekKeySkipped(true);
    }

    public OpenHouseOnboardingState markDeepSeekConfigurationSkipped(boolean skipped) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_CONFIGURATION_SKIPPED, skipped)
            .putBoolean(KEY_OVERLAY_DEEPSEEK_CONFIG_SKIPPED, skipped);
        if (skipped) {
            putStepAtLeastAfterInstallGate(
                editor,
                OpenHouseOnboardingState.Step.WAITING_INSTALL,
                OpenHouseOnboardingState.Step.OPENCODE_LAUNCH);
        }
        editor.apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState skipDeepSeekConfiguration() {
        return markDeepSeekConfigurationSkipped(true);
    }

    public OpenHouseOnboardingState markDeepSeekKeySaved(boolean saved) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_KEY_SAVED, saved);
        if (saved) {
            editor.putBoolean(KEY_KEY_SKIPPED, false);
            editor.putBoolean(KEY_CONFIGURATION_SKIPPED, false);
            editor.putBoolean(KEY_OVERLAY_DEEPSEEK_KEY_SKIPPED, false);
            editor.putBoolean(KEY_OVERLAY_DEEPSEEK_CONFIG_SKIPPED, false);
            putStepAtLeastAfterInstallGate(
                editor,
                OpenHouseOnboardingState.Step.WAITING_INSTALL,
                OpenHouseOnboardingState.Step.DEEPSEEK_CONFIGURATION);
        }
        editor.apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState markDeepSeekConfigured(boolean configured) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_DEEPSEEK_CONFIGURED, configured);
        if (configured) {
            editor.putBoolean(KEY_KEY_SAVED, true);
            editor.putBoolean(KEY_KEY_SKIPPED, false);
            editor.putBoolean(KEY_CONFIGURATION_SKIPPED, false);
            editor.putBoolean(KEY_OVERLAY_DEEPSEEK_KEY_SKIPPED, false);
            editor.putBoolean(KEY_OVERLAY_DEEPSEEK_CONFIG_SKIPPED, false);
            putStepAtLeastAfterInstallGate(
                editor,
                OpenHouseOnboardingState.Step.WAITING_INSTALL,
                OpenHouseOnboardingState.Step.OPENCODE_LAUNCH);
        }
        editor.apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState markLaunchConfirmed(boolean confirmed) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_LAUNCH_CONFIRMED, confirmed);
        if (confirmed) {
            putStepAtLeastAfterInstallGate(
                editor,
                OpenHouseOnboardingState.Step.WAITING_INSTALL,
                OpenHouseOnboardingState.Step.OPENCODE_LAUNCH);
        }
        editor.apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState markOneClickInstallStarted() {
        getOnboardingPrefs().edit()
            .putInt(KEY_CURRENT_STEP, OpenHouseOnboardingState.Step.READING_GUIDE.number)
            .putString(KEY_OVERLAY_STEP, toOverlayStepName(OpenHouseOnboardingState.Step.READING_GUIDE))
            .apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState markOneClickInstallCompleted() {
        SharedPreferences preferences = getOnboardingPrefs();
        boolean keySaved = hasSavedDeepSeekKey();
        boolean keySkipped = preferences.getBoolean(KEY_KEY_SKIPPED, false)
            || preferences.getBoolean(KEY_OVERLAY_DEEPSEEK_KEY_SKIPPED, false);
        boolean configurationSkipped = preferences.getBoolean(KEY_CONFIGURATION_SKIPPED, false)
            || preferences.getBoolean(KEY_OVERLAY_DEEPSEEK_CONFIG_SKIPPED, false);
        boolean deepSeekConfigured = preferences.getBoolean(KEY_DEEPSEEK_CONFIGURED, false);
        OpenHouseOnboardingState.Step nextStep = OpenHouseOnboardingState.Step.DEEPSEEK_KEY;
        if (deepSeekConfigured || configurationSkipped) {
            nextStep = OpenHouseOnboardingState.Step.OPENCODE_LAUNCH;
        } else if (keySaved || keySkipped) {
            nextStep = OpenHouseOnboardingState.Step.DEEPSEEK_CONFIGURATION;
        }

        preferences.edit()
            .putInt(KEY_CURRENT_STEP, nextStep.number)
            .putString(KEY_OVERLAY_STEP, toOverlayStepName(nextStep))
            .apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState resetOnboardingState() {
        getOnboardingPrefs().edit().clear().apply();
        return loadOnboardingState();
    }

    public boolean hasSavedDeepSeekKey() {
        File keyFile = getSavedDeepSeekKeyFile();
        return keyFile.isFile() && keyFile.length() > 0L;
    }

    public static File getOpenHouseStateDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".openhouseai");
    }

    public static File getSavedDeepSeekKeyFile() {
        return new File(getOpenHouseStateDir(), "deepseek-api-key.saved");
    }

    public static File getDeepSeekKeyTempFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs/deepseek-api-key.tmp");
    }

    private boolean isBatteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private boolean isProductPrepared() {
        File docsDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs");
        File workspaceDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "workspace");
        return docsDir.isDirectory()
            && workspaceDir.isDirectory()
            && new File(docsDir, "README.md").isFile()
            && new File(docsDir, "ENVIRONMENT.md").isFile()
            && new File(docsDir, "MODEL_API_SETUP.md").isFile();
    }

    private boolean isOfficialDocsSynced() {
        File officialDocsDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs/official");
        File agentNotesDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs/agent-notes");
        return officialDocsDir.isDirectory()
            && agentNotesDir.isDirectory()
            && new File(officialDocsDir, "START_HERE.md").isFile()
            && new File(officialDocsDir, "ENVIRONMENT.md").isFile()
            && new File(officialDocsDir, "MODEL_API_SETUP.md").isFile();
    }

    private OpenHouseOnboardingState.Step resolveEffectiveStep(OpenHouseOnboardingState.Step storedStep,
                                                               boolean permissionsSkipped,
                                                               boolean keySkipped,
                                                               boolean configurationSkipped,
                                                               boolean keySaved,
                                                               boolean deepSeekConfigured,
                                                               boolean launchConfirmed,
                                                               OpenHouseInstallState installState,
                                                               OpenHouseStatus status) {
        OpenHouseOnboardingState.Step effectiveStep = storedStep == null
            ? OpenHouseOnboardingState.Step.PERMISSIONS
            : storedStep;
        boolean installRunning = installState != null && installState.running;
        boolean installDone = isInstallDone(installState, status);

        if (!installDone) {
            if (keySaved
                || keySkipped
                || configurationSkipped
                || deepSeekConfigured
                || launchConfirmed
                || effectiveStep.number >= OpenHouseOnboardingState.Step.WAITING_INSTALL.number) {
                return OpenHouseOnboardingState.Step.WAITING_INSTALL;
            }

            if (installRunning && effectiveStep.number < OpenHouseOnboardingState.Step.READING_GUIDE.number) {
                return OpenHouseOnboardingState.Step.READING_GUIDE;
            }
            if (installRunning) {
                return effectiveStep;
            }
        }

        if (launchConfirmed) {
            return OpenHouseOnboardingState.Step.OPENCODE_LAUNCH;
        }

        if (installState != null && installState.running) {
            return effectiveStep.number < OpenHouseOnboardingState.Step.READING_GUIDE.number
                ? OpenHouseOnboardingState.Step.READING_GUIDE
                : effectiveStep;
        }
        if (permissionsSkipped && effectiveStep.number < OpenHouseOnboardingState.Step.ONE_CLICK_INSTALL.number) {
            effectiveStep = OpenHouseOnboardingState.Step.ONE_CLICK_INSTALL;
        }
        if (installDone
            && effectiveStep.number < OpenHouseOnboardingState.Step.DEEPSEEK_KEY.number) {
            effectiveStep = OpenHouseOnboardingState.Step.DEEPSEEK_KEY;
        }

        if (installDone
            && (deepSeekConfigured || configurationSkipped)
            && effectiveStep.number < OpenHouseOnboardingState.Step.OPENCODE_LAUNCH.number) {
            effectiveStep = OpenHouseOnboardingState.Step.OPENCODE_LAUNCH;
        } else if (installDone
            && (keySaved || keySkipped)
            && !deepSeekConfigured
            && !configurationSkipped
            && effectiveStep.number < OpenHouseOnboardingState.Step.DEEPSEEK_CONFIGURATION.number) {
            effectiveStep = OpenHouseOnboardingState.Step.DEEPSEEK_CONFIGURATION;
        }

        if (installDone
            && status != null
            && status.openCodeReachable
            && effectiveStep.number < OpenHouseOnboardingState.Step.OPENCODE_LAUNCH.number) {
            effectiveStep = OpenHouseOnboardingState.Step.OPENCODE_LAUNCH;
        }
        return effectiveStep;
    }

    private void putStepAtLeast(SharedPreferences.Editor editor, OpenHouseOnboardingState.Step step) {
        SharedPreferences preferences = getOnboardingPrefs();
        int storedStep = readStoredStep(preferences).number;
        if (step != null && storedStep < step.number) {
            editor.putInt(KEY_CURRENT_STEP, step.number);
            editor.putString(KEY_OVERLAY_STEP, toOverlayStepName(step));
        }
    }

    private void putStepAtLeastAfterInstallGate(SharedPreferences.Editor editor,
                                                OpenHouseOnboardingState.Step stepBeforeInstallDone,
                                                OpenHouseOnboardingState.Step stepAfterInstallDone) {
        if (isOneClickInstallCompleted()) {
            putStepAtLeast(editor, stepAfterInstallDone);
        } else {
            putStep(editor, stepBeforeInstallDone);
        }
    }

    private void putStep(SharedPreferences.Editor editor, OpenHouseOnboardingState.Step step) {
        if (step == null) {
            return;
        }
        editor.putInt(KEY_CURRENT_STEP, step.number);
        editor.putString(KEY_OVERLAY_STEP, toOverlayStepName(step));
    }

    private boolean isOneClickInstallCompleted() {
        try {
            return OpenHouseInstallController.getInstance(context).getState().completed;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInstallDone(OpenHouseInstallState installState, OpenHouseStatus status) {
        if (installState != null && installState.completed) {
            return true;
        }
        return status != null && status.isDeploymentComplete();
    }

    private OpenHouseOnboardingState.Step readStoredStep(SharedPreferences preferences) {
        if (preferences.contains(KEY_CURRENT_STEP)) {
            return OpenHouseOnboardingState.Step.fromNumber(
                preferences.getInt(KEY_CURRENT_STEP, OpenHouseOnboardingState.Step.PERMISSIONS.number));
        }

        String overlayStep = preferences.getString(KEY_OVERLAY_STEP, "");
        if ("INSTALL".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.ONE_CLICK_INSTALL;
        } else if ("READING_GUIDE".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.READING_GUIDE;
        } else if ("DEEPSEEK_KEY".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.DEEPSEEK_KEY;
        } else if ("WAITING_INSTALL".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.WAITING_INSTALL;
        } else if ("CONFIGURE_DEEPSEEK".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.DEEPSEEK_CONFIGURATION;
        } else if ("LAUNCH_CONFIG".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.OPENCODE_LAUNCH;
        }
        return OpenHouseOnboardingState.Step.PERMISSIONS;
    }

    private String toOverlayStepName(OpenHouseOnboardingState.Step step) {
        if (step == null) {
            return "PERMISSION";
        }
        switch (step) {
            case ONE_CLICK_INSTALL:
                return "INSTALL";
            case READING_GUIDE:
                return "READING_GUIDE";
            case DEEPSEEK_KEY:
                return "DEEPSEEK_KEY";
            case WAITING_INSTALL:
                return "WAITING_INSTALL";
            case DEEPSEEK_CONFIGURATION:
                return "CONFIGURE_DEEPSEEK";
            case OPENCODE_LAUNCH:
                return "LAUNCH_CONFIG";
            case PERMISSIONS:
            default:
                return "PERMISSION";
        }
    }

    private SharedPreferences getOnboardingPrefs() {
        return context.getSharedPreferences(ONBOARDING_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private boolean isOpenCodeRunningInRoot() {
        String script = "set -euo pipefail; self=$$; while read -r pid comm args; do "
            + "[ -n \"$pid\" ] || continue; [ \"$pid\" = \"$self\" ] && continue; "
            + "case \"$comm\" in bash|sh|dash|ps|grep|awk|sed) continue ;; esac; "
            + "case \" $args \" in *opencode*' web '*) ;; *) continue ;; esac; "
            + "case \" $args \" in *' --port " + OpenCodeSettings.DEFAULT_OPENCODE_PORT + " '*|*' --port=" + OpenCodeSettings.DEFAULT_OPENCODE_PORT + " '*) ;; *) continue ;; esac; "
            + "if tr '\\0' '\\n' < \"/proc/$pid/environ\" 2>/dev/null | grep -Fxq 'PWD=" + OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY + "'; then exit 0; fi; "
            + "done < <(ps -eo pid=,comm=,args=); exit 1";
        return runTermuxCommand("proot-distro login ubuntu -- bash -lc " + shellQuote(script), 8).isSuccess();
    }

    private ShellCheckResult runTermuxCommand(String command, int timeoutSeconds) {
        Process process = null;
        File outputFile = null;
        try {
            File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
            if (!bash.isFile()) {
                return new ShellCheckResult(127, "Termux bash is not installed yet.");
            }

            outputFile = File.createTempFile("openhouse-status-", ".log", context.getCacheDir());
            ProcessBuilder processBuilder = new ProcessBuilder(
                bash.getAbsolutePath(),
                "-lc",
                command
            );
            processBuilder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.to(outputFile));
            Map<String, String> environment = processBuilder.environment();
            environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            environment.put("LANG", "C.UTF-8");
            environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
            environment.put("TERMUX_NO_AUTO_UBUNTU", "1");

            process = processBuilder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ShellCheckResult(124, readOutput(outputFile));
            }

            return new ShellCheckResult(process.exitValue(), readOutput(outputFile));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run OpenHouseAI status command", e);
            return new ShellCheckResult(1, e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (outputFile != null) {
                outputFile.delete();
            }
        }
    }

    private String readOutput(File outputFile) {
        if (outputFile == null || !outputFile.isFile()) {
            return "";
        }

        try {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(outputFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 800) {
                        if (output.length() > 0) output.append('\n');
                        output.append(line);
                    }
                }
            }
            return output.toString();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final class ShellCheckResult {
        final int exitCode;
        final String output;

        ShellCheckResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
