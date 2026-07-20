import { webApi } from "@/lib/web-api-client";

type Command = Record<string, unknown> & { type?: string };

/**
 * Compatibility surface for the migrated chat hook. It deliberately maps the
 * former in-process Pi commands onto the Web REST API; it never uses /v1/ws.
 */
export async function sendAgentCommand<T = unknown>(sessionId: string, command: Command): Promise<T> {
  switch (command.type) {
    case "prompt":
    case "steer":
    case "follow_up":
      return await webApi.prompt(sessionId, {
        message: command.message,
        images: command.images,
        streamingBehavior: command.streamingBehavior
          ?? (command.type === "steer" ? "steer" : command.type === "follow_up" ? "followUp" : undefined),
      }) as T;
    case "abort":
      return await webApi.abort(sessionId) as T;
    case "abort_compaction":
      return await webApi.abort(sessionId) as T;
    case "compact":
      return await webApi.compact(sessionId, typeof command.customInstructions === "string" ? command.customInstructions : undefined) as T;
    case "fork":
      return await webApi.fork(sessionId, String(command.entryId ?? "")) as T;
    case "set_model":
      return await webApi.updateModel(sessionId, String(command.provider ?? ""), String(command.modelId ?? "")) as T;
    case "set_thinking_level":
      return await webApi.updateThinkingLevel(sessionId, String(command.level ?? "")) as T;
    case "set_tools":
      return await webApi.updateTools(sessionId, Array.isArray(command.toolNames) ? command.toolNames.map(String) : []) as T;
    case "extension_ui_response":
      return await webApi.respondToExtensionUi(sessionId, {
        requestId: String(command.requestId ?? command.id ?? ""),
        ...(typeof command.value === "string" ? { value: command.value } : {}),
        ...(typeof command.confirmed === "boolean" ? { confirmed: command.confirmed } : {}),
        ...(command.cancelled === true ? { cancelled: true } : {}),
      }) as T;
    case "get_tools": {
      const result = await webApi.tools(sessionId);
      const active = new Set(Array.isArray(result.activeToolNames) ? result.activeToolNames.map(String) : []);
      const tools = Array.isArray(result.tools) ? result.tools.map((value) => {
        const tool = value && typeof value === "object" ? value as Record<string, unknown> : {};
        const name = String(tool.name ?? "");
        return { ...tool, name, active: active.has(name) };
      }) : [];
      return tools as T;
    }
    case "get_commands":
    case "get_slash_commands": {
      return await webApi.commands(sessionId) as T;
    }
    case "get_session_stats": {
      return await webApi.stats(sessionId) as T;
    }
    case "get_last_assistant_text": {
      const snapshot = await webApi.snapshot(sessionId);
      const assistant = [...(snapshot.history ?? [])].reverse().find((message) => message.role === "assistant");
      const text = assistant?.role === "assistant"
        ? assistant.content.filter((part) => part.type === "text").map((part) => part.text).join("\n")
        : "";
      return { text } as T;
    }
    case "navigate_tree":
      return await webApi.navigate(sessionId, String(command.targetId ?? "")) as T;
    case "set_session_name":
      return await webApi.setSessionName(sessionId, String(command.name ?? "")) as T;
    default:
      throw new Error(`Unsupported Web command: ${String(command.type ?? "unknown")}`);
  }
}
