import type { ClientRequest } from "./protocol.js";
import { boundedInteger, optionalString, PROTOCOL_NAME, RequestError, requireString } from "./protocol.js";
import { SessionRegistry } from "./session-registry.js";

export class PiSdkAdapter {
  constructor(private readonly registry: SessionRegistry) {}

  async dispatch(request: ClientRequest): Promise<unknown> {
    const payload = request.payload ?? {};
    switch (request.type) {
      case "model.status":
        return this.registry.modelSetup().setup(optionalString(payload, "provider"));
      case "model.login": {
        const method = optionalString(payload, "method") ?? "api_key";
        if (method !== "api_key") throw new RequestError("unsupported_auth_type", "Only api_key login is supported by this client");
        return this.registry.modelSetup().login(requireString(payload, "provider"), requireString(payload, "apiKey"));
      }
      case "model.logout":
        return this.registry.modelSetup().logout(requireString(payload, "provider"));
      case "model.test":
        return this.registry.modelSetup().testModel(payload);
      case "model.reload":
        return this.registry.modelSetup().reload();
      case "model.setDefault":
        if (payload.setGlobalDefault !== undefined && typeof payload.setGlobalDefault !== "boolean") {
          throw new RequestError("invalid_payload", "setGlobalDefault must be a boolean");
        }
        return this.registry.setDefaultModel(
          requireString(payload, "provider"),
          requireString(payload, "modelId"),
          request.sessionId,
          payload.setGlobalDefault as boolean | undefined,
        );
      case "runtime.status":
        return { protocol: PROTOCOL_NAME, ...this.registry.status() };
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
    switch (request.type) {
      case "session.prompt":
        return this.registry.prompt(sessionId, {
          message: requireString(payload, "message"),
          images: payload.images,
          streamingBehavior: payload.streamingBehavior === "steer" || payload.streamingBehavior === "followUp"
            ? payload.streamingBehavior : undefined,
          source: "rpc",
        });
      case "session.steer": await this.registry.steer(sessionId, requireString(payload, "message"), payload.images); return {};
      case "session.followUp": await this.registry.followUp(sessionId, requireString(payload, "message"), payload.images); return {};
      case "session.abort": await this.registry.abort(sessionId); return {};
      case "session.compact": return this.registry.compact(sessionId, optionalString(payload, "customInstructions"));
      case "session.abortCompaction": await this.registry.abortCompaction(sessionId); return {};
      case "session.clearQueue": return this.registry.clearQueue(sessionId);
      case "session.new": return this.registry.newSession(sessionId, optionalString(payload, "parentSession"));
      case "session.switch": return this.registry.switchSession(sessionId, requireString(payload, "sessionPath"));
      case "session.fork": {
        const position = optionalString(payload, "position") ?? "before";
        if (position !== "before" && position !== "at") throw new RequestError("invalid_payload", "position must be before or at");
        return this.registry.fork(sessionId, requireString(payload, "entryId"), position);
      }
      case "session.import": return this.registry.importSession(sessionId, requireString(payload, "inputPath"), optionalString(payload, "cwd"));
      case "session.navigateTree": return this.registry.navigateTree(sessionId, requireString(payload, "targetId"), {
        summarize: payload.summarize === true,
        customInstructions: optionalString(payload, "customInstructions"),
        replaceInstructions: payload.replaceInstructions === true,
        label: optionalString(payload, "label"),
      });
      case "session.reload": await this.registry.reloadSession(sessionId); return {};
      case "session.state": return this.registry.state(sessionId);
      case "session.messages": return this.registry.messages(sessionId);
      case "session.entries": return this.registry.entries(sessionId, optionalString(payload, "since"));
      case "session.tree": return this.registry.tree(sessionId);
      case "session.commands": return this.registry.commands(sessionId);
      case "session.tools": return this.registry.tools(sessionId);
      case "session.setTools": {
        const names = payload.toolNames;
        if (!Array.isArray(names) || !names.every((name) => typeof name === "string")) {
          throw new RequestError("invalid_payload", "toolNames must be an array of strings");
        }
        return this.registry.setTools(sessionId, names);
      }
      case "session.models": return this.registry.sessionModels(sessionId);
      case "session.setModel": return this.registry.setModel(sessionId, requireString(payload, "provider"), requireString(payload, "modelId"));
      case "session.cycleModel": return this.registry.cycleModel(sessionId, payload.direction === "backward" ? "backward" : "forward");
      case "session.setThinkingLevel": return this.registry.setThinkingLevel(sessionId, requireString(payload, "level"));
      case "session.cycleThinkingLevel": return this.registry.cycleThinkingLevel(sessionId);
      case "session.setName": await this.registry.setName(sessionId, requireString(payload, "name")); return {};
      case "session.stats": return this.registry.stats(sessionId);
      case "session.lastAssistantText": return this.registry.lastAssistantText(sessionId);
      case "extension.uiResponse": await this.registry.extensionUiResponse(sessionId, {
        requestId: requireString(payload, "requestId"),
        value: optionalString(payload, "value"),
        confirmed: typeof payload.confirmed === "boolean" ? payload.confirmed : undefined,
        cancelled: payload.cancelled === true,
      }); return {};
      default: throw new RequestError("unknown_command", `Unknown command: ${request.type}`);
    }
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
