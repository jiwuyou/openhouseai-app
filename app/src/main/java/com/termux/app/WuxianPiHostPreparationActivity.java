package com.termux.app;

import android.app.Activity;
import android.os.Bundle;
import android.system.Os;
import android.view.Gravity;
import android.widget.TextView;

import com.termux.app.openhouse.OpenHouseBundledRuntimeSync;
import com.termux.app.openhouse.resources.OpenHouseBundledResourceDelivery;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Exported, deterministic preparation entry for the embedded WuxianPi Termux host. */
public final class WuxianPiHostPreparationActivity extends Activity {

    public static final String ACTION_PREPARE_HOST = "com.termux.WUXIANPI_PREPARE_HOST";
    private static final String LOG_TAG = "WuxianPiHostPrepare";
    private static final String SETUP_COMMAND_RELATIVE_PATH = "bootstrap/scripts/wuxianpi-setup";
    private static final String HOST_STATE_RELATIVE_PATH = ".local/state/wuxianpi-setup/host-preparation.json";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(48, 48, 48, 48);
        statusView.setText("正在准备 WuxianPi Termux 运行环境...");
        setContentView(statusView);
        TermuxInstaller.setupBootstrapIfNeeded(this, this::stageRuntimeHost);
    }

    private void stageRuntimeHost() {
        statusView.setText("正在投放 WuxianPi 安装资源...");
        executor.execute(() -> {
            try {
                OpenHouseBundledRuntimeSync.Result runtime = OpenHouseBundledRuntimeSync.sync(
                    getApplicationContext(), OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL);
                File source = new File(runtime.resourceDir, SETUP_COMMAND_RELATIVE_PATH);
                File target = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "wuxianpi-setup");
                installSetupCommand(source, target);
                writeHostState(true, runtime.resourceDir, target, "ready");
                Logger.logInfo(LOG_TAG, "Prepared embedded host: " + runtime.toLogString());
                runOnUiThread(() -> {
                    statusView.setText("WuxianPi Termux 运行环境已准备完成");
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to prepare embedded WuxianPi host", e);
                try {
                    writeHostState(false, null, null, e.getMessage());
                } catch (Exception ignored) {
                }
                runOnUiThread(() -> {
                    statusView.setText("WuxianPi 运行环境准备失败：" + safeMessage(e));
                    setResult(RESULT_CANCELED);
                });
            }
        });
    }

    static File installSetupCommand(File source, File target) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("Staged wuxianpi-setup is missing");
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Cannot create Termux bin directory");
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Os.chmod(temporary.getAbsolutePath(), 0700);
        } catch (Exception e) {
            if (!temporary.setExecutable(true, true)) {
                throw new IOException("Cannot make wuxianpi-setup executable", e);
            }
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void writeHostState(boolean success, File resourceDir, File command, String message)
        throws Exception {
        File state = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, HOST_STATE_RELATIVE_PATH);
        File parent = state.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Cannot create WuxianPi setup state directory");
        }
        JSONObject value = new JSONObject()
            .put("ready", success)
            .put("resourceDir", resourceDir == null ? "" : resourceDir.getAbsolutePath())
            .put("command", command == null ? "" : command.getAbsolutePath())
            .put("message", message == null ? "" : message)
            .put("updatedAt", System.currentTimeMillis());
        File temporary = new File(parent, state.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Files.move(temporary.toPath(), state.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "unknown error" : error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
