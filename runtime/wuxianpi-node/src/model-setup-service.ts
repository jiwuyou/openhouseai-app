import { createHash, randomUUID } from "node:crypto";
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { ModelRuntime, type SettingsManager } from "@earendil-works/pi-coding-agent";
import { parse as parseJsonc, printParseErrorCode, type ParseError } from "jsonc-parser";
import lockfile from "proper-lockfile";
import { discoverModels, type ModelDiscoveryInput, type ModelDiscoveryResult } from "./model-discovery.js";
import {
  MODEL_PROVIDER_PRESETS,
  normalizeModelProviderApi,
  providerAllowsMissingApiKey,
  type ModelProviderApi,
} from "./model-provider-presets.js";
import { boundedInteger, optionalString, RequestError, requireString } from "./protocol.js";

type JsonRecord = Record<string, unknown>;

export interface ModelsJsonConfig {
  providers: Record<string, JsonRecord>;
}

export interface ModelSetupState {
  revision: string;
  presets: Array<(typeof MODEL_PROVIDER_PRESETS)[number] & { apiType: ModelProviderApi }>;
  config: ModelsJsonConfig;
  providers: Array<{
    id: string;
    name: string;
    authenticated: boolean;
    authType?: string;
    authSource?: string;
    authLabel?: string;
  }>;
  models: Array<{
    provider: string;
    id: string;
    name: string;
    available: boolean;
    reasoning: boolean;
    input: readonly string[];
    contextWindow: number;
    maxTokens: number;
  }>;
  defaultModel: { provider: string; modelId: string } | null;
  availabilityError?: string;
}

export interface ModelSetupServiceOptions {
  agentDir: string;
  modelRuntime: () => Promise<ModelRuntime>;
  settingsManager: SettingsManager;
  reload: () => Promise<void>;
  discover?: (input: ModelDiscoveryInput) => Promise<ModelDiscoveryResult>;
  createModelRuntime?: typeof ModelRuntime.create;
  acquireLock?: (
    path: string,
    options: {
      realpath: boolean;
      stale: number;
      update: number;
      retries: { retries: number; factor: number; minTimeout: number; maxTimeout: number; randomize: boolean };
      onCompromised: (error: Error) => void;
    },
  ) => Promise<() => Promise<void>>;
}

type FileSnapshot = { exists: boolean; content?: string };
type CredentialMutation = { providerId: string; action: "keep" | "set" | "remove"; apiKey?: string };
type TransactionState = {
  original: Map<string, FileSnapshot>;
  owned: Map<string, FileSnapshot>;
  touched: Set<string>;
};

export class ModelSetupService {
  private readonly modelsPath: string;
  private readonly authPath: string;
  private readonly settingsPath: string;
  private readonly transactionLockPath: string;
  private readonly discover: (input: ModelDiscoveryInput) => Promise<ModelDiscoveryResult>;
  private readonly createModelRuntime: typeof ModelRuntime.create;
  private readonly acquireLock: NonNullable<ModelSetupServiceOptions["acquireLock"]>;
  private transaction = Promise.resolve();

  constructor(private readonly options: ModelSetupServiceOptions) {
    this.modelsPath = join(options.agentDir, "models.json");
    this.authPath = join(options.agentDir, "auth.json");
    this.settingsPath = join(options.agentDir, "settings.json");
    this.transactionLockPath = join(options.agentDir, ".model-setup-transaction");
    this.discover = options.discover ?? ((input) => discoverModels(input));
    this.createModelRuntime = options.createModelRuntime ?? ModelRuntime.create.bind(ModelRuntime);
    this.acquireLock = options.acquireLock ?? ((path, lockOptions) => lockfile.lock(path, lockOptions));
  }

  async setup(providerFilter?: string): Promise<ModelSetupState> {
    return this.withTransaction(async () => this.setupUnlocked(providerFilter));
  }

  private async setupUnlocked(providerFilter?: string): Promise<ModelSetupState> {
    const [runtime, snapshots] = await Promise.all([
      this.options.modelRuntime(),
      this.snapshots(),
    ]);
    const config = this.parseConfigSnapshot(snapshots.get(this.modelsPath));
    const revision = this.revisionFromSnapshots(snapshots);
    const providers = runtime.getProviders().filter((provider) => !providerFilter || provider.id === providerFilter);
    if (providerFilter && providers.length === 0) {
      throw new RequestError("provider_not_found", `Provider not found: ${providerFilter}`);
    }
    let available = new Set<string>();
    let availabilityError: string | undefined;
    try {
      available = new Set((await runtime.getAvailable(providerFilter)).map((model) => modelKey(model.provider, model.id)));
    } catch (error) {
      availabilityError = errorMessage(error);
    }
    const providerRows = await Promise.all(providers.map(async (provider) => {
      const configured = runtime.getProviderAuthStatus(provider.id);
      const auth = await runtime.checkAuth(provider.id).catch(() => undefined);
      return {
        id: provider.id,
        name: provider.name,
        authenticated: configured.configured || auth !== undefined,
        ...(auth?.type ? { authType: auth.type } : {}),
        ...(auth?.source ?? configured.source ? { authSource: auth?.source ?? configured.source } : {}),
        ...(configured.label ? { authLabel: configured.label } : {}),
      };
    }));
    const models = providers.flatMap((provider) => runtime.getModels(provider.id).map((model) => ({
      provider: model.provider,
      id: model.id,
      name: model.name,
      available: available.has(modelKey(model.provider, model.id)),
      reasoning: model.reasoning,
      input: model.input,
      contextWindow: model.contextWindow,
      maxTokens: model.maxTokens,
    })));
    const defaultProvider = this.options.settingsManager.getDefaultProvider();
    const defaultModelId = this.options.settingsManager.getDefaultModel();
    return {
      revision,
      presets: MODEL_PROVIDER_PRESETS.map((preset) => ({ ...preset, apiType: preset.api })),
      config: redactConfig(config),
      providers: providerRows,
      models,
      defaultModel: defaultProvider && defaultModelId ? { provider: defaultProvider, modelId: defaultModelId } : null,
      ...(availabilityError ? { availabilityError } : {}),
    };
  }

  async fetchModels(input: JsonRecord): Promise<ModelDiscoveryResult> {
    const providerId = providerIdFrom(input);
    const draft = recordValue(input.draft);
    const provider = recordValue(input.provider) ?? recordValue(draft?.provider);
    const runtime = await this.options.modelRuntime();
    const apiKeyDraft = draftString(input, provider, "apiKey");
    let apiKey = apiKeyDraft.provided ? cleanString(apiKeyDraft.value) : undefined;
    let authHeaders: Record<string, string> | undefined;
    if (!apiKeyDraft.provided && !apiKey && providerId) {
      const auth = await runtime.getAuth(providerId).catch(() => undefined);
      apiKey = auth?.auth.apiKey;
      authHeaders = auth?.auth.headers ? Object.fromEntries(
        Object.entries(auth.auth.headers).filter((entry): entry is [string, string] => typeof entry[1] === "string"),
      ) : undefined;
    }
    const headers = stringRecord(input.headers) ?? stringRecord(draft?.headers) ?? stringRecord(provider?.headers);
    const baseUrl = draftString(input, provider, "baseUrl");
    const api = draftApiValue(input, provider);
    try {
      return await this.discover({
        providerId,
        ...(baseUrl.provided ? { baseUrl: baseUrl.value } : {}),
        ...(api.provided ? { api: api.value } : {}),
        apiKey,
        headers: { ...(authHeaders ?? {}), ...(headers ?? {}) },
        timeoutMs: boundedInteger(input, "timeoutMs", 15_000, 60_000),
      });
    } catch (error) {
      throw sanitizeError(error, apiKey);
    }
  }

  async testModel(input: JsonRecord): Promise<Record<string, unknown>> {
    const draft = recordValue(input.draft);
    const draftProvider = recordValue(input.provider) ?? recordValue(draft?.provider);
    const draftModel = recordValue(input.model) ?? recordValue(draft?.model);
    if (draft || draftProvider || draftModel || input.baseUrl !== undefined || input.apiKey !== undefined || input.api !== undefined || input.apiType !== undefined) {
      return this.testDraft(input, draftProvider, draftModel);
    }
    const provider = providerIdFrom(input);
    const modelId = modelIdFrom(input);
    if (!provider) throw new RequestError("invalid_payload", "provider must be a non-empty string");
    if (!modelId) throw new RequestError("invalid_payload", "modelId must be a non-empty string");
    const runtime = await this.options.modelRuntime();
    const model = runtime.getModel(provider, modelId);
    if (!model) throw new RequestError("model_not_found", `Model not found: ${provider}/${modelId}`);
    return runModelTest(runtime, model, boundedInteger(input, "timeoutMs", 20_000, 60_000), model.api);
  }

  async apply(input: JsonRecord): Promise<ModelSetupState> {
    return this.withTransaction(async (transaction, assertLock) => {
      const requestedRevision = requireString(input, "revision");
      const snapshots = transaction.original;
      const currentRevision = this.revisionFromSnapshots(snapshots);
      if (requestedRevision !== currentRevision) {
        throw new RequestError("model_revision_conflict", "Model configuration changed since it was loaded", {
          expected: requestedRevision,
          actual: currentRevision,
        });
      }

      const current = this.parseConfigSnapshot(snapshots.get(this.modelsPath));
      const { config, credentials } = buildApplyPlan(current, input);
      const nextConfig = stripApiKeys(config);
      await this.validateConfig(nextConfig);
      for (const credential of credentials) {
        if (credential.action === "set" && !credential.apiKey) {
          throw new RequestError("invalid_payload", `API key is required for ${credential.providerId}`);
        }
      }
      const nextDefault = input.setGlobalDefault === true ? parseDefaultModel(input.defaultModel) : undefined;
      if (input.setGlobalDefault === true && !nextDefault) {
        throw new RequestError("invalid_payload", "defaultModel is required when setGlobalDefault is true");
      }

      await this.reloadSettings("model settings");
      await this.assertTransactionCurrent(transaction);
      for (const credential of credentials.filter((item) => item.action === "remove")) {
        assertLock();
        await this.mutateCredential(transaction, credential);
      }
      assertLock();
      await this.writeConfig(transaction, nextConfig);
      await this.options.reload();
      this.throwSettingsErrors("model settings reload");
      for (const credential of credentials.filter((item) => item.action === "set")) {
        assertLock();
        await this.mutateCredential(transaction, credential);
      }
      const reloaded = await this.options.modelRuntime();
      if (nextDefault) {
        if (!reloaded.getModel(nextDefault.provider, nextDefault.modelId)) {
          throw new RequestError("model_not_found", `Model not found: ${nextDefault.provider}/${nextDefault.modelId}`);
        }
        assertLock();
        await this.mutateDefaultSettings(transaction, nextDefault.provider, nextDefault.modelId);
      } else {
        const provider = this.options.settingsManager.getDefaultProvider();
        const modelId = this.options.settingsManager.getDefaultModel();
        if (provider && modelId && !reloaded.getModel(provider, modelId)) {
          throw new RequestError("default_model_removed", "The current default model would be removed; choose a replacement default model");
        }
      }
      await this.assertTransactionCurrent(transaction);
      const state = await this.setupUnlocked();
      await this.assertTransactionCurrent(transaction);
      return state;
    });
  }

  async login(provider: string, apiKey: string): Promise<Record<string, unknown>> {
    return this.withTransaction(async (transaction) => {
      const result = await this.mutateCredential(transaction, { providerId: provider, action: "set", apiKey });
      await this.assertTransactionCurrent(transaction);
      return result;
    });
  }

  async logout(provider: string): Promise<Record<string, unknown>> {
    return this.withTransaction(async (transaction) => {
      const result = await this.mutateCredential(transaction, { providerId: provider, action: "remove" });
      await this.assertTransactionCurrent(transaction);
      return result;
    });
  }

  async setDefault(provider: string, modelId: string): Promise<Record<string, unknown>> {
    return this.withTransaction(async (transaction) => {
      const result = await this.setDefaultNow(transaction, provider, modelId);
      await this.assertTransactionCurrent(transaction);
      return result;
    });
  }

  async reload(): Promise<ModelSetupState> {
    return this.withTransaction(async () => {
      await this.options.reload();
      this.throwSettingsErrors("model settings reload");
      return this.setupUnlocked();
    });
  }

  private async loginNow(provider: string, apiKey: string): Promise<Record<string, unknown>> {
    const runtime = await this.options.modelRuntime();
    if (!runtime.getProvider(provider)) throw new RequestError("provider_not_found", `Provider not found: ${provider}`);
    const trimmed = apiKey.trim();
    if (!trimmed) throw new RequestError("invalid_payload", "apiKey must be a non-empty string");
    await runtime.login(provider, "api_key", { prompt: async () => trimmed, notify: () => {} });
    const auth = runtime.getProviderAuthStatus(provider);
    return {
      provider,
      authenticated: auth.configured,
      ...(auth.source ? { authSource: auth.source } : {}),
      ...(auth.label ? { authLabel: auth.label } : {}),
    };
  }

  private async logoutNow(provider: string): Promise<Record<string, unknown>> {
    const runtime = await this.options.modelRuntime();
    if (!runtime.getProvider(provider)) throw new RequestError("provider_not_found", `Provider not found: ${provider}`);
    await runtime.logout(provider);
    return { provider, authenticated: false };
  }

  private async setDefaultNow(transaction: TransactionState, provider: string, modelId: string): Promise<Record<string, unknown>> {
    const runtime = await this.options.modelRuntime();
    if (!runtime.getModel(provider, modelId)) throw new RequestError("model_not_found", `Model not found: ${provider}/${modelId}`);
    await this.reloadSettings("model settings");
    await this.mutateDefaultSettings(transaction, provider, modelId);
    return { provider, modelId, appliedSessionIds: [] as string[] };
  }

  private async testDraft(input: JsonRecord, providerValue?: JsonRecord, modelValue?: JsonRecord): Promise<Record<string, unknown>> {
    const providerId = providerIdFrom(input);
    const modelId = modelIdFrom(input);
    if (!providerId) throw new RequestError("invalid_payload", "providerId or providerName is required for a draft test");
    if (!modelId) throw new RequestError("invalid_payload", "model.id or modelId is required for a draft test");
    const provider = { ...(providerValue ?? {}) };
    const apiKeyDraft = draftString(input, providerValue, "apiKey");
    const storedAuth = !apiKeyDraft.provided
      ? await (await this.options.modelRuntime()).getAuth(providerId).catch(() => undefined)
      : undefined;
    const apiKey = cleanString(apiKeyDraft.value) ?? storedAuth?.auth.apiKey;
    delete provider.apiKey;
    provider.headers = {
      ...(storedAuth?.auth.headers ? Object.fromEntries(
        Object.entries(storedAuth.auth.headers).filter((entry): entry is [string, string] => typeof entry[1] === "string"),
      ) : {}),
      ...(stringRecord(provider.headers) ?? {}),
      ...(stringRecord(input.headers) ?? {}),
    };
    const baseUrl = draftString(input, providerValue, "baseUrl");
    if (baseUrl.provided) provider.baseUrl = requireDraftString(baseUrl, "baseUrl");
    const api = draftApiValue(input, providerValue);
    const selectedApi = api.provided ? api.value : cleanString(provider.api);
    let normalizedDraftApi: ModelProviderApi | "auto" | undefined;
    if (selectedApi !== undefined) {
      const normalizedApi = normalizeModelProviderApi(selectedApi, { allowAuto: true });
      if (!normalizedApi) throw new RequestError("unsupported_model_api", `Unsupported model API: ${selectedApi}`);
      normalizedDraftApi = normalizedApi;
    }
    const apis: ModelProviderApi[] = normalizedDraftApi === "auto"
      ? ["anthropic-messages", "openai-responses", "openai-completions", "google-generative-ai"]
      : [normalizedDraftApi ?? "openai-completions"];
    const modeResults: Array<Record<string, unknown>> = [];
    for (const candidateApi of apis) {
      try {
        const result = await this.testDraftCandidate({
          providerId,
          modelId,
          provider,
          model: modelValue,
          api: candidateApi,
          apiKey,
          timeoutMs: boundedInteger(input, "timeoutMs", 20_000, 60_000),
        });
        if (normalizedDraftApi === "auto") {
          return { ...result, modeResults: [...modeResults, { api: candidateApi, ok: true }] };
        }
        return result;
      } catch (error) {
        const sanitized = sanitizeError(error, apiKey);
        modeResults.push({
          api: candidateApi,
          ok: false,
          code: sanitized instanceof RequestError ? sanitized.code : "model_test_failed",
          message: errorMessage(sanitized),
          ...(sanitized instanceof RequestError && sanitized.details !== undefined ? { details: sanitized.details } : {}),
        });
        if (normalizedDraftApi !== "auto") throw sanitized;
      }
    }
    throw new RequestError("model_test_failed", "No supported API mode completed the model test", { modeResults });
  }

  private async testDraftCandidate(options: {
    providerId: string;
    modelId: string;
    provider: JsonRecord;
    model?: JsonRecord;
    api: ModelProviderApi;
    apiKey?: string;
    timeoutMs: number;
  }): Promise<Record<string, unknown>> {
    const provider: JsonRecord = { ...options.provider, api: options.api };
    provider.models = [{ ...(options.model ?? {}), id: options.modelId, api: options.api }];
    const directory = await mkdtemp(join(tmpdir(), "wuxianpi-model-test-"));
    try {
      const modelsPath = join(directory, "models.json");
      await writeFile(modelsPath, JSON.stringify({ providers: { [options.providerId]: provider } }, null, 2), { encoding: "utf8", mode: 0o600 });
      const runtime = await this.createModelRuntime({
        authPath: join(directory, "auth.json"),
        modelsPath,
        modelsStorePath: join(directory, "models-store.json"),
        allowModelNetwork: false,
      });
      if (runtime.getError()) throw new RequestError("invalid_model_config", runtime.getError()!);
      const candidate = runtime.getModel(options.providerId, options.modelId);
      if (!candidate) throw new RequestError("model_not_found", `Model not found: ${options.providerId}/${options.modelId}`);
      if (options.apiKey || providerAllowsMissingApiKey(options.providerId, options.api)) {
        await runtime.login(options.providerId, "api_key", { prompt: async () => options.apiKey || "local", notify: () => {} });
      }
      return await runModelTest(runtime, candidate, options.timeoutMs, options.api, options.apiKey);
    } catch (error) {
      throw sanitizeError(error, options.apiKey);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }

  private async validateConfig(config: ModelsJsonConfig): Promise<void> {
    const directory = await mkdtemp(join(tmpdir(), "wuxianpi-model-validate-"));
    try {
      const modelsPath = join(directory, "models.json");
      await writeFile(modelsPath, JSON.stringify(config, null, 2), { encoding: "utf8", mode: 0o600 });
      const runtime = await this.createModelRuntime({
        authPath: join(directory, "auth.json"),
        modelsPath,
        modelsStorePath: join(directory, "models-store.json"),
        allowModelNetwork: false,
      });
      if (runtime.getError()) throw new RequestError("invalid_model_config", runtime.getError()!);
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }

  private parseConfigSnapshot(snapshot: FileSnapshot | undefined): ModelsJsonConfig {
    if (!snapshot?.exists) return { providers: {} };
    const content = snapshot.content ?? "";
    const errors: ParseError[] = [];
    const parsed = parseJsonc(content, errors, { allowTrailingComma: true, disallowComments: false }) as unknown;
    if (errors.length > 0) {
      const first = errors[0]!;
      throw new RequestError("invalid_model_config", `Failed to parse models.json: ${printParseErrorCode(first.error)}`, {
        offset: first.offset,
        length: first.length,
      });
    }
    if (!isRecord(parsed) || !isRecord(parsed.providers)) {
      throw new RequestError("invalid_model_config", "models.json must contain a providers object");
    }
    return { providers: cloneProviders(parsed.providers) };
  }

  private async writeConfig(transaction: TransactionState, config: ModelsJsonConfig): Promise<void> {
    const expected = this.expectedSnapshot(transaction, this.modelsPath);
    await this.assertSnapshotCurrent(this.modelsPath, expected);
    const target = { exists: true, content: `${JSON.stringify(config, null, 2)}\n` };
    await this.writeSnapshotAtomic(this.modelsPath, target, expected);
    transaction.touched.add(this.modelsPath);
    transaction.owned.set(this.modelsPath, target);
    await this.assertSnapshotCurrent(this.modelsPath, target);
  }

  private revisionFromSnapshots(snapshots: Map<string, FileSnapshot>): string {
    const hash = createHash("sha256");
    for (const [index, path] of [this.modelsPath, this.authPath, this.settingsPath].entries()) {
      const snapshot = snapshots.get(path) ?? { exists: false };
      hash.update(`${index}:${snapshot.exists ? "1" : "0"}:`);
      hash.update(snapshot.content ?? "");
      hash.update("\0");
    }
    return hash.digest("hex");
  }

  private async snapshots(): Promise<Map<string, FileSnapshot>> {
    return new Map(await Promise.all([this.modelsPath, this.authPath, this.settingsPath].map(async (path) => [path, await this.snapshot(path)] as const)));
  }

  private async snapshot(path: string): Promise<FileSnapshot> {
    try { return { exists: true, content: await readFile(path, "utf8") }; }
    catch (error) { if (isNotFound(error)) return { exists: false }; throw error; }
  }

  private beginTransaction(original: Map<string, FileSnapshot>): TransactionState {
    return { original, owned: new Map(), touched: new Set() };
  }

  private expectedSnapshot(transaction: TransactionState, path: string): FileSnapshot {
    return transaction.owned.get(path) ?? transaction.original.get(path) ?? { exists: false };
  }

  private async assertTransactionCurrent(transaction: TransactionState): Promise<void> {
    for (const path of [this.modelsPath, this.authPath, this.settingsPath]) {
      await this.assertSnapshotCurrent(path, this.expectedSnapshot(transaction, path));
    }
  }

  private async assertSnapshotCurrent(path: string, expected: FileSnapshot): Promise<void> {
    const actual = await this.snapshot(path);
    if (!sameSnapshot(actual, expected)) {
      throw new RequestError("model_concurrent_write", "Model configuration changed during the update", {
        path,
        expected: snapshotHash(expected),
        actual: snapshotHash(actual),
      });
    }
  }

  private async writeSnapshotAtomic(path: string, target: FileSnapshot, expected: FileSnapshot): Promise<void> {
    await this.assertSnapshotCurrent(path, expected);
    if (!target.exists) {
      await this.assertSnapshotCurrent(path, expected);
      await rm(path, { force: true });
      return;
    }
    await mkdir(dirname(path), { recursive: true });
    const temporary = `${path}.${process.pid}.${randomUUID()}.tmp`;
    try {
      await writeFile(temporary, target.content ?? "", { encoding: "utf8", mode: 0o600 });
      await this.assertSnapshotCurrent(path, expected);
      await rename(temporary, path);
    } finally {
      await rm(temporary, { force: true }).catch(() => undefined);
    }
  }

  private async mutateCredential(
    transaction: TransactionState,
    credential: CredentialMutation,
  ): Promise<Record<string, unknown>> {
    if (credential.action === "keep") return { provider: credential.providerId, authenticated: true };
    const expected = this.expectedSnapshot(transaction, this.authPath);
    await this.assertSnapshotCurrent(this.authPath, expected);
    let result: Record<string, unknown> | undefined;
    let operationError: unknown;
    try {
      result = credential.action === "set"
        ? await this.loginNow(credential.providerId, credential.apiKey ?? "")
        : await this.logoutNow(credential.providerId);
    } catch (error) {
      operationError = error;
    }
    const actual = await this.snapshot(this.authPath);
    if (!sameSnapshot(actual, expected)) {
      if (!authMutationMatches(expected, actual, credential)) {
        throw new RequestError("model_concurrent_write", "Authentication changed concurrently during the update", {
          path: this.authPath,
          provider: credential.providerId,
        });
      }
      transaction.touched.add(this.authPath);
      transaction.owned.set(this.authPath, actual);
    }
    if (operationError !== undefined) throw operationError;
    return result ?? { provider: credential.providerId, authenticated: credential.action === "set" };
  }

  private async mutateDefaultSettings(transaction: TransactionState, provider: string, modelId: string): Promise<void> {
    const expected = this.expectedSnapshot(transaction, this.settingsPath);
    await this.assertSnapshotCurrent(this.settingsPath, expected);
    this.options.settingsManager.drainErrors();
    this.options.settingsManager.setDefaultModelAndProvider(provider, modelId);
    let flushError: unknown;
    try {
      await this.options.settingsManager.flush();
    } catch (error) {
      flushError = error;
    }
    const errors = this.options.settingsManager.drainErrors();
    const actual = await this.snapshot(this.settingsPath);
    if (!sameSnapshot(actual, expected)) {
      if (!settingsMutationMatches(expected, actual, provider, modelId)) {
        throw new RequestError("model_concurrent_write", "Settings changed concurrently during the update", {
          path: this.settingsPath,
        });
      }
      transaction.touched.add(this.settingsPath);
      transaction.owned.set(this.settingsPath, actual);
    }
    if (flushError !== undefined || errors.length > 0) {
      throw settingsPersistError("model settings", errors, flushError);
    }
  }

  private async rollback(transaction: TransactionState, cause: unknown): Promise<void> {
    const conflicts: Array<{ path: string; expected: string; actual: string }> = [];
    const failures: Array<{ path: string; message: string }> = [];
    for (const path of [this.settingsPath, this.authPath, this.modelsPath]) {
      if (!transaction.touched.has(path)) continue;
      const owned = transaction.owned.get(path);
      const original = transaction.original.get(path) ?? { exists: false };
      if (!owned) continue;
      const actual = await this.snapshot(path);
      if (!sameSnapshot(actual, owned)) {
        conflicts.push({ path, expected: snapshotHash(owned), actual: snapshotHash(actual) });
        continue;
      }
      try {
        await this.writeSnapshotAtomic(path, original, owned);
      } catch (error) {
        if (error instanceof RequestError && error.code === "model_concurrent_write") {
          const current = await this.snapshot(path);
          conflicts.push({ path, expected: snapshotHash(owned), actual: snapshotHash(current) });
        } else {
          failures.push({ path, message: errorMessage(error) });
        }
      }
    }

    try {
      if (transaction.touched.has(this.authPath) || conflicts.some((item) => item.path === this.authPath)) {
        const currentAuth = await this.snapshot(this.authPath);
        await this.refreshAuthCache(currentAuth);
      }
      if (transaction.touched.size > 0 || conflicts.length > 0) {
        await this.options.reload();
      } else {
        await this.options.settingsManager.reload();
      }
      this.throwSettingsErrors("model settings rollback");
    } catch (error) {
      if (error instanceof RequestError && error.code === "model_concurrent_write") {
        const actual = await this.snapshot(this.authPath);
        conflicts.push({ path: this.authPath, expected: "auth-cache-refresh", actual: snapshotHash(actual) });
      } else {
        failures.push({ path: "runtime", message: errorMessage(error) });
      }
    }

    if (conflicts.length > 0) {
      throw new RequestError("model_concurrent_write", "Rollback preserved newer external model configuration changes", {
        conflicts,
        originalError: errorMessage(cause),
        ...(failures.length > 0 ? { rollbackErrors: failures } : {}),
      });
    }
    if (failures.length > 0) {
      throw new RequestError("model_rollback_failed", "Failed to restore model configuration after an error", {
        errors: failures,
        originalError: errorMessage(cause),
      });
    }
  }

  private async refreshAuthCache(target: FileSnapshot): Promise<void> {
    const auth = parseAuthSnapshot(target);
    const runtime = await this.options.modelRuntime();
    let sentinel: string;
    do sentinel = `__wuxianpi_auth_reload_${randomUUID()}`;
    while (auth[sentinel] !== undefined || runtime.getProvider(sentinel) !== undefined);

    let logoutError: unknown;
    try {
      await runtime.logout(sentinel);
    } catch (error) {
      logoutError = error;
    }
    const rewritten = await this.snapshot(this.authPath);
    if (!sameJsonRecordSnapshot(rewritten, target)) {
      throw new RequestError("model_concurrent_write", "Authentication changed while refreshing the runtime cache", {
        path: this.authPath,
      });
    }
    await this.writeSnapshotAtomic(this.authPath, target, rewritten);
    await runtime.refresh({ allowNetwork: false });
    if (logoutError !== undefined) {
      throw new RequestError("model_auth_reload_failed", "Failed to refresh the live authentication cache after rollback", {
        cause: errorMessage(logoutError),
      });
    }
  }

  private async reloadSettings(context: string): Promise<void> {
    this.options.settingsManager.drainErrors();
    await this.options.settingsManager.reload();
    this.throwSettingsErrors(context);
  }

  private throwSettingsErrors(context: string): void {
    const errors = this.options.settingsManager.drainErrors();
    if (errors.length > 0) throw settingsPersistError(context, errors);
  }

  private withTransaction<T>(operation: (transaction: TransactionState, assertLock: () => void) => Promise<T>): Promise<T> {
    return this.serial(async () => {
      await mkdir(this.options.agentDir, { recursive: true });
      let compromised: Error | undefined;
      const release = await this.acquireLock(this.transactionLockPath, {
        realpath: false,
        stale: 30_000,
        update: 10_000,
        retries: { retries: 10, factor: 2, minTimeout: 50, maxTimeout: 1_000, randomize: true },
        onCompromised: (error) => { compromised = error; },
      });
      const assertLock = () => {
        if (compromised) throw new RequestError("model_lock_compromised", compromised.message);
      };
      let transaction: TransactionState | undefined;
      let result: T | undefined;
      let operationError: unknown;
      try {
        assertLock();
        transaction = this.beginTransaction(await this.snapshots());
        result = await operation(transaction, assertLock);
        assertLock();
      } catch (error) {
        operationError = error;
      }

      if (operationError === undefined) {
        const releaseError = await this.releaseLock(release, () => compromised);
        if (!releaseError) return result as T;
        if (transaction) await this.rollback(transaction, releaseError);
        throw releaseError;
      }

      let rollbackError: unknown;
      if (transaction) {
        try {
          await this.rollback(transaction, operationError);
        } catch (error) {
          rollbackError = error;
        }
      }
      const releaseError = await this.releaseLock(release, () => compromised);
      if (rollbackError !== undefined) throw withSecondaryError(rollbackError, releaseError, "lockReleaseError");
      if (releaseError !== undefined && !sameRequestErrorCode(operationError, releaseError)) {
        throw new RequestError("model_lock_release_failed", "Failed to release the model configuration lock", {
          originalError: errorMessage(operationError),
          releaseError: errorMessage(releaseError),
        });
      }
      throw operationError;
    });
  }

  private async releaseLock(
    release: () => Promise<void>,
    compromised: () => Error | undefined,
  ): Promise<RequestError | undefined> {
    try {
      await release();
    } catch (error) {
      const compromisedError = compromised();
      if (compromisedError && errorCode(error) === "ERELEASED") {
        return new RequestError("model_lock_compromised", compromisedError.message);
      }
      return new RequestError("model_lock_release_failed", "Failed to release the model configuration lock", {
        cause: errorMessage(error),
        ...(compromisedError ? { compromised: compromisedError.message } : {}),
      });
    }
    const compromisedError = compromised();
    return compromisedError ? new RequestError("model_lock_compromised", compromisedError.message) : undefined;
  }

  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.transaction.then(operation, operation);
    this.transaction = result.then(() => undefined, () => undefined);
    return result;
  }
}

async function runModelTest(
  runtime: ModelRuntime,
  model: ReturnType<ModelRuntime["getModel"]> & {},
  timeoutMs: number,
  resolvedApi?: unknown,
  apiKey?: string,
) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  timer.unref();
  let status: number | undefined;
  const startedAt = Date.now();
  try {
    let response;
    try {
      response = await runtime.completeSimple(model, {
        messages: [{ role: "user", content: "Reply with OK only.", timestamp: Date.now() }],
      }, {
        maxTokens: 16,
        timeoutMs,
        maxRetries: 0,
        cacheRetention: "none",
        signal: controller.signal,
        onResponse: (providerResponse) => { status = providerResponse.status; },
      });
    } catch (error) {
      throw sanitizeError(error, apiKey);
    }
    if (response.stopReason === "error" || response.stopReason === "aborted" || response.errorMessage) {
      throw new RequestError("model_test_failed", redactSecret(
        response.errorMessage ?? (controller.signal.aborted ? "Model test timed out" : "Model test failed"),
        apiKey,
      ));
    }
    return {
      ok: true,
      provider: model.provider,
      modelId: model.id,
      ...(typeof resolvedApi === "string" && resolvedApi ? { resolvedApi } : {}),
      latencyMs: Date.now() - startedAt,
      ...(status !== undefined ? { status } : {}),
      text: response.content.filter((part) => part.type === "text").map((part) => part.text).join("").slice(0, 300),
    };
  } finally {
    clearTimeout(timer);
  }
}

function buildApplyPlan(current: ModelsJsonConfig, input: JsonRecord): { config: ModelsJsonConfig; credentials: CredentialMutation[] } {
  const suppliedConfig = recordValue(input.config);
  const config = suppliedConfig ? parseConfig(suppliedConfig) : structuredClone(current);
  const credentials = new Map<string, CredentialMutation>();
  if (suppliedConfig) collectInlineCredentials(config, credentials);

  const rawChanges = Array.isArray(input.changes) ? input.changes : input.providerId || input.providerName
    ? [{
        providerId: input.providerId ?? input.providerName,
        action: input.action,
        provider: input.provider,
        credential: input.credential,
        apiKey: input.apiKey,
      }]
    : [];
  for (const raw of rawChanges) {
    if (!isRecord(raw)) throw new RequestError("invalid_payload", "Each model change must be an object");
    const providerId = cleanString(raw.providerId) ?? cleanString(raw.id);
    if (!providerId) throw new RequestError("invalid_payload", "change.providerId must be a non-empty string");
    const action = cleanString(raw.action) ?? (raw.provider === null ? "remove" : "upsert");
    if (action === "remove") {
      delete config.providers[providerId];
    } else if (action === "upsert") {
      if (!isRecord(raw.provider)) throw new RequestError("invalid_payload", `Provider config is required for ${providerId}`);
      const provider = structuredClone(raw.provider);
      const inlineKey = cleanString(provider.apiKey);
      delete provider.apiKey;
      config.providers[providerId] = provider;
      if (inlineKey) credentials.set(providerId, { providerId, action: "set", apiKey: inlineKey });
    } else {
      throw new RequestError("invalid_payload", `Unsupported model change action: ${action}`);
    }
    const credential = recordValue(raw.credential);
    const credentialAction = cleanString(credential?.action) ?? (raw.apiKey !== undefined ? "set" : undefined);
    if (credentialAction) {
      if (credentialAction !== "keep" && credentialAction !== "set" && credentialAction !== "remove") {
        throw new RequestError("invalid_payload", `Unsupported credential action: ${credentialAction}`);
      }
      const apiKey = cleanString(credential?.apiKey) ?? cleanString(raw.apiKey);
      credentials.set(providerId, { providerId, action: credentialAction, ...(apiKey ? { apiKey } : {}) });
    }
  }
  return { config, credentials: [...credentials.values()] };
}

function collectInlineCredentials(config: ModelsJsonConfig, credentials: Map<string, CredentialMutation>): void {
  for (const [providerId, provider] of Object.entries(config.providers)) {
    const apiKey = cleanString(provider.apiKey);
    if (apiKey) credentials.set(providerId, { providerId, action: "set", apiKey });
    delete provider.apiKey;
  }
}

function parseConfig(value: JsonRecord): ModelsJsonConfig {
  if (!isRecord(value.providers)) throw new RequestError("invalid_payload", "config.providers must be an object");
  return { providers: cloneProviders(value.providers) };
}

function cloneProviders(value: JsonRecord): Record<string, JsonRecord> {
  const providers: Record<string, JsonRecord> = {};
  for (const [providerId, provider] of Object.entries(value)) {
    if (!providerId.trim() || !isRecord(provider)) throw new RequestError("invalid_model_config", `Invalid provider config: ${providerId}`);
    providers[providerId] = structuredClone(provider);
  }
  return providers;
}

function stripApiKeys(config: ModelsJsonConfig): ModelsJsonConfig {
  return stripSecrets(structuredClone(config), undefined, false) as ModelsJsonConfig;
}

function redactConfig(config: ModelsJsonConfig): ModelsJsonConfig {
  return stripSecrets(structuredClone(config), undefined, true) as ModelsJsonConfig;
}

function stripSecrets(value: unknown, parentKey: string | undefined, redactHeaders: boolean): unknown {
  if (Array.isArray(value)) return value.map((item) => stripSecrets(item, parentKey, redactHeaders));
  if (!isRecord(value)) return value;
  const output: JsonRecord = {};
  for (const [key, item] of Object.entries(value)) {
    if (/^api[-_]?key$/i.test(key)) continue;
    if (redactHeaders && parentKey?.toLowerCase() === "headers" && /^(authorization|x-api-key|x-goog-api-key)$/i.test(key)) continue;
    output[key] = stripSecrets(item, key, redactHeaders);
  }
  return output;
}

function parseDefaultModel(value: unknown): { provider: string; modelId: string } | undefined {
  if (!isRecord(value)) return undefined;
  const provider = cleanString(value.provider);
  const modelId = cleanString(value.modelId) ?? cleanString(value.id);
  return provider && modelId ? { provider, modelId } : undefined;
}

function providerIdFrom(input: JsonRecord): string | undefined {
  const draft = recordValue(input.draft);
  const provider = recordValue(input.provider) ?? recordValue(draft?.provider);
  return cleanString(input.providerId) ?? cleanString(input.providerName) ?? cleanString(input.provider)
    ?? cleanString(draft?.providerId) ?? cleanString(draft?.providerName)
    ?? cleanString(provider?.id) ?? cleanString(provider?.providerId);
}

function modelIdFrom(input: JsonRecord): string | undefined {
  return cleanString(input.modelId) ?? cleanString(recordValue(input.model)?.id) ?? cleanString(recordValue(recordValue(input.draft)?.model)?.id);
}

function stringRecord(value: unknown): Record<string, string> | undefined {
  if (!isRecord(value)) return undefined;
  const output: Record<string, string> = {};
  for (const [key, item] of Object.entries(value)) {
    if (typeof item !== "string") throw new RequestError("invalid_payload", `${key} header must be a string`);
    output[key] = item;
  }
  return output;
}

function draftString(
  input: JsonRecord,
  provider: JsonRecord | undefined,
  key: string,
): { provided: boolean; value?: string } {
  if (Object.prototype.hasOwnProperty.call(input, key)) {
    const value = input[key];
    if (typeof value !== "string") throw new RequestError("invalid_payload", `${key} must be a string`);
    return { provided: true, value };
  }
  const draft = recordValue(input.draft);
  if (draft && Object.prototype.hasOwnProperty.call(draft, key)) {
    const value = draft[key];
    if (typeof value !== "string") throw new RequestError("invalid_payload", `draft.${key} must be a string`);
    return { provided: true, value };
  }
  if (provider && Object.prototype.hasOwnProperty.call(provider, key)) {
    const value = provider[key];
    if (typeof value !== "string") throw new RequestError("invalid_payload", `provider.${key} must be a string`);
    return { provided: true, value };
  }
  return { provided: false };
}

function draftApiValue(
  input: JsonRecord,
  provider: JsonRecord | undefined,
): { provided: boolean; value?: string } {
  const draft = recordValue(input.draft);
  for (const [owner, label] of [[input, ""], [draft, "draft."], [provider, "provider."]] as const) {
    if (!owner) continue;
    for (const key of ["api", "apiType"] as const) {
      if (!Object.prototype.hasOwnProperty.call(owner, key)) continue;
      const value = owner[key];
      if (typeof value !== "string") throw new RequestError("invalid_payload", `${label}${key} must be a string`);
      return { provided: true, value };
    }
  }
  return { provided: false };
}

function requireDraftString(field: { provided: boolean; value?: string }, key: string): string {
  const value = field.value?.trim();
  if (!value) throw new RequestError("invalid_payload", `${key} must be a non-empty string`);
  return value;
}

function sameSnapshot(left: FileSnapshot, right: FileSnapshot): boolean {
  return left.exists === right.exists && (!left.exists || (left.content ?? "") === (right.content ?? ""));
}

function snapshotHash(snapshot: FileSnapshot): string {
  const hash = createHash("sha256");
  hash.update(snapshot.exists ? "1\0" : "0\0");
  hash.update(snapshot.content ?? "");
  return hash.digest("hex");
}

function parseAuthSnapshot(snapshot: FileSnapshot): JsonRecord {
  if (!snapshot.exists || !(snapshot.content ?? "").trim()) return {};
  try {
    const parsed = JSON.parse(snapshot.content ?? "") as unknown;
    if (!isRecord(parsed)) throw new Error("auth.json must contain an object");
    return parsed;
  } catch (error) {
    throw new RequestError("invalid_auth_config", `Failed to parse auth.json: ${errorMessage(error)}`);
  }
}

function authMutationMatches(before: FileSnapshot, after: FileSnapshot, mutation: CredentialMutation): boolean {
  let previous: JsonRecord;
  let next: JsonRecord;
  try {
    previous = parseAuthSnapshot(before);
    next = parseAuthSnapshot(after);
  } catch {
    return false;
  }
  const previousOthers = { ...previous };
  const nextOthers = { ...next };
  delete previousOthers[mutation.providerId];
  delete nextOthers[mutation.providerId];
  if (!sameJson(previousOthers, nextOthers)) return false;
  if (mutation.action === "remove") return next[mutation.providerId] === undefined;
  if (mutation.action !== "set") return sameJson(previous, next);
  const credential = recordValue(next[mutation.providerId]);
  return credential?.type === "api_key" && credential.key === mutation.apiKey;
}

function settingsMutationMatches(
  before: FileSnapshot,
  after: FileSnapshot,
  provider: string,
  modelId: string,
): boolean {
  let previous: JsonRecord;
  let next: JsonRecord;
  try {
    previous = before.exists && (before.content ?? "").trim() ? JSON.parse(before.content ?? "") as JsonRecord : {};
    next = after.exists && (after.content ?? "").trim() ? JSON.parse(after.content ?? "") as JsonRecord : {};
  } catch {
    return false;
  }
  if (!isRecord(previous) || !isRecord(next)) return false;
  if (next.defaultProvider !== provider || next.defaultModel !== modelId) return false;
  const previousOthers = { ...previous };
  const nextOthers = { ...next };
  delete previousOthers.defaultProvider;
  delete previousOthers.defaultModel;
  delete nextOthers.defaultProvider;
  delete nextOthers.defaultModel;
  return sameJson(previousOthers, nextOthers);
}

function sameJsonRecordSnapshot(left: FileSnapshot, right: FileSnapshot): boolean {
  try {
    return sameJson(parseAuthSnapshot(left), parseAuthSnapshot(right));
  } catch {
    return false;
  }
}

function sameJson(left: unknown, right: unknown): boolean {
  return JSON.stringify(sortJson(left)) === JSON.stringify(sortJson(right));
}

function sortJson(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortJson);
  if (!isRecord(value)) return value;
  return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sortJson(value[key])]));
}

function settingsPersistError(
  context: string,
  errors: Array<{ scope: string; error: Error }>,
  cause?: unknown,
): RequestError {
  return new RequestError("settings_persist_failed", `Failed to persist ${context}`, {
    errors: [
      ...errors.map((item) => ({ scope: item.scope, message: item.error.message })),
      ...(cause === undefined ? [] : [{ scope: "global", message: errorMessage(cause) }]),
    ],
  });
}

function recordValue(value: unknown): JsonRecord | undefined { return isRecord(value) ? value : undefined; }
function modelKey(provider: string, modelId: string): string { return `${provider}\0${modelId}`; }
function cleanString(value: unknown): string | undefined { return typeof value === "string" && value.trim() ? value.trim() : undefined; }
function isRecord(value: unknown): value is JsonRecord { return typeof value === "object" && value !== null && !Array.isArray(value); }
function isNotFound(error: unknown): boolean { return isRecord(error) && error.code === "ENOENT"; }
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
function errorCode(error: unknown): string | undefined { return isRecord(error) && typeof error.code === "string" ? error.code : undefined; }

function sameRequestErrorCode(left: unknown, right: unknown): boolean {
  return left instanceof RequestError && right instanceof RequestError && left.code === right.code;
}

function withSecondaryError(error: unknown, secondary: unknown, key: string): unknown {
  if (secondary === undefined) return error;
  if (error instanceof RequestError) {
    const details = isRecord(error.details) ? { ...error.details } : error.details === undefined ? {} : { originalDetails: error.details };
    return new RequestError(error.code, error.message, { ...details, [key]: errorMessage(secondary) });
  }
  return new RequestError("model_transaction_failed", errorMessage(error), { [key]: errorMessage(secondary) });
}

function sanitizeError(error: unknown, apiKey?: string): RequestError {
  if (error instanceof RequestError) {
    return new RequestError(error.code, redactSecret(error.message, apiKey), redactUnknown(error.details, apiKey));
  }
  return new RequestError("model_test_failed", redactSecret(errorMessage(error), apiKey), {
    cause: redactUnknown(error, apiKey),
  });
}

function redactUnknown(value: unknown, apiKey?: string): unknown {
  if (typeof value === "string") return redactSecret(value, apiKey);
  if (Array.isArray(value)) return value.map((item) => redactUnknown(item, apiKey));
  if (value instanceof Error) {
    const record = value as Error & { code?: unknown; details?: unknown; cause?: unknown };
    return {
      name: record.name,
      message: redactSecret(record.message, apiKey),
      ...(typeof record.code === "string" ? { code: record.code } : {}),
      ...(record.details !== undefined ? { details: redactUnknown(record.details, apiKey) } : {}),
      ...(record.cause !== undefined ? { cause: redactUnknown(record.cause, apiKey) } : {}),
    };
  }
  if (!isRecord(value)) return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, redactUnknown(item, apiKey)]));
}

function redactSecret(value: string, secret?: string): string {
  return secret ? value.split(secret).join("[REDACTED]") : value;
}
