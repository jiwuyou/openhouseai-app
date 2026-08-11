package com.openhouse.host.termux;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.wuxianpi.openhouse.core.ControlPlaneResult;
import com.wuxianpi.openhouse.core.ControlPlaneStarter;
import com.wuxianpi.openhouse.core.ControlPlaneBridge;
import com.wuxianpi.openhouse.core.ControlPlaneCommandResult;
import com.wuxianpi.openhouse.core.ControlPlaneOutputListener;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Complete host adapter for the Termux-embedded OpenHouse edition. */
public final class TermuxOpenHouseHost implements OpenHouseHost {

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final String DEFAULT_PI_RUNTIME_URL = "http://127.0.0.1:8765";

    private static final File SERVICE_MANAGER_CONFIG = new File(
        TERMUX_HOME + "/.config/openhouseai/service-manager/config.json");
    private final Context appContext;
    private final ControlPlaneBridge controlPlaneBridge = new TermuxControlPlaneBridge();
    private final ControlPlaneStarter controlPlaneStarter = new TermuxControlPlaneStarter();
    private final LegacyRegistrySource legacyRegistrySource;

    public TermuxOpenHouseHost(Context context) {
        this(context, new File(TERMUX_HOME + "/.config/openhouseai"));
    }

    TermuxOpenHouseHost(Context context, File registryConfigDir) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext == null ? context : applicationContext;
        legacyRegistrySource = createLegacyRegistrySource(registryConfigDir);
    }

    @Override public HostEdition edition() {
        return HostEdition.TERMUX_EMBEDDED;
    }

    @Override public HostCapabilities capabilities() {
        return new HostCapabilities(true, true, true, true, true, true, true, true, false);
    }

    @Override public SetupState setupState() {
        if (!new File(TERMUX_PREFIX + "/bin/bash").isFile()) {
            return new SetupState(SetupState.Status.NOT_CONFIGURED, 0, "Termux bootstrap is not installed");
        }
        if (!SERVICE_MANAGER_CONFIG.isFile()) {
            return new SetupState(SetupState.Status.NOT_CONFIGURED, 50, "OpenHouse product setup is required");
        }
        return SetupState.ready();
    }

    @Override public SetupResult ensureConfigured() {
        SetupState state = setupState();
        if (state.isReady()) {
            return new SetupResult(SetupResult.Status.ALREADY_CONFIGURED, state, "OpenHouse is ready");
        }
        HostActionResult opened = launchActivity(
            "com.termux.app.activities.OpenHouseOnboardingActivity", "OpenHouse setup");
        return new SetupResult(
            opened.isSuccess() ? SetupResult.Status.USER_ACTION_REQUIRED : SetupResult.Status.FAILED,
            state,
            opened.message
        );
    }

    @Override public RuntimeConnection runtimeConnection() {
        JSONObject config = readObject(SERVICE_MANAGER_CONFIG);
        String endpoint = normalizeServiceManagerUrl(firstNonBlank(
            config.optString("baseUrl"), config.optString("base_url"),
            config.optString("listenAddr"), config.optString("listen_addr"),
            config.optString("bind"), "127.0.0.1:20087"));
        String token = firstNonBlank(config.optString("authToken"), config.optString("auth_token"));
        return new RuntimeConnection(endpoint, token, DEFAULT_PI_RUNTIME_URL);
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
        return launchActivity("com.termux.app.TermuxActivity", "Termux terminal");
    }

    @Override public HostActionResult openHostMaintenance() {
        return launchActivity("com.termux.app.activities.MaintenanceCenterActivity", "OpenHouse maintenance");
    }

    private final class TermuxControlPlaneStarter implements ControlPlaneStarter {
        @Override public ControlPlaneResult startControlPlane() {
            ControlPlaneCommandResult result = ControlPlaneStartCoordinator.start(
                controlPlaneBridge, "legacy-manual");
            return new ControlPlaneResult(
                result.isSuccess() ? ControlPlaneResult.Status.STARTED : ControlPlaneResult.Status.FAILED,
                result.combinedOutput()
            );
        }

        @Override public ControlPlaneResult stopControlPlane() {
            File bash = new File(TERMUX_PREFIX + "/bin/bash");
            if (!bash.isFile()) {
                return new ControlPlaneResult(ControlPlaneResult.Status.FAILED, "Termux bash is not installed");
            }
            try {
                Process process = new ProcessBuilder(
                    bash.getAbsolutePath(), "-lc",
                    "pids=$(pgrep -f '(^|/)service-manager[[:space:]]+serve([[:space:]]|$)' || true); "
                        + "[ -z \"$pids\" ] || kill $pids"
                ).directory(new File(TERMUX_HOME)).start();
                int code = process.waitFor();
                return new ControlPlaneResult(
                    code == 0 ? ControlPlaneResult.Status.STOPPED : ControlPlaneResult.Status.FAILED,
                    code == 0 ? "Control plane stopped" : "Unable to stop control plane"
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new ControlPlaneResult(ControlPlaneResult.Status.FAILED, safeMessage(error));
            } catch (Exception error) {
                return new ControlPlaneResult(ControlPlaneResult.Status.FAILED, safeMessage(error));
            }
        }
    }

    private final class TermuxControlPlaneBridge implements ControlPlaneBridge {
        @Override public ControlPlaneCommandResult start(ControlPlaneOutputListener listener) {
            File command = new File(TERMUX_PREFIX + "/bin/openhouse-control-plane-start");
            if (!command.isFile()) {
                return new ControlPlaneCommandResult(
                    127, "", "Missing fixed Termux command: " + command.getAbsolutePath());
            }
            try {
                ProcessBuilder builder = new ProcessBuilder(command.getAbsolutePath())
                    .directory(new File(TERMUX_HOME));
                builder.environment().put("HOME", TERMUX_HOME);
                builder.environment().put("PREFIX", TERMUX_PREFIX);
                builder.environment().put("PATH",
                    TERMUX_PREFIX + "/bin:/system/bin:/system/xbin");
                Process process = builder.start();
                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                Thread stdoutReader = streamReader(process.getInputStream(), "stdout", stdout, listener);
                Thread stderrReader = streamReader(process.getErrorStream(), "stderr", stderr, listener);
                stdoutReader.start();
                stderrReader.start();
                int exitCode = process.waitFor();
                stdoutReader.join();
                stderrReader.join();
                return new ControlPlaneCommandResult(exitCode, stdout.toString(), stderr.toString());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new ControlPlaneCommandResult(130, "", safeMessage(error));
            } catch (Exception error) {
                return new ControlPlaneCommandResult(1, "", safeMessage(error));
            }
        }
    }

    private static Thread streamReader(
        InputStream input,
        String stream,
        StringBuilder output,
        ControlPlaneOutputListener listener
    ) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() > 0) output.append('\n');
                        output.append(line);
                    }
                    if (listener != null) listener.onOutput(stream, line);
                }
            } catch (Exception error) {
                String message = safeMessage(error);
                synchronized (output) {
                    if (output.length() > 0) output.append('\n');
                    output.append(message);
                }
                if (listener != null) listener.onOutput(stream, message);
            }
        }, "openhouse-control-plane-" + stream);
    }

    private HostActionResult launchActivity(String className, String label) {
        try {
            Intent intent = new Intent().setComponent(new ComponentName(TERMUX_PACKAGE, className));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            appContext.startActivity(intent);
            return new HostActionResult(HostActionResult.Status.COMPLETED, label + " opened");
        } catch (Exception error) {
            return new HostActionResult(HostActionResult.Status.FAILED, safeMessage(error));
        }
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

    private static LegacyRegistrySource createLegacyRegistrySource(File configDir) {
        return () -> loadLegacyRegistry(configDir);
    }

    private static LegacyRegistrySnapshot loadLegacyRegistry(File configDir) {
        if (configDir == null) return LegacyRegistrySnapshot.unavailable();
        File componentsDir = new File(configDir, "components.d");
        if (!componentsDir.isDirectory()) return LegacyRegistrySnapshot.unavailable();
        File[] files = componentsDir.listFiles((dir, name) -> name != null && name.endsWith(".json"));
        if (files == null || files.length == 0) return LegacyRegistrySnapshot.unavailable();
        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));

        List<RegistryManifest> manifests = new ArrayList<>();
        int invalidFiles = 0;
        for (File file : files) {
            try {
                String json = readText(file);
                if (json.isEmpty()) throw new IllegalArgumentException("component manifest is empty");
                manifests.add(RegistryManifest.fromManifestJson(
                    "components.d/" + file.getName(), json));
            } catch (Exception ignored) {
                invalidFiles++;
            }
        }
        if (manifests.isEmpty()) {
            return LegacyRegistrySnapshot.failure(
                "no valid component manifests in " + componentsDir.getAbsolutePath()
                    + " (invalid=" + invalidFiles + ")");
        }
        String stateJson = readText(new File(configDir, "registry-state.json"));
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

    private static JSONObject readObject(File file) {
        try {
            String text = readText(file);
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile() || file.length() > 2 * 1024 * 1024) return "";
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            return output.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
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
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }
}
