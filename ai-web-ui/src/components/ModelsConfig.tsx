import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { LatestRequestGate } from "@/lib/latest-request-gate";
import {
  MODEL_API_OPTIONS,
  WebApiError,
  concreteApiForModel,
  configHasAutoApi,
  modelApiLabel,
  normalizeModelDraftResult,
  webApi,
  type ModelDraftResult,
  type ModelProviderApi,
  type ModelProviderDraft,
  type ModelSetupApplyRequest,
  type ModelSetupProviderConfig,
  type ModelSetupState,
} from "@/lib/web-api-client";

interface ModelsConfigProps {
  onClose: () => void;
  onModelsChanged?: () => void;
}

type Mode = "easy" | "advanced";
type Notice = { type: "success" | "error" | "info"; message: string };
type CredentialDraft = { apiKey: string; remove: boolean };
type DiscoveryReport = { providerId: string; api: ModelProviderApi; loading: boolean; result?: ModelDraftResult; error?: string };
type EasyModel = NonNullable<ModelSetupProviderConfig["models"]>[number] & { sourceApis?: ModelProviderApi[] };

const DRAFT_REQUEST_DEBOUNCE_MS = 500;
const AUTO_DISCOVERY_MODES = MODEL_API_OPTIONS.filter((option) => option.value !== "auto");

function apiOptions(current: ModelProviderApi) {
  return MODEL_API_OPTIONS.some((option) => option.value === current)
    ? MODEL_API_OPTIONS
    : [...MODEL_API_OPTIONS, { value: current, label: modelApiLabel(current) }];
}

function cloneConfig(config: ModelSetupState["config"]): ModelSetupState["config"] {
  return {
    providers: Object.fromEntries(Object.entries(config.providers).map(([id, provider]) => [id, {
      ...provider,
      headers: provider.headers ? { ...provider.headers } : undefined,
      models: provider.models?.map((model) => ({ ...model })),
    }])),
  };
}

function statusFor(setup: ModelSetupState | null, providerId: string) {
  return setup?.providers.find((provider) => provider.id === providerId);
}

function mergeModels(current: ModelSetupProviderConfig["models"], fetched: ModelDraftResult["models"]) {
  const byId = new Map((current ?? []).map((model) => [model.id, model]));
  for (const model of fetched) byId.set(model.id, { ...byId.get(model.id), id: model.id, name: model.name });
  return Array.from(byId.values());
}

function mergeEasyModels(current: EasyModel[], fetched: ModelDraftResult["models"]): EasyModel[] {
  const byId = new Map(current.map((model) => [model.id, model]));
  for (const model of fetched) byId.set(model.id, { ...byId.get(model.id), ...model });
  return Array.from(byId.values());
}

function modelConfig(model: EasyModel | undefined, fallbackId: string): NonNullable<ModelSetupProviderConfig["models"]>[number] {
  if (!model) return { id: fallbackId };
  return Object.fromEntries(Object.entries(model).filter(([key]) => key !== "sourceApis")) as NonNullable<ModelSetupProviderConfig["models"]>[number];
}

function parseHeaders(value: string): Record<string, string> | undefined {
  const headers = Object.fromEntries(value.split("\n").flatMap((line) => {
    const index = line.indexOf(":");
    if (index < 1) return [];
    const key = line.slice(0, index).trim();
    const header = line.slice(index + 1).trim();
    return key && header ? [[key, header]] : [];
  }));
  return Object.keys(headers).length ? headers : undefined;
}

function formatHeaders(headers: Record<string, string> | undefined): string {
  return Object.entries(headers ?? {}).map(([key, value]) => `${key}: ${value}`).join("\n");
}

function parseModels(value: string): ModelSetupProviderConfig["models"] {
  const seen = new Set<string>();
  return value.split("\n").flatMap((line) => {
    const [rawId, ...nameParts] = line.split("|");
    const id = rawId.trim();
    if (!id || seen.has(id)) return [];
    seen.add(id);
    const name = nameParts.join("|").trim();
    return [{ id, ...(name ? { name } : {}) }];
  });
}

function formatModels(models: ModelSetupProviderConfig["models"]): string {
  return (models ?? []).map((model) => `${model.id}${model.name ? ` | ${model.name}` : ""}`).join("\n");
}

function errorText(reason: unknown): string {
  if (reason instanceof WebApiError && reason.status === 409) return "配置已在其他页面更新，请刷新后重试。";
  return reason instanceof Error ? reason.message : String(reason);
}

function errorDraftResult(reason: unknown): ModelDraftResult | undefined {
  if (!(reason instanceof WebApiError) || reason.details == null) return undefined;
  const result = normalizeModelDraftResult(reason.details);
  return result.modeResults.length > 0 ? { ...result, ok: false, message: reason.message } : undefined;
}

function DiscoveryStatus({ report }: { report: DiscoveryReport | null }) {
  const modeRows = report?.api === "auto"
    ? AUTO_DISCOVERY_MODES.map((option) => ({ option, result: report.result?.modeResults.find((mode) => mode.api === option.value) }))
    : (report?.result?.modeResults ?? []).map((result) => ({ option: { value: result.api, label: result.label }, result }));
  return <div className={`model-discovery-report${report ? " active" : ""}`} aria-live="polite">
    {!report ? null : <>
      <header><strong>{report.providerId}</strong><span>{report.loading ? "等待 Runtime 返回" : report.error ? "模型列表获取失败" : report.result ? `已返回 ${report.result.models.length} 个去重模型` : "模型列表获取失败"}</span></header>
      {report.loading && <strong>{report.api === "auto" ? "需要探测多种模式，请耐心等待" : "正在获取模型列表…"}</strong>}
      {modeRows.length > 0 && <div className="model-discovery-modes">
        {modeRows.map(({ option, result }) => <div key={option.value}>
          <strong>{option.label}</strong>
          {report.loading
            ? <span>进行中 · 正在尝试模型列表 URL/auth 规则</span>
            : result?.ok
              ? <span className="success">模型列表获取成功 · {result.modelCount} 个模型</span>
              : <span className="error">模型列表获取失败 · {result?.error ?? "未返回结果"}</span>}
        </div>)}
      </div>}
      {!report.loading && report.error && <span className="error">{report.error}</span>}
      {!report.loading && report.result && report.result.models.length > 0 && <div className="model-discovery-models">
        <strong>去重模型列表</strong>
        {report.result.models.map((model) => <div key={model.id}>
          <code>{model.id}</code>
          {!!model.sourceApis?.length && <span>{model.sourceApis.map(modelApiLabel).join(" / ")}</span>}
        </div>)}
      </div>}
      {!report.loading && report.api === "auto" && report.result && <span>这里只验证模型列表获取；生成协议需选择具体 API 类型后点击测试。</span>}
      {!report.loading && report.result && report.result.modeResults.length === 0 && <span>{report.result.message ?? `${modelApiLabel(report.api)} 模型列表获取完成`}</span>}
    </>}
  </div>;
}

function sameConfig(left: ModelSetupProviderConfig | undefined, right: ModelSetupProviderConfig | undefined): boolean {
  return JSON.stringify(left ?? {}) === JSON.stringify(right ?? {});
}

function isDraftBusy(key: string | null): boolean {
  return key === "fetch" || key === "test" || key?.startsWith("fetch:") === true || key?.startsWith("test:") === true;
}

export function ModelsConfig({ onClose, onModelsChanged }: ModelsConfigProps) {
  const [mode, setMode] = useState<Mode>("easy");
  const [setup, setSetup] = useState<ModelSetupState | null>(null);
  const [config, setConfig] = useState<ModelSetupState["config"]>({ providers: {} });
  const [credentials, setCredentials] = useState<Record<string, CredentialDraft>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [selectedPresetId, setSelectedPresetId] = useState("");
  const [easyBaseUrl, setEasyBaseUrl] = useState("");
  const [easyApi, setEasyApi] = useState<ModelProviderApi>("openai-completions");
  const [easyApiKey, setEasyApiKey] = useState("");
  const [easyModels, setEasyModels] = useState<EasyModel[]>([]);
  const [easyModelId, setEasyModelId] = useState("");
  const [manualModelId, setManualModelId] = useState("");
  const [setGlobalDefault, setSetGlobalDefault] = useState(true);
  const [advancedDefault, setAdvancedDefault] = useState("");
  const [advancedSetDefault, setAdvancedSetDefault] = useState(false);
  const [newProviderId, setNewProviderId] = useState("");
  const [discoveryReport, setDiscoveryReport] = useState<DiscoveryReport | null>(null);
  const draftRequestGateRef = useRef<LatestRequestGate | null>(null);
  if (!draftRequestGateRef.current) draftRequestGateRef.current = new LatestRequestGate();

  const invalidateDraftRequests = useCallback(() => {
    draftRequestGateRef.current?.invalidate();
    setBusy((current) => isDraftBusy(current) ? null : current);
    setDiscoveryReport(null);
  }, []);

  const beginDraftRequest = useCallback(() => (
    draftRequestGateRef.current?.schedule(DRAFT_REQUEST_DEBOUNCE_MS) ?? Promise.resolve(null)
  ), []);

  const isCurrentDraftRequest = useCallback((version: number) => (
    draftRequestGateRef.current?.isCurrent(version) === true
  ), []);

  useEffect(() => () => draftRequestGateRef.current?.invalidate(), []);

  const load = useCallback(async () => {
    invalidateDraftRequests();
    setLoading(true);
    setNotice(null);
    try {
      const next = await webApi.modelSetup();
      setSetup(next);
      setConfig(cloneConfig(next.config));
      setCredentials({});
      setSelectedPresetId((current) => current && next.presets.some((preset) => preset.id === current) ? current : next.presets[0]?.id ?? "");
      setAdvancedDefault(next.defaultModel ? `${next.defaultModel.provider}/${next.defaultModel.modelId}` : "");
      setAdvancedSetDefault(false);
    } catch (reason) {
      setNotice({ type: "error", message: errorText(reason) });
    } finally {
      setLoading(false);
    }
  }, [invalidateDraftRequests]);

  useEffect(() => { void load(); }, [load]);

  const selectedPreset = setup?.presets.find((preset) => preset.id === selectedPresetId) ?? null;
  const easyProviderId = selectedPreset?.providerId ?? "";
  const storedCredential = !!statusFor(setup, easyProviderId)?.authenticated;

  useEffect(() => {
    if (!selectedPreset) return;
    const existing = config.providers[selectedPreset.providerId];
    const models = existing?.models?.length
      ? existing.models
      : selectedPreset.recommendedModels.map((id) => ({ id }));
    setEasyBaseUrl(existing?.baseUrl ?? selectedPreset.baseUrl ?? "");
    setEasyApi(existing?.api ?? selectedPreset.api ?? "openai-completions");
    setEasyApiKey("");
    setEasyModels(models);
    setEasyModelId(
      setup?.defaultModel?.provider === selectedPreset.providerId
        ? setup.defaultModel.modelId
        : selectedPreset.recommendedModel ?? models[0]?.id ?? "",
    );
    setManualModelId("");
    setNotice(null);
  }, [selectedPresetId, selectedPreset, setup?.defaultModel, config.providers]);

  const effectiveEasyModelId = manualModelId.trim() || easyModelId.trim();
  const easyDraft = useMemo(() => selectedPreset ? {
    providerId: easyProviderId,
    presetId: selectedPreset.id,
    baseUrl: easyBaseUrl.trim() || undefined,
    api: easyApi,
    apiKey: easyApiKey.trim() || undefined,
  } : null, [easyApi, easyApiKey, easyBaseUrl, easyProviderId, selectedPreset]);
  const easyKeyMissing = !!selectedPreset?.requiresApiKey && !easyApiKey.trim() && !storedCredential;
  const easySavedModelExists = !!setup?.models.some((model) => model.provider === easyProviderId && model.id === effectiveEasyModelId);
  const easyOriginalProvider = setup?.config.providers[easyProviderId];
  const easyCanUseSavedTest = !easyApiKey.trim()
    && storedCredential
    && easySavedModelExists
    && easyBaseUrl.trim() === (easyOriginalProvider?.baseUrl ?? selectedPreset?.baseUrl ?? "").trim()
    && easyApi === (easyOriginalProvider?.api ?? selectedPreset?.api ?? "openai-completions");
  const easyDraftTestKeyMissing = !!selectedPreset?.requiresApiKey && !easyApiKey.trim() && !easyCanUseSavedTest;
  const easyApiUnresolved = easyApi === "auto";

  const selectEasyModel = (modelId: string, manual: boolean) => {
    invalidateDraftRequests();
    if (manual) setManualModelId(modelId);
    else {
      setEasyModelId(modelId);
      setManualModelId("");
    }
    const concreteApi = concreteApiForModel(easyModels.find((model) => model.id === modelId), easyApi);
    if (concreteApi) setEasyApi(concreteApi);
  };

  const runDraft = async (kind: "fetch" | "test", draft: ModelProviderDraft, modelId?: string) => {
    setBusy(kind);
    setNotice(null);
    if (kind === "fetch") setDiscoveryReport({ providerId: draft.providerId, api: draft.api ?? "openai-completions", loading: true });
    const requestVersion = await beginDraftRequest();
    if (requestVersion === null) return;
    try {
      const result = kind === "fetch"
        ? await webApi.fetchModelDraft(draft)
        : await webApi.testModelDraft({ ...draft, modelId: modelId!, timeoutMs: 20_000 });
      if (!isCurrentDraftRequest(requestVersion)) return;
      if (!result.ok && result.models.length === 0) throw new Error(result.message ?? "Runtime 未能获取可用模型列表。");
      if (kind === "fetch") {
        const merged = mergeEasyModels(easyModels, result.models);
        const selectedModelId = result.recommendedModel ?? merged[0]?.id ?? "";
        setEasyModels(merged);
        setEasyModelId(selectedModelId);
        setManualModelId("");
        const concreteApi = concreteApiForModel(merged.find((model) => model.id === selectedModelId), draft.api);
        if (concreteApi) setEasyApi(concreteApi);
        setDiscoveryReport({ providerId: draft.providerId, api: draft.api ?? "openai-completions", loading: false, result });
        setNotice({ type: "success", message: result.message ?? `已获取 ${result.models.length} 个模型。` });
      } else {
        setNotice({ type: "success", message: result.message ?? `连接测试通过${result.latencyMs != null ? `，${result.latencyMs}ms` : ""}。` });
      }
    } catch (reason) {
      if (!isCurrentDraftRequest(requestVersion)) return;
      if (kind === "fetch") {
        const result = errorDraftResult(reason);
        setDiscoveryReport({ providerId: draft.providerId, api: draft.api ?? "openai-completions", loading: false, ...(result ? { result } : {}), error: errorText(reason) });
      }
      setNotice({ type: "error", message: errorText(reason) });
    } finally {
      if (isCurrentDraftRequest(requestVersion)) setBusy(null);
    }
  };

  const fetchAdvancedDraft = async (providerId: string, draft: ModelProviderDraft) => {
    setBusy(`fetch:${providerId}`);
    setNotice(null);
    setDiscoveryReport({ providerId, api: draft.api ?? "openai-completions", loading: true });
    const requestVersion = await beginDraftRequest();
    if (requestVersion === null) return;
    try {
      const result = await webApi.fetchModelDraft(draft);
      if (!isCurrentDraftRequest(requestVersion)) return;
      if (!result.ok && result.models.length === 0) throw new Error(result.message ?? "Runtime 未能获取可用模型列表。");
      const selectedModelId = result.recommendedModel ?? result.models[0]?.id ?? "";
      const concreteApi = concreteApiForModel(result.models.find((model) => model.id === selectedModelId), draft.api);
      updateProvider(providerId, (current) => ({
        ...current,
        ...(concreteApi ? { api: concreteApi } : {}),
        models: mergeModels(current.models, result.models),
      }));
      setDiscoveryReport({ providerId, api: draft.api ?? "openai-completions", loading: false, result });
      setNotice({ type: "success", message: result.message ?? `已获取 ${result.models.length} 个模型。` });
    } catch (reason) {
      if (!isCurrentDraftRequest(requestVersion)) return;
      const result = errorDraftResult(reason);
      setDiscoveryReport({ providerId, api: draft.api ?? "openai-completions", loading: false, ...(result ? { result } : {}), error: errorText(reason) });
      setNotice({ type: "error", message: errorText(reason) });
    } finally {
      if (isCurrentDraftRequest(requestVersion)) setBusy(null);
    }
  };

  const testDraftOrSaved = async (key: string, draft: ModelProviderDraft, modelId: string, useSaved: boolean) => {
    if (!useSaved && draft.api === "auto") {
      setNotice({ type: "error", message: "请选择具体 API 类型后再测试模型。" });
      return;
    }
    setBusy(key);
    setNotice(null);
    const requestVersion = await beginDraftRequest();
    if (requestVersion === null) return;
    try {
      const result = useSaved
        ? await webApi.testModel(draft.providerId, modelId)
        : await webApi.testModelDraft({ ...draft, modelId, timeoutMs: 20_000 });
      if (!isCurrentDraftRequest(requestVersion)) return;
      if (!result.ok) throw new Error(typeof result.message === "string" ? result.message : "模型生成测试失败。");
      const latency = typeof result.latencyMs === "number" ? `，${result.latencyMs}ms` : "";
      setNotice({ type: "success", message: `连接测试通过${latency}。` });
    } catch (reason) {
      if (!isCurrentDraftRequest(requestVersion)) return;
      setNotice({ type: "error", message: errorText(reason) });
    } finally {
      if (isCurrentDraftRequest(requestVersion)) setBusy(null);
    }
  };

  const apply = async (input: Omit<ModelSetupApplyRequest, "revision">) => {
    if (!setup) return false;
    if (configHasAutoApi(input.config)) {
      setNotice({ type: "error", message: "模型配置仍包含 Auto API 类型，请先选择具体类型。" });
      return false;
    }
    invalidateDraftRequests();
    setBusy("apply");
    setNotice(null);
    try {
      const next = await webApi.applyModelSetup({ revision: setup.revision, ...input });
      setSetup(next);
      setConfig(cloneConfig(next.config));
      setCredentials({});
      setEasyApiKey("");
      setNotice({ type: "success", message: "模型配置已启用。" });
      onModelsChanged?.();
      return true;
    } catch (reason) {
      setNotice({ type: "error", message: errorText(reason) });
      return false;
    } finally {
      setBusy(null);
    }
  };

  const applyEasy = async () => {
    if (!selectedPreset || !effectiveEasyModelId || !easyDraft || easyKeyMissing || easyApiUnresolved) return;
    const existing = config.providers[easyProviderId];
    const model = modelConfig(easyModels.find((item) => item.id === effectiveEasyModelId), effectiveEasyModelId);
    const nextConfig = cloneConfig(config);
    nextConfig.providers[easyProviderId] = {
      ...existing,
      baseUrl: easyBaseUrl.trim() || undefined,
      api: easyApi,
      models: [model, ...(existing?.models ?? []).filter((item) => item.id !== model.id)],
    };
    await apply({
      config: nextConfig,
      credentials: {
        [easyProviderId]: easyApiKey.trim()
          ? { action: "set", apiKey: easyApiKey.trim() }
          : { action: "keep" },
      },
      ...(setGlobalDefault ? { defaultModel: { provider: easyProviderId, modelId: effectiveEasyModelId } } : {}),
      setGlobalDefault,
    });
  };

  const updateProvider = (providerId: string, update: (current: ModelSetupProviderConfig) => ModelSetupProviderConfig) => {
    setConfig((current) => ({ providers: { ...current.providers, [providerId]: update(current.providers[providerId] ?? {}) } }));
  };

  const addProvider = () => {
    const id = newProviderId.trim();
    if (!id || config.providers[id]) return;
    setConfig((current) => ({ providers: { ...current.providers, [id]: { api: "openai-completions", models: [] } } }));
    setNewProviderId("");
  };

  const modelOptions = Object.entries(config.providers).flatMap(([providerId, provider]) => (provider.models ?? []).map((model) => ({
    value: `${providerId}/${model.id}`,
    label: `${providerId} / ${model.name ?? model.id}`,
  })));
  const configContainsAuto = configHasAutoApi(config);

  const applyAdvanced = async () => {
    const credentialActions = Object.fromEntries(Object.entries(credentials).map(([providerId, credential]) => [providerId,
      credential.remove ? { action: "remove" as const } : credential.apiKey.trim()
        ? { action: "set" as const, apiKey: credential.apiKey.trim() }
        : { action: "keep" as const },
    ]));
    const separator = advancedDefault.indexOf("/");
    const defaultModel = advancedSetDefault && separator > 0
      ? { provider: advancedDefault.slice(0, separator), modelId: advancedDefault.slice(separator + 1) }
      : undefined;
    await apply({ config, credentials: credentialActions, ...(defaultModel ? { defaultModel } : {}), setGlobalDefault: advancedSetDefault });
  };

  return <div className="wuxianpi-modal-backdrop models-page-backdrop" role="presentation" data-page-shell="models">
    <section className="wuxianpi-modal models-modal models-page-shell" role="dialog" aria-modal="true" aria-labelledby="models-config-title" tabIndex={-1}>
      <header className="wuxianpi-modal-header models-page-header">
        <div><span className="eyebrow">MODELS</span><h2 id="models-config-title">模型服务</h2></div>
        <button type="button" className="icon-button models-page-close" onClick={onClose} aria-label="返回" title="返回">×</button>
      </header>
      <div className="models-mode-switch" role="tablist" aria-label="配置模式">
        <button type="button" className={mode === "easy" ? "active" : ""} onClick={() => { invalidateDraftRequests(); setMode("easy"); }}>小白模式</button>
        <button type="button" className={mode === "advanced" ? "active" : ""} onClick={() => { invalidateDraftRequests(); setMode("advanced"); }}>高级配置</button>
      </div>
      <main className="wuxianpi-modal-body models-page-body">
        {loading && <div className="wuxianpi-state">正在读取模型配置…</div>}
        {notice && <div className={`wuxianpi-state ${notice.type === "error" ? "error" : notice.type === "success" ? "success" : ""}`}>
          <span>{notice.message}</span>
          {notice.type === "error" && <button type="button" onClick={() => void load()}>刷新</button>}
        </div>}

        {!loading && mode === "easy" && <div className="model-setup-easy">
          {setup?.presets.length ? <>
            <section className="model-provider-picker" aria-label="供应商">
              {setup.presets.map((preset) => <button key={preset.id} type="button" className={preset.id === selectedPresetId ? "active" : ""} onClick={() => { invalidateDraftRequests(); setSelectedPresetId(preset.id); }}>
                <strong>{preset.label}</strong>
                <small>{preset.description ?? preset.category ?? preset.providerId}</small>
              </button>)}
            </section>
            <section className="model-setup-panel">
              <div className="form-grid compact">
                <label className="span-2">API Key
                  <input type="password" autoComplete="off" value={easyApiKey} onChange={(event) => { invalidateDraftRequests(); setEasyApiKey(event.target.value); }} placeholder={storedCredential ? "已保存，留空继续使用" : selectedPreset?.keyPlaceholder ?? "API Key"} />
                </label>
                <label className="span-2">Base URL
                  <input value={easyBaseUrl} onChange={(event) => { invalidateDraftRequests(); setEasyBaseUrl(event.target.value); }} placeholder="https://…" />
                </label>
                <label>API 类型
                  <select value={easyApi} onChange={(event) => { invalidateDraftRequests(); setEasyApi(event.target.value); }}>{apiOptions(easyApi).map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select>
                </label>
                <label>模型
                  <select value={easyModelId} onChange={(event) => selectEasyModel(event.target.value, false)}>
                    <option value="">选择模型</option>
                    {easyModels.map((model) => <option key={model.id} value={model.id}>{model.name ? `${model.name} (${model.id})` : model.id}</option>)}
                  </select>
                </label>
                <label className="span-2">手动模型 ID
                  <input value={manualModelId} onChange={(event) => selectEasyModel(event.target.value, true)} placeholder={easyModelId || "model-id"} />
                </label>
              </div>
              <div className="model-setup-actions">
                <button type="button" disabled={!easyDraft || easyKeyMissing || !easyBaseUrl.trim() || busy !== null} onClick={() => easyDraft && void runDraft("fetch", easyDraft)}>{busy === "fetch" ? "获取中…" : "获取模型"}</button>
                <button type="button" title={easyApiUnresolved ? "手动模型没有探测来源，请选择具体 API 类型" : easyDraftTestKeyMissing ? "草稿已修改，请重新输入 API Key" : undefined} disabled={!easyDraft || easyApiUnresolved || easyDraftTestKeyMissing || !effectiveEasyModelId || busy !== null} onClick={() => easyDraft && void testDraftOrSaved("test", easyDraft, effectiveEasyModelId, easyCanUseSavedTest)}>{busy === "test" ? "测试中…" : "测试"}</button>
                <button type="button" className="primary-button" title={easyApiUnresolved ? "请选择具体 API 类型后再启用" : undefined} disabled={!effectiveEasyModelId || easyApiUnresolved || easyKeyMissing || busy !== null} onClick={() => void applyEasy()}>{busy === "apply" ? "启用中…" : "保存并启用"}</button>
              </div>
              <DiscoveryStatus report={discoveryReport} />
              <label className="model-default-toggle"><input type="checkbox" checked={setGlobalDefault} onChange={(event) => setSetGlobalDefault(event.target.checked)} />设为全局默认模型</label>
            </section>
          </> : <div className="wuxianpi-state warning">Runtime 没有返回供应商预设。</div>}
        </div>}

        {!loading && mode === "advanced" && <div className="settings-stack model-advanced-list">
          <DiscoveryStatus report={discoveryReport} />
          <section className="settings-card model-default-editor">
            <div className="form-grid compact">
              <label>全局默认模型<select value={advancedDefault} onChange={(event) => setAdvancedDefault(event.target.value)}><option value="">未选择</option>{modelOptions.map((model) => <option key={model.value} value={model.value}>{model.label}</option>)}</select></label>
              <label className="model-default-toggle"><input type="checkbox" checked={advancedSetDefault} onChange={(event) => setAdvancedSetDefault(event.target.checked)} />保存时设为全局默认</label>
            </div>
          </section>
          {Object.entries(config.providers).map(([providerId, provider]) => {
            const credential = credentials[providerId] ?? { apiKey: "", remove: false };
            const authenticated = statusFor(setup, providerId)?.authenticated;
            const firstModel = provider.models?.[0]?.id ?? "";
            const canUseSavedTest = !!authenticated && !credential.apiKey.trim() && sameConfig(provider, setup?.config.providers[providerId]);
            const requiresApiKey = setup?.presets.find((preset) => preset.providerId === providerId)?.requiresApiKey ?? true;
            const draftTestKeyMissing = requiresApiKey && !credential.apiKey.trim() && !canUseSavedTest;
            return <section className="settings-card model-provider-editor" key={providerId}>
              <header><div><strong>{providerId}</strong><small>{authenticated ? "凭据已保存" : "未保存凭据"}</small></div><button type="button" className="danger-link" onClick={() => {
                invalidateDraftRequests();
                setConfig((current) => ({ providers: Object.fromEntries(Object.entries(current.providers).filter(([id]) => id !== providerId)) }));
                if (authenticated) setCredentials((current) => ({ ...current, [providerId]: { apiKey: "", remove: true } }));
              }}>删除</button></header>
              <div className="form-grid compact">
                <label className="span-2">Base URL<input value={provider.baseUrl ?? ""} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, baseUrl: event.target.value || undefined })); }} /></label>
                <label>API 类型<select value={provider.api ?? "openai-completions"} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, api: event.target.value })); }}>{apiOptions(provider.api ?? "openai-completions").map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
                <label>API Key<input type="password" autoComplete="off" value={credential.apiKey} disabled={credential.remove} onChange={(event) => { invalidateDraftRequests(); setCredentials((current) => ({ ...current, [providerId]: { apiKey: event.target.value, remove: false } })); }} placeholder={authenticated ? "已保存，留空不修改" : "API Key"} /></label>
                <label className="span-2">Headers<textarea rows={3} value={formatHeaders(provider.headers)} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, headers: parseHeaders(event.target.value) })); }} placeholder="Header-Name: value" /></label>
                <label className="span-2">模型列表<textarea rows={5} value={formatModels(provider.models)} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, models: parseModels(event.target.value) })); }} placeholder="model-id | 显示名称" /></label>
              </div>
              <div className="model-setup-actions">
                <label className="model-default-toggle"><input type="checkbox" checked={credential.remove} onChange={(event) => { invalidateDraftRequests(); setCredentials((current) => ({ ...current, [providerId]: { apiKey: "", remove: event.target.checked } })); }} />移除已保存凭据</label>
                <button type="button" disabled={!provider.baseUrl || busy !== null} onClick={() => void fetchAdvancedDraft(providerId, { providerId, baseUrl: provider.baseUrl, api: provider.api, headers: provider.headers, apiKey: credential.apiKey || undefined })}>{busy === `fetch:${providerId}` ? "获取中…" : "获取模型"}</button>
                <button type="button" title={provider.api === "auto" ? "请选择具体 API 类型后再测试" : draftTestKeyMissing ? "草稿已修改，请重新输入 API Key" : undefined} disabled={!firstModel || provider.api === "auto" || draftTestKeyMissing || busy !== null} onClick={() => void testDraftOrSaved(`test:${providerId}`, { providerId, baseUrl: provider.baseUrl, api: provider.api, headers: provider.headers, apiKey: credential.apiKey || undefined }, firstModel, canUseSavedTest)}>{busy === `test:${providerId}` ? "测试中…" : "测试首个模型"}</button>
              </div>
            </section>;
          })}
          <section className="model-provider-add">
            <input value={newProviderId} onChange={(event) => setNewProviderId(event.target.value)} placeholder="new-provider-id" />
            <button type="button" disabled={!newProviderId.trim() || !!config.providers[newProviderId.trim()]} onClick={addProvider}>新增 Provider</button>
          </section>
        </div>}
      </main>
      <footer className="wuxianpi-modal-footer models-page-footer">
        <button type="button" className="secondary-button" disabled={busy !== null} onClick={() => void load()}>刷新</button>
        {mode === "advanced" && <button type="button" className="primary-button" title={configContainsAuto ? "仍有 Provider 使用 Auto，请选择具体 API 类型" : undefined} disabled={busy !== null || configContainsAuto || (advancedSetDefault && !advancedDefault)} onClick={() => void applyAdvanced()}>{busy === "apply" ? "保存中…" : "保存全部"}</button>}
        <button type="button" className="secondary-button" onClick={onClose}>完成</button>
      </footer>
    </section>
  </div>;
}
