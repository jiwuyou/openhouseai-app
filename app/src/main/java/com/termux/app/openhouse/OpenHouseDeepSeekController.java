package com.termux.app.openhouse;

import android.content.Context;

import com.termux.app.OpenCodeSettings;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class OpenHouseDeepSeekController {

    private static final String LOG_TAG = "OpenHouseDeepSeek";
    private static final int MAX_KEY_BYTES = 8192;

    private static volatile OpenHouseDeepSeekController instance;

    private final Context context;
    private final OpenHouseStatusRepository statusRepository;
    private final OpenHouseMaintainerRunner maintainerRunner;

    public static OpenHouseDeepSeekController getInstance(Context context) {
        if (instance == null) {
            synchronized (OpenHouseDeepSeekController.class) {
                if (instance == null) {
                    instance = new OpenHouseDeepSeekController(context);
                }
            }
        }
        return instance;
    }

    public OpenHouseDeepSeekController(Context context) {
        this.context = context.getApplicationContext();
        this.statusRepository = new OpenHouseStatusRepository(this.context);
        this.maintainerRunner = new OpenHouseMaintainerRunner(this.context);
    }

    public SaveResult saveKey(String apiKey) {
        String normalizedKey = normalizeApiKey(apiKey);
        if (normalizedKey.isEmpty()) {
            return new SaveResult(false, 2, "DeepSeek API Key 不能为空", statusRepository.loadOnboardingState());
        }

        try {
            writeSecretFile(OpenHouseStatusRepository.getSavedDeepSeekKeyFile(), normalizedKey);
            statusRepository.markDeepSeekConfigured(false);
            OpenHouseOnboardingState state = statusRepository.markDeepSeekKeySaved(true);
            return new SaveResult(true, 0, "DeepSeek API Key 已保存，尚未配置。", state);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save DeepSeek API key", e);
            return new SaveResult(false, 1, safeMessage(e), statusRepository.loadOnboardingState());
        }
    }

    public OpenHouseMaintainerRunner.Result configureKey(String apiKey) {
        SaveResult saveResult = saveKey(apiKey);
        if (!saveResult.isSuccess()) {
            return new OpenHouseMaintainerRunner.Result(
                OpenHouseMaintainerRunner.Action.CONFIGURE_DEEPSEEK,
                saveResult.exitCode,
                saveResult.message);
        }
        return configureSavedKey();
    }

    public SaveResult prepareKeyForTerminalConfiguration(String apiKey) {
        String normalizedKey = normalizeApiKey(apiKey);
        SaveResult saveResult = saveKey(normalizedKey);
        if (!saveResult.isSuccess()) {
            return saveResult;
        }

        try {
            writeSecretFile(OpenHouseStatusRepository.getDeepSeekKeyTempFile(), normalizedKey);
            return new SaveResult(true, 0, "DeepSeek API Key 已保存，准备配置。", saveResult.onboardingState);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to prepare DeepSeek API key for terminal configuration", e);
            return new SaveResult(false, 1, safeMessage(e), statusRepository.loadOnboardingState());
        }
    }

    public OpenHouseMaintainerRunner.Result configureSavedKey() {
        return configureSavedKey(true, true, true);
    }

    public OpenHouseMaintainerRunner.Result configureSavedKey(boolean configureOpenCode,
                                                             boolean configureClaudeCode,
                                                             boolean configureReasonix) {
        File savedKeyFile = OpenHouseStatusRepository.getSavedDeepSeekKeyFile();
        File tempKeyFile = OpenHouseStatusRepository.getDeepSeekKeyTempFile();
        if (!savedKeyFile.isFile() || savedKeyFile.length() <= 0L) {
            return new OpenHouseMaintainerRunner.Result(
                OpenHouseMaintainerRunner.Action.CONFIGURE_DEEPSEEK,
                2,
                "请先保存 DeepSeek API Key。");
        }

        try {
            String savedKey = normalizeApiKey(readSecretFile(savedKeyFile));
            if (savedKey.isEmpty()) {
                return new OpenHouseMaintainerRunner.Result(
                    OpenHouseMaintainerRunner.Action.CONFIGURE_DEEPSEEK,
                    2,
                    "DeepSeek API Key 为空，请重新保存。");
            }

            writeSecretFile(tempKeyFile, savedKey);
            Map<String, String> environment = new HashMap<>();
            environment.put("OPENHOUSEAI_CONFIGURE_OPENCODE", configureOpenCode ? "1" : "0");
            environment.put("OPENHOUSEAI_CONFIGURE_CLAUDE", configureClaudeCode ? "1" : "0");
            environment.put("OPENHOUSEAI_CONFIGURE_REASONIX", configureReasonix ? "1" : "0");
            OpenHouseMaintainerRunner.Result result = maintainerRunner.run(
                OpenHouseMaintainerRunner.Action.CONFIGURE_DEEPSEEK,
                OpenCodeSettings.DEFAULT_OPENCODE_PORT,
                environment);
            if (result.isSuccess()) {
                statusRepository.markDeepSeekConfigured(true);
            }
            return result;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to configure DeepSeek API key", e);
            return new OpenHouseMaintainerRunner.Result(
                OpenHouseMaintainerRunner.Action.CONFIGURE_DEEPSEEK,
                1,
                safeMessage(e));
        } finally {
            if (tempKeyFile.isFile() && !tempKeyFile.delete()) {
                Logger.logWarn(LOG_TAG, "Failed to delete temporary DeepSeek API key file");
            }
        }
    }

    public boolean hasSavedKey() {
        return statusRepository.hasSavedDeepSeekKey();
    }

    public OpenHouseOnboardingState skipKey() {
        return statusRepository.skipDeepSeekKey();
    }

    public OpenHouseOnboardingState skipConfiguration() {
        return statusRepository.skipDeepSeekConfiguration();
    }

    public OpenHouseOnboardingState getOnboardingState() {
        return statusRepository.loadOnboardingState();
    }

    public OpenHouseOnboardingState clearSavedKey() {
        File keyFile = OpenHouseStatusRepository.getSavedDeepSeekKeyFile();
        if (keyFile.isFile() && !keyFile.delete()) {
            Logger.logWarn(LOG_TAG, "Failed to delete saved DeepSeek API key file");
        }
        return statusRepository.markDeepSeekKeySaved(false);
    }

    private String readSecretFile(File file) throws Exception {
        long length = file.length();
        if (length <= 0L) {
            return "";
        }
        if (length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("DeepSeek API Key 文件过大");
        }

        byte[] data = new byte[(int) length];
        int offset = 0;
        try (FileInputStream inputStream = new FileInputStream(file)) {
            while (offset < data.length) {
                int read = inputStream.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return new String(data, 0, offset, StandardCharsets.UTF_8);
    }

    private void writeSecretFile(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        }
        restrictOwnerOnly(file);
    }

    private void restrictOwnerOnly(File file) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private String normalizeApiKey(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        return apiKey.replace("\r", "").replace("\n", "").trim();
    }

    private String safeMessage(Exception e) {
        String message = e == null ? "" : e.getMessage();
        if (message == null || message.isEmpty()) {
            return "unknown error";
        }
        return message.replaceAll("(?i)(sk-[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+", "$1***");
    }

    public static final class SaveResult {
        public final boolean success;
        public final int exitCode;
        public final String message;
        public final OpenHouseOnboardingState onboardingState;

        SaveResult(boolean success, int exitCode, String message, OpenHouseOnboardingState onboardingState) {
            this.success = success;
            this.exitCode = exitCode;
            this.message = message == null ? "" : message;
            this.onboardingState = onboardingState;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}
