package com.openhouse.host.nativeapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.wuxianpi.openhouse.core.ControlPlaneResult;
import com.wuxianpi.openhouse.core.ControlPlaneStarter;
import com.wuxianpi.openhouse.core.HostActionResult;
import com.wuxianpi.openhouse.core.HostCapabilities;
import com.wuxianpi.openhouse.core.HostEdition;
import com.wuxianpi.openhouse.core.OpenHouseHost;
import com.wuxianpi.openhouse.core.RuntimeConnection;
import com.wuxianpi.openhouse.core.SetupResult;
import com.wuxianpi.openhouse.core.SetupState;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Complete host adapter for OpenHouse backed by an external Termux installation. */
public final class NativeOpenHouseHost implements OpenHouseHost {

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final String DEFAULT_PI_RUNTIME_URL = "http://127.0.0.1:8765";

    private static final String RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER";
    private static final String EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";

    private final Context appContext;
    private final ControlPlaneStarter controlPlaneStarter = new NativeControlPlaneStarter();

    public NativeOpenHouseHost(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        appContext = context.getApplicationContext();
    }

    @Override public HostEdition edition() {
        return HostEdition.NATIVE_ANDROID;
    }

    @Override public HostCapabilities capabilities() {
        boolean termuxInstalled = isTermuxInstalled();
        return new HostCapabilities(true, true, true, true, termuxInstalled, true,
            termuxInstalled, termuxInstalled, false);
    }

    @Override public SetupState setupState() {
        if (!isTermuxInstalled()) {
            return new SetupState(SetupState.Status.NOT_CONFIGURED, 0, "Termux is not installed");
        }
        if (findInTermuxHome(".config/openhouseai/service-manager/config.json") == null) {
            return new SetupState(SetupState.Status.NOT_CONFIGURED, 50,
                "OpenHouse setup or Termux Home access is required");
        }
        return SetupState.ready();
    }

    @Override public SetupResult ensureConfigured() {
        SetupState state = setupState();
        if (state.isReady()) {
            return new SetupResult(SetupResult.Status.ALREADY_CONFIGURED, state,
                "External Termux runtime is already paired");
        }
        HostActionResult opened = openTerminal();
        return new SetupResult(
            opened.isSuccess() ? SetupResult.Status.USER_ACTION_REQUIRED : SetupResult.Status.FAILED,
            state,
            opened.isSuccess()
                ? "Termux opened; finish OpenHouse setup and grant Termux Home access"
                : opened.message
        );
    }

    @Override public RuntimeConnection runtimeConnection() {
        JSONObject config = readJson(findInTermuxHome(".config/openhouseai/service-manager/config.json"));
        String serviceManagerUrl = normalizeServiceManagerUrl(firstNonBlank(
            config.optString("baseUrl"), config.optString("base_url"),
            config.optString("listenAddr"), config.optString("listen_addr"),
            config.optString("bind"), "127.0.0.1:20087"));
        String token = firstNonBlank(config.optString("authToken"), config.optString("auth_token"));
        return new RuntimeConnection(serviceManagerUrl, token, DEFAULT_PI_RUNTIME_URL);
    }

    @Override public ControlPlaneStarter controlPlaneStarter() {
        return controlPlaneStarter;
    }

    @Override public HostActionResult openTerminal() {
        try {
            Intent intent = appContext.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
            if (intent == null) {
                return new HostActionResult(HostActionResult.Status.USER_ACTION_REQUIRED,
                    "Termux is not installed");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            appContext.startActivity(intent);
            return new HostActionResult(HostActionResult.Status.COMPLETED, "Termux opened");
        } catch (Exception error) {
            return new HostActionResult(HostActionResult.Status.FAILED, safeMessage(error));
        }
    }

    @Override public HostActionResult openHostMaintenance() {
        try {
            Intent intent = new Intent()
                .setClassName(appContext.getPackageName(), "com.ai.assistance.operit.rescue.ui.RescueActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            appContext.startActivity(intent);
            return new HostActionResult(HostActionResult.Status.COMPLETED, "Repair mode opened");
        } catch (Exception error) {
            return new HostActionResult(HostActionResult.Status.FAILED, safeMessage(error));
        }
    }

    public Intent createTermuxHomeAccessIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }

    private final class NativeControlPlaneStarter implements ControlPlaneStarter {
        @Override public ControlPlaneResult startControlPlane() {
            return submitTermuxScript("openhouse-host/start-control-plane.sh",
                "Start OpenHouse control plane", ControlPlaneResult.Status.STARTED);
        }

        @Override public ControlPlaneResult stopControlPlane() {
            return submitTermuxScript("openhouse-host/stop-control-plane.sh",
                "Stop OpenHouse control plane", ControlPlaneResult.Status.STOPPED);
        }
    }

    private ControlPlaneResult submitTermuxScript(String assetPath, String label,
                                                   ControlPlaneResult.Status successStatus) {
        if (!isTermuxInstalled()) {
            return new ControlPlaneResult(ControlPlaneResult.Status.USER_ACTION_REQUIRED,
                "Termux is not installed");
        }
        try {
            Intent intent = new Intent(RUN_COMMAND_ACTION)
                .setComponent(new ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE))
                .putExtra(EXTRA_COMMAND_PATH, TERMUX_PREFIX + "/bin/bash")
                .putExtra(EXTRA_ARGUMENTS, new String[]{"-s"})
                .putExtra(EXTRA_STDIN, readAsset(assetPath))
                .putExtra(EXTRA_WORKDIR, TERMUX_HOME)
                .putExtra(EXTRA_RUNNER, "app-shell")
                .putExtra(EXTRA_LABEL, label);
            ComponentName submitted = appContext.startService(intent);
            if (submitted == null) {
                return new ControlPlaneResult(ControlPlaneResult.Status.FAILED,
                    "Termux rejected RUN_COMMAND");
            }
            return new ControlPlaneResult(successStatus, label + " submitted");
        } catch (Exception error) {
            return new ControlPlaneResult(ControlPlaneResult.Status.FAILED, safeMessage(error));
        }
    }

    private boolean isTermuxInstalled() {
        try {
            appContext.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private DocumentFile termuxHomeTree() {
        List<UriPermission> permissions = new ArrayList<>(
            appContext.getContentResolver().getPersistedUriPermissions());
        permissions.sort((left, right) -> score(right.getUri()) - score(left.getUri()));
        for (UriPermission permission : permissions) {
            if (!permission.isReadPermission()) continue;
            DocumentFile root = DocumentFile.fromTreeUri(appContext, permission.getUri());
            if (root != null && root.isDirectory()) return root;
        }
        return null;
    }

    private static int score(Uri uri) {
        String value = uri == null ? "" : uri.toString().toLowerCase();
        return value.contains("termux") ? 10 : 0;
    }

    private DocumentFile findInTermuxHome(String relativePath) {
        DocumentFile current = termuxHomeTree();
        if (current == null) return null;
        for (String part : relativePath.split("/")) {
            if (part.isEmpty()) continue;
            current = current.findFile(part);
            if (current == null) return null;
        }
        return current;
    }

    private JSONObject readJson(DocumentFile file) {
        try {
            String text = readText(file);
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private String readText(DocumentFile file) {
        if (file == null || !file.isFile() || file.length() > 2 * 1024 * 1024) return "";
        try (InputStream input = appContext.getContentResolver().openInputStream(file.getUri())) {
            return input == null ? "" : readStream(input);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readAsset(String path) throws Exception {
        try (InputStream input = appContext.getAssets().open(path)) {
            return readStream(input);
        }
    }

    private static String readStream(InputStream input) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        return output.toString();
    }

    static String normalizeServiceManagerUrl(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) text = "127.0.0.1:20087";
        if (text.startsWith(":")) text = "127.0.0.1" + text;
        if (text.startsWith("0.0.0.0:")) text = "127.0.0.1:" + text.substring("0.0.0.0:".length());
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://" + text;
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) return value.trim();
            }
        }
        return "";
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
