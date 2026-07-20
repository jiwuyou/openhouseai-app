import type { AgentMessage, SessionInfo, SessionTreeNode } from "@/lib/types";

export const WEB_API_BASE = "/api/web/v1";

export interface SessionSnapshot {
  type?: "snapshot";
  sessionId: string;
  filePath?: string;
  state?: Record<string, unknown>;
  history?: AgentMessage[];
  entries?: string[];
  sessionEntries?: Array<Record<string, unknown>>;
  leafId?: string | null;
  tree?: SessionTreeNode[];
  context?: {
    messages?: AgentMessage[];
    entryIds?: string[];
    thinkingLevel?: string;
    model?: { provider: string; modelId: string } | null;
  };
}

export type WebSessionEvent =
  | SessionSnapshot & { type: "snapshot" }
  | { type: "agent"; sessionId: string; payload: Record<string, unknown> }
  | { type: "runtime-error"; sessionId: string; error: unknown }
  | { type: "heartbeat"; at: number };

type JsonRecord = Record<string, unknown>;

export class WebApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = "WebApiError";
  }
}

function record(value: unknown): JsonRecord {
  return value && typeof value === "object" && !Array.isArray(value) ? value as JsonRecord : {};
}

function text(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function numberValue(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function sessionRows(body: unknown): unknown[] {
  if (Array.isArray(body)) return body;
  const root = record(body);
  return Array.isArray(root.sessions) ? root.sessions : [];
}

export function normalizeSessionList(body: unknown): SessionInfo[] {
  const rows = sessionRows(body).map(record);
  const idByPath = new Map(rows.map((row) => [text(row.sessionPath ?? row.path), text(row.sessionId ?? row.id)]));
  return rows.map((row) => {
    const path = text(row.sessionPath ?? row.path);
    const id = text(row.sessionId ?? row.id);
    const parentPath = text(row.parentSessionPath);
    return {
      id,
      path,
      cwd: text(row.cwd),
      name: typeof row.name === "string" ? row.name : undefined,
      created: text(row.createdAt ?? row.created),
      modified: text(row.modifiedAt ?? row.modified ?? row.createdAt ?? row.created),
      messageCount: numberValue(row.messageCount),
      firstMessage: text(row.firstMessage, "新对话"),
      parentSessionId: text(row.parentSessionId) || idByPath.get(parentPath) || undefined,
    };
  }).filter((session) => session.id.length > 0);
}

export interface NormalizedModels {
  providers: Array<Record<string, unknown>>;
  models: Record<string, string>;
  modelList: Array<{ id: string; name: string; provider: string; available?: boolean; reasoning?: boolean }>;
  defaultModel: { provider: string; modelId: string } | null;
  thinkingLevels: Record<string, string[]>;
  thinkingLevelMaps: Record<string, Record<string, string | null>>;
  availabilityError?: string;
}

export function normalizeModels(body: unknown): NormalizedModels {
  const root = record(body);
  const rawModels = Array.isArray(root.models) ? root.models.map(record) : [];
  const legacyNames = !Array.isArray(root.models) ? record(root.models) : {};
  const modelList = rawModels.length > 0
    ? rawModels.map((model) => ({
        id: text(model.id), provider: text(model.provider), name: text(model.name, text(model.id)),
        available: typeof model.available === "boolean" ? model.available : undefined,
        reasoning: typeof model.reasoning === "boolean" ? model.reasoning : undefined,
      })).filter((model) => model.id && model.provider)
    : (Array.isArray(root.modelList) ? root.modelList.map(record).map((model) => ({
        id: text(model.id), provider: text(model.provider), name: text(model.name, text(model.id)),
      })).filter((model) => model.id && model.provider) : []);
  const names = Object.fromEntries(modelList.map((model) => [`${model.provider}:${model.id}`, model.name]));
  for (const [key, value] of Object.entries(legacyNames)) if (typeof value === "string") names[key] = value;
  const defaultModel = record(root.defaultModel);
  return {
    providers: Array.isArray(root.providers) ? root.providers.map(record) : [],
    models: names,
    modelList,
    defaultModel: text(defaultModel.provider) && text(defaultModel.modelId)
      ? { provider: text(defaultModel.provider), modelId: text(defaultModel.modelId) }
      : null,
    thinkingLevels: record(root.thinkingLevels) as Record<string, string[]>,
    thinkingLevelMaps: record(root.thinkingLevelMaps) as Record<string, Record<string, string | null>>,
    availabilityError: typeof root.availabilityError === "string" ? root.availabilityError : undefined,
  };
}

function entryIds(entries: unknown, historyLength: number): { ids: string[]; sessionEntries: Array<Record<string, unknown>> } {
  if (!Array.isArray(entries)) return { ids: [], sessionEntries: [] };
  if (entries.every((entry) => typeof entry === "string")) return { ids: entries as string[], sessionEntries: [] };
  const objects = entries.map(record);
  const messageIds = objects
    .filter((entry) => entry.type === "message" || entry.type === "custom_message")
    .map((entry) => text(entry.id))
    .filter(Boolean);
  return { ids: historyLength > 0 ? messageIds.slice(-historyLength) : [], sessionEntries: objects };
}

export function normalizeSnapshot(body: unknown, fallbackSessionId: string): SessionSnapshot {
  const root = record(body);
  const context = record(root.context);
  const state = record(root.state);
  const history = (Array.isArray(root.history) ? root.history : Array.isArray(context.messages) ? context.messages : []) as AgentMessage[];
  const rawEntries = root.entries ?? context.entryIds;
  const normalizedEntries = entryIds(rawEntries, history.length);
  const explicitSessionEntries = Array.isArray(root.sessionEntries) ? root.sessionEntries.map(record) : normalizedEntries.sessionEntries;
  const treeValue = Array.isArray(root.tree) ? root.tree : Array.isArray(state.tree) ? state.tree : undefined;
  return {
    ...root,
    sessionId: text(root.sessionId, fallbackSessionId),
    filePath: text(root.filePath ?? root.sessionPath ?? state.sessionFile),
    state,
    history,
    entries: normalizedEntries.ids,
    sessionEntries: explicitSessionEntries,
    leafId: typeof root.leafId === "string" ? root.leafId : null,
    ...(treeValue ? { tree: treeValue as SessionTreeNode[] } : {}),
    context: {
      messages: history,
      entryIds: normalizedEntries.ids,
      thinkingLevel: text(context.thinkingLevel ?? state.thinkingLevel, "off"),
      model: record(context.model ?? state.model).provider
        ? { provider: text(record(context.model ?? state.model).provider), modelId: text(record(context.model ?? state.model).modelId ?? record(context.model ?? state.model).id) }
        : null,
    },
  } as SessionSnapshot;
}

function unwrap<T>(body: unknown, status: number): T {
  const root = record(body);
  if (root.success === false || root.ok === false) throw new WebApiError(errorMessage(body, status), status);
  if ("data" in root && root.data !== undefined) return root.data as T;
  return body as T;
}

function errorMessage(body: unknown, status: number): string {
  const root = record(body);
  const nested = record(root.error);
  return String(nested.message ?? root.error ?? root.message ?? `HTTP ${status}`);
}

export class WebApiClient {
  constructor(readonly baseUrl = WEB_API_BASE) {}

  url(path: string, query?: Record<string, string | number | boolean | null | undefined>): string {
    const normalized = path.startsWith("/") ? path : `/${path}`;
    const url = `${this.baseUrl}${normalized}`;
    if (!query) return url;
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null) params.set(key, String(value));
    }
    const suffix = params.toString();
    return suffix ? `${url}?${suffix}` : url;
  }

  async request<T>(path: string, init: RequestInit = {}, query?: Record<string, string | number | boolean | null | undefined>): Promise<T> {
    const headers = new Headers(init.headers);
    if (init.body != null && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }
    const response = await fetch(this.url(path, query), { ...init, headers });
    const contentType = response.headers.get("content-type") ?? "";
    const body = contentType.includes("json") ? await response.json().catch(() => ({})) : await response.text().catch(() => "");
    if (!response.ok) throw new WebApiError(errorMessage(body, response.status), response.status);
    return unwrap<T>(body, response.status);
  }

  async raw(path: string, init: RequestInit = {}, query?: Record<string, string | number | boolean | null | undefined>): Promise<Response> {
    const response = await fetch(this.url(path, query), init);
    if (!response.ok) throw new WebApiError(`HTTP ${response.status}`, response.status);
    return response;
  }

  status() { return this.request<Record<string, unknown>>("/status"); }

  async listSessions(): Promise<SessionInfo[]> {
    return normalizeSessionList(await this.request<unknown>("/sessions"));
  }

  async createSession(input: Record<string, unknown>): Promise<{ sessionId: string; session?: SessionInfo }> {
    const body = await this.request<JsonRecord>("/sessions", { method: "POST", body: JSON.stringify(input) });
    const normalized = normalizeSessionList([body.session ?? body]);
    const session = normalized[0];
    const sessionId = String(body.sessionId ?? session?.id ?? body.id ?? "");
    if (!sessionId) throw new Error("Runtime did not return a session id");
    return { sessionId, ...(session?.id ? { session } : {}) };
  }

  async snapshot(sessionId: string, leafId?: string | null): Promise<SessionSnapshot> {
    return normalizeSnapshot(await this.request<unknown>(`/sessions/${encodeURIComponent(sessionId)}/snapshot`, {}, leafId ? { leafId } : undefined), sessionId);
  }

  subscribe(sessionId: string, onEvent: (event: WebSessionEvent) => void, onError?: (event: Event) => void): EventSource {
    const source = new EventSource(this.url(`/sessions/${encodeURIComponent(sessionId)}/events`));
    const receive = (message: MessageEvent<string>) => {
      try {
        const parsed = JSON.parse(message.data) as WebSessionEvent;
        onEvent(parsed.type === "snapshot" ? { ...normalizeSnapshot(parsed, sessionId), type: "snapshot" } : parsed);
      } catch (error) {
        onEvent({ type: "runtime-error", sessionId, error: error instanceof Error ? error.message : String(error) });
      }
    };
    source.onmessage = receive;
    for (const type of ["snapshot", "agent", "runtime-error", "heartbeat"] as const) {
      source.addEventListener(type, receive as EventListener);
    }
    if (onError) source.onerror = onError;
    return source;
  }

  prompt(sessionId: string, input: Record<string, unknown>) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/prompt`, { method: "POST", body: JSON.stringify(input) });
  }

  abort(sessionId: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/abort`, { method: "POST", body: "{}" });
  }

  compact(sessionId: string, customInstructions?: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/compact`, { method: "POST", body: JSON.stringify(customInstructions ? { customInstructions } : {}) });
  }

  async fork(sessionId: string, entryId: string) {
    const result = await this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/fork`, { method: "POST", body: JSON.stringify({ entryId }) });
    return { ...result, newSessionId: text(result.newSessionId ?? result.sessionId) || undefined };
  }

  navigate(sessionId: string, targetId: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/navigate`, { method: "POST", body: JSON.stringify({ targetId }) });
  }

  tools(sessionId: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/tools`);
  }

  commands(sessionId: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/commands`);
  }

  tree(sessionId: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/tree`);
  }

  stats(sessionId: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/stats`);
  }

  setSessionName(sessionId: string, name: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}`, { method: "PATCH", body: JSON.stringify({ name }) });
  }

  updateModel(sessionId: string, provider: string, modelId: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/model`, { method: "PATCH", body: JSON.stringify({ provider, modelId }) });
  }

  updateThinkingLevel(sessionId: string, level: string) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/thinking-level`, { method: "PATCH", body: JSON.stringify({ level }) });
  }

  updateTools(sessionId: string, toolNames: string[]) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/tools`, { method: "PATCH", body: JSON.stringify({ toolNames }) });
  }

  respondToExtensionUi(sessionId: string, input: Record<string, unknown>) {
    return this.request<JsonRecord>(`/sessions/${encodeURIComponent(sessionId)}/extension-ui-responses`, { method: "POST", body: JSON.stringify(input) });
  }

  async models(cwd?: string): Promise<NormalizedModels> {
    return normalizeModels(await this.request<unknown>("/models", {}, cwd ? { cwd } : undefined));
  }

  loginModel(provider: string, apiKey: string) {
    return this.request<JsonRecord>("/models/login", { method: "POST", body: JSON.stringify({ provider, apiKey }) });
  }

  logoutModel(provider: string) {
    return this.request<JsonRecord>("/models/logout", { method: "POST", body: JSON.stringify({ provider }) });
  }

  setDefaultModel(provider: string, modelId: string, sessionId?: string) {
    return this.request<JsonRecord>("/models/default", { method: "PATCH", body: JSON.stringify({ provider, modelId, ...(sessionId ? { sessionId } : {}) }) });
  }

  testModel(provider: string, modelId: string, timeoutMs = 20_000) {
    return this.request<JsonRecord>("/models/test", { method: "POST", body: JSON.stringify({ provider, modelId, timeoutMs }) });
  }

  skills(cwd?: string) {
    return this.request<JsonRecord>("/skills", {}, cwd ? { cwd } : undefined);
  }

  searchPackages(query: string) {
    return this.request<JsonRecord>("/skills/search", {}, { q: query });
  }

  installPackage(source: string, options: { cwd?: string; local?: boolean } = {}) {
    return this.request<JsonRecord>("/skills/install", { method: "POST", body: JSON.stringify({ source, ...options }) });
  }

  endpoint(group: "assistants" | "files" | "skills" | "extensions" | "capabilities", suffix = "") {
    return `/${group}${suffix ? (suffix.startsWith("/") ? suffix : `/${suffix}`) : ""}`;
  }
}

export const webApi = new WebApiClient();

export function runtimeErrorText(error: unknown): string {
  if (typeof error === "string") return error;
  const value = record(error);
  return String(value.message ?? value.error ?? "Runtime error");
}
