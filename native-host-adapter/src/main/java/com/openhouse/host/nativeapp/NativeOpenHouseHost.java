package com.openhouse.host.nativeapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.wuxianpi.openhouse.core.ControlPlaneResult;
import com.wuxianpi.openhouse.core.ControlPlaneStarter;
import com.wuxianpi.openhouse.core.ControlPlaneBridge;
import com.wuxianpi.openhouse.core.ControlPlaneCommandResult;
import com.wuxianpi.openhouse.core.ControlPlaneStartCoordinator;
import com.wuxianpi.openhouse.core.HostActionResult;
import com.wuxianpi.openhouse.core.HostCapabilities;
import com.wuxianpi.openhouse.core.HostEdition;
import com.wuxianpi.openhouse.core.LegacyRegistrySource;
import com.wuxianpi.openhouse.core.OpenHouseHost;
import com.wuxianpi.openhouse.core.RuntimeConnection;
import com.wuxianpi.openhouse.core.SetupResult;
import com.wuxianpi.openhouse.core.SetupState;
import com.wuxianpi.openhouse.core.registry.LegacyRegistrySnapshot;
import com.wuxianpi.openhouse.core.registry.RegistryManifest;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Complete host adapter for OpenHouse backed by an external Termux installation. */
public final class NativeOpenHouseHost implements OpenHouseHost {

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final String DEFAULT_PI_RUNTIME_URL = "http://127.0.0.1:20765";

    private static final String RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER";
    private static final String EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private final Context appContext;
    private final ControlPlaneBridge controlPlaneBridge;
    private final ControlPlaneStarter controlPlaneStarter = new NativeControlPlaneStarter();
    private final LegacyRegistrySource legacyRegistrySource;

    public NativeOpenHouseHost(Context context) {
        this(context, null);
    }

    NativeOpenHouseHost(Context context, RegistryFileAccess registryFileAccess) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext == null ? context : applicationContext;
        controlPlaneBridge = new NativeControlPlaneBridge(appContext);
        RegistryFileAccess files = registryFileAccess == null
            ? new SafRegistryFileAccess()
            : registryFileAccess;
        legacyRegistrySource = createLegacyRegistrySource(files);
    }

    @Override public HostEdition edition() {
        return HostEdition.NATIVE_ANDROID;
    }

    @Override public HostCapabilities capabilities() {
        boolean termuxInstalled = isPackageInstalled(TERMUX_PACKAGE);
        boolean runCommandAvailable = isRunCommandAvailable();
        return new HostCapabilities(true, true, true, true, termuxInstalled, true,
            runCommandAvailable, termuxInstalled, false);
    }

    @Override public SetupState setupState() {
        if (!isPackageInstalled(TERMUX_PACKAGE)) {
            return new SetupState(SetupState.Status.NOT_CONFIGURED, 0,
                "Termux package com.termux is not installed");
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

    @Override public ControlPlaneBridge controlPlaneBridge() {
        return controlPlaneBridge;
    }

    @Override public LegacyRegistrySource legacyRegistrySource() {
        return legacyRegistrySource;
    }

    @Override public HostActionResult openTerminal() {
        try {
            if (!isPackageInstalled(TERMUX_PACKAGE)) {
                return new HostActionResult(HostActionResult.Status.USER_ACTION_REQUIRED,
                    "Termux package com.termux is not installed");
            }
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
            ControlPlaneCommandResult result = ControlPlaneStartCoordinator.start(
                controlPlaneBridge, "legacy-manual");
            ControlPlaneResult.Status status = result.isSuccess()
                ? ControlPlaneResult.Status.STARTED
                : result.exitCode == 126
                    ? ControlPlaneResult.Status.USER_ACTION_REQUIRED
                    : ControlPlaneResult.Status.FAILED;
            return new ControlPlaneResult(status, result.combinedOutput());
        }

        @Override public ControlPlaneResult stopControlPlane() {
            return submitTermuxScript("openhouse-host/stop-control-plane.sh",
                "Stop OpenHouse control plane", ControlPlaneResult.Status.STOPPED);
        }
    }

    private ControlPlaneResult submitTermuxScript(String assetPath, String label,
                                                   ControlPlaneResult.Status successStatus) {
        if (!isRunCommandAvailable()) {
            return new ControlPlaneResult(ControlPlaneResult.Status.USER_ACTION_REQUIRED,
                isPackageInstalled(TERMUX_PACKAGE)
                    ? "Installed Termux does not expose com.termux.RUN_COMMAND"
                    : "Termux package com.termux is not installed");
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

    private boolean isRunCommandAvailable() {
        Intent commandIntent = new Intent(RUN_COMMAND_ACTION).setPackage(TERMUX_PACKAGE);
        return appContext.getPackageManager().resolveService(commandIntent, 0) != null;
    }

    @SuppressWarnings("deprecation")
    private boolean isPackageInstalled(String packageName) {
        try {
            appContext.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private DocumentFile termuxHomeTree() {
        List<UriPermission> permissions = new ArrayList<>(
            appContext.getContentResolver().getPersistedUriPermissions());
        for (UriPermission permission : permissions) {
            if (!permission.isReadPermission()) continue;
            if (!NativeExternalRuntimeHostKt.isValidatedTermuxHomeTree(permission.getUri())) continue;
            DocumentFile root = DocumentFile.fromTreeUri(appContext, permission.getUri());
            if (root != null && root.isDirectory()) return root;
        }
        return null;
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

    interface RegistryFileAccess {
        List<String> listJsonFiles(String relativeDirectory);
        String readText(String relativePath);
    }

    private final class SafRegistryFileAccess implements RegistryFileAccess {
        @Override public List<String> listJsonFiles(String relativeDirectory) {
            DocumentFile directory = findInTermuxHome(relativeDirectory);
            if (directory == null || !directory.isDirectory()) return null;
            List<String> names = new ArrayList<>();
            for (DocumentFile file : directory.listFiles()) {
                String name = file == null ? null : file.getName();
                if (file != null && file.isFile() && name != null && name.endsWith(".json")) {
                    names.add(name);
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }

        @Override public String readText(String relativePath) {
            return NativeOpenHouseHost.this.readText(findInTermuxHome(relativePath));
        }
    }

    private static LegacyRegistrySource createLegacyRegistrySource(RegistryFileAccess files) {
        return () -> loadLegacyRegistry(files);
    }

    private static LegacyRegistrySnapshot loadLegacyRegistry(RegistryFileAccess files) {
        if (files == null) return LegacyRegistrySnapshot.unavailable();
        List<String> names;
        try {
            names = files.listJsonFiles(".config/openhouseai/components.d");
        } catch (Exception error) {
            return LegacyRegistrySnapshot.failure(safeMessage(error));
        }
        if (names == null || names.isEmpty()) return LegacyRegistrySnapshot.unavailable();
        names = new ArrayList<>(names);
        names.sort(String.CASE_INSENSITIVE_ORDER);

        List<RegistryManifest> manifests = new ArrayList<>();
        int invalidFiles = 0;
        for (String name : names) {
            if (name == null || name.contains("/") || name.contains("\\") || !name.endsWith(".json")) {
                invalidFiles++;
                continue;
            }
            try {
                String relativePath = ".config/openhouseai/components.d/" + name;
                String json = files.readText(relativePath);
                if (json == null || json.trim().isEmpty()) {
                    throw new IllegalArgumentException("component manifest is empty");
                }
                manifests.add(RegistryManifest.fromManifestJson("components.d/" + name, json));
            } catch (Exception ignored) {
                invalidFiles++;
            }
        }
        if (manifests.isEmpty()) {
            return LegacyRegistrySnapshot.failure(
                "no valid component manifests in Termux Home (invalid=" + invalidFiles + ")");
        }
        String stateJson = files.readText(".config/openhouseai/registry-state.json");
        return LegacyRegistrySnapshot.available(resolveLegacyRevision(stateJson, manifests), manifests);
    }

    private static String resolveLegacyRevision(String stateJson, List<RegistryManifest> manifests) {
        try {
            if (stateJson != null && !stateJson.trim().isEmpty()) {
                JSONObject state = new JSONObject(stateJson);
                String revision = firstNonBlank(
                    state.optString("revision"),
                    state.optString("generatedAt"), state.optString("generated_at"),
                    state.optString("syncedAt"), state.optString("synced_at"),
                    state.optString("updatedAt"), state.optString("updated_at"));
                if (!revision.isEmpty()) return revision;
            }
        } catch (Exception ignored) {
            // A malformed state file must not hide otherwise valid component manifests.
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (RegistryManifest manifest : manifests) {
                digest.update(manifest.relativePath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(manifest.normalizedJson.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder revision = new StringBuilder("legacy-");
            for (byte value : digest.digest()) {
                revision.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                revision.append(Character.forDigit(value & 0x0f, 16));
            }
            return revision.toString();
        } catch (Exception ignored) {
            return "legacy-" + Integer.toHexString(manifests.toString().hashCode());
        }
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
