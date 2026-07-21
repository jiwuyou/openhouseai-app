package com.openhouse.host.termux;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

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
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Complete host adapter for the Termux-embedded OpenHouse edition. */
public final class TermuxOpenHouseHost implements OpenHouseHost {

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final String DEFAULT_PI_RUNTIME_URL = "http://127.0.0.1:8765";

    private static final File SERVICE_MANAGER_CONFIG = new File(
        TERMUX_HOME + "/.config/openhouseai/service-manager/config.json");
    private final Context appContext;
    private final ControlPlaneStarter controlPlaneStarter = new TermuxControlPlaneStarter();

    public TermuxOpenHouseHost(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        appContext = context.getApplicationContext();
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

    @Override public HostActionResult openTerminal() {
        return launchActivity("com.termux.app.TermuxActivity", "Termux terminal");
    }

    @Override public HostActionResult openHostMaintenance() {
        return launchActivity("com.termux.app.activities.MaintenanceCenterActivity", "OpenHouse maintenance");
    }

    private final class TermuxControlPlaneStarter implements ControlPlaneStarter {
        @Override public ControlPlaneResult startControlPlane() {
            try {
                Class<?> runnerClass = Class.forName("com.termux.app.openhouse.OpenHouseMaintainerRunner");
                Class<?> actionClass = Class.forName("com.termux.app.openhouse.OpenHouseMaintainerRunner$Action");
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object action = Enum.valueOf((Class<? extends Enum>) actionClass.asSubclass(Enum.class),
                    "START_CONTROL_PLANE");
                Constructor<?> constructor = runnerClass.getConstructor(Context.class);
                Object runner = constructor.newInstance(appContext);
                Method run = runnerClass.getMethod("run", actionClass, int.class);
                Object result = run.invoke(runner, action, 0);
                Field exitCode = result.getClass().getField("exitCode");
                Field output = result.getClass().getField("output");
                int code = exitCode.getInt(result);
                String message = String.valueOf(output.get(result));
                return new ControlPlaneResult(
                    code == 0 ? ControlPlaneResult.Status.STARTED : ControlPlaneResult.Status.FAILED,
                    message
                );
            } catch (Exception error) {
                return new ControlPlaneResult(ControlPlaneResult.Status.FAILED, safeMessage(error));
            }
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
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
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
