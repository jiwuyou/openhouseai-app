import { existsSync, readFileSync } from "fs";
import { join } from "path";
import { getDefaultCwd } from "./runtime-paths";
import {
  AuthStorage,
  getAgentDir,
  ModelRegistry,
  SettingsManager,
  type AuthStatus,
} from "@earendil-works/pi-coding-agent";
import {
  getModelProviderPreset,
  isSupportedModelProviderApi,
  type ModelProviderApi,
} from "./model-provider-presets";
import {
  inferApiFromEndpoint,
  isApiKeyRequiredForBaseUrl,
  normalizeModelBaseUrl,
  sanitizeModelConfigUrlForResponse,
} from "./model-config-compat";

export type ModelConfigMissingReason =
  | "models_config_missing"
  | "models_config_invalid"
  | "no_providers"
  | "no_models"
  | "no_usable_models"
  | "default_model_missing"
  | "default_model_not_usable"
  | "provider_base_url_missing"
  | "provider_api_missing"
  | "provider_api_key_missing";

export interface ModelConfigHealth {
  hasUsableModel: boolean;
  modelCount: number;
  totalModelCount: number;
  defaultModel: {
    provider: string;
    modelId: string;
    name?: string;
    usable: boolean;
    exists: boolean;
  } | null;
  missingReasons: ModelConfigMissingReason[];
  providers: SafeProviderStatus[];
  loadError?: string;
}

export interface SafeProviderStatus {
  id: string;
  label: string;
  api?: ModelProviderApi;
  baseUrl?: string;
  normalizedBaseUrl?: string;
  catalogId?: string;
  requiresApiKey: boolean;
  hasCredential: boolean;
  credentialSource?: AuthStatus["source"];
  modelCount: number;
  availableModelCount: number;
  issues: ModelConfigMissingReason[];
}

interface ModelsConfigFile {
  providers?: Record<string, ProviderConfigFile>;
}

interface ProviderConfigFile {
  baseUrl?: unknown;
  api?: unknown;
  apiKey?: unknown;
  authHeader?: unknown;
  headers?: unknown;
  compat?: unknown;
  models?: unknown;
  modelOverrides?: unknown;
}

interface ModelConfigFile {
  id?: unknown;
  name?: unknown;
  api?: unknown;
  baseUrl?: unknown;
  apiKey?: unknown;
}

interface RegistryModelSummary {
  id: string;
  name?: string;
  provider: string;
  api?: string;
  baseUrl?: string;
}

export async function getModelConfigHealth(options: { cwd?: string } = {}): Promise<ModelConfigHealth> {
  const cwd = options.cwd || getDefaultCwd();
  const agentDir = getAgentDir();
  const modelsPath = join(agentDir, "models.json");
  const { config, exists, parseError } = readModelsConfig(modelsPath);
  const authStorage = AuthStorage.create();
  const registry = ModelRegistry.create(authStorage, modelsPath);
  const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });

  const allModels = registry.getAll().map(toModelSummary);
  const availableModels = registry.getAvailable().map(toModelSummary);
  const loadError = registry.getError() ?? parseError;
  const providerEntries = Object.entries(config.providers ?? {});
  const providerIds = collectProviderIds(providerEntries, allModels, availableModels, settingsManager.getDefaultProvider());
  const providers = providerIds.map((providerId) => buildProviderStatus({
    providerId,
    providerConfig: config.providers?.[providerId],
    authStatus: registry.getProviderAuthStatus(providerId),
    allModels,
    availableModels,
  }));

  const defaultProvider = settingsManager.getDefaultProvider();
  const defaultModelId = settingsManager.getDefaultModel();
  const defaultModel = resolveDefaultModel(defaultProvider, defaultModelId, allModels, availableModels);

  const missingReasons = collectMissingReasons({
    exists,
    loadError,
    providerEntries,
    providers,
    allModels,
    availableModels,
    defaultModel,
    hasDefaultSelection: !!(defaultProvider && defaultModelId),
  });

  const hasUsableModel = availableModels.length > 0 && (!defaultModel || defaultModel.usable);

  return {
    hasUsableModel,
    modelCount: availableModels.length,
    totalModelCount: allModels.length,
    defaultModel,
    missingReasons,
    providers,
    ...(loadError ? { loadError: redactSecrets(loadError) } : {}),
  };
}

function readModelsConfig(modelsPath: string): { config: ModelsConfigFile; exists: boolean; parseError?: string } {
  if (!existsSync(modelsPath)) return { config: { providers: {} }, exists: false };
  try {
    const parsed = JSON.parse(readFileSync(modelsPath, "utf8")) as unknown;
    if (!isRecord(parsed)) return { config: { providers: {} }, exists: true, parseError: "models.json root must be an object" };
    const providers = isRecord(parsed.providers) ? parsed.providers : {};
    return {
      config: {
        providers: Object.fromEntries(
          Object.entries(providers).flatMap(([key, value]) => isRecord(value) ? [[key, value as ProviderConfigFile]] : []),
        ),
      },
      exists: true,
    };
  } catch (error) {
    return {
      config: { providers: {} },
      exists: true,
      parseError: error instanceof Error ? error.message : String(error),
    };
  }
}

function collectProviderIds(
  providerEntries: Array<[string, ProviderConfigFile]>,
  allModels: RegistryModelSummary[],
  availableModels: RegistryModelSummary[],
  defaultProvider: string | undefined,
): string[] {
  const ids = new Set<string>();
  for (const [providerId] of providerEntries) ids.add(providerId);
  for (const model of availableModels) ids.add(model.provider);
  if (defaultProvider) ids.add(defaultProvider);
  if (ids.size === 0) {
    for (const model of allModels.slice(0, 8)) ids.add(model.provider);
  }
  return Array.from(ids).sort((a, b) => a.localeCompare(b));
}

function buildProviderStatus(options: {
  providerId: string;
  providerConfig?: ProviderConfigFile;
  authStatus: AuthStatus;
  allModels: RegistryModelSummary[];
  availableModels: RegistryModelSummary[];
}): SafeProviderStatus {
  const { providerId, providerConfig, authStatus, allModels, availableModels } = options;
  const preset = getModelProviderPreset(providerId);
  const api = resolveProviderApi(providerConfig, preset?.api);
  const baseUrl = optionalString(providerConfig?.baseUrl) ?? firstModelBaseUrl(providerConfig) ?? preset?.baseUrl;
  const normalizedBaseUrl = normalizeModelBaseUrl(baseUrl).baseUrl;
  const modelCount = allModels.filter((model) => model.provider === providerId).length;
  const availableModelCount = availableModels.filter((model) => model.provider === providerId).length;
  const requiresApiKey = isApiKeyRequiredForBaseUrl(preset, normalizedBaseUrl);
  const hasCredential = authStatus.configured || !requiresApiKey;
  const issues = collectProviderIssues({
    providerConfig,
    modelCount,
    api,
    normalizedBaseUrl,
    requiresApiKey,
    hasCredential,
  });

  return {
    id: providerId,
    label: preset?.label ?? providerId,
    ...(api ? { api } : {}),
    ...(baseUrl ? { baseUrl: sanitizeStatusUrl(baseUrl) } : {}),
    ...(normalizedBaseUrl ? { normalizedBaseUrl: sanitizeStatusUrl(normalizedBaseUrl) } : {}),
    ...(preset ? { catalogId: preset.id } : {}),
    requiresApiKey,
    hasCredential,
    ...(authStatus.source ? { credentialSource: authStatus.source } : {}),
    modelCount,
    availableModelCount,
    issues,
  };
}

function collectProviderIssues(options: {
  providerConfig?: ProviderConfigFile;
  modelCount: number;
  api?: ModelProviderApi;
  normalizedBaseUrl?: string;
  requiresApiKey: boolean;
  hasCredential: boolean;
}): ModelConfigMissingReason[] {
  const issues: ModelConfigMissingReason[] = [];
  const hasProviderConfig = !!options.providerConfig;
  if (hasProviderConfig && options.modelCount === 0) issues.push("no_models");
  if (hasProviderConfig && !options.normalizedBaseUrl && hasModels(options.providerConfig)) issues.push("provider_base_url_missing");
  if (hasProviderConfig && !options.api && hasModels(options.providerConfig)) issues.push("provider_api_missing");
  if (options.requiresApiKey && !options.hasCredential) issues.push("provider_api_key_missing");
  return uniqueReasons(issues);
}

function collectMissingReasons(options: {
  exists: boolean;
  loadError?: string;
  providerEntries: Array<[string, ProviderConfigFile]>;
  providers: SafeProviderStatus[];
  allModels: RegistryModelSummary[];
  availableModels: RegistryModelSummary[];
  defaultModel: ModelConfigHealth["defaultModel"];
  hasDefaultSelection: boolean;
}): ModelConfigMissingReason[] {
  const reasons: ModelConfigMissingReason[] = [];
  if (!options.exists) reasons.push("models_config_missing");
  if (options.loadError) reasons.push("models_config_invalid");
  if (options.providerEntries.length === 0 && options.availableModels.length === 0) reasons.push("no_providers");
  if (options.allModels.length === 0) reasons.push("no_models");
  if (options.availableModels.length === 0) reasons.push("no_usable_models");
  if (options.hasDefaultSelection && options.defaultModel && !options.defaultModel.exists) reasons.push("default_model_missing");
  if (options.hasDefaultSelection && options.defaultModel?.exists && !options.defaultModel.usable) reasons.push("default_model_not_usable");
  for (const provider of options.providers) reasons.push(...provider.issues);
  return uniqueReasons(reasons);
}

function resolveDefaultModel(
  provider: string | undefined,
  modelId: string | undefined,
  allModels: RegistryModelSummary[],
  availableModels: RegistryModelSummary[],
): ModelConfigHealth["defaultModel"] {
  if (!provider || !modelId) return null;
  const model = allModels.find((item) => item.provider === provider && item.id === modelId);
  const usable = availableModels.some((item) => item.provider === provider && item.id === modelId);
  return {
    provider,
    modelId,
    ...(model?.name ? { name: model.name } : {}),
    exists: !!model,
    usable,
  };
}

function resolveProviderApi(providerConfig: ProviderConfigFile | undefined, fallback: ModelProviderApi | undefined): ModelProviderApi | undefined {
  const providerApi = optionalString(providerConfig?.api);
  if (isSupportedModelProviderApi(providerApi)) return providerApi;

  const firstModel = firstModelConfig(providerConfig);
  const modelApi = optionalString(firstModel?.api);
  if (isSupportedModelProviderApi(modelApi)) return modelApi;

  const inferred = inferApiFromEndpoint(optionalString(providerConfig?.baseUrl) ?? optionalString(firstModel?.baseUrl), fallback);
  return isSupportedModelProviderApi(inferred) ? inferred : fallback;
}

function firstModelBaseUrl(providerConfig: ProviderConfigFile | undefined): string | undefined {
  return optionalString(firstModelConfig(providerConfig)?.baseUrl);
}

function firstModelConfig(providerConfig: ProviderConfigFile | undefined): ModelConfigFile | undefined {
  if (!Array.isArray(providerConfig?.models)) return undefined;
  return providerConfig.models.find((model): model is ModelConfigFile => isRecord(model));
}

function hasModels(providerConfig: ProviderConfigFile | undefined): boolean {
  return Array.isArray(providerConfig?.models) && providerConfig.models.some((model) => isRecord(model) && typeof model.id === "string" && model.id.trim());
}

function toModelSummary(model: { id: string; name?: string; provider: string; api?: string; baseUrl?: string }): RegistryModelSummary {
  return {
    id: model.id,
    ...(model.name ? { name: model.name } : {}),
    provider: model.provider,
    ...(model.api ? { api: model.api } : {}),
    ...(model.baseUrl ? { baseUrl: model.baseUrl } : {}),
  };
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function uniqueReasons(reasons: ModelConfigMissingReason[]): ModelConfigMissingReason[] {
  return Array.from(new Set(reasons));
}

function sanitizeStatusUrl(value: string): string {
  return sanitizeModelConfigUrlForResponse(value) ?? "";
}

function redactSecrets(value: string): string {
  return value
    .replace(/sk-[A-Za-z0-9_-]{8,}/g, "sk-[redacted]")
    .replace(/Bearer\s+[A-Za-z0-9._-]{8,}/gi, "Bearer [redacted]")
    .replace(/api[_-]?key["'\s:=]+[A-Za-z0-9._-]{8,}/gi, "api_key=[redacted]")
    .slice(0, 2000);
}
