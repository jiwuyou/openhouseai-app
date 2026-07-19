import type { ModelProviderApi, ModelProviderPreset } from "./model-provider-presets";

export type CcSwitchApiFormat = "anthropic" | "openai_chat" | "openai_responses" | "gemini_native";

export interface NormalizedModelEndpoint {
  input: string;
  baseUrl?: string;
  removedPath?: string;
  api?: ModelProviderApi;
  reason?: string;
}

export function normalizeProviderId(providerId: string): string {
  return providerId.trim().toLowerCase().replace(/[\s_]+/g, "-");
}

export function isLoopbackBaseUrl(value: string | undefined): boolean {
  if (!value) return false;
  try {
    const url = new URL(coerceUrlInput(value));
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
    return host === "localhost"
      || host === "127.0.0.1"
      || host === "::1"
      || host === "0.0.0.0"
      || host === "10.0.2.2";
  } catch {
    const normalized = value.trim().toLowerCase();
    return normalized.startsWith("localhost:")
      || normalized.startsWith("127.0.0.1:")
      || normalized.startsWith("[::1]:")
      || normalized.startsWith("::1:")
      || normalized.startsWith("0.0.0.0:")
      || normalized.startsWith("10.0.2.2:");
  }
}

export function inferApiFromEndpoint(value: string | undefined, fallback?: ModelProviderApi): ModelProviderApi | undefined {
  if (!value) return fallback;
  const lower = value.toLowerCase();
  if (lower.includes(":generatecontent") || lower.includes("generativelanguage.googleapis.com")) return "google-generative-ai";
  if (lower.endsWith("/responses") || lower.includes("/responses?")) return "openai-responses";
  if (lower.endsWith("/messages") || lower.includes("/messages?")) return "anthropic-messages";
  return fallback;
}

export function normalizeModelBaseUrl(input: string | undefined, fallback?: string): NormalizedModelEndpoint {
  const raw = (input || fallback || "").trim();
  if (!raw) return { input: raw, reason: "empty" };

  try {
    const url = new URL(coerceUrlInput(raw));
    stripUrlCredentialsAndMetadata(url);
    const originalPath = url.pathname;
    const { path, removedPath } = normalizeEndpointPath(url.pathname);
    url.pathname = path;
    const normalized = trimUrlTrailingSlash(url.toString(), url.pathname);
    return {
      input: raw,
      baseUrl: normalized,
      removedPath: removedPath || (originalPath !== path ? originalPath : undefined),
      api: inferApiFromEndpoint(raw),
    };
  } catch {
    return { input: raw, reason: "invalid-url" };
  }
}

export function sanitizeModelConfigUrlForResponse(value: string | undefined): string | undefined {
  const raw = value?.trim();
  if (!raw) return undefined;

  try {
    const url = new URL(coerceUrlInput(raw));
    stripUrlCredentialsAndMetadata(url);
    return trimUrlTrailingSlash(url.toString(), url.pathname);
  } catch {
    return raw
      .replace(/([a-z][a-z0-9+.-]*:\/\/)[^/@\s]+@/i, "$1")
      .replace(/[?#].*$/, "");
  }
}

export function normalizeEndpointCandidates(values: Array<string | undefined>): string[] {
  const seen = new Set<string>();
  const output: string[] = [];
  for (const value of values) {
    const normalized = normalizeModelBaseUrl(value).baseUrl;
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    output.push(normalized);
  }
  return output;
}

export function getPresetEndpointCandidates(preset: ModelProviderPreset | undefined): string[] {
  if (!preset) return [];
  return normalizeEndpointCandidates([
    preset.baseUrl,
    ...(preset.endpointCandidates ?? []),
  ]);
}

export function isApiKeyRequiredForBaseUrl(preset: ModelProviderPreset | undefined, baseUrl: string | undefined): boolean {
  if (baseUrl && isLoopbackBaseUrl(baseUrl)) return false;
  return preset?.requiresApiKey ?? true;
}

function coerceUrlInput(value: string): string {
  const trimmed = value.trim();
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  if (/^(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|10\.0\.2\.2):\d+/i.test(trimmed)) {
    return `http://${trimmed}`;
  }
  return trimmed;
}

function trimUrlTrailingSlash(value: string, pathname: string): string {
  return value.endsWith("/") && pathname !== "/" ? value.slice(0, -1) : value;
}

function stripUrlCredentialsAndMetadata(url: URL): void {
  url.username = "";
  url.password = "";
  url.search = "";
  url.hash = "";
}

function normalizeEndpointPath(pathname: string): { path: string; removedPath?: string } {
  let path = pathname.replace(/\/+$/, "") || "/";
  const original = path;
  const lower = () => path.toLowerCase();

  const suffixes = [
    "/chat/completions",
    "/v1/chat/completions",
    "/responses",
    "/v1/responses",
    "/messages",
    "/v1/messages",
    "/models",
  ];

  let changed = true;
  while (changed) {
    changed = false;
    for (const suffix of suffixes) {
      if (lower().endsWith(suffix)) {
        path = path.slice(0, -suffix.length) || "/";
        changed = true;
        break;
      }
    }
  }

  const modelsSegment = lower().indexOf("/models/");
  if (modelsSegment >= 0) path = path.slice(0, modelsSegment) || "/";

  const generateContentIndex = lower().indexOf(":generatecontent");
  if (generateContentIndex >= 0) path = path.slice(0, generateContentIndex) || "/";

  path = collapseDuplicateVersionTail(path);
  path = path.replace(/\/+$/, "") || "/";

  return {
    path,
    removedPath: original !== path ? original : undefined,
  };
}

function collapseDuplicateVersionTail(path: string): string {
  const parts = path.split("/");
  if (parts.length < 3) return path;
  const last = parts[parts.length - 1];
  const previous = parts[parts.length - 2];
  if (/^v\d+(?:alpha|beta)?$/i.test(last) && last.toLowerCase() === previous.toLowerCase()) {
    parts.splice(parts.length - 1, 1);
  }
  return parts.join("/") || "/";
}
