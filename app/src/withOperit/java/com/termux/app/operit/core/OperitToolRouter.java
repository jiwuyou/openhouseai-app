package com.termux.app.operit.core;

import com.termux.app.operit.runtime.OperitCommandResult;
import com.termux.app.operit.runtime.OperitRuntimeBridge;
import com.termux.app.operit.runtime.OperitRuntimeTarget;
import com.termux.app.operit.runtime.OperitServiceManagerResult;

import java.util.Locale;

public final class OperitToolRouter {

    private static final String TERMUX_PREFIX = "/termux";
    private static final String UBUNTU_PREFIX = "/ubuntu";
    private static final String SERVICE_MANAGER_PREFIX = "/service-manager";
    private static final String SUPPORTED_COMMANDS_TEXT = "/termux <command>\n"
        + "/ubuntu <command>\n"
        + "/service-manager health\n"
        + "/service-manager status <serviceId>\n"
        + "/service-manager repair\n"
        + "/service-manager recover";

    private final OperitRuntimeBridge runtimeBridge;

    public OperitToolRouter(OperitRuntimeBridge runtimeBridge) {
        if (runtimeBridge == null) {
            throw new IllegalArgumentException("runtimeBridge must not be null");
        }
        this.runtimeBridge = runtimeBridge;
    }

    public OperitAssistantResponse route(String input) throws Exception {
        String originalInput = input == null ? "" : input;
        String trimmedInput = originalInput.trim();

        if (trimmedInput.isEmpty()) {
            return integrationStatus(originalInput);
        }
        if (matchesCommand(trimmedInput, TERMUX_PREFIX)) {
            return executeRuntimeCommand(
                originalInput,
                trimmedInput,
                TERMUX_PREFIX,
                OperitRuntimeTarget.TERMUX,
                "Termux"
            );
        }
        if (matchesCommand(trimmedInput, UBUNTU_PREFIX)) {
            return executeRuntimeCommand(
                originalInput,
                trimmedInput,
                UBUNTU_PREFIX,
                OperitRuntimeTarget.UBUNTU,
                "Ubuntu"
            );
        }
        if (matchesCommand(trimmedInput, SERVICE_MANAGER_PREFIX)) {
            return executeServiceManagerCommand(originalInput, trimmedInput);
        }

        return integrationStatus(originalInput);
    }

    private OperitAssistantResponse executeRuntimeCommand(
        String originalInput,
        String trimmedInput,
        String prefix,
        OperitRuntimeTarget target,
        String targetLabel
    ) throws Exception {
        String command = argumentAfterPrefix(trimmedInput, prefix);
        if (command.isEmpty()) {
            return OperitAssistantResponse.invalid(
                originalInput,
                "缺少命令。用法：" + prefix + " <command>"
            );
        }
        if (looksLikeBackgroundDaemonCommand(command)) {
            return OperitAssistantResponse.invalid(
                originalInput,
                "Operit Core Adapter 不处理长驻后台进程。请通过 SmallPhoneAI service-manager 管理常驻服务；如果控制中枢不可达，请使用 /service-manager recover。"
            );
        }

        OperitCommandResult result = runtimeBridge.execute(target, command);
        return OperitAssistantResponse.command(
            originalInput,
            targetLabel + " 命令已通过 Operit runtime bridge 执行。",
            result
        );
    }

    private OperitAssistantResponse executeServiceManagerCommand(
        String originalInput,
        String trimmedInput
    ) throws Exception {
        String argument = argumentAfterPrefix(trimmedInput, SERVICE_MANAGER_PREFIX);
        if (argument.isEmpty()) {
            return OperitAssistantResponse.invalid(
                originalInput,
                serviceManagerUsage()
            );
        }
        if ("health".equals(argument)) {
            OperitServiceManagerResult result = runtimeBridge.getServiceManagerHealth();
            return OperitAssistantResponse.serviceManager(
                originalInput,
                "service-manager health 已通过 Operit runtime bridge 查询。",
                result
            );
        }
        if ("repair".equals(argument) || "recover".equals(argument)) {
            OperitServiceManagerResult result = runtimeBridge.recoverServiceManager();
            return OperitAssistantResponse.serviceManager(
                originalInput,
                "service-manager 控制中枢恢复已通过 Operit typed runtime bridge 触发。",
                result
            );
        }
        if (argument.startsWith("status ")) {
            String serviceId = argument.substring("status ".length()).trim();
            if (serviceId.isEmpty()) {
                return OperitAssistantResponse.invalid(
                    originalInput,
                    "缺少 serviceId。用法：/service-manager status <serviceId>"
                );
            }
            OperitServiceManagerResult result = runtimeBridge.getServiceManagerStatus(serviceId);
            return OperitAssistantResponse.serviceManager(
                originalInput,
                "service-manager 服务状态已通过 Operit runtime bridge 查询。",
                result
            );
        }

        return OperitAssistantResponse.invalid(originalInput, serviceManagerUsage());
    }

    private OperitAssistantResponse integrationStatus(String originalInput) {
        return OperitAssistantResponse.message(
            originalInput,
            "Operit Core Adapter 已接入 SmallPhoneAI 宿主，但完整 LLM、Compose UI、MCP 和 native 能力尚未迁入。\n"
                + "当前支持：\n"
                + SUPPORTED_COMMANDS_TEXT
        );
    }

    private static String serviceManagerUsage() {
        return "不支持的 service-manager 命令。当前支持：\n"
            + "/service-manager health\n"
            + "/service-manager status <serviceId>\n"
            + "/service-manager repair\n"
            + "/service-manager recover";
    }

    public static String supportedCommandsText() {
        return SUPPORTED_COMMANDS_TEXT;
    }

    private static boolean looksLikeBackgroundDaemonCommand(String command) {
        String normalized = command.trim().toLowerCase(Locale.US);
        return normalized.endsWith(" &")
            || normalized.startsWith("nohup ")
            || normalized.contains(" nohup ")
            || normalized.startsWith("setsid ")
            || normalized.contains(" setsid ")
            || normalized.contains(" --daemon")
            || normalized.contains(" daemon ")
            || normalized.startsWith("daemon ")
            || normalized.startsWith("systemctl start ")
            || normalized.contains(" systemctl start ")
            || (normalized.startsWith("service ") && normalized.endsWith(" start"));
    }

    private static boolean matchesCommand(String input, String command) {
        return input.equals(command) || input.startsWith(command + " ");
    }

    private static String argumentAfterPrefix(String input, String prefix) {
        if (input.length() == prefix.length()) {
            return "";
        }
        return input.substring(prefix.length()).trim();
    }
}
