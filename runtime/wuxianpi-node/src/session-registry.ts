import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import {
  type AgentSession, type AgentSessionEvent, type AgentSessionRuntime, buildSessionContext,
  type CreateAgentSessionRuntimeFactory, createAgentSessionFromServices,
  createAgentSessionRuntime, createAgentSessionServices, getAgentDir, ModelRuntime,
  type SessionEntry, SessionManager, SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { ExtensionUiBridge } from "./extension-ui.js";
import type { PersistentDiagnostics } from "./diagnostics.js";
import { ModelSetupService } from "./model-setup-service.js";
import { RequestError } from "./protocol.js";

export class SerialExecutor {
  private tail: Promise<void> = Promise.resolve();
  run<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.tail.then(operation, operation);
    this.tail = result.then(() => undefined, () => undefined);
    return result;
  }
}

export function requireIdle(slot: RuntimeSlot, commandType: string): void {
  if (slot.isRunning || !slot.runtime.session.isIdle) {
    throw new RequestError("session_busy", `${commandType} requires agent_settled`);
  }
}

export function runDetached(operation: Promise<unknown>, onError: (error: unknown) => void): void {
  void operation.catch(onError);
}

export interface RuntimeIdentity {
  sessionId: string; sessionPath?: string; cwd: string; isRunning: boolean; isIdle: boolean;
}

export type RuntimeSlot = {
  runtime: AgentSessionRuntime; serial: SerialExecutor; isRunning: boolean;
  agentStartCount: number; createdAt: Date; closeAfterSettled: boolean; unsubscribe?: () => void;
  ui?: ExtensionUiBridge; reclaimTimer?: NodeJS.Timeout;
  modelStatus: {
    state: "ready" | "pending" | "invalid";
    provider?: string;
    modelId?: string;
    code?: string;
    message?: string;
  };
};

export interface RegistrySessionEvent {
  sessionId: string;
  sessionPath?: string;
  payload: unknown;
  /** An in-memory runtime token. Transports may project it into their own stream identity. */
  runtime: RuntimeSlot;
}

export type EventSink = (event: RegistrySessionEvent) => void;

export interface PromptInput {
  message: string;
  images?: unknown;
  streamingBehavior?: "steer" | "followUp";
  source?: "rpc" | "interactive" | "extension";
}

export interface SnapshotSubscription {
  snapshot: Record<string, unknown>;
  activate(): void;
  unsubscribe(): void;
}

export class SessionRegistry {
  private readonly byId = new Map<string, RuntimeSlot>();
  private readonly byPath = new Map<string, RuntimeSlot>();
  private readonly opening = new Map<string, Promise<RuntimeSlot>>();
  private readonly slots = new Set<RuntimeSlot>();
  private readonly idleTimeoutMs: number;
  private readonly agentDir: string;
  private readonly sharedModelRuntime: Promise<ModelRuntime>;
  private readonly modelSettings: SettingsManager;
  private readonly modelSetupService: ModelSetupService;
  private readonly listeners = new Set<EventSink>();

  constructor(emitEvent?: EventSink, options: {
    idleTimeoutMs?: number;
    agentDir?: string;
    modelRuntime?: ModelRuntime;
    settingsManager?: SettingsManager;
    diagnostics?: PersistentDiagnostics;
  } = {}) {
    this.idleTimeoutMs = options.idleTimeoutMs ?? 5 * 60_000;
    this.agentDir = options.agentDir ?? getAgentDir();
    this.sharedModelRuntime = options.modelRuntime
      ? Promise.resolve(options.modelRuntime)
      : ModelRuntime.create({
          authPath: join(this.agentDir, "auth.json"),
          modelsPath: join(this.agentDir, "models.json"),
        });
    this.modelSettings = options.settingsManager ?? SettingsManager.create(process.cwd(), this.agentDir);
    this.modelSetupService = new ModelSetupService({
      agentDir: this.agentDir,
      modelRuntime: () => this.sharedModelRuntime,
      settingsManager: this.modelSettings,
      reload: () => this.reloadModelConfiguration(),
    });
    this.diagnostics = options.diagnostics;
    if (emitEvent) this.listeners.add(emitEvent);
  }

  private readonly diagnostics?: PersistentDiagnostics;

  get size(): number { return this.slots.size; }

  subscribe(listener: EventSink): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  status() {
    return { activeSessions: [...this.slots].map((slot) => this.identity(slot)) };
  }

  models(): Promise<ModelRuntime> { return this.sharedModelRuntime; }
  settings(): SettingsManager { return this.modelSettings; }
  modelSetup(): ModelSetupService { return this.modelSetupService; }

  async reloadModelConfiguration(): Promise<void> {
    const modelRuntime = await this.sharedModelRuntime;
    await modelRuntime.reloadConfig();
    await this.modelSettings.reload();
    const errors = this.modelSettings.drainErrors();
    if (errors.length > 0) throw settingsErrors("model settings reload", errors);
    await Promise.all([...this.slots].map((slot) => this.refreshSessionModel(slot)));
  }

  async setDefaultModel(provider: string, modelId: string, sessionId?: string, setGlobalDefault?: boolean) {
    if (!sessionId) return this.modelSetupService.setDefault(provider, modelId);
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "model.setDefault");
      const model = (await slot.runtime.session.modelRuntime.getAvailable())
        .find((item) => item.provider === provider && item.id === modelId);
      if (!model) throw new RequestError("model_not_found", `Model not found: ${provider}/${modelId}`);
      if (setGlobalDefault === true) await this.modelSetupService.setDefault(provider, modelId);
      await slot.runtime.session.setModel(model);
      this.markSessionModelReady(slot);
      await slot.runtime.session.settingsManager.flush();
      const errors = slot.runtime.session.settingsManager.drainErrors();
      if (errors.length > 0) throw settingsErrors("session model settings", errors);
      return { provider, modelId, appliedSessionIds: [slot.runtime.session.sessionId] };
    });
  }

  async list(options: { cwd?: string; all?: boolean; offset: number; limit: number }) {
    const sessions = options.all || !options.cwd ? await SessionManager.listAll() : await SessionManager.list(resolve(options.cwd));
    const rows = sessions.map((session) => ({
      sessionPath: session.path, sessionId: session.id, cwd: session.cwd, name: session.name,
      parentSessionPath: session.parentSessionPath, createdAt: session.created.toISOString(),
      modifiedAt: session.modified.toISOString(), messageCount: session.messageCount,
      firstMessage: session.firstMessage, isRunning: this.byPath.get(this.canonicalPath(session.path))?.isRunning ?? false,
    }));
    const knownIds = new Set(rows.map((row) => row.sessionId));
    for (const slot of this.slots) {
      const session = slot.runtime.session;
      if (knownIds.has(session.sessionId)) continue;
      if (options.cwd && !options.all && resolve(slot.runtime.cwd) !== resolve(options.cwd)) continue;
      rows.push({
        sessionPath: session.sessionFile ?? "", sessionId: session.sessionId, cwd: slot.runtime.cwd,
        name: session.sessionName, parentSessionPath: undefined, createdAt: slot.createdAt.toISOString(),
        modifiedAt: slot.createdAt.toISOString(), messageCount: session.messages.length,
        firstMessage: this.firstUserMessage(session.messages), isRunning: slot.isRunning || session.isStreaming,
      });
    }
    rows.sort((left, right) => right.modifiedAt.localeCompare(left.modifiedAt));
    return { sessions: rows.slice(options.offset, options.offset + options.limit), total: rows.length,
      offset: options.offset, limit: options.limit };
  }

  async history(reference: string, offset: number, limit: number) {
    const active = this.activeReference(reference);
    if (active) {
      const manager = active.runtime.session.sessionManager;
      const allMessages = manager.buildSessionContext().messages;
      return { messages: allMessages.slice(offset, offset + limit), entries: manager.getEntries(), total: allMessages.length,
        offset, limit, sessionPath: manager.getSessionFile(), sessionId: manager.getSessionId(), cwd: manager.getCwd() };
    }
    const sessionPath = await this.resolveSessionPath(reference);
    const manager = SessionManager.open(sessionPath);
    const allMessages = manager.buildSessionContext().messages;
    return {
      messages: allMessages.slice(offset, offset + limit), entries: manager.getEntries(), total: allMessages.length,
      offset, limit, sessionPath, sessionId: manager.getSessionId(), cwd: manager.getCwd(),
    };
  }

  async create(cwd = process.cwd()): Promise<RuntimeIdentity> {
    return this.identity(await this.createSlot(SessionManager.create(resolve(cwd))));
  }

  async open(reference: string): Promise<RuntimeIdentity> {
    const active = this.activeReference(reference);
    if (active) { this.cancelReclaim(active); return this.identity(active); }
    const path = await this.resolveSessionPath(reference);
    const canonical = this.canonicalPath(path);
    const existing = this.byPath.get(canonical);
    if (existing) { this.cancelReclaim(existing); return this.identity(existing); }
    const inFlight = this.opening.get(canonical);
    if (inFlight) return this.identity(await inFlight);
    const opening = this.createSlot(SessionManager.open(path));
    this.opening.set(canonical, opening);
    try { return this.identity(await opening); } finally { this.opening.delete(canonical); }
  }

  async getOrOpen(sessionId: string): Promise<RuntimeSlot> {
    const existing = this.byId.get(sessionId);
    if (existing) { this.cancelReclaim(existing); return existing; }
    const openedIdentity = await this.open(sessionId);
    const opened = this.byId.get(openedIdentity.sessionId);
    if (!opened) throw new RequestError("session_not_found", `Session not found: ${sessionId}`);
    return opened;
  }

  async run<T>(sessionId: string, operation: (slot: RuntimeSlot) => Promise<T>): Promise<T> {
    const slot = await this.getOrOpen(sessionId);
    this.cancelReclaim(slot);
    return slot.serial.run(() => operation(slot));
  }

  async control<T>(sessionId: string, operation: (slot: RuntimeSlot) => Promise<T>): Promise<T> {
    const slot = await this.getOrOpen(sessionId);
    this.cancelReclaim(slot);
    return operation(slot);
  }

  async prompt(sessionId: string, input: PromptInput): Promise<RuntimeIdentity & { accepted: true; userEntryId: string }> {
    return this.run(sessionId, async (slot) => {
      this.requireSessionModelReady(slot, "session.prompt");
      const session = slot.runtime.session;
      const agentStartCount = slot.agentStartCount;
      return new Promise((resolvePrompt, rejectPrompt) => {
        let accepted = false;
        const run = session.prompt(input.message, {
          images: input.images as never,
          streamingBehavior: input.streamingBehavior,
          source: input.source ?? "rpc",
          preflightResult: (success) => {
            if (!success) {
              rejectPrompt(new RequestError("prompt_rejected", "Prompt was rejected before it was accepted"));
              return;
            }
            const userEntryId = session.sessionManager.getLeafId();
            if (!userEntryId) {
              rejectPrompt(new RequestError("missing_user_entry", "Prompt was accepted without a persisted user entry"));
              return;
            }
            accepted = true;
            resolvePrompt({ accepted: true, userEntryId, ...this.identity(slot) });
          },
        });
        void run.catch((error) => {
          if (!accepted) rejectPrompt(error);
          else this.emitRuntimeError(slot, "session.prompt", error);
        }).then(() => {
          if (accepted && slot.agentStartCount === agentStartCount) this.emitPromptCompleted(slot);
        });
      });
    });
  }

  async steer(sessionId: string, message: string, images?: unknown): Promise<void> {
    await this.control(sessionId, async (slot) => {
      this.requireSessionModelReady(slot, "session.steer");
      await slot.runtime.session.steer(message, images as never);
    });
  }

  async followUp(sessionId: string, message: string, images?: unknown): Promise<void> {
    await this.control(sessionId, async (slot) => {
      this.requireSessionModelReady(slot, "session.followUp");
      await slot.runtime.session.followUp(message, images as never);
    });
  }

  async abort(sessionId: string): Promise<void> {
    await this.control(sessionId, async (slot) => slot.runtime.session.abort());
  }

  async compact(sessionId: string, customInstructions?: string): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.compact");
      this.requireSessionModelReady(slot, "session.compact");
      return slot.runtime.session.compact(customInstructions);
    });
  }

  async abortCompaction(sessionId: string): Promise<void> {
    await this.control(sessionId, async (slot) => { slot.runtime.session.abortCompaction(); });
  }

  async clearQueue(sessionId: string): Promise<unknown> {
    return this.control(sessionId, async (slot) => slot.runtime.session.clearQueue());
  }

  async newSession(sessionId: string, parentSession?: string): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.new");
      const result = await slot.runtime.newSession(parentSession ? { parentSession } : undefined);
      return { ...result, ...this.identity(slot) };
    });
  }

  async switchSession(sessionId: string, sessionPath: string): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.switch");
      return this.switch(slot, sessionPath);
    });
  }

  async fork(sessionId: string, entryId: string, position: "before" | "at" = "before"): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.fork");
      const result = await slot.runtime.fork(entryId, { position });
      return { cancelled: result.cancelled, text: result.selectedText, ...this.identity(slot) };
    });
  }

  async importSession(sessionId: string, inputPath: string, cwd?: string): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.import");
      const result = await slot.runtime.importFromJsonl(inputPath, cwd);
      return { ...result, ...this.identity(slot) };
    });
  }

  async navigateTree(sessionId: string, targetId: string, options: {
    summarize?: boolean; customInstructions?: string; replaceInstructions?: boolean; label?: string;
  } = {}): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.navigateTree");
      return slot.runtime.session.navigateTree(targetId, options);
    });
  }

  async reloadSession(sessionId: string): Promise<void> {
    await this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.reload");
      await slot.runtime.session.reload();
    });
  }

  async state(sessionId: string): Promise<Record<string, unknown>> {
    return this.control(sessionId, async (slot) => this.stateOf(slot));
  }

  async snapshot(sessionId: string, leafId?: string | null): Promise<Record<string, unknown>> {
    return this.control(sessionId, async (slot) => this.snapshotOf(slot, leafId));
  }

  async snapshotAndSubscribe(
    sessionId: string,
    listener: EventSink,
    leafId?: string | null,
  ): Promise<SnapshotSubscription> {
    const slot = await this.getOrOpen(sessionId);
    this.cancelReclaim(slot);
    let active = false;
    let subscribed = true;
    const pending: RegistrySessionEvent[] = [];
    const wrapped: EventSink = (event) => {
      if (!subscribed || event.runtime !== slot || event.sessionId !== sessionId) return;
      if (active) listener(event);
      else pending.push(event);
    };
    this.listeners.add(wrapped);
    try {
      // Deliberately no await between listener registration and snapshot creation.
      // JavaScript cannot interleave a session event inside this synchronous region.
      const snapshot = this.snapshotOf(slot, leafId);
      return {
        snapshot,
        activate: () => {
          if (!subscribed || active) return;
          active = true;
          for (const event of pending.splice(0)) listener(event);
        },
        unsubscribe: () => {
          subscribed = false;
          pending.length = 0;
          this.listeners.delete(wrapped);
        },
      };
    } catch (error) {
      this.listeners.delete(wrapped);
      throw error;
    }
  }

  async messages(sessionId: string): Promise<{ messages: readonly unknown[] }> {
    return this.control(sessionId, async (slot) => ({ messages: slot.runtime.session.messages }));
  }

  async entries(sessionId: string, since?: string): Promise<unknown> {
    return this.control(sessionId, async (slot) => {
      let entries = slot.runtime.session.sessionManager.getEntries();
      if (since) {
        const index = entries.findIndex((entry) => entry.id === since);
        if (index < 0) throw new RequestError("entry_not_found", `Entry not found: ${since}`);
        entries = entries.slice(index + 1);
      }
      return { entries, leafId: slot.runtime.session.sessionManager.getLeafId() };
    });
  }

  async tree(sessionId: string): Promise<unknown> {
    return this.control(sessionId, async (slot) => ({
      tree: slot.runtime.session.sessionManager.getTree(),
      leafId: slot.runtime.session.sessionManager.getLeafId(),
    }));
  }

  async commands(sessionId: string): Promise<unknown> {
    return this.control(sessionId, async (slot) => ({ commands: this.commandsOf(slot) }));
  }

  async tools(sessionId: string): Promise<unknown> {
    return this.control(sessionId, async (slot) => ({
      tools: slot.runtime.session.getAllTools(), activeToolNames: slot.runtime.session.getActiveToolNames(),
    }));
  }

  async setTools(sessionId: string, toolNames: string[]): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.setTools");
      const available = new Set(slot.runtime.session.getAllTools().map((tool) => tool.name));
      const requested = [...new Set(toolNames)];
      const unknown = requested.filter((name) => !available.has(name));
      const availableToolNames = [...available];
      const warnings = unknown.length > 0 ? [{
        code: "unknown_tool",
        message: `Unknown tool name(s) ignored: ${unknown.join(", ")}`,
        unknown,
        available: availableToolNames,
      }] : [];
      if (unknown.length > 0) {
        this.diagnostics?.record("tools.warning", {
          sessionId: slot.runtime.session.sessionId,
          unknown,
          available: availableToolNames,
          code: "unknown_tool",
        });
      }
      slot.runtime.session.setActiveToolsByName(requested.filter((name) => available.has(name)));
      return { activeToolNames: slot.runtime.session.getActiveToolNames(), warnings };
    });
  }

  async sessionModels(sessionId: string): Promise<unknown> {
    return this.control(sessionId, async (slot) => ({ models: await slot.runtime.session.modelRuntime.getAvailable() }));
  }

  async setModel(sessionId: string, provider: string, modelId: string): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.setModel");
      const model = (await slot.runtime.session.modelRuntime.getAvailable())
        .find((item) => item.provider === provider && item.id === modelId);
      if (!model) throw new RequestError("model_not_found", `Model not found: ${provider}/${modelId}`);
      await slot.runtime.session.setModel(model);
      this.markSessionModelReady(slot);
      return model;
    });
  }

  async cycleModel(sessionId: string, direction: "forward" | "backward"): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.cycleModel");
      const model = (await slot.runtime.session.cycleModel(direction)) ?? null;
      if (model) this.markSessionModelReady(slot);
      return model;
    });
  }

  async setThinkingLevel(sessionId: string, level: string): Promise<{ level: string }> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.setThinkingLevel");
      slot.runtime.session.setThinkingLevel(level as Parameters<typeof slot.runtime.session.setThinkingLevel>[0]);
      return { level: slot.runtime.session.thinkingLevel };
    });
  }

  async cycleThinkingLevel(sessionId: string): Promise<unknown> {
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "session.cycleThinkingLevel");
      const level = slot.runtime.session.cycleThinkingLevel();
      return level ? { level } : null;
    });
  }

  async setName(sessionId: string, name: string): Promise<void> {
    await this.run(sessionId, async (slot) => { slot.runtime.session.setSessionName(name.trim()); });
  }

  async stats(sessionId: string): Promise<unknown> {
    return this.control(sessionId, async (slot) => slot.runtime.session.getSessionStats());
  }

  async lastAssistantText(sessionId: string): Promise<{ text: string | undefined }> {
    return this.control(sessionId, async (slot) => ({ text: slot.runtime.session.getLastAssistantText() }));
  }

  async extensionUiResponse(sessionId: string, response: {
    requestId: string; value?: string; confirmed?: boolean; cancelled?: boolean;
  }): Promise<void> {
    await this.control(sessionId, async (slot) => { slot.ui?.respond(response); });
  }

  describe(slot: RuntimeSlot): RuntimeIdentity { return this.identity(slot); }
  session(slot: RuntimeSlot): AgentSession { return slot.runtime.session; }
  runtime(slot: RuntimeSlot): AgentSessionRuntime { return slot.runtime; }
  agentStartCount(slot: RuntimeSlot): number { return slot.agentStartCount; }
  emitPromptCompleted(slot: RuntimeSlot): void {
    this.diagnostics?.flushStreamSummaries("prompt_completed", { sessionId: slot.runtime.session.sessionId });
    this.diagnostics?.record("session.prompt_completed", {
      sessionId: slot.runtime.session.sessionId,
      isRunning: false,
      handledWithoutAgent: true,
    });
    this.emit(slot, { type: "prompt_completed", handledWithoutAgent: true, isRunning: false });
    this.scheduleReclaim(slot);
  }

  emitRuntimeError(slot: RuntimeSlot, commandType: string, error: unknown): void {
    this.emit(slot, { type: "runtime_error", phase: "command", commandType,
      message: error instanceof Error ? error.message : String(error), recoverable: true });
  }

  respondToExtensionUi(slot: RuntimeSlot, response: { requestId: string; value?: string; confirmed?: boolean; cancelled?: boolean }): void {
    slot.ui?.respond(response);
  }

  async switch(slot: RuntimeSlot, reference: string): Promise<RuntimeIdentity & { cancelled?: boolean; reused?: boolean }> {
    const activeReference = this.activeReference(reference);
    if (activeReference) {
      this.cancelReclaim(activeReference);
      return { ...this.identity(activeReference), cancelled: false, reused: activeReference !== slot };
    }
    const targetPath = await this.resolveSessionPath(reference);
    const existing = this.byPath.get(this.canonicalPath(targetPath));
    if (existing && existing !== slot) {
      this.cancelReclaim(existing);
      return { ...this.identity(existing), cancelled: false, reused: true };
    }
    const result = await slot.runtime.switchSession(targetPath);
    return { ...this.identity(slot), cancelled: result.cancelled };
  }

  async close(sessionId: string): Promise<{ closed: boolean; deferred: boolean }> {
    const slot = await this.getOrOpen(sessionId);
    if (slot.isRunning || !slot.runtime.session.isIdle) {
      slot.closeAfterSettled = true;
      return { closed: false, deferred: true };
    }
    await this.disposeSlot(slot);
    return { closed: true, deferred: false };
  }

  async dispose(): Promise<void> {
    this.diagnostics?.record("registry.dispose.start", { activeSessions: this.slots.size });
    await Promise.all([...this.slots].map((slot) => this.disposeSlot(slot)));
    this.diagnostics?.record("registry.dispose.end", { activeSessions: this.slots.size });
  }

  private stateOf(slot: RuntimeSlot): Record<string, unknown> {
    const session = slot.runtime.session;
    const identity = this.identity(slot);
    const extensionUi = slot.ui?.state();
    return {
      ...identity,
      model: session.model,
      thinkingLevel: session.thinkingLevel,
      isStreaming: session.isStreaming,
      isCompacting: session.isCompacting,
      steeringMode: session.steeringMode,
      followUpMode: session.followUpMode,
      sessionFile: session.sessionFile,
      sessionName: session.sessionName,
      autoCompactionEnabled: session.autoCompactionEnabled,
      messageCount: session.messages.length,
      pendingMessageCount: session.pendingMessageCount,
      contextUsage: session.getContextUsage(),
      systemPrompt: session.systemPrompt,
      extensionStatuses: extensionUi?.statuses ?? [],
      extensionWidgets: extensionUi?.widgets ?? [],
      queuedMessages: {
        steering: [...session.getSteeringMessages()],
        followUp: [...session.getFollowUpMessages()],
      },
      isPromptRunning: identity.isRunning,
      tools: session.getAllTools(),
      activeToolNames: session.getActiveToolNames(),
      slashCommands: { commands: this.commandsOf(slot) },
      sessionStats: session.getSessionStats(),
      modelStatus: { ...slot.modelStatus },
    };
  }

  private snapshotOf(slot: RuntimeSlot, requestedLeafId?: string | null): Record<string, unknown> {
    const session = slot.runtime.session;
    const manager = session.sessionManager;
    const sessionEntries = manager.getEntries();
    const leafId = requestedLeafId === undefined ? manager.getLeafId() : requestedLeafId;
    if (leafId !== null && leafId !== undefined && !sessionEntries.some((entry) => entry.id === leafId)) {
      throw new RequestError("entry_not_found", `Entry not found: ${leafId}`);
    }
    const byId = new Map(sessionEntries.map((entry) => [entry.id, entry]));
    const context = buildSessionContext(sessionEntries, leafId, byId);
    const entryIds = contextEntryIds(sessionEntries, leafId);
    const state = this.stateOf(slot);
    return {
      type: "snapshot",
      sessionId: session.sessionId,
      filePath: manager.getSessionFile(),
      state,
      history: context.messages,
      entries: entryIds,
      sessionEntries,
      leafId: leafId ?? null,
      tree: manager.getTree(),
      context: {
        messages: context.messages,
        entryIds,
        thinkingLevel: context.thinkingLevel,
        model: context.model,
      },
    };
  }

  private commandsOf(slot: RuntimeSlot) {
    const session = slot.runtime.session;
    return [
      ...session.extensionRunner.getRegisteredCommands().map((command) => ({
        name: command.invocationName,
        description: command.description,
        source: "extension",
        sourceInfo: command.sourceInfo,
      })),
      ...session.promptTemplates.map((template) => ({
        name: template.name,
        description: template.description,
        source: "prompt",
        sourceInfo: template.sourceInfo,
      })),
      ...session.resourceLoader.getSkills().skills.map((skill) => ({
        name: `skill:${skill.name}`,
        description: skill.description,
        source: "skill",
        sourceInfo: skill.sourceInfo,
      })),
    ];
  }

  private async resolveSessionPath(reference: string): Promise<string> {
    const candidate = resolve(reference);
    if (reference.includes("/") || reference.endsWith(".jsonl")) {
      if (!existsSync(candidate)) throw new RequestError("session_not_found", `Session file not found: ${reference}`);
      return candidate;
    }
    const match = (await SessionManager.listAll()).find((session) => session.id === reference);
    if (!match) throw new RequestError("session_not_found", `Session not found: ${reference}`);
    return resolve(match.path);
  }

  private async createSlot(manager: SessionManager): Promise<RuntimeSlot> {
    const createRuntime: CreateAgentSessionRuntimeFactory = async ({ cwd, sessionManager, sessionStartEvent }) => {
      const settingsManager = isolatedSessionSettings(cwd, this.agentDir);
      const services = await createAgentSessionServices({
        cwd,
        agentDir: this.agentDir,
        modelRuntime: await this.sharedModelRuntime,
        settingsManager,
      });
      return { ...(await createAgentSessionFromServices({ services, sessionManager, sessionStartEvent })),
        services, diagnostics: services.diagnostics };
    };
    const runtime = await createAgentSessionRuntime(createRuntime, {
      cwd: manager.getCwd(), agentDir: this.agentDir, sessionManager: manager,
    });
    const slot: RuntimeSlot = { runtime, serial: new SerialExecutor(), isRunning: false,
      agentStartCount: 0, createdAt: new Date(), closeAfterSettled: false,
      modelStatus: runtime.session.model ? {
        state: "ready",
        provider: runtime.session.model.provider,
        modelId: runtime.session.model.id,
      } : {
        state: "invalid",
        code: "session_model_missing",
        message: "The session does not have a configured model",
      } };
    this.diagnostics?.record("session.slot.created", {
      sessionId: runtime.session.sessionId,
      hasSessionPath: runtime.session.sessionFile !== undefined,
      cwd: runtime.cwd,
    });
    runtime.setRebindSession(async (session) => this.bindSlot(slot, session));
    this.slots.add(slot);
    try {
      await this.bindSlot(slot, runtime.session);
      for (const diagnostic of runtime.diagnostics) this.emit(slot, {
        type: "runtime_diagnostic", diagnosticType: diagnostic.type, message: diagnostic.message,
      });
      return slot;
    } catch (error) {
      this.diagnostics?.record("session.slot.create_failed", {
        sessionId: runtime.session.sessionId,
        errorName: error instanceof Error ? error.name : typeof error,
      }, { error });
      this.slots.delete(slot);
      await runtime.dispose().catch(() => undefined);
      throw error;
    }
  }

  private async bindSlot(slot: RuntimeSlot, session: AgentSession): Promise<void> {
    this.diagnostics?.record("session.slot.bind", {
      sessionId: session.sessionId,
      hasSessionPath: session.sessionFile !== undefined,
    });
    this.removeIndexes(slot);
    const idCollision = this.byId.get(session.sessionId);
    const path = session.sessionFile ? this.canonicalPath(session.sessionFile) : undefined;
    const pathCollision = path ? this.byPath.get(path) : undefined;
    if ((idCollision && idCollision !== slot) || (pathCollision && pathCollision !== slot)) {
      throw new RequestError("session_already_active", `Session is already active: ${session.sessionId}`);
    }
    this.byId.set(session.sessionId, slot);
    if (path) this.byPath.set(path, slot);
    slot.unsubscribe?.();
    slot.ui?.dispose();
    slot.ui = new ExtensionUiBridge((payload) => this.emit(slot, payload));
    await session.bindExtensions({
      mode: "rpc", uiContext: slot.ui.context,
      commandContextActions: {
        waitForIdle: () => session.waitForIdle(), newSession: (options) => slot.runtime.newSession(options),
        fork: async (entryId, options) => ({ cancelled: (await slot.runtime.fork(entryId, options)).cancelled }),
        navigateTree: async (targetId, options) => ({ cancelled: (await session.navigateTree(targetId, options)).cancelled }),
        switchSession: (sessionPath, options) => slot.runtime.switchSession(sessionPath, options),
        reload: () => session.reload(),
      },
      shutdownHandler: () => { slot.closeAfterSettled = true; },
      onError: (error) => this.emit(slot, { type: "extension_error", ...error }),
    });
    slot.unsubscribe = session.subscribe((event) => this.onSessionEvent(slot, event));
    this.markSessionModelReady(slot);
  }

  private onSessionEvent(slot: RuntimeSlot, event: AgentSessionEvent): void {
    if (event.type === "agent_start") {
      slot.agentStartCount++;
      slot.isRunning = true;
      this.cancelReclaim(slot);
      this.diagnostics?.record("session.agent_start", {
        sessionId: slot.runtime.session.sessionId,
        agentStartCount: slot.agentStartCount,
        isRunning: true,
      });
    }
    if (event.type === "agent_settled") {
      this.diagnostics?.flushStreamSummaries("agent_settled", { sessionId: slot.runtime.session.sessionId });
    }
    this.emit(slot, event);
    if (event.type === "message_end" && event.message.role === "assistant" && event.message.errorMessage) {
      this.emit(slot, { type: "runtime_error", phase: "provider", message: event.message.errorMessage, recoverable: true });
    }
    if (event.type === "agent_settled") {
      slot.isRunning = false;
      this.diagnostics?.record("session.agent_settled", {
        sessionId: slot.runtime.session.sessionId,
        isRunning: false,
        closeAfterSettled: slot.closeAfterSettled,
      });
      if (slot.closeAfterSettled) {
        runDetached(this.disposeSlot(slot), (error) => this.emit(slot, {
          type: "runtime_error", phase: "dispose", message: error instanceof Error ? error.message : String(error), recoverable: true,
        }));
      } else {
        if (slot.modelStatus.state === "pending") {
          runDetached(this.refreshSessionModel(slot, true), (error) => this.emit(slot, {
            type: "runtime_error", phase: "model.refresh", message: error instanceof Error ? error.message : String(error), recoverable: true,
          }));
        }
        this.scheduleReclaim(slot);
      }
    }
  }

  private async refreshSessionModel(slot: RuntimeSlot, afterSettled = false): Promise<void> {
    const current = slot.runtime.session.model;
    if (!current) {
      slot.modelStatus = {
        state: "invalid",
        code: "session_model_missing",
        message: "The session does not have a configured model",
      };
      return;
    }
    if (!afterSettled && (slot.isRunning || !slot.runtime.session.isIdle)) {
      slot.modelStatus = { state: "pending", provider: current.provider, modelId: current.id };
      return;
    }
    const modelRuntime = await this.sharedModelRuntime;
    const replacement = modelRuntime.getModel(current.provider, current.id);
    if (!replacement) {
      slot.modelStatus = {
        state: "invalid",
        provider: current.provider,
        modelId: current.id,
        code: "session_model_removed",
        message: `The configured session model no longer exists: ${current.provider}/${current.id}`,
      };
      return;
    }
    try {
      await slot.runtime.session.setModel(replacement);
      this.markSessionModelReady(slot);
    } catch (error) {
      slot.modelStatus = {
        state: "invalid",
        provider: current.provider,
        modelId: current.id,
        code: "session_model_unavailable",
        message: error instanceof Error ? error.message : String(error),
      };
    }
  }

  private markSessionModelReady(slot: RuntimeSlot): void {
    const model = slot.runtime.session.model;
    if (!model) {
      slot.modelStatus = {
        state: "invalid",
        code: "session_model_missing",
        message: "The session does not have a configured model",
      };
      return;
    }
    slot.modelStatus = {
      state: "ready",
      provider: model.provider,
      modelId: model.id,
    };
  }

  private requireSessionModelReady(slot: RuntimeSlot, command: string): void {
    if (slot.modelStatus.state === "ready") return;
    throw new RequestError(
      slot.modelStatus.state === "pending" ? "session_model_refresh_pending" : "session_model_invalid",
      slot.modelStatus.message ?? `${command} cannot run until the session model is refreshed`,
      { ...slot.modelStatus },
    );
  }

  private emit(slot: RuntimeSlot, payload: unknown): void {
    const session = slot.runtime.session;
    const diagnosticType = eventType(payload);
    if (isHighFrequencyEvent(payload)) {
      this.diagnostics?.recordStream({
        stage: "produced",
        sessionId: session.sessionId,
        eventType: diagnosticType,
      }, { payload });
    } else {
      this.diagnostics?.record("event.produced", {
        sessionId: session.sessionId,
        eventType: diagnosticType,
        hasSessionPath: session.sessionFile !== undefined,
      }, { payload });
    }
    const event: RegistrySessionEvent = {
      sessionId: session.sessionId,
      sessionPath: session.sessionFile,
      payload,
      runtime: slot,
    };
    for (const listener of this.listeners) listener(event);
  }

  private identity(slot: RuntimeSlot): RuntimeIdentity {
    const session = slot.runtime.session;
    return { sessionId: session.sessionId, sessionPath: session.sessionFile, cwd: slot.runtime.cwd,
      isRunning: slot.isRunning || session.isStreaming, isIdle: !slot.isRunning && session.isIdle };
  }

  private canonicalPath(path: string): string { return resolve(path); }
  private activeReference(reference: string): RuntimeSlot | undefined {
    const byId = this.byId.get(reference);
    if (byId) return byId;
    if (reference.includes("/") || reference.endsWith(".jsonl")) return this.byPath.get(this.canonicalPath(reference));
    return undefined;
  }
  private firstUserMessage(messages: readonly unknown[]): string {
    const message = messages.find((item) => !!item && typeof item === "object" && (item as { role?: string }).role === "user") as
      | { content?: string | Array<{ type?: string; text?: string }> } | undefined;
    if (!message) return "";
    if (typeof message.content === "string") return message.content;
    return (message.content ?? []).filter((part) => part.type === "text").map((part) => part.text ?? "").join("");
  }
  private removeIndexes(slot: RuntimeSlot): void {
    for (const [key, value] of this.byId) if (value === slot) this.byId.delete(key);
    for (const [key, value] of this.byPath) if (value === slot) this.byPath.delete(key);
  }
  private cancelReclaim(slot: RuntimeSlot): void {
    if (slot.reclaimTimer) {
      clearTimeout(slot.reclaimTimer);
      this.diagnostics?.record("session.reclaim.cancelled", { sessionId: slot.runtime.session.sessionId });
    }
    slot.reclaimTimer = undefined;
  }
  private scheduleReclaim(slot: RuntimeSlot): void {
    this.cancelReclaim(slot);
    if (this.idleTimeoutMs <= 0) return;
    this.diagnostics?.record("session.reclaim.scheduled", {
      sessionId: slot.runtime.session.sessionId,
      idleTimeoutMs: this.idleTimeoutMs,
    });
    slot.reclaimTimer = setTimeout(() => {
      this.diagnostics?.record("session.reclaim.fired", { sessionId: slot.runtime.session.sessionId });
      runDetached(this.disposeSlot(slot), (error) => this.emit(slot, {
        type: "runtime_error", phase: "dispose", message: error instanceof Error ? error.message : String(error), recoverable: true,
      }));
    }, this.idleTimeoutMs);
    slot.reclaimTimer.unref();
  }
  private async disposeSlot(slot: RuntimeSlot): Promise<void> {
    if (!this.slots.delete(slot)) return;
    const sessionId = slot.runtime.session.sessionId;
    this.diagnostics?.flushStreamSummaries("session_dispose", { sessionId });
    this.diagnostics?.record("session.dispose.start", { sessionId });
    this.cancelReclaim(slot); this.removeIndexes(slot); slot.unsubscribe?.(); slot.ui?.dispose();
    try {
      await slot.runtime.dispose();
      this.diagnostics?.record("session.dispose.end", { sessionId });
    } catch (error) {
      this.diagnostics?.record("session.dispose.failed", {
        sessionId,
        errorName: error instanceof Error ? error.name : typeof error,
      }, { error });
      throw error;
    }
  }
}

function isolatedSessionSettings(cwd: string, agentDir: string): SettingsManager {
  const persisted = SettingsManager.create(cwd, agentDir);
  const loadErrors = persisted.drainErrors();
  if (loadErrors.length > 0) throw settingsErrors("session settings", loadErrors);
  let defaultProvider = persisted.getDefaultProvider();
  let defaultModel = persisted.getDefaultModel();
  return new Proxy(persisted, {
    get(target, property) {
      if (property === "getDefaultProvider") return () => defaultProvider;
      if (property === "getDefaultModel") return () => defaultModel;
      if (property === "setDefaultProvider") return (provider: string) => { defaultProvider = provider; };
      if (property === "setDefaultModel") return (modelId: string) => { defaultModel = modelId; };
      if (property === "setDefaultModelAndProvider") return (provider: string, modelId: string) => {
        defaultProvider = provider;
        defaultModel = modelId;
      };
      if (property === "getGlobalSettings") return () => ({
        ...target.getGlobalSettings(),
        ...(defaultProvider === undefined ? {} : { defaultProvider }),
        ...(defaultModel === undefined ? {} : { defaultModel }),
      });
      if (property === "reload") return async () => target.reload();
      const value = Reflect.get(target, property, target) as unknown;
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

function settingsErrors(context: string, errors: Array<{ scope: string; error: Error }>): RequestError {
  return new RequestError("settings_persist_failed", `Failed to persist ${context}`, {
    errors: errors.map((item) => ({ scope: item.scope, message: item.error.message })),
  });
}

function eventType(payload: unknown): string {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return typeof payload;
  const value = payload as { type?: unknown; assistantMessageEvent?: { type?: unknown } };
  const type = value.type;
  if (type === "message_update") {
    const nestedType = value.assistantMessageEvent?.type;
    if (nestedType === "text_delta" || nestedType === "thinking_delta") return nestedType;
  }
  return typeof type === "string" ? type : "unknown";
}

function isHighFrequencyEvent(payload: unknown): boolean {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return false;
  const value = payload as { type?: unknown; assistantMessageEvent?: { type?: unknown } };
  return value.type === "message_update" || value.type === "tool_execution_update" ||
    value.type === "text_delta" || value.type === "thinking_delta" ||
    value.assistantMessageEvent?.type === "text_delta" || value.assistantMessageEvent?.type === "thinking_delta";
}

function contextEntryIds(entries: SessionEntry[], requestedLeafId?: string | null): string[] {
  if (requestedLeafId === null || entries.length === 0) return [];
  const byId = new Map(entries.map((entry) => [entry.id, entry]));
  let leaf = requestedLeafId ? byId.get(requestedLeafId) : entries.at(-1);
  if (!leaf) return [];
  const path: SessionEntry[] = [];
  while (leaf) {
    path.unshift(leaf);
    leaf = leaf.parentId ? byId.get(leaf.parentId) : undefined;
  }
  let compactionId: string | undefined;
  let firstKeptEntryId: string | undefined;
  for (const entry of path) {
    if (entry.type === "compaction") {
      compactionId = entry.id;
      firstKeptEntryId = (entry as SessionEntry & { firstKeptEntryId?: string }).firstKeptEntryId;
    }
  }
  const ids: string[] = [];
  if (!compactionId) {
    for (const entry of path) if (isContextMessageEntry(entry)) ids.push(entry.id);
    return ids;
  }
  ids.push(compactionId);
  const compactionIndex = path.findIndex((entry) => entry.id === compactionId);
  const firstKeptIndex = firstKeptEntryId
    ? path.findIndex((entry, index) => index < compactionIndex && entry.id === firstKeptEntryId)
    : -1;
  const startIndex = firstKeptIndex >= 0 ? firstKeptIndex : compactionIndex;
  for (let index = startIndex; index < compactionIndex; index++) {
    if (isContextMessageEntry(path[index]!)) ids.push(path[index]!.id);
  }
  for (let index = compactionIndex + 1; index < path.length; index++) {
    if (isContextMessageEntry(path[index]!)) ids.push(path[index]!.id);
  }
  return ids;
}

function isContextMessageEntry(entry: SessionEntry): boolean {
  return entry.type === "message" || entry.type === "custom_message" ||
    (entry.type === "branch_summary" && !!(entry as SessionEntry & { summary?: string }).summary);
}
