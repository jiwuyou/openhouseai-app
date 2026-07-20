import type { ClientRequest } from "./protocol.js";
import { boundedInteger, optionalString, RequestError, requireString } from "./protocol.js";
import { requireIdle, type RuntimeSlot, SessionRegistry } from "./session-registry.js";

export class PiSdkAdapter {
  constructor(private readonly registry: SessionRegistry) {}

  async dispatch(request: ClientRequest): Promise<unknown> {
    const payload = request.payload ?? {};
    switch (request.type) {
      case "model.status":
        return this.modelStatus(optionalString(payload, "provider"));
      case "model.login":
        return this.modelLogin(payload);
      case "model.logout":
        return this.modelLogout(requireString(payload, "provider"));
      case "model.test":
        return this.modelTest(payload);
      case "model.reload":
        await this.registry.reloadModelConfiguration();
        return this.modelStatus();
      case "model.setDefault":
        return this.registry.setDefaultModel(
          requireString(payload, "provider"),
          requireString(payload, "modelId"),
          request.sessionId,
        );
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
            const userEntryId = session.sessionManager.getLeafId();
            if (!userEntryId) {
              reject(new RequestError("missing_user_entry", "Prompt was accepted without a persisted user entry"));
              return;
            }
            accepted = true;
            resolve({ accepted: true, userEntryId, ...this.registry.describe(slot) });
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

  private async modelStatus(providerFilter?: string) {
    const runtime = await this.registry.models();
    const providers = runtime.getProviders().filter((provider) => !providerFilter || provider.id === providerFilter);
    if (providerFilter && providers.length === 0) {
      throw new RequestError("provider_not_found", `Provider not found: ${providerFilter}`);
    }
    let available = new Set<string>();
    let availabilityError: string | undefined;
    try {
      available = new Set((await runtime.getAvailable(providerFilter)).map((model) => `${model.provider}\u0000${model.id}`));
    } catch (error) {
      availabilityError = error instanceof Error ? error.message : String(error);
    }
    const providerRows = await Promise.all(providers.map(async (provider) => {
      const configured = runtime.getProviderAuthStatus(provider.id);
      const check = await runtime.checkAuth(provider.id).catch(() => undefined);
      return {
        id: provider.id,
        name: provider.name,
        authenticated: configured.configured || check !== undefined,
        authType: check?.type,
        authSource: check?.source ?? configured.source,
        authLabel: configured.label,
      };
    }));
    const models = providers.flatMap((provider) => runtime.getModels(provider.id).map((model) => ({
      provider: model.provider,
      id: model.id,
      name: model.name,
      available: available.has(`${model.provider}\u0000${model.id}`),
      reasoning: model.reasoning,
      input: model.input,
      contextWindow: model.contextWindow,
      maxTokens: model.maxTokens,
    })));
    const settings = this.registry.settings();
    const defaultProvider = settings.getDefaultProvider();
    const defaultModelId = settings.getDefaultModel();
    return {
      providers: providerRows,
      models,
      defaultModel: defaultProvider && defaultModelId ? { provider: defaultProvider, modelId: defaultModelId } : null,
      availabilityError,
    };
  }

  private async modelLogin(payload: Record<string, unknown>) {
    const provider = requireString(payload, "provider");
    const method = optionalString(payload, "method") ?? "api_key";
    if (method !== "api_key") throw new RequestError("unsupported_auth_type", "Only api_key login is supported by this client");
    const apiKey = requireString(payload, "apiKey").trim();
    const runtime = await this.registry.models();
    if (!runtime.getProvider(provider)) throw new RequestError("provider_not_found", `Provider not found: ${provider}`);
    await runtime.login(provider, "api_key", {
      prompt: async () => apiKey,
      notify: () => {},
    });
    const auth = runtime.getProviderAuthStatus(provider);
    return { provider, authenticated: auth.configured, authSource: auth.source, authLabel: auth.label };
  }

  private async modelLogout(provider: string) {
    const runtime = await this.registry.models();
    if (!runtime.getProvider(provider)) throw new RequestError("provider_not_found", `Provider not found: ${provider}`);
    await runtime.logout(provider);
    return { provider, authenticated: false };
  }

  private async modelTest(payload: Record<string, unknown>) {
    const provider = requireString(payload, "provider");
    const modelId = requireString(payload, "modelId");
    const runtime = await this.registry.models();
    const model = runtime.getModel(provider, modelId);
    if (!model) throw new RequestError("model_not_found", `Model not found: ${provider}/${modelId}`);
    const timeoutMs = Math.max(1_000, boundedInteger(payload, "timeoutMs", 20_000, 60_000));
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    timer.unref();
    let responseStatus: number | undefined;
    const startedAt = Date.now();
    try {
      const response = await runtime.completeSimple(model, {
        messages: [{ role: "user", content: "Reply with OK only.", timestamp: Date.now() }],
      }, {
        maxTokens: 16,
        timeoutMs,
        maxRetries: 0,
        cacheRetention: "none",
        signal: controller.signal,
        onResponse: (providerResponse) => { responseStatus = providerResponse.status; },
      });
      if (response.stopReason === "error" || response.errorMessage) {
        throw new RequestError("model_test_failed", response.errorMessage ?? "Model test failed");
      }
      const text = response.content
        .filter((part): part is Extract<(typeof response.content)[number], { type: "text" }> => part.type === "text")
        .map((part) => part.text)
        .join("");
      return { ok: true, provider, modelId, latencyMs: Date.now() - startedAt, status: responseStatus, text };
    } finally {
      clearTimeout(timer);
    }
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
