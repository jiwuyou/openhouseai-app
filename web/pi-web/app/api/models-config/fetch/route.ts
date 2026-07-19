import { NextResponse } from "next/server";
import {
  getModelProviderPreset,
  isSupportedModelProviderApi,
  providerAllowsMissingApiKey,
  type ModelProviderApi,
  type ModelProviderPreset,
} from "@/lib/model-provider-presets";

export const dynamic = "force-dynamic";

const FETCH_TIMEOUT_MS = 15_000;
const ANTHROPIC_VERSION = "2023-06-01";

interface FetchModelsRequest {
  providerId?: unknown;
  baseUrl?: unknown;
  apiKey?: unknown;
  api?: unknown;
}

interface FetchedModel {
  id: string;
  name?: string;
  ownedBy?: string | null;
}

type AttemptFailureKind = "http" | "network" | "timeout" | "format";

interface AttemptFailure {
  kind: AttemptFailureKind;
  url: string;
  status?: number;
  message: string;
}

type JsonAttempt =
  | { ok: true; url: string; status: number; json: unknown }
  | { ok: false; failure: AttemptFailure };

const modelCollator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });

const MODEL_PRIORITIES: Record<string, string[]> = {
  deepseek: ["deepseek-chat", "deepseek-reasoner"],
  openai: ["gpt-5.4", "gpt-5", "gpt-4.1", "gpt-4o", "gpt-4o-mini"],
  "gpt-responses": ["gpt-5.4", "gpt-5", "gpt-4.1"],
  "gpt-responses-compatible": ["gpt-5.4", "gpt-5", "gpt-4.1"],
  anthropic: ["claude-sonnet-4-6", "claude-sonnet-4", "claude-3-7-sonnet", "claude-3-5-sonnet"],
  "custom-anthropic": ["claude-sonnet-4-6", "claude-sonnet-4", "claude-3-7-sonnet", "claude-3-5-sonnet"],
  google: ["gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash"],
  openrouter: ["openai/gpt-5.4", "anthropic/claude-sonnet-4", "google/gemini-2.5-flash"],
  siliconflow: ["deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-Coder-32B-Instruct"],
  moonshot: ["kimi-k2-0711-preview", "moonshot-v1-32k", "moonshot-v1-8k"],
  doubao: ["doubao-seed-1-6-250615", "doubao-pro-32k"],
  ollama: ["qwen2.5-coder:7b", "llama3.1:8b", "llama3:8b"],
  lmstudio: ["local-model"],
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" ? value.trim() : undefined;
}

function readStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function dedupeModels(models: FetchedModel[]): FetchedModel[] {
  const seen = new Set<string>();
  const deduped: FetchedModel[] = [];
  for (const model of models) {
    const id = model.id.trim();
    if (!id || seen.has(id)) continue;
    seen.add(id);
    deduped.push({
      id,
      name: model.name?.trim() || undefined,
      ownedBy: model.ownedBy ?? null,
    });
  }
  return deduped.sort((a, b) => modelCollator.compare(a.id, b.id));
}

function sanitizeUrlForResponse(url: string): string {
  try {
    const parsed = new URL(url);
    parsed.search = "";
    parsed.hash = "";
    return parsed.toString();
  } catch {
    return url.replace(/\?.*$/, "");
  }
}

function sanitizeCandidates(urls: string[]): string[] {
  return Array.from(new Set(urls.map(sanitizeUrlForResponse)));
}

function redactSecret(value: string, apiKey: string): string {
  if (!apiKey || apiKey.length < 4) return value;
  return value.split(apiKey).join("[api-key]");
}

function jsonFailure(error: string, hint?: string, candidates?: string[], status = 200) {
  return NextResponse.json({
    ok: false,
    error,
    ...(hint ? { hint } : {}),
    ...(candidates?.length ? { candidates } : {}),
  }, { status });
}

function normalizeBaseUrl(input: string | undefined, fallback: string | undefined): string | undefined {
  const raw = (input || fallback || "").trim();
  if (!raw) return undefined;
  try {
    const url = new URL(raw);
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
  const suffixes = ["/chat/completions", "/responses", "/messages", "/models"];
  for (const suffix of suffixes) {
    if (path.toLowerCase().endsWith(suffix)) {
      path = path.slice(0, -suffix.length) || "/";
      break;
    }
  }

  const modelsSegment = path.toLowerCase().indexOf("/models/");
  if (modelsSegment >= 0) path = path.slice(0, modelsSegment) || "/";
  if (path.includes(":generateContent")) {
    path = path.slice(0, path.indexOf(":generateContent")) || "/";
  }
  return path || "/";
}

function appendPath(baseUrl: string, suffix: string): string {
  const url = new URL(baseUrl);
  const basePath = url.pathname.replace(/\/+$/, "");
  url.pathname = `${basePath}/${suffix.replace(/^\/+/, "")}`;
  url.search = "";
  url.hash = "";
  return url.toString();
}

function pathLooksVersioned(baseUrl: string): boolean {
  const path = new URL(baseUrl).pathname.replace(/\/+$/, "");
  return /\/(?:api\/)?v\d+(?:alpha|beta)?$/i.test(path);
}

function openAiModelUrls(baseUrl: string, providerId: string | undefined): string[] {
  const urls = pathLooksVersioned(baseUrl)
    ? [appendPath(baseUrl, "models")]
    : [appendPath(baseUrl, "v1/models"), appendPath(baseUrl, "models")];
  if (providerId === "ollama") urls.push(appendPath(new URL(baseUrl).origin, "api/tags"));
  return Array.from(new Set(urls));
}

function anthropicModelUrls(baseUrl: string): string[] {
  return pathLooksVersioned(baseUrl)
    ? [appendPath(baseUrl, "models")]
    : [appendPath(baseUrl, "v1/models"), appendPath(baseUrl, "models")];
}

function geminiModelUrls(baseUrl: string, apiKey: string): string[] {
  const url = pathLooksVersioned(baseUrl) ? appendPath(baseUrl, "models") : appendPath(baseUrl, "v1beta/models");
  const parsed = new URL(url);
  parsed.searchParams.set("key", apiKey);
  return [parsed.toString()];
}

function extractUpstreamMessage(text: string): string {
  const trimmed = text.trim();
  if (!trimmed) return "";
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    const message = findMessageField(parsed);
    if (message) return message;
  } catch {
    // Fall back to the plain response text below.
  }
  return trimmed.slice(0, 500);
}

function findMessageField(value: unknown): string | undefined {
  if (typeof value === "string") return value;
  if (!isRecord(value)) return undefined;
  const direct = value.message ?? value.detail ?? value.error_description;
  if (typeof direct === "string") return direct;
  const error = value.error;
  if (typeof error === "string") return error;
  if (isRecord(error)) return findMessageField(error);
  return undefined;
}

async function fetchJson(url: string, headers: Record<string, string>, apiKey: string): Promise<JsonAttempt> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);

  try {
    const response = await fetch(url, {
      method: "GET",
      headers,
      signal: controller.signal,
      cache: "no-store",
    });
    const text = await response.text();
    if (!response.ok) {
      const message = redactSecret(extractUpstreamMessage(text) || response.statusText || `HTTP ${response.status}`, apiKey);
      return { ok: false, failure: { kind: "http", url, status: response.status, message } };
    }
    try {
      return { ok: true, url, status: response.status, json: text.trim() ? JSON.parse(text) as unknown : {} };
    } catch {
      return { ok: false, failure: { kind: "format", url, status: response.status, message: "接口返回的不是 JSON" } };
    }
  } catch (error) {
    const name = error instanceof Error ? error.name : "";
    const message = error instanceof Error ? error.message : String(error);
    const kind: AttemptFailureKind = name === "AbortError" || controller.signal.aborted ? "timeout" : "network";
    return { ok: false, failure: { kind, url, message: redactSecret(message, apiKey) } };
  } finally {
    clearTimeout(timeout);
  }
}

function parseOpenAiModels(payload: unknown): FetchedModel[] | undefined {
  if (!isRecord(payload)) return undefined;
  const rawModels = Array.isArray(payload.data) ? payload.data : Array.isArray(payload.models) ? payload.models : undefined;
  if (!rawModels) return undefined;

  return dedupeModels(rawModels.flatMap((item): FetchedModel[] => {
    if (!isRecord(item)) return [];
    const id = optionalString(item.id) || optionalString(item.model) || optionalString(item.name);
    if (!id) return [];
    return [{
      id,
      name: optionalString(item.name) && optionalString(item.name) !== id ? optionalString(item.name) : undefined,
      ownedBy: optionalString(item.owned_by) || optionalString(item.ownedBy) || optionalString(item.owner) || null,
    }];
  }));
}

function parseAnthropicModels(payload: unknown): FetchedModel[] | undefined {
  if (!isRecord(payload)) return undefined;
  const rawModels = Array.isArray(payload.data) ? payload.data : Array.isArray(payload.models) ? payload.models : undefined;
  if (!rawModels) return undefined;

  return dedupeModels(rawModels.flatMap((item): FetchedModel[] => {
    if (!isRecord(item)) return [];
    const id = optionalString(item.id) || optionalString(item.name);
    if (!id) return [];
    return [{
      id,
      name: optionalString(item.display_name) || optionalString(item.displayName) || optionalString(item.name),
      ownedBy: "anthropic",
    }];
  }));
}

function parseGeminiModels(payload: unknown): FetchedModel[] | undefined {
  if (!isRecord(payload) || !Array.isArray(payload.models)) return undefined;

  return dedupeModels(payload.models.flatMap((item): FetchedModel[] => {
    if (!isRecord(item)) return [];
    const methods = readStringArray(item.supportedGenerationMethods);
    if (methods.length > 0 && !methods.includes("generateContent")) return [];
    const rawName = optionalString(item.name);
    const id = rawName?.replace(/^models\//, "") || optionalString(item.id);
    if (!id) return [];
    return [{
      id,
      name: optionalString(item.displayName) || optionalString(item.display_name) || id,
      ownedBy: "google",
    }];
  }));
}

function classifyFailure(failures: AttemptFailure[], apiKey: string): { error: string; hint: string } {
  const failure =
    failures.find((item) => item.status === 401 || item.status === 403)
    ?? failures.find((item) => item.status === 429)
    ?? failures.find((item) => item.kind === "timeout")
    ?? failures.find((item) => item.kind === "network")
    ?? failures.find((item) => item.kind === "format")
    ?? failures[failures.length - 1];

  const upstream = failure?.message ? `（${redactSecret(failure.message, apiKey)}）` : "";
  if (!failure) {
    return { error: "没有可用的模型列表地址", hint: "请检查供应商和 Base URL 是否填写正确。" };
  }
  if (failure.status === 401 || failure.status === 403) {
    return {
      error: `API Key 无效或没有访问权限${upstream}`,
      hint: "重新复制 API Key，确认账号余额、模型权限和供应商区域是否正确。",
    };
  }
  if (failure.status === 429) {
    return {
      error: `供应商限流或余额不足${upstream}`,
      hint: "稍后重试，或检查该 API Key 的额度和账单状态。",
    };
  }
  if (failure.status === 404) {
    return {
      error: `没有找到模型列表接口${upstream}`,
      hint: "检查 Base URL。可以填根地址或 /v1 地址，不要填 /chat/completions。",
    };
  }
  if (failure.kind === "timeout") {
    return {
      error: "连接超时",
      hint: "检查网络、代理和 Base URL。局域网本地模型也要确认服务已启动。",
    };
  }
  if (failure.kind === "network") {
    return {
      error: `地址无法连接${upstream}`,
      hint: "检查 Base URL 是否能从当前服务器访问，本地模型请确认端口正在监听。",
    };
  }
  if (failure.kind === "format") {
    return {
      error: `接口返回格式无法识别${upstream}`,
      hint: "这个地址可能不是模型列表接口。OpenAI 兼容服务通常应支持 /v1/models。",
    };
  }
  if (failure.status && failure.status >= 500) {
    return {
      error: `供应商服务暂时不可用${upstream}`,
      hint: "稍后重试。如果一直失败，检查供应商状态页或换一个 Base URL。",
    };
  }
  return {
    error: `获取模型失败${upstream}`,
    hint: "检查 API Key、Base URL 和供应商类型是否匹配。",
  };
}

function findRecommendedModel(models: FetchedModel[], preset: ModelProviderPreset | undefined): string | undefined {
  if (models.length === 0) return preset?.recommendedModel;
  const priorities = [
    ...(preset?.recommendedModel ? [preset.recommendedModel] : []),
    ...(preset ? MODEL_PRIORITIES[preset.id] ?? [] : []),
  ];
  for (const wanted of priorities) {
    const exact = models.find((model) => model.id.toLowerCase() === wanted.toLowerCase());
    if (exact) return exact.id;
  }
  for (const wanted of priorities) {
    const partial = models.find((model) => model.id.toLowerCase().includes(wanted.toLowerCase()));
    if (partial) return partial.id;
  }
  return models[0]?.id;
}

async function fetchWithCandidates(
  urls: string[],
  headers: Record<string, string>,
  parser: (payload: unknown) => FetchedModel[] | undefined,
  apiKey: string,
): Promise<{ ok: true; models: FetchedModel[]; tried: string[] } | { ok: false; failures: AttemptFailure[]; tried: string[] }> {
  const failures: AttemptFailure[] = [];
  const tried: string[] = [];
  for (const url of urls) {
    tried.push(url);
    const attempt = await fetchJson(url, headers, apiKey);
    if (!attempt.ok) {
      failures.push(attempt.failure);
      if (attempt.failure.status === 401 || attempt.failure.status === 403 || attempt.failure.status === 429) break;
      continue;
    }

    const models = parser(attempt.json);
    if (models) return { ok: true, models, tried };
    failures.push({ kind: "format", url: attempt.url, status: attempt.status, message: "模型列表字段不符合预期" });
  }
  return { ok: false, failures, tried };
}

function resolveApi(api: unknown, preset: ModelProviderPreset | undefined): ModelProviderApi {
  const requested = optionalString(api);
  return isSupportedModelProviderApi(requested) ? requested : preset?.api ?? "openai-completions";
}

export async function POST(req: Request) {
  let body: FetchModelsRequest;
  try {
    body = await req.json() as FetchModelsRequest;
  } catch {
    return jsonFailure("请求体不是有效 JSON", "请刷新页面后重试。", undefined, 400);
  }
  if (!isRecord(body)) return jsonFailure("请求体格式不正确", "请刷新页面后重试。", undefined, 400);

  const providerIdInput = optionalString(body.providerId);
  const preset = getModelProviderPreset(providerIdInput);
  const providerId = preset?.id ?? providerIdInput;
  const api = resolveApi(body.api, preset);
  const baseUrl = normalizeBaseUrl(optionalString(body.baseUrl), preset?.baseUrl);
  const apiKey = optionalString(body.apiKey) ?? "";
  const allowsMissingKey = providerAllowsMissingApiKey(providerId, api);

  if (!baseUrl) {
    return jsonFailure(
      "Base URL 无效或为空",
      preset ? "请检查 Base URL 是否包含 http:// 或 https://。" : "自定义供应商需要填写 Base URL，例如 https://api.example.com/v1。",
      undefined,
      400,
    );
  }
  if (!apiKey && !allowsMissingKey) {
    return jsonFailure("请先填写 API Key", "这个供应商需要 API Key 才能获取模型列表。", undefined, 400);
  }

  let urls: string[];
  let headers: Record<string, string>;
  let parser: (payload: unknown) => FetchedModel[] | undefined;

  if (api === "google-generative-ai") {
    urls = geminiModelUrls(baseUrl, apiKey);
    headers = { Accept: "application/json" };
    parser = parseGeminiModels;
  } else if (api === "anthropic-messages") {
    urls = anthropicModelUrls(baseUrl);
    headers = {
      Accept: "application/json",
      "anthropic-version": ANTHROPIC_VERSION,
      ...(apiKey ? { "x-api-key": apiKey } : {}),
    };
    parser = parseAnthropicModels;
  } else {
    urls = openAiModelUrls(baseUrl, providerId);
    headers = {
      Accept: "application/json",
      ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
      ...(providerId === "openrouter" ? {
        "HTTP-Referer": "http://localhost:30141",
        "X-Title": "pi-web",
      } : {}),
    };
    parser = parseOpenAiModels;
  }

  const result = await fetchWithCandidates(urls, headers, parser, apiKey);
  const candidates = sanitizeCandidates(result.tried);
  if (!result.ok) {
    const classified = classifyFailure(result.failures, apiKey);
    return jsonFailure(classified.error, classified.hint, candidates);
  }

  const recommendedModel = findRecommendedModel(result.models, preset);
  return NextResponse.json({
    ok: true,
    models: result.models,
    ...(recommendedModel ? { recommendedModel } : {}),
    candidates,
    ...(result.models.length === 0 ? { message: "连接成功，但供应商没有返回可用模型。可以手动输入模型 ID。" } : {}),
  });
}
