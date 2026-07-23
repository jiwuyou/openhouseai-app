import { RequestError } from "./protocol.js";
import {
  getModelProviderPreset,
  normalizeModelProviderApi,
  providerAllowsMissingApiKey,
  type ModelProviderApi,
  type ModelProviderApiSelection,
  type ModelProviderPreset,
} from "./model-provider-presets.js";

const FETCH_TIMEOUT_MS = 15_000;
const ANTHROPIC_VERSION = "2023-06-01";
const DISCOVERY_APIS: readonly ModelProviderApi[] = [
  "anthropic-messages",
  "openai-responses",
  "openai-completions",
  "google-generative-ai",
];

const API_LABELS: Record<ModelProviderApi, string> = {
  "anthropic-messages": "Claude / Anthropic",
  "openai-responses": "GPT",
  "openai-completions": "OpenAI",
  "google-generative-ai": "Gemini",
};

export interface ModelDiscoveryInput {
  providerId?: string;
  baseUrl?: string;
  apiKey?: string;
  api?: string;
  apiType?: string;
  headers?: Record<string, string>;
  timeoutMs?: number;
}

export interface DiscoveredModel {
  id: string;
  name?: string;
  ownedBy?: string | null;
  sources?: ModelProviderApi[];
}

export interface ModelDiscoveryModeResult {
  api: ModelProviderApi;
  label: string;
  ok: boolean;
  modelCount: number;
  models: DiscoveredModel[];
  candidates: string[];
  latencyMs: number;
  source?: "model-list";
  endpoint?: string;
  status?: number;
  error?: string;
  hint?: string;
}

export interface ModelDiscoveryResult {
  ok: true;
  models: DiscoveredModel[];
  recommendedModel?: string;
  candidates: string[];
  modeResults: ModelDiscoveryModeResult[];
  resolvedApi?: ModelProviderApi;
  message?: string;
}

type AttemptFailure = {
  kind: "http" | "network" | "timeout" | "format";
  url: string;
  status?: number;
  message: string;
};

type Parser = (payload: unknown) => DiscoveredModel[] | undefined;

export async function discoverModels(
  input: ModelDiscoveryInput,
  fetchImpl: typeof fetch = fetch,
): Promise<ModelDiscoveryResult> {
  const preset = getModelProviderPreset(input.providerId);
  const providerId = preset?.id ?? cleanString(input.providerId);
  const selection = resolveApiSelection(input.api, input.apiType, preset);
  const baseUrl = normalizeBaseUrl(input.baseUrl, preset?.baseUrl);
  const apiKey = input.apiKey?.trim() ?? "";
  if (!baseUrl) throw new RequestError("invalid_base_url", "Base URL must be an absolute HTTP or HTTPS URL");
  if (!apiKey && !providerAllowsMissingApiKey(providerId, selection === "auto" ? undefined : selection)) {
    throw new RequestError("api_key_required", `API key is required for ${providerId ?? "this provider"}`);
  }

  const modes = selection === "auto" ? DISCOVERY_APIS : [selection];
  const timeoutMs = Math.max(1_000, Math.min(input.timeoutMs ?? FETCH_TIMEOUT_MS, 60_000));
  const modeResults = await Promise.all(modes.map((api) => discoverMode({
    api,
    baseUrl,
    apiKey,
    providerId,
    headers: input.headers ?? {},
    timeoutMs,
    fetchImpl,
  })));
  const successful = modeResults.filter((result) => result.ok);
  if (successful.length === 0) {
    throw new RequestError("model_discovery_failed", "No supported API mode returned a model list", {
      hint: "Check the API key, Base URL, and selected API type.",
      modeResults,
    });
  }

  const models = mergeModeModels(successful);
  const recommendedModel = findRecommendedModel(models, preset);
  return {
    ok: true,
    models,
    ...(recommendedModel ? { recommendedModel } : {}),
    candidates: unique(modeResults.flatMap((result) => result.candidates)),
    modeResults,
    ...(selection === "auto" ? {} : { resolvedApi: selection }),
    message: selection === "auto"
      ? `${successful.length} of ${modeResults.length} API modes returned model lists; generation compatibility is verified by the model test.`
      : `${API_LABELS[selection]} model list fetched; generation compatibility is verified by the model test.`,
  };
}

async function discoverMode(options: {
  api: ModelProviderApi;
  baseUrl: string;
  apiKey: string;
  providerId?: string;
  headers: Record<string, string>;
  timeoutMs: number;
  fetchImpl: typeof fetch;
}): Promise<ModelDiscoveryModeResult> {
  const startedAt = Date.now();
  const requests = modelListRequests(options.api, options.baseUrl, options.apiKey, options.providerId, options.headers);
  const failures: AttemptFailure[] = [];
  for (const request of requests) {
    const remainingMs = options.timeoutMs - (Date.now() - startedAt);
    if (remainingMs <= 0) {
      failures.push({ kind: "timeout", url: request.url, message: "Model-list request timed out" });
      break;
    }
    const attempt = await fetchJson(request.url, request.headers, options.apiKey, remainingMs, options.fetchImpl);
    if (!attempt.ok) {
      failures.push(attempt.failure);
      continue;
    }
    const models = request.parser(attempt.json);
    if (!models) {
      failures.push({
        kind: "format",
        url: request.url,
        status: attempt.status,
        message: "Model list response has an unsupported shape",
      });
      continue;
    }
    return {
      api: options.api,
      label: API_LABELS[options.api],
      ok: true,
      modelCount: models.length,
      models,
      candidates: sanitizeCandidates(requests.map((item) => item.url)),
      latencyMs: Date.now() - startedAt,
      source: "model-list",
      endpoint: sanitizeCandidate(request.url),
      status: attempt.status,
    };
  }

  const failure = classifyFailure(failures, options.apiKey);
  return {
    api: options.api,
    label: API_LABELS[options.api],
    ok: false,
    modelCount: 0,
    models: [],
    candidates: sanitizeCandidates(requests.map((item) => item.url)),
    latencyMs: Date.now() - startedAt,
    source: "model-list",
    error: failure.message,
    hint: failure.hint,
    ...(failure.status !== undefined ? { status: failure.status } : {}),
  };
}

function resolveApiSelection(
  api: string | undefined,
  apiType: string | undefined,
  preset: ModelProviderPreset | undefined,
): ModelProviderApiSelection {
  const explicit = api !== undefined ? api : apiType;
  if (explicit !== undefined) {
    const normalized = normalizeModelProviderApi(explicit, { allowAuto: true });
    if (!normalized) throw new RequestError("unsupported_model_api", `Unsupported model API: ${explicit}`);
    return normalized;
  }
  return preset?.api ?? "openai-completions";
}

function normalizeBaseUrl(input: string | undefined, fallback: string | undefined): string | undefined {
  const raw = (input !== undefined ? input : fallback ?? "").trim();
  if (!raw) return undefined;
  try {
    const url = new URL(raw);
    if (url.protocol !== "http:" && url.protocol !== "https:") return undefined;
    url.search = "";
    url.hash = "";
    url.pathname = normalizeEndpointPath(url.pathname);
    const normalized = url.toString();
    return normalized.endsWith("/") && url.pathname !== "/" ? normalized.slice(0, -1) : normalized;
  } catch {
    return undefined;
  }
}

function normalizeEndpointPath(pathname: string): string {
  let path = pathname.replace(/\/+$/, "") || "/";
  path = path.replace(/\/models\/[^/]+:(?:generateContent|streamGenerateContent)$/i, "") || "/";
  const suffixes = [
    "/v1beta/chat/completions",
    "/v1/chat/completions",
    "/chat/completions",
    "/v1beta/responses",
    "/v1/responses",
    "/responses",
    "/v1beta/messages",
    "/v1/messages",
    "/messages",
    "/chat/completions",
    "/completions",
    "/embeddings",
    "/models",
  ].sort((left, right) => right.length - left.length);
  const lower = path.toLowerCase();
  const suffix = suffixes.find((candidate) => lower.endsWith(candidate.toLowerCase()));
  if (suffix) path = path.slice(0, -suffix.length) || "/";
  return path.replace(/\/+$/, "") || "/";
}

function appendPath(baseUrl: string, suffix: string): string {
  const url = new URL(baseUrl);
  url.pathname = `${url.pathname.replace(/\/+$/, "")}/${suffix.replace(/^\/+/, "")}`;
  url.search = "";
  url.hash = "";
  return url.toString();
}

function isVersioned(baseUrl: string): boolean {
  return /\/(?:api\/)?v\d+(?:alpha|beta)?$/i.test(new URL(baseUrl).pathname.replace(/\/+$/, ""));
}

function genericModelUrls(baseUrl: string): string[] {
  return unique(isVersioned(baseUrl)
    ? [appendPath(baseUrl, "models")]
    : [appendPath(baseUrl, "v1/models"), appendPath(baseUrl, "models")]);
}

function geminiModelUrls(baseUrl: string, apiKey: string): string[] {
  const paths = isVersioned(baseUrl)
    ? [appendPath(baseUrl, "models")]
    : [appendPath(baseUrl, "v1beta/models"), appendPath(baseUrl, "v1/models"), appendPath(baseUrl, "models")];
  return unique(paths.map((value) => {
    const url = new URL(value);
    if (apiKey) url.searchParams.set("key", apiKey);
    return url.toString();
  }));
}

function modelListRequests(
  api: ModelProviderApi,
  baseUrl: string,
  apiKey: string,
  providerId: string | undefined,
  customHeaders: Record<string, string>,
): Array<{ url: string; headers: Record<string, string>; parser: Parser }> {
  if (api === "google-generative-ai") {
    const headers = {
      Accept: "application/json",
      ...(apiKey ? { "x-goog-api-key": apiKey } : {}),
      ...customHeaders,
    };
    return geminiModelUrls(baseUrl, apiKey).map((url) => ({ url, headers, parser: parseGeminiModels }));
  }
  if (api === "anthropic-messages") {
    const headers = {
      Accept: "application/json",
      "anthropic-version": ANTHROPIC_VERSION,
      ...(apiKey ? { "x-api-key": apiKey } : {}),
      ...customHeaders,
    };
    return genericModelUrls(baseUrl).map((url) => ({ url, headers, parser: parseAnthropicModels }));
  }
  const headers = {
    Accept: "application/json",
    ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
    ...(providerId === "openrouter" ? { "HTTP-Referer": "http://localhost:30141", "X-Title": "WuxianPi" } : {}),
    ...customHeaders,
  };
  const urls = genericModelUrls(baseUrl);
  if (providerId === "ollama") urls.push(appendPath(new URL(baseUrl).origin, "api/tags"));
  return unique(urls).map((url) => ({ url, headers, parser: parseOpenAiModels }));
}

async function fetchJson(
  url: string,
  headers: Record<string, string>,
  apiKey: string,
  timeoutMs: number,
  fetchImpl: typeof fetch,
): Promise<{ ok: true; status: number; json: unknown } | { ok: false; failure: AttemptFailure }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  timer.unref();
  try {
    const response = await fetchImpl(url, { method: "GET", headers, signal: controller.signal });
    const body = await response.text();
    if (!response.ok) {
      return { ok: false, failure: {
        kind: "http",
        url,
        status: response.status,
        message: redactSecret(extractUpstreamMessage(body) || response.statusText || `HTTP ${response.status}`, apiKey),
      } };
    }
    try {
      return { ok: true, status: response.status, json: body.trim() ? JSON.parse(body) as unknown : {} };
    } catch {
      return { ok: false, failure: { kind: "format", url, status: response.status, message: "Response was not valid JSON" } };
    }
  } catch (error) {
    const timeout = controller.signal.aborted || (error instanceof Error && error.name === "AbortError");
    return { ok: false, failure: {
      kind: timeout ? "timeout" : "network",
      url,
      message: redactSecret(error instanceof Error ? error.message : String(error), apiKey),
    } };
  } finally {
    clearTimeout(timer);
  }
}

function parseOpenAiModels(payload: unknown): DiscoveredModel[] | undefined {
  if (!isRecord(payload)) return undefined;
  const rows = Array.isArray(payload.data) ? payload.data : Array.isArray(payload.models) ? payload.models : undefined;
  if (!rows) return undefined;
  return dedupeModels(rows.flatMap((row): DiscoveredModel[] => {
    if (typeof row === "string") return [{ id: row }];
    if (!isRecord(row)) return [];
    const id = cleanString(row.id) ?? cleanString(row.model) ?? cleanString(row.name);
    if (!id) return [];
    return [{ id, name: cleanString(row.name), ownedBy: cleanString(row.owned_by) ?? cleanString(row.ownedBy) ?? null }];
  }));
}

function parseAnthropicModels(payload: unknown): DiscoveredModel[] | undefined {
  if (!isRecord(payload)) return undefined;
  const rows = Array.isArray(payload.data) ? payload.data : Array.isArray(payload.models) ? payload.models : undefined;
  if (!rows) return undefined;
  return dedupeModels(rows.flatMap((row): DiscoveredModel[] => {
    if (typeof row === "string") return [{ id: row, ownedBy: "anthropic" }];
    if (!isRecord(row)) return [];
    const id = cleanString(row.id) ?? cleanString(row.name);
    return id ? [{
      id,
      name: cleanString(row.display_name) ?? cleanString(row.displayName) ?? cleanString(row.name),
      ownedBy: "anthropic",
    }] : [];
  }));
}

function parseGeminiModels(payload: unknown): DiscoveredModel[] | undefined {
  if (!isRecord(payload) || !Array.isArray(payload.models)) return undefined;
  return dedupeModels(payload.models.flatMap((row): DiscoveredModel[] => {
    if (typeof row === "string") return [{ id: row.replace(/^models\//, ""), ownedBy: "google" }];
    if (!isRecord(row)) return [];
    const methods = Array.isArray(row.supportedGenerationMethods) ? row.supportedGenerationMethods : [];
    if (methods.length > 0 && !methods.includes("generateContent")) return [];
    const id = cleanString(row.name)?.replace(/^models\//, "") ?? cleanString(row.id);
    return id ? [{ id, name: cleanString(row.displayName) ?? cleanString(row.display_name) ?? id, ownedBy: "google" }] : [];
  }));
}

function mergeModeModels(results: ModelDiscoveryModeResult[]): DiscoveredModel[] {
  const merged = new Map<string, DiscoveredModel>();
  for (const result of results) {
    for (const model of result.models) {
      const existing = merged.get(model.id);
      if (!existing) {
        merged.set(model.id, { ...model, sources: [result.api] });
        continue;
      }
      existing.name ??= model.name;
      existing.ownedBy ??= model.ownedBy;
      existing.sources = unique([...(existing.sources ?? []), result.api]) as ModelProviderApi[];
    }
  }
  return dedupeModels([...merged.values()]);
}

function dedupeModels(models: DiscoveredModel[]): DiscoveredModel[] {
  const seen = new Set<string>();
  return models.filter((model) => {
    model.id = model.id.trim();
    if (!model.id || seen.has(model.id)) return false;
    seen.add(model.id);
    return true;
  }).sort((left, right) => left.id.localeCompare(right.id, undefined, { numeric: true, sensitivity: "base" }));
}

function findRecommendedModel(models: DiscoveredModel[], preset: ModelProviderPreset | undefined): string | undefined {
  const wanted = preset?.recommendedModels ?? (preset?.recommendedModel ? [preset.recommendedModel] : []);
  for (const candidate of wanted) {
    const exact = models.find((model) => model.id.toLowerCase() === candidate.toLowerCase());
    if (exact) return exact.id;
  }
  for (const candidate of wanted) {
    const partial = models.find((model) => model.id.toLowerCase().includes(candidate.toLowerCase()));
    if (partial) return partial.id;
  }
  return models[0]?.id ?? preset?.recommendedModel;
}

function classifyFailure(failures: AttemptFailure[], apiKey: string): { message: string; hint: string; status?: number } {
  const failure = failures.find((item) => item.status === 401 || item.status === 403)
    ?? failures.find((item) => item.status === 429)
    ?? failures.find((item) => item.kind === "timeout")
    ?? failures.find((item) => item.kind === "network")
    ?? failures.at(-1);
  const detail = failure?.message ? `: ${redactSecret(failure.message, apiKey)}` : "";
  if (!failure) return { message: "No model-list endpoint was available", hint: "Check the provider and Base URL." };
  if (failure.status === 401 || failure.status === 403) return {
    message: `API key is invalid or unauthorized${detail}`,
    hint: "Check the API key, account access, and provider region.",
    status: failure.status,
  };
  if (failure.status === 429) return {
    message: `Provider rate limit or quota exceeded${detail}`,
    hint: "Retry later or check account quota.",
    status: failure.status,
  };
  if (failure.status === 404) return {
    message: `Model-list endpoint was not found${detail}`,
    hint: "Use the provider root or versioned Base URL, not a generation endpoint.",
    status: 404,
  };
  if (failure.kind === "timeout") return { message: "Model-list request timed out", hint: "Check network access and the Base URL." };
  if (failure.kind === "network") return { message: `Model-list request could not connect${detail}`, hint: "Check network access and whether local services are running." };
  if (failure.kind === "format") return { message: `Model-list response was not recognized${detail}`, hint: "Check that the URL points to a compatible model API." };
  return { message: `Model-list request failed${detail}`, hint: "Check the API key, Base URL, and API type.", status: failure.status };
}

function extractUpstreamMessage(body: string): string {
  const trimmed = body.trim();
  if (!trimmed) return "";
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    return findMessage(parsed) ?? trimmed.slice(0, 500);
  } catch {
    return trimmed.slice(0, 500);
  }
}

function findMessage(value: unknown): string | undefined {
  if (typeof value === "string") return value;
  if (!isRecord(value)) return undefined;
  for (const key of ["message", "detail", "error_description", "error"]) {
    const message = findMessage(value[key]);
    if (message) return message;
  }
  return undefined;
}

function sanitizeCandidates(urls: string[]): string[] {
  return unique(urls.map(sanitizeCandidate));
}

function sanitizeCandidate(value: string): string {
  try {
    const url = new URL(value);
    url.search = "";
    url.hash = "";
    return url.toString();
  } catch {
    return value.replace(/\?.*$/, "");
  }
}

function redactSecret(value: string, secret: string): string {
  return secret.length >= 4 ? value.split(secret).join("[api-key]") : value;
}

function unique<T>(values: T[]): T[] { return [...new Set(values)]; }
function cleanString(value: unknown): string | undefined { return typeof value === "string" && value.trim() ? value.trim() : undefined; }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === "object" && value !== null && !Array.isArray(value); }
