"use client";

export type PiRpcCommand = {
  id?: string;
  type: string;
  [key: string]: unknown;
};

export type PiRpcFrame = {
  id?: string;
  type: string;
  command?: string;
  success?: boolean;
  data?: unknown;
  error?: string;
  [key: string]: unknown;
};

type RuntimeConfig = {
  adminBaseUrl: string;
  wsUrl: string;
  protocol: "pi-jsonl-rpc-v1";
};

export type LeaseRequest = {
  sessionId?: string;
  sessionPath?: string;
  cwd?: string;
  takeover?: boolean;
};

type LeaseResponse = {
  leaseId?: string;
  id?: string;
  sessionId?: string;
  sessionPath?: string;
  cwd?: string;
  clientId?: string;
  wsUrl?: string;
  wsPath?: string;
  token?: string;
};

type PendingRequest = {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

export type PiTransportState = "idle" | "connecting" | "open" | "reconnecting" | "closed";
export type PiFrameListener = (frame: PiRpcFrame) => void;
export type PiStateListener = (state: PiTransportState) => void;

const COMMAND_TIMEOUT_MS = 120_000;
const RECONNECT_DELAYS_MS = [250, 500, 1_000, 2_000, 5_000];

let runtimeConfigPromise: Promise<RuntimeConfig> | null = null;

async function runtimeConfig(): Promise<RuntimeConfig> {
  runtimeConfigPromise ??= fetch("/api/runtime/config", { cache: "no-store" })
    .then(async (response) => {
      if (!response.ok) throw new Error(`Unable to load Pi runtime configuration (HTTP ${response.status})`);
      return response.json() as Promise<RuntimeConfig>;
    });
  return runtimeConfigPromise;
}

function leaseIdFrom(response: LeaseResponse): string {
  const leaseId = response.leaseId || response.id;
  if (!leaseId) throw new Error("Pi runtime returned a lease without an id");
  return leaseId;
}

function responseError(value: unknown): string | undefined {
  if (typeof value === "string") return value;
  if (value && typeof value === "object" && typeof (value as { message?: unknown }).message === "string") {
    return (value as { message: string }).message;
  }
  return undefined;
}

function websocketUrl(base: string, leaseId: string): string {
  const url = new URL(base, window.location.href);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("leaseId", leaseId);
  return url.toString();
}

const browserClientId = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
  ? `pi-web-${crypto.randomUUID()}`
  : `pi-web-${Date.now()}-${Math.random().toString(36).slice(2)}`;

export class PiLeaseConflictError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PiLeaseConflictError";
  }
}

export class PiWebSocketTransport {
  private socket: WebSocket | null = null;
  private config: RuntimeConfig | null = null;
  private lease: LeaseResponse | null = null;
  private leaseId = "";
  private registeredSessionId = "";
  private requestCounter = 0;
  private pending = new Map<string, PendingRequest>();
  private frameListeners = new Set<PiFrameListener>();
  private stateListeners = new Set<PiStateListener>();
  private state: PiTransportState = "idle";
  private connectPromise: Promise<void> | null = null;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private intentionallyClosed = false;

  constructor(private leaseRequest: LeaseRequest) {}

  get sessionId(): string | undefined {
    return this.lease?.sessionId || this.leaseRequest.sessionId;
  }

  get sessionPath(): string | undefined {
    return this.lease?.sessionPath || this.leaseRequest.sessionPath;
  }

  get currentState(): PiTransportState {
    return this.state;
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
    this.intentionallyClosed = true;
    this.socket?.close(1000, "takeover requested");
    this.socket = null;
    await this.release(false);
    this.leaseRequest = { ...this.leaseRequest, takeover: true };
    this.intentionallyClosed = false;
    await this.connect();
  }

  async send<T = unknown>(command: PiRpcCommand): Promise<T> {
    await this.connect();
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) throw new Error("Pi runtime WebSocket is not connected");

    const id = command.id || `web-${Date.now().toString(36)}-${++this.requestCounter}`;
    const frame = { ...command, id };
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Pi RPC command timed out: ${command.type}`));
      }, COMMAND_TIMEOUT_MS);
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
      socket.send(JSON.stringify(frame));
    });
  }

  async close(): Promise<void> {
    this.intentionallyClosed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.rejectPending(new Error("Pi runtime connection closed"));
    this.socket?.close(1000, "client closed");
    this.socket = null;
    await this.release(true);
    this.setState("closed");
  }

  private async connectInternal(reconnecting: boolean): Promise<void> {
    this.setState(reconnecting ? "reconnecting" : "connecting");
    this.config ??= await runtimeConfig();
    if (!this.leaseId) await this.acquireLease();

    const config = this.config;
    const wsUrl = websocketUrl(config.wsUrl, this.leaseId);
    await new Promise<void>((resolve, reject) => {
      const socket = new WebSocket(wsUrl);
      this.socket = socket;
      let settled = false;

      socket.onopen = () => {
        settled = true;
        this.reconnectAttempt = 0;
        this.setState("open");
        resolve();
      };
      socket.onmessage = (event) => this.handleMessage(event.data);
      socket.onerror = () => {
        if (!settled) reject(new Error("Unable to connect to the Pi runtime WebSocket"));
      };
      socket.onclose = () => {
        this.socket = null;
        this.rejectPending(new Error("Pi runtime WebSocket disconnected"));
        if (!settled) reject(new Error("Pi runtime WebSocket closed during connection"));
        if (!this.intentionallyClosed) this.scheduleReconnect();
      };
    });
  }

  private async acquireLease(): Promise<void> {
    const config = this.config || await runtimeConfig();
    if (!this.registeredSessionId) {
      const sessionResponse = await fetch(`${config.adminBaseUrl}/sessions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...(this.leaseRequest.sessionId ? { sessionId: this.leaseRequest.sessionId } : {}),
          ...(this.leaseRequest.sessionPath ? { sessionPath: this.leaseRequest.sessionPath } : {}),
          ...(this.leaseRequest.cwd ? { cwd: this.leaseRequest.cwd } : {}),
        }),
      });
      const session = await sessionResponse.json().catch(() => ({})) as { sessionId?: string; error?: unknown };
      if (sessionResponse.status === 409 && this.leaseRequest.sessionId) {
        this.registeredSessionId = this.leaseRequest.sessionId;
      } else if (!sessionResponse.ok || !session.sessionId) {
        throw new Error(responseError(session.error) || `Unable to register Pi session (HTTP ${sessionResponse.status})`);
      } else {
        this.registeredSessionId = session.sessionId;
      }
    }
    this.leaseRequest = { ...this.leaseRequest, sessionId: this.registeredSessionId };
    const response = await fetch(`${config.adminBaseUrl}/leases`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionId: this.registeredSessionId,
        clientId: browserClientId,
        takeover: Boolean(this.leaseRequest.takeover),
      }),
    });
    if (response.status === 409 || response.status === 423) {
      throw new PiLeaseConflictError("This conversation is controlled by another client. Take over explicitly to continue.");
    }
    const body = await response.json().catch(() => ({})) as LeaseResponse & { error?: unknown };
    if (!response.ok) throw new Error(responseError(body.error) || `Unable to acquire Pi session lease (HTTP ${response.status})`);
    this.lease = body;
    this.leaseId = leaseIdFrom(body);
  }

  private async release(remote: boolean): Promise<void> {
    const config = this.config;
    const leaseId = this.leaseId;
    this.leaseId = "";
    this.lease = null;
    if (!remote || !config || !leaseId) return;
    await fetch(`${config.adminBaseUrl}/leases/${encodeURIComponent(leaseId)}`, {
      method: "DELETE",
      keepalive: true,
    }).catch(() => undefined);
  }

  private handleMessage(raw: unknown): void {
    if (typeof raw !== "string") return;
    let frame: PiRpcFrame;
    try {
      frame = JSON.parse(raw) as PiRpcFrame;
    } catch {
      return;
    }

    if (frame.type === "response" && frame.id) {
      const pending = this.pending.get(frame.id);
      if (pending) {
        clearTimeout(pending.timer);
        this.pending.delete(frame.id);
        if (frame.success === false) pending.reject(new Error(frame.error || `Pi RPC command failed: ${frame.command || "unknown"}`));
        else pending.resolve(frame.data);
      }
      return;
    }

    for (const listener of this.frameListeners) listener(frame);
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
          this.emit({ type: "runtime_resync", state, messages });
        })
        .catch(() => {
          this.leaseId = "";
          this.lease = null;
          this.scheduleReconnect();
        });
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
  if (!request.sessionId) {
    const transport = new PiWebSocketTransport(request);
    await transport.connect();
    const resolvedId = transport.sessionId;
    if (resolvedId) transports.set(resolvedId, transport);
    return transport;
  }
  const key = request.sessionId;
  let transport = transports.get(key);
  if (!transport || transport.currentState === "closed") {
    transport = new PiWebSocketTransport(request);
    transports.set(key, transport);
  }
  await transport.connect();
  const resolvedId = transport.sessionId;
  if (resolvedId && resolvedId !== key) {
    transports.delete(key);
    transports.set(resolvedId, transport);
  }
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
    .filter(([, transport]) => transport.currentState === "open")
    .map(([sessionId]) => sessionId)
    .filter((sessionId) => !sessionId.startsWith("new:"));
}
