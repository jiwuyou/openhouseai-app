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
  constructor(message: string, readonly status: number, readonly details?: unknown) {
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

export type ModelProviderApi =
  | "auto"
  | "openai-completions"
  | "openai-responses"
  | "anthropic-messages"
  | "google-generative-ai"
  | string;

export const MODEL_API_OPTIONS: Array<{ value: ModelProviderApi; label: string }> = [
  { value: "auto", label: "Auto（多协议）" },
  { value: "anthropic-messages", label: "Claude / Anthropic" },
  { value: "openai-responses", label: "GPT" },
  { value: "openai-completions", label: "OpenAI" },
  { value: "google-generative-ai", label: "Gemini" },
];

export function modelApiLabel(api: ModelProviderApi): string {
  return MODEL_API_OPTIONS.find((option) => option.value === api)?.label ?? api;
}

export function concreteApiForModel(
  model: { sourceApis?: ModelProviderApi[] } | undefined,
  currentApi?: ModelProviderApi,
): ModelProviderApi | undefined {
  return model?.sourceApis?.find((api) => api !== "auto") ?? (currentApi && currentApi !== "auto" ? currentApi : undefined);
}

export function configHasAutoApi(config: ModelSetupState["config"]): boolean {
  return Object.values(config.providers).some((provider) => provider.api === "auto");
}

export interface ModelSetupModel {
  id: string;
  provider: string;
  name?: string;
  available?: boolean;
  reasoning?: boolean;
}

export interface ModelSetupPreset {
  id: string;
  label: string;
  providerId: string;
  description?: string;
  baseUrl?: string;
  api?: ModelProviderApi;
  requiresApiKey: boolean;
  keyPlaceholder?: string;
  recommendedModel?: string;
  recommendedModels: string[];
  category?: string;
}

export interface ModelSetupProviderConfig {
  [key: string]: unknown;
  baseUrl?: string;
  api?: ModelProviderApi;
  headers?: Record<string, string>;
  models?: Array<{
    [key: string]: unknown;
    id: string;
    name?: string;
    api?: ModelProviderApi;
    reasoning?: boolean;
  }>;
}

export interface ModelSetupProviderStatus {
  id: string;
  label: string;
  authenticated: boolean;
  authLabel?: string;
  modelCount?: number;
}

export interface ModelSetupState {
  revision: string;
  presets: ModelSetupPreset[];
  config: { providers: Record<string, ModelSetupProviderConfig> };
  providers: ModelSetupProviderStatus[];
  models: ModelSetupModel[];
  defaultModel: { provider: string; modelId: string } | null;
}

export interface ModelProviderDraft {
  providerId: string;
  presetId?: string;
  baseUrl?: string;
  api?: ModelProviderApi;
  headers?: Record<string, string>;
  apiKey?: string;
  models?: ModelSetupProviderConfig["models"];
}

export interface ModelSetupApplyRequest {
  revision: string;
  config: ModelSetupState["config"];
  credentials?: Record<string, { action: "keep" | "set" | "remove"; apiKey?: string }>;
  defaultModel?: { provider: string; modelId: string } | null;
  setGlobalDefault?: boolean;
}

export interface RuntimeModelSetupApplyRequest {
  revision: string;
  config: ModelSetupState["config"];
  changes: Array<{
    providerId: string;
    action: "upsert" | "remove";
    provider?: ModelSetupProviderConfig;
    credential?: { action: "keep" | "set" | "remove"; apiKey?: string };
  }>;
  defaultModel?: { provider: string; modelId: string } | null;
  setGlobalDefault?: boolean;
}

export interface ModelDraftResult {
  ok: boolean;
  models: Array<{ id: string; name?: string; ownedBy?: string; sourceApis?: ModelProviderApi[] }>;
  recommendedModel?: string;
  message?: string;
  hint?: string;
  latencyMs?: number;
  status?: number;
  responseText?: string;
  resolvedApi?: ModelProviderApi;
  modeResults: Array<{
    api: ModelProviderApi;
    label: string;
    ok: boolean;
    modelCount: number;
    models: string[];
    error?: string;
    hint?: string;
    latencyMs?: number;
  }>;
}

function optionalText(value: unknown): string | undefined {
  const normalized = typeof value === "string" ? value.trim() : "";
  return normalized || undefined;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.flatMap((item) => optionalText(item) ? [optionalText(item)!] : [])
    : [];
}

function modelSourceApis(value: unknown): ModelProviderApi[] {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.flatMap((source) => {
    if (typeof source === "string") return optionalText(source) ? [optionalText(source)! as ModelProviderApi] : [];
    const api = optionalText(record(source).api ?? record(source).mode ?? record(source).protocol);
    return api ? [api as ModelProviderApi] : [];
  })));
}

function stripApiKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stripApiKeys);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value as Record<string, unknown>).flatMap(([key, item]) =>
    key.toLowerCase().replace(/[_-]/g, "") === "apikey" ? [] : [[key, stripApiKeys(item)]],
  ));
}

function normalizeProviderConfig(value: unknown): ModelSetupProviderConfig {
  const provider = record(value);
  const sanitized = record(stripApiKeys(provider));
  const headers = record(provider.headers);
  const models = Array.isArray(provider.models)
    ? provider.models.map(record).flatMap((model) => {
        const id = optionalText(model.id);
        if (!id) return [];
        return [{
          ...record(stripApiKeys(model)),
          id,
          ...(optionalText(model.name) ? { name: optionalText(model.name) } : {}),
          ...(optionalText(model.api) ? { api: optionalText(model.api) as ModelProviderApi } : {}),
          ...(typeof model.reasoning === "boolean" ? { reasoning: model.reasoning } : {}),
        }];
      })
    : undefined;
  return {
    ...sanitized,
    ...(optionalText(provider.baseUrl) ? { baseUrl: optionalText(provider.baseUrl) } : {}),
    ...(optionalText(provider.api) ? { api: optionalText(provider.api) as ModelProviderApi } : {}),
    ...(Object.keys(headers).length > 0
      ? { headers: Object.fromEntries(Object.entries(headers).flatMap(([key, header]) => optionalText(header) ? [[key, optionalText(header)!]] : [])) }
      : {}),
    ...(models ? { models } : {}),
  };
}

export function normalizeModelSetup(body: unknown): ModelSetupState {
  const root = record(body);
  const rawConfig = record(root.config);
  const rawProvidersConfig = record(rawConfig.providers);
  const presets = Array.isArray(root.presets) ? root.presets.map(record).flatMap((preset) => {
    const id = optionalText(preset.id);
    if (!id) return [];
    const providerId = optionalText(preset.providerId ?? preset.provider ?? preset.providerName) ?? id;
    return [{
      id,
      providerId,
      label: optionalText(preset.label ?? preset.name) ?? id,
      ...(optionalText(preset.description) ? { description: optionalText(preset.description) } : {}),
      ...(optionalText(preset.baseUrl) ? { baseUrl: optionalText(preset.baseUrl) } : {}),
      ...(optionalText(preset.api) ? { api: optionalText(preset.api) as ModelProviderApi } : {}),
      requiresApiKey: typeof preset.requiresApiKey === "boolean"
        ? preset.requiresApiKey
        : typeof preset.keyRequired === "boolean" ? preset.keyRequired : true,
      ...(optionalText(preset.keyPlaceholder) ? { keyPlaceholder: optionalText(preset.keyPlaceholder) } : {}),
      ...(optionalText(preset.recommendedModel) ? { recommendedModel: optionalText(preset.recommendedModel) } : {}),
      recommendedModels: stringArray(preset.recommendedModels ?? preset.defaultModels),
      ...(optionalText(preset.category) ? { category: optionalText(preset.category) } : {}),
    }];
  }) : [];
  const providers = Array.isArray(root.providers) ? root.providers.map(record).flatMap((provider) => {
    const id = optionalText(provider.id ?? provider.provider);
    if (!id) return [];
    const auth = record(provider.auth);
    return [{
      id,
      label: optionalText(provider.label ?? provider.name) ?? id,
      authenticated: provider.authenticated === true || provider.configured === true || provider.hasCredential === true || auth.configured === true,
      ...(optionalText(provider.authLabel ?? provider.credentialSource ?? auth.label ?? auth.source) ? { authLabel: optionalText(provider.authLabel ?? provider.credentialSource ?? auth.label ?? auth.source) } : {}),
      ...(typeof provider.modelCount === "number" ? { modelCount: provider.modelCount } : {}),
    }];
  }) : [];
  const models = Array.isArray(root.models) ? root.models.map(record).flatMap((model) => {
    const id = optionalText(model.id);
    const provider = optionalText(model.provider);
    if (!id || !provider) return [];
    return [{
      id,
      provider,
      ...(optionalText(model.name) ? { name: optionalText(model.name) } : {}),
      ...(typeof model.available === "boolean" ? { available: model.available } : {}),
      ...(typeof model.reasoning === "boolean" ? { reasoning: model.reasoning } : {}),
    }];
  }) : [];
  const defaultModel = record(root.defaultModel);
  return {
    revision: optionalText(root.revision) ?? "",
    presets,
    config: { providers: Object.fromEntries(Object.entries(rawProvidersConfig).map(([id, provider]) => [id, normalizeProviderConfig(provider)])) },
    providers,
    models,
    defaultModel: optionalText(defaultModel.provider) && optionalText(defaultModel.modelId ?? defaultModel.id)
      ? { provider: optionalText(defaultModel.provider)!, modelId: optionalText(defaultModel.modelId ?? defaultModel.id)! }
      : null,
  };
}

export function normalizeModelDraftResult(body: unknown): ModelDraftResult {
  const root = record(body);
  const rawModes = Array.isArray(root.modeResults)
    ? root.modeResults
    : Array.isArray(root.protocols) ? root.protocols
      : Array.isArray(root.modes) ? root.modes
        : Array.isArray(root.attempts) ? root.attempts : [];
  const modeResults = rawModes.map(record).flatMap((mode) => {
    const api = optionalText(mode.api ?? mode.mode ?? mode.protocol);
    if (!api) return [];
    const modeModels = Array.isArray(mode.models) ? Array.from(new Set(mode.models.flatMap((model) => {
      if (typeof model === "string") return optionalText(model) ? [optionalText(model)!] : [];
      const id = optionalText(record(model).id ?? record(model).model);
      return id ? [id] : [];
    }))) : [];
    const nestedError = record(mode.error);
    const status = optionalText(mode.status);
    const ok = mode.ok === true || mode.success === true || status === "success" || status === "ok";
    return [{
      api,
      label: optionalText(mode.label) ?? modelApiLabel(api),
      ok,
      modelCount: typeof mode.modelCount === "number" ? mode.modelCount : modeModels.length,
      models: modeModels,
      ...(!ok && optionalText(nestedError.message ?? mode.error ?? mode.message) ? { error: optionalText(nestedError.message ?? mode.error ?? mode.message) } : {}),
      ...(optionalText(mode.hint) ? { hint: optionalText(mode.hint) } : {}),
      ...(typeof mode.latencyMs === "number" ? { latencyMs: mode.latencyMs } : {}),
    }];
  });
  const dedupedModels = new Map<string, { id: string; name?: string; ownedBy?: string; sourceApis?: ModelProviderApi[] }>();
  if (Array.isArray(root.models)) {
    for (const model of root.models.map(record)) {
      const id = optionalText(model.id ?? model.model);
      if (!id || dedupedModels.has(id)) continue;
      const sourceApis = modelSourceApis(model.sourceApis ?? model.sources ?? model.sourceModes ?? model.apis ?? model.modes);
      dedupedModels.set(id, {
        id,
        ...(optionalText(model.name) ? { name: optionalText(model.name) } : {}),
        ...(optionalText(model.ownedBy ?? model.owned_by) ? { ownedBy: optionalText(model.ownedBy ?? model.owned_by) } : {}),
        ...(sourceApis.length ? { sourceApis } : {}),
      });
    }
  }
  for (const mode of modeResults) {
    for (const id of mode.models) {
      const current = dedupedModels.get(id) ?? { id };
      const sourceApis = Array.from(new Set([...(current.sourceApis ?? []), mode.api]));
      dedupedModels.set(id, { ...current, sourceApis });
    }
  }
  const anyModeSucceeded = modeResults.some((mode) => mode.ok);
  const allModesFailed = modeResults.length > 0 && !anyModeSucceeded;
  const explicitlyFailed = root.ok === false || root.success === false;
  return {
    ok: anyModeSucceeded || (!explicitlyFailed && !allModesFailed),
    models: Array.from(dedupedModels.values()),
    ...(optionalText(root.recommendedModel) ? { recommendedModel: optionalText(root.recommendedModel) } : {}),
    ...(optionalText(root.message) ? { message: optionalText(root.message) } : {}),
    ...(optionalText(root.hint) ? { hint: optionalText(root.hint) } : {}),
    ...(typeof root.latencyMs === "number" ? { latencyMs: root.latencyMs } : {}),
    ...(typeof root.status === "number" ? { status: root.status } : {}),
    ...(optionalText(root.responseText ?? root.text) ? { responseText: optionalText(root.responseText ?? root.text) } : {}),
    ...(optionalText(root.resolvedApi ?? root.api) ? { resolvedApi: optionalText(root.resolvedApi ?? root.api) } : {}),
    modeResults,
  };
}

export function toRuntimeModelSetupApplyRequest(input: ModelSetupApplyRequest): RuntimeModelSetupApplyRequest {
  if (configHasAutoApi(input.config)) throw new Error("模型配置仍包含 Auto API 类型，请先选择具体类型。");
  return {
    revision: input.revision,
    config: input.config,
    changes: Object.entries(input.credentials ?? {}).map(([providerId, credential]) => {
      const provider = input.config.providers[providerId];
      return provider
        ? { providerId, action: "upsert" as const, provider, credential }
        : { providerId, action: "remove" as const, credential: { action: "remove" as const } };
    }),
    ...(input.defaultModel !== undefined ? { defaultModel: input.defaultModel } : {}),
    ...(input.setGlobalDefault !== undefined ? { setGlobalDefault: input.setGlobalDefault } : {}),
  };
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
    if (!response.ok) {
      const root = record(body);
      const error = record(root.error);
      throw new WebApiError(errorMessage(body, response.status), response.status, error.details ?? root.details);
    }
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

  async modelSetup(): Promise<ModelSetupState> {
    return normalizeModelSetup(await this.request<unknown>("/models/setup"));
  }

  async fetchModelDraft(draft: ModelProviderDraft): Promise<ModelDraftResult> {
    return normalizeModelDraftResult(await this.request<unknown>("/models/fetch", { method: "POST", body: JSON.stringify(draft) }));
  }

  async testModelDraft(draft: ModelProviderDraft & { modelId: string; timeoutMs?: number }): Promise<ModelDraftResult> {
    const { providerId, modelId, timeoutMs, presetId: _presetId, models: _models, ...provider } = draft;
    if (provider.api === "auto") throw new Error("请选择具体 API 类型后再测试模型。");
    return normalizeModelDraftResult(await this.request<unknown>("/models/test", {
      method: "POST",
      body: JSON.stringify({ providerId, modelId, timeoutMs, provider }),
    }));
  }

  async applyModelSetup(input: ModelSetupApplyRequest): Promise<ModelSetupState> {
    if (configHasAutoApi(input.config)) throw new Error("模型配置仍包含 Auto API 类型，请先选择具体类型。");
    return normalizeModelSetup(await this.request<unknown>("/models/apply", { method: "POST", body: JSON.stringify(toRuntimeModelSetupApplyRequest(input)) }));
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
