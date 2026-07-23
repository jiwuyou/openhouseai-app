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

export interface ModelSetupProviderConfig {
  [key: string]: unknown;
  baseUrl?: string;
  api?: ModelProviderApi;
  headers?: Record<string, string>;
  models?: Array<{ [key: string]: unknown; id: string; name?: string; api?: ModelProviderApi; reasoning?: boolean }>;
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

export interface ModelSetupState {
  revision: string;
  presets: ModelSetupPreset[];
  config: { providers: Record<string, ModelSetupProviderConfig> };
  providers: Array<{ id: string; label: string; authenticated: boolean; authLabel?: string; modelCount?: number }>;
  models: Array<{ id: string; provider: string; name?: string; available?: boolean; reasoning?: boolean }>;
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

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function optionalText(value: unknown): string | undefined {
  const normalized = typeof value === "string" ? value.trim() : "";
  return normalized || undefined;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.flatMap((item) => optionalText(item) ? [optionalText(item)!] : []) : [];
}

function modelSourceApis(value: unknown): ModelProviderApi[] {
  if (!Array.isArray(value)) return [];
  return Array.from(new Set(value.flatMap((source) => {
    if (typeof source === "string") return optionalText(source) ? [optionalText(source)! as ModelProviderApi] : [];
    const api = optionalText(record(source).api ?? record(source).mode ?? record(source).protocol);
    return api ? [api as ModelProviderApi] : [];
  })));
}

function dataRoot(value: unknown): Record<string, unknown> {
  const root = record(value);
  return "data" in root ? record(root.data) : root;
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
  const rawHeaders = record(provider.headers);
  const headers = Object.fromEntries(Object.entries(rawHeaders).flatMap(([key, header]) => optionalText(header) ? [[key, optionalText(header)!]] : []));
  const models = Array.isArray(provider.models) ? provider.models.map(record).flatMap((model) => {
    const id = optionalText(model.id);
    if (!id) return [];
    return [{
      ...record(stripApiKeys(model)),
      id,
      ...(optionalText(model.name) ? { name: optionalText(model.name) } : {}),
      ...(optionalText(model.api) ? { api: optionalText(model.api) } : {}),
      ...(typeof model.reasoning === "boolean" ? { reasoning: model.reasoning } : {}),
    }];
  }) : undefined;
  return {
    ...sanitized,
    ...(optionalText(provider.baseUrl) ? { baseUrl: optionalText(provider.baseUrl) } : {}),
    ...(optionalText(provider.api) ? { api: optionalText(provider.api) } : {}),
    ...(Object.keys(headers).length ? { headers } : {}),
    ...(models ? { models } : {}),
  };
}

export function normalizeModelSetup(value: unknown): ModelSetupState {
  const root = dataRoot(value);
  const rawConfig = record(root.config);
  const rawProviderConfigs = record(rawConfig.providers);
  const presets = Array.isArray(root.presets) ? root.presets.map(record).flatMap((preset) => {
    const id = optionalText(preset.id);
    if (!id) return [];
    return [{
      id,
      label: optionalText(preset.label ?? preset.name) ?? id,
      providerId: optionalText(preset.providerId ?? preset.provider ?? preset.providerName) ?? id,
      ...(optionalText(preset.description) ? { description: optionalText(preset.description) } : {}),
      ...(optionalText(preset.baseUrl) ? { baseUrl: optionalText(preset.baseUrl) } : {}),
      ...(optionalText(preset.api) ? { api: optionalText(preset.api) } : {}),
      requiresApiKey: typeof preset.requiresApiKey === "boolean" ? preset.requiresApiKey : typeof preset.keyRequired === "boolean" ? preset.keyRequired : true,
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
    config: { providers: Object.fromEntries(Object.entries(rawProviderConfigs).map(([id, provider]) => [id, normalizeProviderConfig(provider)])) },
    providers,
    models,
    defaultModel: optionalText(defaultModel.provider) && optionalText(defaultModel.modelId ?? defaultModel.id)
      ? { provider: optionalText(defaultModel.provider)!, modelId: optionalText(defaultModel.modelId ?? defaultModel.id)! }
      : null,
  };
}

export function normalizeModelDraftResult(value: unknown): ModelDraftResult {
  const root = dataRoot(value);
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

export function modelSetupHealth(setup: ModelSetupState) {
  const usableModels = setup.models.filter((model) => model.available !== false);
  const defaultModel = setup.defaultModel && {
    ...setup.defaultModel,
    exists: setup.models.some((model) => model.provider === setup.defaultModel!.provider && model.id === setup.defaultModel!.modelId),
    usable: usableModels.some((model) => model.provider === setup.defaultModel!.provider && model.id === setup.defaultModel!.modelId),
  };
  const missingReasons: string[] = [];
  if (Object.keys(setup.config.providers).length === 0 && setup.providers.length === 0) missingReasons.push("no_providers");
  if (setup.models.length === 0) missingReasons.push("no_models");
  if (usableModels.length === 0) missingReasons.push("no_usable_models");
  if (defaultModel && !defaultModel.exists) missingReasons.push("default_model_missing");
  if (defaultModel?.exists && !defaultModel.usable) missingReasons.push("default_model_not_usable");
  return {
    hasUsableModel: usableModels.length > 0 && (!defaultModel || defaultModel.usable),
    modelCount: usableModels.length,
    totalModelCount: setup.models.length,
    defaultModel,
    providers: setup.providers,
    missingReasons,
  };
}
