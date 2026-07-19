import { existsSync } from "node:fs";
import { join, resolve } from "node:path";
import {
  type AgentSession, type AgentSessionEvent, type AgentSessionRuntime,
  type CreateAgentSessionRuntimeFactory, createAgentSessionFromServices,
  createAgentSessionRuntime, createAgentSessionServices, getAgentDir, ModelRuntime,
  SessionManager, SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { ExtensionUiBridge } from "./extension-ui.js";
import { type AgentEventEnvelope, RequestError } from "./protocol.js";

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
  runtime: AgentSessionRuntime; serial: SerialExecutor; sequence: number; isRunning: boolean;
  agentStartCount: number; createdAt: Date; closeAfterSettled: boolean; unsubscribe?: () => void;
  ui?: ExtensionUiBridge; reclaimTimer?: NodeJS.Timeout;
};

export type EventSink = (event: AgentEventEnvelope) => void;

export class SessionRegistry {
  private readonly byId = new Map<string, RuntimeSlot>();
  private readonly byPath = new Map<string, RuntimeSlot>();
  private readonly opening = new Map<string, Promise<RuntimeSlot>>();
  private readonly slots = new Set<RuntimeSlot>();
  private readonly idleTimeoutMs: number;
  private readonly agentDir: string;
  private readonly sharedModelRuntime: Promise<ModelRuntime>;
  private readonly modelSettings: SettingsManager;

  constructor(private readonly emitEvent: EventSink, options: {
    idleTimeoutMs?: number;
    agentDir?: string;
    modelRuntime?: ModelRuntime;
    settingsManager?: SettingsManager;
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
  }

  get size(): number { return this.slots.size; }

  status() {
    return { protocol: "wuxianpi-sdk-v1" as const, activeSessions: [...this.slots].map((slot) => this.identity(slot)) };
  }

  models(): Promise<ModelRuntime> { return this.sharedModelRuntime; }
  settings(): SettingsManager { return this.modelSettings; }

  async reloadModelConfiguration(): Promise<void> {
    const modelRuntime = await this.sharedModelRuntime;
    await modelRuntime.reloadConfig();
    await this.modelSettings.reload();
    await Promise.all([...this.slots].map((slot) => slot.runtime.services.settingsManager.reload()));
  }

  async setDefaultModel(provider: string, modelId: string, sessionId?: string) {
    const modelRuntime = await this.sharedModelRuntime;
    const model = modelRuntime.getModel(provider, modelId);
    if (!model) throw new RequestError("model_not_found", `Model not found: ${provider}/${modelId}`);
    const persist = async () => {
      this.modelSettings.setDefaultModelAndProvider(provider, modelId);
      await this.modelSettings.flush();
    };
    if (!sessionId) {
      await persist();
      return { provider, modelId, appliedSessionIds: [] as string[] };
    }
    return this.run(sessionId, async (slot) => {
      requireIdle(slot, "model.setDefault");
      await persist();
      const session = slot.runtime.session;
      await session.setModel(model);
      await session.settingsManager.flush();
      return { provider, modelId, appliedSessionIds: [session.sessionId] };
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

  describe(slot: RuntimeSlot): RuntimeIdentity { return this.identity(slot); }
  session(slot: RuntimeSlot): AgentSession { return slot.runtime.session; }
  runtime(slot: RuntimeSlot): AgentSessionRuntime { return slot.runtime; }
  agentStartCount(slot: RuntimeSlot): number { return slot.agentStartCount; }
  emitPromptCompleted(slot: RuntimeSlot): void {
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

  async dispose(): Promise<void> { await Promise.all([...this.slots].map((slot) => this.disposeSlot(slot))); }

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
      const services = await createAgentSessionServices({
        cwd,
        agentDir: this.agentDir,
        modelRuntime: await this.sharedModelRuntime,
      });
      return { ...(await createAgentSessionFromServices({ services, sessionManager, sessionStartEvent })),
        services, diagnostics: services.diagnostics };
    };
    const runtime = await createAgentSessionRuntime(createRuntime, {
      cwd: manager.getCwd(), agentDir: this.agentDir, sessionManager: manager,
    });
    const slot: RuntimeSlot = { runtime, serial: new SerialExecutor(), sequence: 0, isRunning: false,
      agentStartCount: 0, createdAt: new Date(), closeAfterSettled: false };
    runtime.setRebindSession(async (session) => this.bindSlot(slot, session));
    this.slots.add(slot);
    try {
      await this.bindSlot(slot, runtime.session);
      for (const diagnostic of runtime.diagnostics) this.emit(slot, {
        type: "runtime_diagnostic", diagnosticType: diagnostic.type, message: diagnostic.message,
      });
      return slot;
    } catch (error) {
      this.slots.delete(slot);
      await runtime.dispose().catch(() => undefined);
      throw error;
    }
  }

  private async bindSlot(slot: RuntimeSlot, session: AgentSession): Promise<void> {
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
  }

  private onSessionEvent(slot: RuntimeSlot, event: AgentSessionEvent): void {
    if (event.type === "agent_start") { slot.agentStartCount++; slot.isRunning = true; this.cancelReclaim(slot); }
    this.emit(slot, event);
    if (event.type === "message_end" && event.message.role === "assistant" && event.message.errorMessage) {
      this.emit(slot, { type: "runtime_error", phase: "provider", message: event.message.errorMessage, recoverable: true });
    }
    if (event.type === "agent_settled") {
      slot.isRunning = false;
      if (slot.closeAfterSettled) {
        runDetached(this.disposeSlot(slot), (error) => this.emit(slot, {
          type: "runtime_error", phase: "dispose", message: error instanceof Error ? error.message : String(error), recoverable: true,
        }));
      } else this.scheduleReclaim(slot);
    }
  }

  private emit(slot: RuntimeSlot, payload: unknown): void {
    const session = slot.runtime.session;
    this.emitEvent({ type: "agent.event", sessionId: session.sessionId, sessionPath: session.sessionFile,
      sequence: ++slot.sequence, payload });
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
    if (slot.reclaimTimer) clearTimeout(slot.reclaimTimer);
    slot.reclaimTimer = undefined;
  }
  private scheduleReclaim(slot: RuntimeSlot): void {
    this.cancelReclaim(slot);
    if (this.idleTimeoutMs <= 0) return;
    slot.reclaimTimer = setTimeout(() => runDetached(this.disposeSlot(slot), (error) => this.emit(slot, {
      type: "runtime_error", phase: "dispose", message: error instanceof Error ? error.message : String(error), recoverable: true,
    })), this.idleTimeoutMs);
    slot.reclaimTimer.unref();
  }
  private async disposeSlot(slot: RuntimeSlot): Promise<void> {
    if (!this.slots.delete(slot)) return;
    this.cancelReclaim(slot); this.removeIndexes(slot); slot.unsubscribe?.(); slot.ui?.dispose();
    await slot.runtime.dispose();
  }
}
