import type { ClientRequest } from "./protocol.js";
import { boundedInteger, optionalString, RequestError, requireString } from "./protocol.js";
import { requireIdle, type RuntimeSlot, SessionRegistry } from "./session-registry.js";

export class PiSdkAdapter {
  constructor(private readonly registry: SessionRegistry) {}

  async dispatch(request: ClientRequest): Promise<unknown> {
    const payload = request.payload ?? {};
    switch (request.type) {
      case "runtime.status":
        return this.registry.status();
      case "session.list":
        return this.registry.list({
          cwd: optionalString(payload, "cwd"), all: payload.all === true,
          offset: boundedInteger(payload, "offset", 0, Number.MAX_SAFE_INTEGER),
          limit: boundedInteger(payload, "limit", 100, 1000),
        });
      case "session.history": {
        const reference = optionalString(payload, "sessionPath") ?? request.sessionId;
        if (!reference) throw new RequestError("invalid_payload", "sessionPath or sessionId is required");
        return this.registry.history(reference, boundedInteger(payload, "offset", 0, Number.MAX_SAFE_INTEGER),
          boundedInteger(payload, "limit", 200, 1000));
      }
      case "session.create":
        return this.registry.create(optionalString(payload, "cwd"));
      case "session.open": {
        const reference = optionalString(payload, "sessionPath") ?? optionalString(payload, "sessionId") ?? request.sessionId;
        if (!reference) throw new RequestError("invalid_payload", "sessionPath or sessionId is required");
        return this.registry.open(reference);
      }
      case "session.close":
        return this.registry.close(this.requireSessionId(request));
      default:
        return this.dispatchToSession(request, payload);
    }
  }

  private async dispatchToSession(request: ClientRequest, payload: Record<string, unknown>): Promise<unknown> {
    const sessionId = this.requireSessionId(request);
    const operation = async (slot: RuntimeSlot) => {
      const session = this.registry.session(slot);
      try {
        switch (request.type) {
          case "session.prompt":
            return this.prompt(slot, requireString(payload, "message"), payload);
          case "session.steer":
            await session.steer(requireString(payload, "message"), payload.images as never);
            return {};
          case "session.followUp":
            await session.followUp(requireString(payload, "message"), payload.images as never);
            return {};
          case "session.abort":
            await session.abort();
            return {};
          case "session.compact":
            requireIdle(slot, request.type);
            return session.compact(optionalString(payload, "customInstructions"));
          case "session.abortCompaction":
            session.abortCompaction();
            return {};
          case "session.clearQueue":
            return session.clearQueue();
          case "session.new": {
            requireIdle(slot, request.type);
            const parentSession = optionalString(payload, "parentSession");
            const result = await this.registry.runtime(slot).newSession(parentSession ? { parentSession } : undefined);
            return { ...result, ...this.registry.describe(slot) };
          }
          case "session.switch":
            requireIdle(slot, request.type);
            return this.registry.switch(slot, requireString(payload, "sessionPath"));
          case "session.fork": {
            requireIdle(slot, request.type);
            const position = optionalString(payload, "position") ?? "before";
            if (position !== "before" && position !== "at") {
              throw new RequestError("invalid_payload", "position must be before or at");
            }
            const result = await this.registry.runtime(slot).fork(requireString(payload, "entryId"), { position });
            return { cancelled: result.cancelled, text: result.selectedText, ...this.registry.describe(slot) };
          }
          case "session.import": {
            requireIdle(slot, request.type);
            const result = await this.registry.runtime(slot).importFromJsonl(
              requireString(payload, "inputPath"), optionalString(payload, "cwd"));
            return { ...result, ...this.registry.describe(slot) };
          }
          case "session.navigateTree":
            requireIdle(slot, request.type);
            return session.navigateTree(requireString(payload, "targetId"), {
              summarize: payload.summarize === true,
              customInstructions: optionalString(payload, "customInstructions"),
              replaceInstructions: payload.replaceInstructions === true,
              label: optionalString(payload, "label"),
            });
          case "session.reload":
            requireIdle(slot, request.type);
            await session.reload();
            return {};
          case "session.state":
            return this.state(slot);
          case "session.messages":
            return { messages: session.messages };
          case "session.entries": {
            let entries = session.sessionManager.getEntries();
            const since = optionalString(payload, "since");
            if (since) {
              const index = entries.findIndex((entry) => entry.id === since);
              if (index < 0) throw new RequestError("entry_not_found", `Entry not found: ${since}`);
              entries = entries.slice(index + 1);
            }
            return { entries, leafId: session.sessionManager.getLeafId() };
          }
          case "session.tree":
            return { tree: session.sessionManager.getTree(), leafId: session.sessionManager.getLeafId() };
          case "session.commands":
            return { commands: this.commands(slot) };
          case "session.tools":
            return { tools: session.getAllTools(), activeToolNames: session.getActiveToolNames() };
          case "session.setTools": {
            requireIdle(slot, request.type);
            const names = payload.toolNames;
            if (!Array.isArray(names) || !names.every((name) => typeof name === "string")) {
              throw new RequestError("invalid_payload", "toolNames must be an array of strings");
            }
            session.setActiveToolsByName(names);
            return { activeToolNames: session.getActiveToolNames() };
          }
          case "session.models":
            return { models: await session.modelRuntime.getAvailable() };
          case "session.setModel": {
            requireIdle(slot, request.type);
            const provider = requireString(payload, "provider");
            const modelId = requireString(payload, "modelId");
            const model = (await session.modelRuntime.getAvailable()).find((item) => item.provider === provider && item.id === modelId);
            if (!model) throw new RequestError("model_not_found", `Model not found: ${provider}/${modelId}`);
            await session.setModel(model);
            return model;
          }
          case "session.cycleModel":
            requireIdle(slot, request.type);
            return (await session.cycleModel(payload.direction === "backward" ? "backward" : "forward")) ?? null;
          case "session.setThinkingLevel":
            requireIdle(slot, request.type);
            session.setThinkingLevel(requireString(payload, "level") as Parameters<typeof session.setThinkingLevel>[0]);
            return { level: session.thinkingLevel };
          case "session.cycleThinkingLevel": {
            requireIdle(slot, request.type);
            const level = session.cycleThinkingLevel();
            return level ? { level } : null;
          }
          case "session.setName": {
            const name = requireString(payload, "name").trim();
            session.setSessionName(name);
            return {};
          }
          case "session.stats":
            return session.getSessionStats();
          case "session.lastAssistantText":
            return { text: session.getLastAssistantText() };
          case "extension.uiResponse": {
            const requestId = requireString(payload, "requestId");
            this.registry.respondToExtensionUi(slot, {
              requestId,
              value: optionalString(payload, "value"),
              confirmed: typeof payload.confirmed === "boolean" ? payload.confirmed : undefined,
              cancelled: payload.cancelled === true,
            });
            return {};
          }
          default:
            throw new RequestError("unknown_command", `Unknown command: ${request.type}`);
        }
      } catch (error) { throw error; }
    };
    return CONTROL_COMMANDS.has(request.type)
      ? this.registry.control(sessionId, operation)
      : this.registry.run(sessionId, operation);
  }

  private prompt(slot: RuntimeSlot, message: string, payload: Record<string, unknown>): Promise<unknown> {
    const session = this.registry.session(slot);
    const agentStartCount = this.registry.agentStartCount(slot);
    return new Promise((resolve, reject) => {
      let accepted = false;
      const run = session.prompt(message, {
        images: payload.images as never,
        streamingBehavior: payload.streamingBehavior === "steer" || payload.streamingBehavior === "followUp"
          ? payload.streamingBehavior : undefined,
        source: "rpc",
        preflightResult: (success) => {
          if (success) {
            accepted = true;
            resolve({ accepted: true, ...this.registry.describe(slot) });
          } else {
            reject(new RequestError("prompt_rejected", "Prompt was rejected before it was accepted"));
          }
        },
      });
      void run.catch((error) => {
        if (!accepted) reject(error);
        else this.registry.emitRuntimeError(slot, "session.prompt", error);
      }).then(() => {
        if (accepted && this.registry.agentStartCount(slot) === agentStartCount) this.registry.emitPromptCompleted(slot);
      });
    });
  }

  private state(slot: RuntimeSlot) {
    const session = this.registry.session(slot);
    const identity = this.registry.describe(slot);
    return {
      ...identity,
      model: session.model, thinkingLevel: session.thinkingLevel, isStreaming: session.isStreaming,
      isCompacting: session.isCompacting, steeringMode: session.steeringMode, followUpMode: session.followUpMode,
      sessionFile: session.sessionFile, sessionName: session.sessionName,
      autoCompactionEnabled: session.autoCompactionEnabled, messageCount: session.messages.length,
      pendingMessageCount: session.pendingMessageCount, contextUsage: session.getContextUsage(),
      systemPrompt: session.systemPrompt, extensionStatuses: {}, extensionWidgets: {},
      queuedMessages: { steering: [...session.getSteeringMessages()], followUp: [...session.getFollowUpMessages()] },
      isPromptRunning: identity.isRunning,
    };
  }

  private commands(slot: RuntimeSlot) {
    const session = this.registry.session(slot);
    return [
      ...session.extensionRunner.getRegisteredCommands().map((command) => ({
        name: command.invocationName, description: command.description, source: "extension", sourceInfo: command.sourceInfo,
      })),
      ...session.promptTemplates.map((template) => ({
        name: template.name, description: template.description, source: "prompt", sourceInfo: template.sourceInfo,
      })),
      ...session.resourceLoader.getSkills().skills.map((skill) => ({
        name: `skill:${skill.name}`, description: skill.description, source: "skill", sourceInfo: skill.sourceInfo,
      })),
    ];
  }

  private requireSessionId(request: ClientRequest): string {
    if (!request.sessionId) throw new RequestError("session_required", "sessionId is required");
    return request.sessionId;
  }
}

export const CONTROL_COMMANDS = new Set([
  "session.steer", "session.followUp", "session.abort", "session.abortCompaction",
  "session.clearQueue", "extension.uiResponse", "session.state",
]);
