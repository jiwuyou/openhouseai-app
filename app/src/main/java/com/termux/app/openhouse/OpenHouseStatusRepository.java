package com.termux.app.openhouse;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import com.termux.app.smallphone.SmallPhoneRuntime;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class OpenHouseStatusRepository {

    private static final String LOG_TAG = "OpenHouseStatus";
    private static final String ONBOARDING_PREFS_NAME = "openhouse_onboarding";
    private static final String KEY_CURRENT_STEP = "current_step";
    private static final String KEY_PERMISSIONS_SKIPPED = "permissions_skipped";
    private static final String KEY_LAUNCH_CONFIRMED = "launch_confirmed";
    private static final String KEY_OVERLAY_STEP = "step";
    private static final String KEY_OVERLAY_BATTERY_SKIPPED = "battery_skipped";
    private static final String KEY_OVERLAY_GUIDE_DISMISSED = "guide_dismissed";
    private static final String PI_WEB_DEFAULT_URL = "http://127.0.0.1:30141/";

    private final Context context;

    public OpenHouseStatusRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public OpenHouseStatus loadStatus() {
        String diagnostic = "";
        boolean termuxReady = isTermuxReady();
        ShellCheckResult ubuntuCheck = termuxReady
            ? runTermuxCommand("proot-distro login ubuntu -- true", 10)
            : new ShellCheckResult(127, "Termux bash is not installed yet.");
        if (!ubuntuCheck.isSuccess()) {
            diagnostic = ubuntuCheck.output;
        }
        boolean ubuntuInstalled = ubuntuCheck.isSuccess();
        boolean entryUbuntuConfigured = termuxReady && runTermuxCommand("test \"$(tr -d '[:space:]' < \"$HOME/.openhouseai/entry-mode\" 2>/dev/null || true)\" = ubuntu && test -f \"$HOME/.openhouseai/entry.sh\" && { grep -Fq '# OpenHouseAI startup entry' \"$HOME/.bashrc\" 2>/dev/null || grep -Fq '# SmallPhoneAI startup entry' \"$HOME/.bashrc\" 2>/dev/null; }", 8).isSuccess();
        boolean nodeInstalled = ubuntuInstalled && runUbuntuCheck("command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && node -e \"process.exit(parseInt(process.versions.node.split('.')[0], 10) >= 24 ? 0 : 1)\"", 12);
        boolean codexInstalled = ubuntuInstalled && runUbuntuCheck("command -v codex >/dev/null 2>&1", 12);
        boolean claudeCodeInstalled = ubuntuInstalled && runUbuntuCheck("command -v claude >/dev/null 2>&1", 12);
        boolean cloudCliInstalled = ubuntuInstalled && runUbuntuCheck("command -v cloudcli >/dev/null 2>&1 && test -s \"$HOME/.config/openhouseai/claude-code-ui-port\" && test -s \"$HOME/.config/openhouseai/claude-code-ui-url\"", 12);
        boolean serviceManagerInstalled = ubuntuInstalled && runUbuntuCheck("command -v service-manager >/dev/null 2>&1 || test -x \"$HOME/smallphoneai-repos/service-manager/service-manager\" || test -x \"$HOME/smallphoneai-repos/service-manager/target/release/service-manager\" || test -x \"$HOME/smallphoneai-repos/service-manager/target/debug/service-manager\"", 12);
        boolean piAgentInstalled = ubuntuInstalled && runUbuntuCheck("{ test -d \"$HOME/smallphoneai-repos/pi-agent\" && { test -f \"$HOME/smallphoneai-repos/pi-agent/scripts/register-service.sh\" || test -x \"$HOME/smallphoneai-repos/pi-agent/bin/openhouse-pi-agent-sentinel\" || test -f \"$HOME/smallphoneai-repos/pi-agent/package.json\"; }; } || command -v pi >/dev/null 2>&1", 12);
        boolean piWebInstalled = ubuntuInstalled && runUbuntuCheck("test -d \"$HOME/smallphoneai-repos/pi-web\" && { test -f \"$HOME/smallphoneai-repos/pi-web/runtime/pi-web/server.js\" || test -f \"$HOME/smallphoneai-repos/pi-web/server.js\" || test -x \"$HOME/smallphoneai-repos/pi-web/bin/openhouse-pi-web-start\" || test -f \"$HOME/smallphoneai-repos/pi-web/scripts/register-service.sh\"; }", 12);
        boolean openhouseConnectInstalled = ubuntuInstalled && runUbuntuCheck("test -d \"$HOME/smallphoneai-repos/openhouse-connect\" && { test -f \"$HOME/smallphoneai-repos/openhouse-connect/scripts/register-service.sh\" || test -f \"$HOME/smallphoneai-repos/openhouse-connect/package.json\" || test -f \"$HOME/smallphoneai-repos/openhouse-connect/Makefile\"; }", 12);
        boolean smallPhoneRuntimeInstalled = ubuntuInstalled && runUbuntuCheck("test -d \"$HOME/smallphoneai-repos/smallphone-active\" && { test -d \"$HOME/smallphoneai-repos/smallphone-active/openhouse-components\" || test -d \"$HOME/smallphoneai-repos/smallphone-active/standalone-apps\" || test -f \"$HOME/smallphoneai-repos/smallphone-active/package.json\"; }", 12);
        boolean registrySynced = termuxReady && isRegistrySynced();

        SmallPhoneRuntime.Status runtimeStatus = new SmallPhoneRuntime(context).loadStatus();
        boolean serviceManagerReachable = runtimeStatus.serviceManager.reachable;
        boolean piWebReachable = probeUrl(PI_WEB_DEFAULT_URL);
        boolean openhouseConnectReachable = runtimeStatus.ccConnect.reachable || runtimeStatus.ccConnectDisabled;
        boolean smallPhoneReachable = runtimeStatus.smallPhone.reachable && runtimeStatus.smallPhoneCore.reachable;

        return new OpenHouseStatus(
            termuxReady,
            isBatteryOptimizationIgnored(),
            isProductPrepared(),
            ubuntuInstalled,
            isOfficialDocsSynced(),
            entryUbuntuConfigured,
            nodeInstalled,
            codexInstalled,
            claudeCodeInstalled,
            cloudCliInstalled,
            serviceManagerInstalled,
            piAgentInstalled,
            piWebInstalled,
            openhouseConnectInstalled,
            smallPhoneRuntimeInstalled,
            registrySynced,
            serviceManagerReachable,
            piWebReachable,
            openhouseConnectReachable,
            smallPhoneReachable,
            getOnboardingPrefs().getBoolean(KEY_LAUNCH_CONFIRMED, false),
            diagnostic
        );
    }

    public boolean isCoreDeploymentComplete() {
        return isCoreDeploymentComplete(loadStatus());
    }

    public static boolean isCoreDeploymentComplete(OpenHouseStatus status) {
        return isFirstUseReady(status);
    }

    public boolean isFirstUseReady() {
        return isFirstUseReady(loadStatus());
    }

    public static boolean isFirstUseReady(OpenHouseStatus status) {
        return status != null && status.piWebReachable;
    }

    public boolean isPiWebReachable() {
        return probeUrl(PI_WEB_DEFAULT_URL);
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
        boolean launchConfirmed = preferences.getBoolean(KEY_LAUNCH_CONFIRMED, false)
            || preferences.getBoolean(KEY_OVERLAY_GUIDE_DISMISSED, false)
            || (status != null && status.launchConfirmed);

        OpenHouseOnboardingState.Step effectiveStep = resolveEffectiveStep(
            storedStep,
            permissionsSkipped,
            launchConfirmed,
            installState,
            status);
        return new OpenHouseOnboardingState(
            effectiveStep,
            permissionsSkipped,
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

    public OpenHouseOnboardingState markLaunchConfirmed(boolean confirmed) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_LAUNCH_CONFIRMED, confirmed);
        if (confirmed) {
            putStepAtLeastAfterInstallGate(
                editor,
                OpenHouseOnboardingState.Step.WAITING_INSTALL,
                OpenHouseOnboardingState.Step.READY_TO_USE);
        }
        editor.apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState markOneClickInstallStarted() {
        getOnboardingPrefs().edit()
            .putInt(KEY_CURRENT_STEP, OpenHouseOnboardingState.Step.WAITING_INSTALL.number)
            .putString(KEY_OVERLAY_STEP, toOverlayStepName(OpenHouseOnboardingState.Step.WAITING_INSTALL))
            .apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState markOneClickInstallCompleted() {
        if (!isOneClickInstallCompleted()) {
            getOnboardingPrefs().edit()
                .putInt(KEY_CURRENT_STEP, OpenHouseOnboardingState.Step.WAITING_INSTALL.number)
                .putString(KEY_OVERLAY_STEP, toOverlayStepName(OpenHouseOnboardingState.Step.WAITING_INSTALL))
                .apply();
            return loadOnboardingState();
        }

        getOnboardingPrefs().edit()
            .putInt(KEY_CURRENT_STEP, OpenHouseOnboardingState.Step.READY_TO_USE.number)
            .putString(KEY_OVERLAY_STEP, toOverlayStepName(OpenHouseOnboardingState.Step.READY_TO_USE))
            .apply();
        return loadOnboardingState();
    }

    public OpenHouseOnboardingState resetOnboardingState() {
        getOnboardingPrefs().edit().clear().apply();
        return loadOnboardingState();
    }

    public static File getOpenHouseStateDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".openhouseai");
    }

    private boolean isBatteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private boolean isProductPrepared() {
        File docsDir = firstExistingDirectory(
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs"),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "smallphoneai-docs"));
        File workspaceDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "workspace");
        return docsDir != null
            && workspaceDir.isDirectory()
            && new File(docsDir, "README.md").isFile()
            && new File(docsDir, "ENVIRONMENT.md").isFile()
            && new File(docsDir, "MODEL_API_SETUP.md").isFile();
    }

    private boolean isOfficialDocsSynced() {
        File docsDir = firstExistingDirectory(
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs"),
            new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "smallphoneai-docs"));
        if (docsDir == null) {
            return false;
        }
        File officialDocsDir = new File(docsDir, "official");
        File agentNotesDir = new File(docsDir, "agent-notes");
        return officialDocsDir.isDirectory()
            && agentNotesDir.isDirectory()
            && new File(officialDocsDir, "START_HERE.md").isFile()
            && new File(officialDocsDir, "ENVIRONMENT.md").isFile()
            && new File(officialDocsDir, "MODEL_API_SETUP.md").isFile();
    }

    private boolean isRegistrySynced() {
        File configDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/openhouseai");
        File componentsDir = new File(configDir, "components.d");
        File serviceSpecsDir = new File(configDir, "service-manager/services.d");
        if (hasJsonFile(componentsDir) && hasJsonFile(serviceSpecsDir)) {
            return true;
        }

        File registryState = new File(configDir, "registry-state.json");
        File serviceManagerConfig = new File(configDir, "service-manager/config.json");
        return serviceManagerConfig.isFile() && isRegistryStateSuccessful(registryState);
    }

    private boolean hasJsonFile(File dir) {
        File[] files = dir == null ? null : dir.listFiles((file, name) ->
            file.isFile() && name != null && name.endsWith(".json"));
        return files != null && files.length > 0;
    }

    private boolean isRegistryStateSuccessful(File stateFile) {
        if (stateFile == null || !stateFile.isFile()) {
            return false;
        }
        String content = readTextFile(stateFile, 4096);
        String compact = content == null ? "" : content.replace(" ", "");
        return compact.contains("\"status\":\"success\"");
    }

    private OpenHouseOnboardingState.Step resolveEffectiveStep(OpenHouseOnboardingState.Step storedStep,
                                                               boolean permissionsSkipped,
                                                               boolean launchConfirmed,
                                                               OpenHouseInstallState installState,
                                                               OpenHouseStatus status) {
        OpenHouseOnboardingState.Step effectiveStep = storedStep == null
            ? OpenHouseOnboardingState.Step.PERMISSIONS
            : storedStep;
        boolean installRunning = installState != null && installState.running;
        boolean installDone = isInstallDone(installState, status);

        if (installDone) {
            return OpenHouseOnboardingState.Step.READY_TO_USE;
        }

        if (installRunning
            || launchConfirmed
            || effectiveStep.number >= OpenHouseOnboardingState.Step.WAITING_INSTALL.number) {
            return OpenHouseOnboardingState.Step.WAITING_INSTALL;
        }

        if (permissionsSkipped && effectiveStep.number < OpenHouseOnboardingState.Step.ONE_CLICK_INSTALL.number) {
            effectiveStep = OpenHouseOnboardingState.Step.ONE_CLICK_INSTALL;
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
            OpenHouseStatus status = loadStatus();
            return isFirstUseReady(status);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInstallDone(OpenHouseInstallState installState, OpenHouseStatus status) {
        return installState != null && installState.completed || isFirstUseReady(status);
    }

    private OpenHouseOnboardingState.Step readStoredStep(SharedPreferences preferences) {
        if (preferences.contains(KEY_CURRENT_STEP)) {
            return OpenHouseOnboardingState.Step.fromNumber(
                preferences.getInt(KEY_CURRENT_STEP, OpenHouseOnboardingState.Step.PERMISSIONS.number));
        }

        String overlayStep = preferences.getString(KEY_OVERLAY_STEP, "");
        if ("INSTALL".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.ONE_CLICK_INSTALL;
        } else if ("WAITING_INSTALL".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.WAITING_INSTALL;
        } else if ("LAUNCH_CONFIG".equals(overlayStep)) {
            return OpenHouseOnboardingState.Step.READY_TO_USE;
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
            case WAITING_INSTALL:
                return "WAITING_INSTALL";
            case READY_TO_USE:
                return "LAUNCH_CONFIG";
            case PERMISSIONS:
            default:
                return "PERMISSION";
        }
    }

    private SharedPreferences getOnboardingPrefs() {
        return context.getSharedPreferences(ONBOARDING_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private boolean isTermuxReady() {
        File prefixDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        return prefixDir.isDirectory() && bash.isFile();
    }

    private File firstExistingDirectory(File... candidates) {
        if (candidates == null) {
            return null;
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean runUbuntuCheck(String script, int timeoutSeconds) {
        String path = "export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH\"; ";
        return runTermuxCommand("proot-distro login ubuntu -- bash -lc " + shellQuote(path + script), timeoutSeconds).isSuccess();
    }

    private boolean probeUrl(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1800);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    private String readTextFile(File file, int maxChars) {
        if (file == null || !file.isFile()) {
            return "";
        }

        try {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
                int next;
                while ((next = reader.read()) != -1 && output.length() < maxChars) {
                    output.append((char) next);
                }
            }
            return output.toString();
        } catch (Exception e) {
            return "";
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
