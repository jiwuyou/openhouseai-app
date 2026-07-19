"use client";

export type PiRpcCommand = {
  id?: string;
  type: string;
  [key: string]: unknown;
};

export type PiRpcFrame = {
  id?: string;
  type: string;
  sessionId?: string;
  sessionPath?: string;
  sequence?: number;
  [key: string]: unknown;
};

type RuntimeConfig = {
  wsUrl: string;
  protocol: "wuxianpi-sdk-v1";
};

export type LeaseRequest = {
  sessionId?: string;
  sessionPath?: string;
  cwd?: string;
  takeover?: boolean;
};

type RuntimeResponse = {
  id: string;
  ok: boolean;
  result?: unknown;
  error?: { code?: string; message?: string; details?: unknown } | string;
};

type RuntimeEvent = {
  type: "agent.event";
  sessionId: string;
  sessionPath?: string;
  sequence: number;
  payload: PiRpcFrame;
};

type PendingRequest = {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

type MappedCommand = {
  type: string;
  payload?: Record<string, unknown>;
};

export type PiTransportState = "idle" | "connecting" | "open" | "reconnecting" | "closed";
export type PiFrameListener = (frame: PiRpcFrame) => void;
export type PiStateListener = (state: PiTransportState) => void;

const COMMAND_TIMEOUT_MS = 120_000;
const LONG_COMMAND_TIMEOUT_MS = 30 * 60_000;
const RECONNECT_DELAYS_MS = [250, 500, 1_000, 2_000, 5_000];
const LONG_COMMANDS = new Set(["session.prompt", "session.compact", "session.fork", "session.navigateTree"]);

let runtimeConfigPromise: Promise<RuntimeConfig> | null = null;

async function runtimeConfig(): Promise<RuntimeConfig> {
  runtimeConfigPromise ??= fetch("/api/runtime/config", { cache: "no-store" })
    .then(async (response) => {
      if (!response.ok) throw new Error(`Unable to load WuxianPi runtime configuration (HTTP ${response.status})`);
      const config = await response.json() as RuntimeConfig;
      if (config.protocol !== "wuxianpi-sdk-v1") {
        throw new Error(`Unsupported WuxianPi runtime protocol: ${String(config.protocol)}`);
      }
      return config;
    });
  return runtimeConfigPromise;
}

function websocketUrl(base: string): string {
  const url = new URL(base, window.location.href);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function runtimeError(value: RuntimeResponse["error"], command: string): Error {
  if (typeof value === "string") return new Error(value);
  const message = value?.message || `WuxianPi command failed: ${command}`;
  const error = new Error(message);
  error.name = value?.code || "WuxianPiRuntimeError";
  return error;
}

function commandPayload(command: PiRpcCommand): Record<string, unknown> {
  const payload: Record<string, unknown> = { ...command };
  delete payload.id;
  delete payload.type;
  return payload;
}

export function mapCommand(command: PiRpcCommand): MappedCommand {
  const payload = commandPayload(command);
  if ((command.type === "extension_ui_response" || command.type === "extension_ui_input") && command.id) {
    payload.requestId = command.id;
  }
  const mapped: Record<string, string> = {
    get_state: "session.state",
    get_messages: "session.messages",
    get_commands: "session.commands",
    get_tools: "session.tools",
    prompt: "session.prompt",
    steer: "session.steer",
    follow_up: "session.followUp",
    abort: "session.abort",
    compact: "session.compact",
    fork: "session.fork",
    switch_session: "session.switch",
    new_session: "session.new",
    import_session: "session.import",
    reload: "session.reload",
    set_model: "session.setModel",
    set_thinking_level: "session.setThinkingLevel",
    navigate_tree: "session.navigateTree",
    set_session_name: "session.setName",
    get_session_stats: "session.stats",
    get_last_assistant_text: "session.lastAssistantText",
    abort_compaction: "session.abortCompaction",
    clear_queue: "session.clearQueue",
    extension_ui_response: "extension.uiResponse",
    extension_ui_input: "extension.uiInput",
  };
  const type = mapped[command.type] || command.type;
  if (!type.startsWith("session.") && !type.startsWith("runtime.") && !type.startsWith("extension.")) {
    throw new Error(`Unsupported WuxianPi command: ${command.type}`);
  }
  return { type, ...(Object.keys(payload).length ? { payload } : {}) };
}

export class PiLeaseConflictError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PiLeaseConflictError";
  }
}

export class PiWebSocketTransport {
  private socket: WebSocket | null = null;
  private config: RuntimeConfig | null = null;
  private runtimeSessionId = "";
  private runtimeSessionPath = "";
  private requestCounter = 0;
  private pending = new Map<string, PendingRequest>();
  private frameListeners = new Set<PiFrameListener>();
  private stateListeners = new Set<PiStateListener>();
  private state: PiTransportState = "idle";
  private connectPromise: Promise<void> | null = null;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private intentionallyClosed = false;
  private running = false;

  constructor(private request: LeaseRequest) {}

  get sessionId(): string | undefined {
    return this.runtimeSessionId || this.request.sessionId;
  }

  get sessionPath(): string | undefined {
    return this.runtimeSessionPath || this.request.sessionPath;
  }

  get currentState(): PiTransportState {
    return this.state;
  }

  get isRunning(): boolean {
    return this.running;
  }

  subscribe(listener: PiFrameListener): () => void {
    this.frameListeners.add(listener);
    return () => this.frameListeners.delete(listener);
  }

  subscribeState(listener: PiStateListener): () => void {
    this.stateListeners.add(listener);
    listener(this.state);
    return () => this.stateListeners.delete(listener);
  }

  async connect(): Promise<void> {
    if (this.state === "open") return;
    if (this.connectPromise) return this.connectPromise;
    this.intentionallyClosed = false;
    this.connectPromise = this.connectInternal(false).finally(() => {
      this.connectPromise = null;
    });
    return this.connectPromise;
  }

  async takeover(): Promise<void> {
    // The SDK service shares one RuntimeSlot for a canonical Pi JSONL file, so
    // there is no exclusive lease to steal. Keep this method for existing UI callers.
    await this.connect();
  }

  async send<T = unknown>(command: PiRpcCommand): Promise<T> {
    await this.connect();
    const mapped = mapCommand(command);
    const isExtensionReply = command.type === "extension_ui_response" || command.type === "extension_ui_input";
    return this.sendMapped<T>(mapped, isExtensionReply ? undefined : command.id);
  }

  async close(): Promise<void> {
    this.intentionallyClosed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.rejectPending(new Error("WuxianPi runtime connection closed"));
    this.socket?.close(1000, "client closed");
    this.socket = null;
    this.running = false;
    this.setState("closed");
  }

  private async connectInternal(reconnecting: boolean): Promise<void> {
    this.setState(reconnecting ? "reconnecting" : "connecting");
    this.config ??= await runtimeConfig();
    const wsUrl = websocketUrl(this.config.wsUrl);

    await new Promise<void>((resolve, reject) => {
      const socket = new WebSocket(wsUrl);
      this.socket = socket;
      let settled = false;

      socket.onopen = () => {
        settled = true;
        resolve();
      };
      socket.onmessage = (event) => this.handleMessage(event.data);
      socket.onerror = () => {
        if (!settled) reject(new Error("Unable to connect to the WuxianPi runtime WebSocket"));
      };
      socket.onclose = () => {
        this.socket = null;
        this.rejectPending(new Error("WuxianPi runtime WebSocket disconnected"));
        if (!settled) reject(new Error("WuxianPi runtime WebSocket closed during connection"));
        if (!this.intentionallyClosed) this.scheduleReconnect();
      };
    });

    try {
      await this.attachSession();
      this.reconnectAttempt = 0;
      this.setState("open");
    } catch (error) {
      this.socket?.close(1011, "session attach failed");
      this.socket = null;
      throw error;
    }
  }

  private async attachSession(): Promise<void> {
    const sessionPath = this.runtimeSessionPath || this.request.sessionPath;
    const requestedSessionId = this.runtimeSessionId || this.request.sessionId;
    const result = sessionPath || requestedSessionId
      ? await this.sendMapped<Record<string, unknown>>({
          type: "session.open",
          payload: {
            ...(sessionPath ? { sessionPath } : {}),
            ...(requestedSessionId ? { sessionId: requestedSessionId } : {}),
          },
        })
      : await this.sendMapped<Record<string, unknown>>({ type: "session.create", payload: { cwd: this.request.cwd || "" } });
    const sessionId = typeof result.sessionId === "string" ? result.sessionId : "";
    const attachedPath = typeof result.sessionPath === "string" ? result.sessionPath : sessionPath || "";
    if (!sessionId) throw new Error("WuxianPi runtime did not return a Pi session id");
    this.runtimeSessionId = sessionId;
    this.runtimeSessionPath = attachedPath;
    this.request = { ...this.request, sessionId, ...(attachedPath ? { sessionPath: attachedPath } : {}) };
  }

  private async sendMapped<T>(mapped: MappedCommand, explicitId?: string): Promise<T> {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      throw new Error("WuxianPi runtime WebSocket is not connected");
    }
    const id = explicitId || `web-${Date.now().toString(36)}-${++this.requestCounter}`;
    const frame = {
      id,
      type: mapped.type,
      ...(this.runtimeSessionId ? { sessionId: this.runtimeSessionId } : {}),
      ...(mapped.payload ? { payload: mapped.payload } : {}),
    };
    const timeoutMs = LONG_COMMANDS.has(mapped.type) ? LONG_COMMAND_TIMEOUT_MS : COMMAND_TIMEOUT_MS;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`WuxianPi command timed out: ${mapped.type}`));
      }, timeoutMs);
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
      socket.send(JSON.stringify(frame));
    });
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== "string") return;
    let frame: RuntimeResponse | RuntimeEvent | PiRpcFrame;
    try {
      frame = JSON.parse(raw) as RuntimeResponse | RuntimeEvent | PiRpcFrame;
    } catch {
      return;
    }

    if ("id" in frame && typeof frame.id === "string" && "ok" in frame) {
      const response = frame as RuntimeResponse;
      const pending = this.pending.get(response.id);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.pending.delete(response.id);
      if (!response.ok) pending.reject(runtimeError(response.error, response.id));
      else pending.resolve(response.result);
      return;
    }

    if ("type" in frame && frame.type === "agent.event") {
      const event = frame as RuntimeEvent;
      if (event.sessionId && this.runtimeSessionId && event.sessionId !== this.runtimeSessionId) return;
      const payload = {
        ...event.payload,
        sessionId: event.sessionId,
        ...(event.sessionPath ? { sessionPath: event.sessionPath } : {}),
        sequence: event.sequence,
      };
      if (payload.type === "agent_start") this.running = true;
      if (payload.type === "agent_settled") this.running = false;
      this.emit(payload);
      return;
    }

    this.emit(frame as PiRpcFrame);
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer || this.intentionallyClosed) return;
    const delay = RECONNECT_DELAYS_MS[Math.min(this.reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)];
    this.reconnectAttempt += 1;
    this.setState("reconnecting");
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.connectInternal(true)
        .then(async () => {
          const [state, messages] = await Promise.all([
            this.send({ type: "get_state" }),
            this.send({ type: "get_messages" }),
          ]);
          this.emit({ type: "runtime_resync", state, messages, sessionId: this.runtimeSessionId });
        })
        .catch(() => this.scheduleReconnect());
    }, delay);
  }

  private emit(frame: PiRpcFrame): void {
    for (const listener of this.frameListeners) listener(frame);
  }

  private rejectPending(error: Error): void {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }

  private setState(state: PiTransportState): void {
    if (this.state === state) return;
    this.state = state;
    for (const listener of this.stateListeners) listener(state);
  }
}

const transports = new Map<string, PiWebSocketTransport>();

export function getPiTransport(sessionId: string): PiWebSocketTransport | undefined {
  return transports.get(sessionId);
}

export function bindPiTransportSession(sessionId: string, transport: PiWebSocketTransport): void {
  for (const [key, candidate] of transports) {
    if (candidate === transport && key !== sessionId) transports.delete(key);
  }
  transports.set(sessionId, transport);
}

export async function openPiTransport(request: LeaseRequest): Promise<PiWebSocketTransport> {
  const key = request.sessionId;
  if (key) {
    const existing = transports.get(key);
    if (existing && existing.currentState !== "closed") {
      await existing.connect();
      return existing;
    }
  }
  const transport = new PiWebSocketTransport(request);
  await transport.connect();
  const resolvedId = transport.sessionId;
  if (resolvedId) transports.set(resolvedId, transport);
  return transport;
}

export async function closePiTransport(sessionId: string): Promise<void> {
  const transport = transports.get(sessionId);
  if (!transport) return;
  transports.delete(sessionId);
  await transport.close();
}

export function runningPiSessionIds(): string[] {
  return [...transports.entries()]
    .filter(([, transport]) => transport.currentState === "open" && transport.isRunning)
    .map(([sessionId]) => sessionId)
    .filter((sessionId) => !sessionId.startsWith("new:"));
}
