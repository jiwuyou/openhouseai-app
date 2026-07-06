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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
    private static final String AIONUI_DEFAULT_URL = "http://127.0.0.1:25808/";

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
        boolean aionUiInstalled = ubuntuInstalled && isAionUiInstalled();
        boolean registrySynced = termuxReady && isRegistrySynced();

        SmallPhoneRuntime.Status runtimeStatus = new SmallPhoneRuntime(context).loadStatus();
        boolean serviceManagerReachable = runtimeStatus.serviceManager.reachable;
        boolean piWebReachable = probeUrl(PI_WEB_DEFAULT_URL);
        boolean openhouseConnectReachable = runtimeStatus.ccConnect.reachable || runtimeStatus.ccConnectDisabled;
        boolean smallPhoneReachable = runtimeStatus.smallPhone.reachable && runtimeStatus.smallPhoneCore.reachable;
        String aionUiUrl = resolveAionUiUrl(ubuntuInstalled);
        boolean aionUiReachable = aionUiInstalled && isAionUiReachable(aionUiUrl);

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
            aionUiInstalled,
            registrySynced,
            serviceManagerReachable,
            piWebReachable,
            openhouseConnectReachable,
            smallPhoneReachable,
            aionUiReachable,
            aionUiUrl,
            getOnboardingPrefs().getBoolean(KEY_LAUNCH_CONFIRMED, false),
            diagnostic
        );
    }

    public boolean isCoreDeploymentComplete() {
        return isCoreDeploymentComplete(loadStatus());
    }

    public static boolean isCoreDeploymentComplete(OpenHouseStatus status) {
        return status != null && status.isCoreDeploymentComplete();
    }

    public boolean isFirstUseReady() {
        return isFirstUseReady(loadStatus());
    }

    public static boolean isFirstUseReady(OpenHouseStatus status) {
        return status != null && status.isFirstUseReady();
    }

    public boolean isRuntimeEnvironmentPrepared() {
        return isRuntimeEnvironmentPrepared(loadStatus());
    }

    public static boolean isRuntimeEnvironmentPrepared(OpenHouseStatus status) {
        return status != null && status.isRuntimeEnvironmentPrepared();
    }

    public boolean isAiFeaturesReady() {
        return isAiFeaturesReady(loadStatus());
    }

    public static boolean isAiFeaturesReady(OpenHouseStatus status) {
        return status != null && status.isAiFeaturesReady();
    }

    public boolean isPiWebReachable() {
        if (!probeUrl(PI_WEB_DEFAULT_URL)) {
            return false;
        }
        if (!isTermuxReady()) {
            return false;
        }
        boolean ubuntuInstalled = runTermuxCommand("proot-distro login ubuntu -- true", 10).isSuccess();
        return ubuntuInstalled
            && isAionUiInstalled()
            && isAionUiReachable(resolveAionUiUrl(true));
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

    public OpenHouseOnboardingState loadOnboardingStateWithoutStatusProbe() {
        OpenHouseInstallState installState = OpenHouseInstallState.idle();
        try {
            installState = OpenHouseInstallController.getInstance(context).getState();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read one-click install state", e);
        }
        return loadOnboardingState(installState, OpenHouseStatus.checking());
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
        return markLaunchConfirmed(confirmed, null);
    }

    public OpenHouseOnboardingState markLaunchConfirmed(boolean confirmed, OpenHouseStatus knownStatus) {
        SharedPreferences.Editor editor = getOnboardingPrefs().edit()
            .putBoolean(KEY_LAUNCH_CONFIRMED, confirmed);
        if (confirmed) {
            putStep(editor, isFirstUseReady(knownStatus)
                ? OpenHouseOnboardingState.Step.READY_TO_USE
                : OpenHouseOnboardingState.Step.WAITING_INSTALL);
        }
        editor.apply();

        OpenHouseInstallState installState = OpenHouseInstallState.idle();
        try {
            installState = OpenHouseInstallController.getInstance(context).getState();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read one-click install state", e);
        }
        return loadOnboardingState(
            installState,
            knownStatus == null ? OpenHouseStatus.checking() : knownStatus);
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

    private boolean isAionUiInstalled() {
        return runUbuntuCheck(
            "for dir in \"$HOME/aionui-web\" \"$HOME/openhouse/aionui-web\" \"$HOME/openhouseai/aionui-web\" \"$HOME/.local/share/openhouseai/aionui-web\" \"$HOME/.local/opt/aionui-web\" \"$HOME/smallphoneai-repos/aionui-web\" \"/opt/openhouseai/aionui-web\"; do "
                + "[ -x \"$dir/aionui-web\" ] && [ -f \"$dir/static/index.html\" ] && exit 0; "
                + "done; command -v aionui-web >/dev/null 2>&1",
            12);
    }

    private String resolveAionUiUrl(boolean ubuntuInstalled) {
        String configuredUrl = firstNonEmptyTermuxStateFile(
            ".config/openhouseai/aionui-url",
            ".config/openhouseai/aionui-web-url",
            ".openhouseai/aionui-url",
            ".openhouseai/aionui-web-url");
        if (configuredUrl.isEmpty()) {
            String configuredPort = firstNonEmptyTermuxStateFile(
                ".config/openhouseai/aionui-port",
                ".config/openhouseai/aionui-web-port",
                ".openhouseai/aionui-port",
                ".openhouseai/aionui-web-port");
            configuredUrl = urlFromPort(configuredPort);
        }
        if (configuredUrl.isEmpty() && ubuntuInstalled) {
            configuredUrl = readAionUiUrlFromUbuntu();
        }
        return normalizeAionUiUrl(configuredUrl);
    }

    private String readAionUiUrlFromUbuntu() {
        String script =
            "for f in \"$HOME/.config/openhouseai/aionui-url\" \"$HOME/.config/openhouseai/aionui-web-url\" \"$HOME/.aionui-web/url\"; do "
                + "[ -s \"$f\" ] && { sed -n '1p' \"$f\"; exit 0; }; "
                + "done; "
                + "for f in \"$HOME/.config/openhouseai/aionui-port\" \"$HOME/.config/openhouseai/aionui-web-port\" \"$HOME/.aionui-web/port\"; do "
                + "[ -s \"$f\" ] && { port=\"$(tr -dc '0-9' < \"$f\" | head -c 5)\"; [ -n \"$port\" ] && { printf 'http://127.0.0.1:%s/\\n' \"$port\"; exit 0; }; }; "
                + "done";
        ShellCheckResult result = runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc " + shellQuote(script),
            8);
        return result.isSuccess() ? firstLine(result.output) : "";
    }

    private boolean isAionUiReachable(String configuredUrl) {
        String normalizedUrl = normalizeAionUiUrl(configuredUrl);
        if (probeAionUiUrl(normalizedUrl)) {
            return true;
        }
        return !AIONUI_DEFAULT_URL.equals(normalizedUrl) && probeAionUiUrl(AIONUI_DEFAULT_URL);
    }

    private boolean probeAionUiUrl(String url) {
        String normalizedUrl = normalizeAionUiUrl(url);
        HttpTextResponse home = fetchHttpText(normalizedUrl, 4096);
        if (home.isSuccess() && isAionUiHtml(home.body)) {
            return true;
        }

        HttpTextResponse authStatus = fetchHttpText(aionUiEndpointUrl(normalizedUrl, "/api/auth/status"), 4096);
        return authStatus.isUsable() && isAionUiAuthStatus(authStatus.body);
    }

    private boolean isAionUiHtml(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT).replace('\'', '"');
        return normalized.contains("<title>aionui</title>")
            || (normalized.contains("application-name") && normalized.contains("content=\"aionui\""))
            || (normalized.contains("apple-mobile-web-app-title") && normalized.contains("content=\"aionui\""));
    }

    private boolean isAionUiAuthStatus(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        String compact = body.toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "");
        boolean hasNeedsSetup = compact.contains("\"needs_setup\"");
        boolean hasUserCount = compact.contains("\"user_count\"");
        boolean hasAuthenticated = compact.contains("\"is_authenticated\"");
        if (hasNeedsSetup && hasUserCount && hasAuthenticated) {
            return true;
        }
        return compact.contains("\"success\"")
            && (hasNeedsSetup || hasUserCount || hasAuthenticated);
    }

    private String aionUiEndpointUrl(String value, String path) {
        try {
            URL parsed = new URL(normalizeAionUiUrl(value));
            StringBuilder endpoint = new StringBuilder();
            endpoint.append(parsed.getProtocol().toLowerCase(Locale.ROOT)).append("://127.0.0.1");
            if (parsed.getPort() > 0) {
                endpoint.append(':').append(parsed.getPort());
            }
            endpoint.append(path.startsWith("/") ? path : "/" + path);
            return endpoint.toString();
        } catch (Exception e) {
            return "http://127.0.0.1:25808" + (path.startsWith("/") ? path : "/" + path);
        }
    }

    private String firstNonEmptyTermuxStateFile(String... relativePaths) {
        if (relativePaths == null) {
            return "";
        }
        for (String relativePath : relativePaths) {
            if (relativePath == null || relativePath.trim().isEmpty()) {
                continue;
            }
            String value = firstLine(readTextFile(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, relativePath), 512));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeAionUiUrl(String value) {
        String candidate = firstLine(value);
        if (candidate.isEmpty()) {
            return AIONUI_DEFAULT_URL;
        }

        String portUrl = urlFromPort(candidate);
        if (!portUrl.isEmpty()) {
            return portUrl;
        }

        try {
            URL parsed = new URL(candidate);
            String protocol = parsed.getProtocol();
            String host = parsed.getHost();
            if (!("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                || !isLoopbackHost(host)) {
                return AIONUI_DEFAULT_URL;
            }
            int port = parsed.getPort();
            String path = parsed.getPath();
            String query = parsed.getQuery();
            StringBuilder normalized = new StringBuilder();
            normalized.append(protocol.toLowerCase()).append("://127.0.0.1");
            if (port > 0) {
                normalized.append(':').append(port);
            }
            normalized.append(path == null || path.isEmpty() ? "/" : path);
            if (query != null && !query.isEmpty()) {
                normalized.append('?').append(query);
            }
            return normalized.toString();
        } catch (Exception e) {
            return AIONUI_DEFAULT_URL;
        }
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        return "127.0.0.1".equals(host)
            || "localhost".equalsIgnoreCase(host)
            || "0.0.0.0".equals(host)
            || "::1".equals(host);
    }

    private String urlFromPort(String value) {
        String port = firstLine(value).replaceAll("[^0-9]", "");
        if (port.isEmpty() || port.length() > 5) {
            return "";
        }
        try {
            int parsedPort = Integer.parseInt(port);
            if (parsedPort <= 0 || parsedPort > 65535) {
                return "";
            }
            return "http://127.0.0.1:" + parsedPort + "/";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private String firstLine(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int newline = trimmed.indexOf('\n');
        if (newline >= 0) {
            trimmed = trimmed.substring(0, newline).trim();
        }
        return trimmed;
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
        if (isFirstUseReady(status)) {
            return true;
        }
        if (!isOverallInstallTaskCompleted(installState)) {
            return false;
        }
        try {
            return isFirstUseReady(status == null ? loadStatus() : status);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isOverallInstallTaskCompleted(OpenHouseInstallState installState) {
        if (installState == null || !installState.completed) {
            return false;
        }

        OpenHouseInstallState.TaskScope taskScope = installState.taskScope == null
            ? OpenHouseInstallState.TaskScope.FULL
            : installState.taskScope;
        return taskScope == OpenHouseInstallState.TaskScope.FULL
            || taskScope == OpenHouseInstallState.TaskScope.AI_FEATURES;
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

    private HttpTextResponse fetchHttpText(String url, int maxChars) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1800);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.1");
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
            String body = readHttpBody(stream, maxChars);
            return new HttpTextResponse(code, body);
        } catch (Exception e) {
            return new HttpTextResponse(0, "");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readHttpBody(InputStream stream, int maxChars) {
        if (stream == null || maxChars <= 0) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            int next;
            while ((next = reader.read()) != -1 && output.length() < maxChars) {
                output.append((char) next);
            }
            return output.toString();
        } catch (Exception e) {
            return "";
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

    private static final class HttpTextResponse {
        final int code;
        final String body;

        HttpTextResponse(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean isSuccess() {
            return code >= 200 && code < 400;
        }

        boolean isUsable() {
            return code >= 200 && code < 500;
        }
    }
}
