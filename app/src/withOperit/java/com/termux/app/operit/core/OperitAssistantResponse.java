package com.termux.app.operit.core;

import com.termux.app.operit.init.OperitFeatureInitializer;
import com.termux.app.operit.init.OperitFeatureSnapshot;
import com.termux.app.operit.runtime.OperitCommandResult;
import com.termux.app.operit.runtime.OperitServiceManagerResult;

public final class OperitAssistantResponse {

    private final String input;
    private final String output;
    private final boolean success;
    private final String error;
    private final OperitCommandResult commandResult;
    private final OperitServiceManagerResult serviceManagerResult;
    private final OperitFeatureSnapshot featureSnapshot;

    private OperitAssistantResponse(
        String input,
        String output,
        boolean success,
        String error,
        OperitCommandResult commandResult,
        OperitServiceManagerResult serviceManagerResult,
        OperitFeatureSnapshot featureSnapshot
    ) {
        this.input = input == null ? "" : input;
        this.output = output == null ? "" : output;
        this.success = success;
        this.error = error == null ? "" : error;
        this.commandResult = commandResult;
        this.serviceManagerResult = serviceManagerResult;
        this.featureSnapshot = featureSnapshot == null ? currentFeatureSnapshot() : featureSnapshot;
    }

    public static OperitAssistantResponse message(String input, String output) {
        return new OperitAssistantResponse(input, output, true, "", null, null, currentFeatureSnapshot());
    }

    public static OperitAssistantResponse invalid(String input, String output) {
        return new OperitAssistantResponse(input, output, false, output, null, null, currentFeatureSnapshot());
    }

    public static OperitAssistantResponse initializationRequired(
        String input,
        OperitFeatureSnapshot featureSnapshot
    ) {
        String output = "Operit Core Adapter 尚未由 SmallPhoneAI 宿主初始化。"
            + "请使用带 Context 的 OperitAssistantFacade 构造方式接入，当前不会伪装完整 LLM 已接入。";
        String snapshotText = featureSnapshot == null ? "" : featureSnapshot.toDisplayText();
        if (!snapshotText.isEmpty()) {
            output = output + "\n" + snapshotText;
        }
        String error = featureSnapshot == null || featureSnapshot.getError().isEmpty()
            ? "Operit feature initializer has not run."
            : featureSnapshot.getError();
        return new OperitAssistantResponse(input, output, false, error, null, null, featureSnapshot);
    }

    public static OperitAssistantResponse command(
        String input,
        String output,
        OperitCommandResult commandResult
    ) {
        if (commandResult == null) {
            return new OperitAssistantResponse(
                input,
                output,
                false,
                "Runtime bridge returned no command result.",
                null,
                null,
                currentFeatureSnapshot()
            );
        }
        return new OperitAssistantResponse(
            input,
            output,
            commandResult.isSuccess(),
            commandResult.error,
            commandResult,
            null,
            currentFeatureSnapshot()
        );
    }

    public static OperitAssistantResponse serviceManager(
        String input,
        String output,
        OperitServiceManagerResult serviceManagerResult
    ) {
        if (serviceManagerResult == null) {
            return new OperitAssistantResponse(
                input,
                output,
                false,
                "Runtime bridge returned no service-manager result.",
                null,
                null,
                currentFeatureSnapshot()
            );
        }
        return new OperitAssistantResponse(
            input,
            output,
            serviceManagerResult.success,
            serviceManagerResult.error,
            null,
            serviceManagerResult,
            currentFeatureSnapshot()
        );
    }

    public static OperitAssistantResponse failure(String input, String output, Throwable throwable) {
        String baseOutput = output == null || output.trim().isEmpty()
            ? "Operit core adapter 调用失败。"
            : output;
        String error = throwable == null
            ? baseOutput
            : throwable.getClass().getSimpleName() + ": " + safeMessage(throwable);
        String visibleOutput = baseOutput;
        if (error != null && !error.isEmpty()) {
            visibleOutput = baseOutput + "\n" + error;
        }
        return new OperitAssistantResponse(
            input,
            visibleOutput,
            false,
            error,
            null,
            null,
            currentFeatureSnapshot()
        );
    }

    public OperitAssistantResponse withFeatureSnapshot(OperitFeatureSnapshot snapshot) {
        if (snapshot == null || snapshot == featureSnapshot) {
            return this;
        }
        return new OperitAssistantResponse(
            input,
            output,
            success,
            error,
            commandResult,
            serviceManagerResult,
            snapshot
        );
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public OperitCommandResult getCommandResult() {
        return commandResult;
    }

    public boolean hasCommandResult() {
        return commandResult != null;
    }

    public OperitServiceManagerResult getServiceManagerResult() {
        return serviceManagerResult;
    }

    public boolean hasServiceManagerResult() {
        return serviceManagerResult != null;
    }

    public OperitFeatureSnapshot getFeatureSnapshot() {
        return featureSnapshot;
    }

    public boolean hasInitializedFeature() {
        return featureSnapshot != null && featureSnapshot.isInitialized();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "no detail message" : message;
    }

    private static OperitFeatureSnapshot currentFeatureSnapshot() {
        return OperitFeatureInitializer.getInstance().snapshot();
    }
}
