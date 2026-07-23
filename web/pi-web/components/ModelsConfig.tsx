"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useIsMobile } from "@/hooks/useIsMobile";
import { LatestRequestGate } from "@/lib/latest-request-gate";
import {
  MODEL_API_OPTIONS,
  concreteApiForModel,
  configHasAutoApi,
  modelApiLabel,
  normalizeModelDraftResult,
  normalizeModelSetup,
  toRuntimeModelSetupApplyRequest,
  type ModelDraftResult,
  type ModelProviderApi,
  type ModelProviderDraft,
  type ModelSetupApplyRequest,
  type ModelSetupProviderConfig,
  type ModelSetupState,
} from "@/lib/model-setup-contract";

interface ModelsConfigProps {
  onClose: () => void;
  onModelsChanged?: () => void;
  initialNoModelMode?: boolean;
  onAddAppAndStartChat?: () => void;
  canAddAppAndStartChat?: boolean;
  addAppAndStartChatDisabledReason?: string;
}

type Notice = { type: "success" | "error" | "info"; message: string };
type CredentialDraft = { apiKey: string; remove: boolean };
type Mode = "easy" | "advanced";
type DiscoveryReport = { providerId: string; api: ModelProviderApi; loading: boolean; result?: ModelDraftResult; error?: string };
type EasyModel = NonNullable<ModelSetupProviderConfig["models"]>[number] & { sourceApis?: ModelProviderApi[] };

const DRAFT_REQUEST_DEBOUNCE_MS = 500;
const AUTO_DISCOVERY_MODES = MODEL_API_OPTIONS.filter((option) => option.value !== "auto");

function apiOptions(current: ModelProviderApi) {
  return MODEL_API_OPTIONS.some((option) => option.value === current)
    ? MODEL_API_OPTIONS
    : [...MODEL_API_OPTIONS, { value: current, label: modelApiLabel(current) }];
}

const inputStyle: React.CSSProperties = {
  width: "100%",
  minHeight: 38,
  padding: "8px 10px",
  border: "1px solid var(--border)",
  borderRadius: 7,
  background: "var(--bg-panel)",
  color: "var(--text)",
  fontSize: 12,
  boxSizing: "border-box",
};

const buttonStyle: React.CSSProperties = {
  minHeight: 36,
  padding: "7px 11px",
  border: "1px solid var(--border)",
  borderRadius: 7,
  background: "var(--bg-panel)",
  color: "var(--text)",
  cursor: "pointer",
  fontSize: 12,
  fontWeight: 650,
};

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

function responseError(value: unknown, status: number): string {
  const root = value && typeof value === "object" ? value as Record<string, unknown> : {};
  const nested = root.error && typeof root.error === "object" ? root.error as Record<string, unknown> : {};
  return String(nested.message ?? root.error ?? root.message ?? `HTTP ${status}`);
}

class ModelSetupRequestError extends Error {
  constructor(message: string, readonly status: number, readonly details?: unknown) {
    super(message);
    this.name = "ModelSetupRequestError";
  }
}

function errorDraftResult(reason: unknown): ModelDraftResult | undefined {
  if (!(reason instanceof ModelSetupRequestError) || reason.details == null) return undefined;
  const result = normalizeModelDraftResult(reason.details);
  return result.modeResults.length > 0 ? { ...result, ok: false, message: reason.message } : undefined;
}

function sameConfig(left: ModelSetupProviderConfig | undefined, right: ModelSetupProviderConfig | undefined): boolean {
  return JSON.stringify(left ?? {}) === JSON.stringify(right ?? {});
}

function isDraftBusy(key: string | null): boolean {
  return key === "easy-fetch" || key === "easy-test" || key?.startsWith("fetch:") === true || key?.startsWith("test:") === true;
}

async function requestJson(path: string, init: RequestInit = {}): Promise<unknown> {
  const response = await fetch(path, {
    ...init,
    cache: "no-store",
    headers: init.body ? { "Content-Type": "application/json", ...init.headers } : init.headers,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const prefix = response.status === 409 ? "配置已在其他页面更新，请刷新后重试。" : responseError(payload, response.status);
    const root = payload && typeof payload === "object" ? payload as Record<string, unknown> : {};
    const error = root.error && typeof root.error === "object" ? root.error as Record<string, unknown> : {};
    throw new ModelSetupRequestError(prefix, response.status, error.details ?? root.details);
  }
  return payload;
}

function Field({ label, children, span = false }: { label: string; children: React.ReactNode; span?: boolean }) {
  return <label style={{ display: "flex", flexDirection: "column", gap: 6, minWidth: 0, gridColumn: span ? "1 / -1" : undefined }}>
    <span style={{ color: "var(--text-muted)", fontSize: 11, fontWeight: 650 }}>{label}</span>
    {children}
  </label>;
}

function DiscoveryStatus({ report }: { report: DiscoveryReport | null }) {
  const modeRows = report?.api === "auto"
    ? AUTO_DISCOVERY_MODES.map((option) => ({ option, result: report.result?.modeResults.find((mode) => mode.api === option.value) }))
    : (report?.result?.modeResults ?? []).map((result) => ({ option: { value: result.api, label: result.label }, result }));
  return <div aria-live="polite" style={{ height: 220, marginTop: 12, padding: "10px 11px", overflowY: "auto", border: `1px solid ${report ? "var(--border)" : "transparent"}`, borderRadius: 8, background: report ? "var(--bg-panel)" : "transparent", color: "var(--text-muted)", fontSize: 11, lineHeight: 1.45, boxSizing: "border-box" }}>
    {!report ? null : <>
      <header style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, marginBottom: 7 }}><strong>{report.providerId}</strong><span>{report.loading ? "等待 Runtime 返回" : report.error ? "模型列表获取失败" : report.result ? `已返回 ${report.result.models.length} 个去重模型` : "模型列表获取失败"}</span></header>
      {report.loading && <strong>{report.api === "auto" ? "需要探测多种模式，请耐心等待" : "正在获取模型列表…"}</strong>}
      {modeRows.length > 0 && <div style={{ display: "grid", gap: 4, marginTop: 6 }}>
        {modeRows.map(({ option, result }) => <div key={option.value} style={{ display: "grid", gridTemplateColumns: "minmax(92px, .45fr) minmax(0, 1fr)", gap: 8 }}>
          <strong>{option.label}</strong>
          {report.loading
            ? <span>进行中 · 正在尝试模型列表 URL/auth 规则</span>
            : result?.ok
              ? <span style={{ color: "#16a34a" }}>模型列表获取成功 · {result.modelCount} 个模型</span>
              : <span style={{ color: "#ef4444" }}>模型列表获取失败 · {result?.error ?? "未返回结果"}</span>}
        </div>)}
      </div>}
      {!report.loading && report.error && <span style={{ color: "#ef4444" }}>{report.error}</span>}
      {!report.loading && report.result && report.result.models.length > 0 && <div style={{ display: "grid", gap: 4, marginTop: 9, paddingTop: 8, borderTop: "1px solid var(--border)" }}>
        <strong>去重模型列表</strong>
        {report.result.models.map((model) => <div key={model.id} style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 10, minWidth: 0 }}>
          <code style={{ color: "var(--text)", overflowWrap: "anywhere" }}>{model.id}</code>
          {!!model.sourceApis?.length && <span style={{ flex: "0 0 auto", textAlign: "right" }}>{model.sourceApis.map(modelApiLabel).join(" / ")}</span>}
        </div>)}
      </div>}
      {!report.loading && report.api === "auto" && report.result && <span>这里只验证模型列表获取；生成协议需选择具体 API 类型后点击测试。</span>}
      {!report.loading && report.result && report.result.modeResults.length === 0 && <span>{report.result.message ?? `${modelApiLabel(report.api)} 模型列表获取完成`}</span>}
    </>}
  </div>;
}

export function ModelsConfig({
  onClose,
  onModelsChanged,
  initialNoModelMode = false,
  onAddAppAndStartChat,
  canAddAppAndStartChat = false,
  addAppAndStartChatDisabledReason,
}: ModelsConfigProps) {
  const isMobile = useIsMobile();
  const [mode, setMode] = useState<Mode>("easy");
  const [setup, setSetup] = useState<ModelSetupState | null>(null);
  const [config, setConfig] = useState<ModelSetupState["config"]>({ providers: {} });
  const [credentials, setCredentials] = useState<Record<string, CredentialDraft>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [hasApplied, setHasApplied] = useState(false);
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
      const next = normalizeModelSetup(await requestJson("/api/models-config"));
      setSetup(next);
      setConfig(cloneConfig(next.config));
      setCredentials({});
      setSelectedPresetId((current) => current && next.presets.some((preset) => preset.id === current) ? current : next.presets[0]?.id ?? "");
      setAdvancedDefault(next.defaultModel ? `${next.defaultModel.provider}/${next.defaultModel.modelId}` : "");
      setAdvancedSetDefault(false);
    } catch (reason) {
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
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
    const models = existing?.models?.length ? existing.models : selectedPreset.recommendedModels.map((id) => ({ id }));
    setEasyBaseUrl(existing?.baseUrl ?? selectedPreset.baseUrl ?? "");
    setEasyApi(existing?.api ?? selectedPreset.api ?? "openai-completions");
    setEasyApiKey("");
    setEasyModels(models);
    setEasyModelId(setup?.defaultModel?.provider === selectedPreset.providerId
      ? setup.defaultModel.modelId
      : selectedPreset.recommendedModel ?? models[0]?.id ?? "");
    setManualModelId("");
    setNotice(null);
  }, [selectedPresetId, selectedPreset, setup?.defaultModel, config.providers]);

  const effectiveEasyModelId = manualModelId.trim() || easyModelId.trim();
  const easyDraft = useMemo<ModelProviderDraft | null>(() => selectedPreset ? {
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

  const fetchDraft = async (key: string, draft: ModelProviderDraft, onResult: (result: ModelDraftResult) => void) => {
    setBusy(key);
    setNotice(null);
    setDiscoveryReport({ providerId: draft.providerId, api: draft.api ?? "openai-completions", loading: true });
    const requestVersion = await beginDraftRequest();
    if (requestVersion === null) return;
    try {
      const result = normalizeModelDraftResult(await requestJson("/api/models-config/fetch", { method: "POST", body: JSON.stringify(draft) }));
      if (!isCurrentDraftRequest(requestVersion)) return;
      if (!result.ok && result.models.length === 0) throw new Error(result.message ?? "Runtime 未能获取可用模型列表。");
      onResult(result);
      setDiscoveryReport({ providerId: draft.providerId, api: draft.api ?? "openai-completions", loading: false, result });
      setNotice({ type: "success", message: result.message ?? `已获取 ${result.models.length} 个模型。` });
    } catch (reason) {
      if (!isCurrentDraftRequest(requestVersion)) return;
      const result = errorDraftResult(reason);
      setDiscoveryReport({ providerId: draft.providerId, api: draft.api ?? "openai-completions", loading: false, ...(result ? { result } : {}), error: reason instanceof Error ? reason.message : String(reason) });
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
    } finally {
      if (isCurrentDraftRequest(requestVersion)) setBusy(null);
    }
  };

  const testDraft = async (key: string, draft: ModelProviderDraft, modelId: string) => {
    if (draft.api === "auto") {
      setNotice({ type: "error", message: "请选择具体 API 类型后再测试模型。" });
      return;
    }
    setBusy(key);
    setNotice(null);
    const requestVersion = await beginDraftRequest();
    if (requestVersion === null) return;
    try {
      const result = normalizeModelDraftResult(await requestJson("/api/models-config/test", {
        method: "POST",
        body: JSON.stringify({
          providerId: draft.providerId,
          modelId,
          timeoutMs: 20_000,
          provider: {
            baseUrl: draft.baseUrl,
            api: draft.api,
            headers: draft.headers,
            apiKey: draft.apiKey,
          },
        }),
      }));
      if (!isCurrentDraftRequest(requestVersion)) return;
      if (!result.ok) throw new Error(result.message ?? "模型生成测试失败。");
      setNotice({ type: "success", message: result.message ?? `连接测试通过${result.latencyMs != null ? `，${result.latencyMs}ms` : ""}。` });
    } catch (reason) {
      if (!isCurrentDraftRequest(requestVersion)) return;
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
    } finally {
      if (isCurrentDraftRequest(requestVersion)) setBusy(null);
    }
  };

  const testSaved = async (key: string, providerId: string, modelId: string) => {
    setBusy(key);
    setNotice(null);
    const requestVersion = await beginDraftRequest();
    if (requestVersion === null) return;
    try {
      const result = normalizeModelDraftResult(await requestJson("/api/models-config/test", {
        method: "POST",
        body: JSON.stringify({ provider: providerId, modelId, timeoutMs: 20_000 }),
      }));
      if (!isCurrentDraftRequest(requestVersion)) return;
      if (!result.ok) throw new Error(result.message ?? "模型生成测试失败。");
      setNotice({ type: "success", message: result.message ?? `连接测试通过${result.latencyMs != null ? `，${result.latencyMs}ms` : ""}。` });
    } catch (reason) {
      if (!isCurrentDraftRequest(requestVersion)) return;
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
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
      const next = normalizeModelSetup(await requestJson("/api/models-config/apply", {
        method: "POST",
        body: JSON.stringify(toRuntimeModelSetupApplyRequest({ revision: setup.revision, ...input })),
      }));
      setSetup(next);
      setConfig(cloneConfig(next.config));
      setCredentials({});
      setEasyApiKey("");
      setHasApplied(true);
      setNotice({ type: "success", message: "模型配置已启用。" });
      onModelsChanged?.();
      return true;
    } catch (reason) {
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
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
      credentials: { [easyProviderId]: easyApiKey.trim() ? { action: "set", apiKey: easyApiKey.trim() } : { action: "keep" } },
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

  const modelOptions = Object.entries(config.providers).flatMap(([providerId, provider]) => (provider.models ?? []).map((model) => ({
    value: `${providerId}/${model.id}`,
    label: `${providerId} / ${model.name ?? model.id}`,
  })));
  const configContainsAuto = configHasAutoApi(config);
  const showStartChat = !!onAddAppAndStartChat && (hasApplied || canAddAppAndStartChat);
  const canStartChat = !!onAddAppAndStartChat && canAddAppAndStartChat;

  return <div
    style={{ position: "fixed", inset: 0, zIndex: 1000, background: "rgba(0,0,0,0.35)", display: "flex", alignItems: "center", justifyContent: "center", padding: isMobile ? 8 : 12, boxSizing: "border-box" }}
    onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}
  >
    <section style={{ width: isMobile ? "calc(100vw - 16px)" : "min(980px, 100%)", height: isMobile ? "calc(100dvh - 16px)" : "min(86vh, 820px)", border: "1px solid var(--border)", borderRadius: 8, background: "var(--bg)", color: "var(--text)", display: "flex", flexDirection: "column", overflow: "hidden", boxShadow: "0 12px 36px rgba(0,0,0,.2)" }} role="dialog" aria-modal="true" aria-labelledby="models-title">
      <header style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, padding: "12px 16px", borderBottom: "1px solid var(--border)" }}>
        <strong id="models-title" style={{ fontSize: 15 }}>连接模型</strong>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 3, padding: 3, border: "1px solid var(--border)", borderRadius: 7, background: "var(--bg-panel)" }}>
            {(["easy", "advanced"] as const).map((item) => <button key={item} type="button" onClick={() => { invalidateDraftRequests(); setMode(item); }} style={{ minHeight: 29, padding: "0 10px", border: 0, borderRadius: 5, background: mode === item ? "var(--accent)" : "transparent", color: mode === item ? "#fff" : "var(--text-muted)", cursor: "pointer", fontSize: 12 }}>{item === "easy" ? "小白模式" : "高级配置"}</button>)}
          </div>
          <button type="button" onClick={onClose} aria-label="关闭" title="关闭" style={{ width: 32, height: 32, border: 0, background: "transparent", color: "var(--text-muted)", cursor: "pointer", fontSize: 21 }}>×</button>
        </div>
      </header>

      {initialNoModelMode && <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, padding: "10px 16px", borderBottom: "1px solid var(--border)", background: "color-mix(in srgb, var(--accent) 8%, transparent)", flexWrap: "wrap" }}>
        <strong style={{ fontSize: 12 }}>尚未检测到可用模型</strong>
        {showStartChat ? <button type="button" disabled={!canStartChat} title={!canStartChat ? addAppAndStartChatDisabledReason : undefined} onClick={onAddAppAndStartChat} style={{ ...buttonStyle, background: canStartChat ? "#16a34a" : "var(--bg-panel)", color: canStartChat ? "#fff" : "var(--text-dim)", cursor: canStartChat ? "pointer" : "not-allowed" }}>添加应用并开启新对话</button> : null}
      </div>}

      <main style={{ flex: 1, minHeight: 0, overflowY: "auto", padding: isMobile ? 12 : 16 }}>
        {loading && <div style={{ padding: 24, textAlign: "center", color: "var(--text-muted)", fontSize: 12 }}>正在读取模型配置…</div>}
        {notice && <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, marginBottom: 12, padding: "9px 11px", border: `1px solid ${notice.type === "error" ? "#ef4444" : notice.type === "success" ? "#16a34a" : "var(--border)"}`, borderRadius: 7, color: notice.type === "error" ? "#ef4444" : notice.type === "success" ? "#16a34a" : "var(--text-muted)", fontSize: 11 }}>
          <span>{notice.message}</span>
          {notice.type === "error" && <button type="button" onClick={() => void load()} style={buttonStyle}>刷新</button>}
        </div>}

        {!loading && mode === "easy" && (setup?.presets.length ? <div style={{ display: "grid", gridTemplateColumns: isMobile ? "1fr" : "minmax(190px, .7fr) minmax(0, 1.3fr)", gap: 16 }}>
          <div style={{ display: isMobile ? "flex" : "grid", alignContent: "start", gap: 6, overflowX: isMobile ? "auto" : undefined }}>
            {setup.presets.map((preset) => <button key={preset.id} type="button" onClick={() => { invalidateDraftRequests(); setSelectedPresetId(preset.id); }} style={{ minWidth: isMobile ? 150 : 0, minHeight: 55, padding: "9px 11px", border: `1px solid ${preset.id === selectedPresetId ? "var(--accent)" : "var(--border)"}`, borderRadius: 7, background: preset.id === selectedPresetId ? "color-mix(in srgb, var(--accent) 8%, var(--bg))" : "transparent", color: "var(--text)", textAlign: "left", cursor: "pointer", display: "flex", flexDirection: "column", gap: 3 }}>
              <strong style={{ fontSize: 12 }}>{preset.label}</strong>
              <small style={{ color: "var(--text-muted)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{preset.description ?? preset.category ?? preset.providerId}</small>
            </button>)}
          </div>
          <section style={{ alignSelf: "start", padding: 14, border: "1px solid var(--border)", borderRadius: 8, background: "var(--bg-panel)" }}>
            <div style={{ display: "grid", gridTemplateColumns: isMobile ? "1fr" : "repeat(2, minmax(0, 1fr))", gap: 11 }}>
              <Field label="API Key" span><input type="password" autoComplete="off" value={easyApiKey} onChange={(event) => { invalidateDraftRequests(); setEasyApiKey(event.target.value); }} placeholder={storedCredential ? "已保存，留空继续使用" : selectedPreset?.keyPlaceholder ?? "API Key"} style={inputStyle} /></Field>
              <Field label="Base URL" span><input value={easyBaseUrl} onChange={(event) => { invalidateDraftRequests(); setEasyBaseUrl(event.target.value); }} placeholder="https://…" style={inputStyle} /></Field>
              <Field label="API 类型"><select value={easyApi} onChange={(event) => { invalidateDraftRequests(); setEasyApi(event.target.value); }} style={inputStyle}>{apiOptions(easyApi).map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></Field>
              <Field label="模型"><select value={easyModelId} onChange={(event) => selectEasyModel(event.target.value, false)} style={inputStyle}><option value="">选择模型</option>{easyModels.map((model) => <option key={model.id} value={model.id}>{model.name ? `${model.name} (${model.id})` : model.id}</option>)}</select></Field>
              <Field label="手动模型 ID" span><input value={manualModelId} onChange={(event) => selectEasyModel(event.target.value, true)} placeholder={easyModelId || "model-id"} style={inputStyle} /></Field>
            </div>
            <div style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", flexWrap: "wrap", gap: 8, marginTop: 12 }}>
              <button type="button" disabled={!easyDraft || easyKeyMissing || !easyBaseUrl.trim() || busy !== null} onClick={() => easyDraft && void fetchDraft("easy-fetch", easyDraft, (result) => {
                const merged = mergeEasyModels(easyModels, result.models);
                const selectedModelId = result.recommendedModel ?? merged[0]?.id ?? "";
                setEasyModels(merged);
                setEasyModelId(selectedModelId);
                setManualModelId("");
                const concreteApi = concreteApiForModel(merged.find((model) => model.id === selectedModelId), easyDraft.api);
                if (concreteApi) setEasyApi(concreteApi);
              })} style={buttonStyle}>{busy === "easy-fetch" ? "获取中…" : "获取模型"}</button>
              <button type="button" title={easyApiUnresolved ? "手动模型没有探测来源，请选择具体 API 类型" : easyDraftTestKeyMissing ? "草稿已修改，请重新输入 API Key" : undefined} disabled={!easyDraft || easyApiUnresolved || easyDraftTestKeyMissing || !effectiveEasyModelId || busy !== null} onClick={() => easyDraft && void (easyCanUseSavedTest ? testSaved("easy-test", easyProviderId, effectiveEasyModelId) : testDraft("easy-test", easyDraft, effectiveEasyModelId))} style={buttonStyle}>{busy === "easy-test" ? "测试中…" : "测试"}</button>
              <button type="button" title={easyApiUnresolved ? "请选择具体 API 类型后再启用" : undefined} disabled={!effectiveEasyModelId || easyApiUnresolved || easyKeyMissing || busy !== null} onClick={() => void applyEasy()} style={{ ...buttonStyle, borderColor: "var(--accent)", background: "var(--accent)", color: "#fff" }}>{busy === "apply" ? "启用中…" : "保存并启用"}</button>
            </div>
            <DiscoveryStatus report={discoveryReport} />
            <label style={{ display: "flex", alignItems: "center", gap: 7, marginTop: 11, color: "var(--text-muted)", fontSize: 11 }}><input type="checkbox" checked={setGlobalDefault} onChange={(event) => setSetGlobalDefault(event.target.checked)} />设为全局默认模型</label>
          </section>
        </div> : <div style={{ padding: 20, color: "var(--text-muted)", textAlign: "center" }}>Runtime 没有返回供应商预设。</div>)}

        {!loading && mode === "advanced" && <div style={{ display: "grid", gap: 12 }}>
          <DiscoveryStatus report={discoveryReport} />
          <section style={{ padding: 12, border: "1px solid var(--border)", borderRadius: 8, background: "var(--bg-panel)", display: "grid", gridTemplateColumns: isMobile ? "1fr" : "minmax(0, 1fr) auto", alignItems: "end", gap: 10 }}>
            <Field label="全局默认模型"><select value={advancedDefault} onChange={(event) => setAdvancedDefault(event.target.value)} style={inputStyle}><option value="">未选择</option>{modelOptions.map((model) => <option key={model.value} value={model.value}>{model.label}</option>)}</select></Field>
            <label style={{ display: "flex", alignItems: "center", gap: 7, minHeight: 38, color: "var(--text-muted)", fontSize: 11 }}><input type="checkbox" checked={advancedSetDefault} onChange={(event) => setAdvancedSetDefault(event.target.checked)} />保存时设为全局默认</label>
          </section>
          {Object.entries(config.providers).map(([providerId, provider]) => {
            const credential = credentials[providerId] ?? { apiKey: "", remove: false };
            const authenticated = statusFor(setup, providerId)?.authenticated;
            const firstModel = provider.models?.[0]?.id ?? "";
            const canUseSavedTest = !!authenticated && !credential.apiKey.trim() && sameConfig(provider, setup?.config.providers[providerId]);
            const requiresApiKey = setup?.presets.find((preset) => preset.providerId === providerId)?.requiresApiKey ?? true;
            const draftTestKeyMissing = requiresApiKey && !credential.apiKey.trim() && !canUseSavedTest;
            return <section key={providerId} style={{ padding: 14, border: "1px solid var(--border)", borderRadius: 8, background: "var(--bg-panel)" }}>
              <header style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, marginBottom: 12 }}><div style={{ display: "flex", flexDirection: "column", gap: 3 }}><strong style={{ fontSize: 13 }}>{providerId}</strong><small style={{ color: "var(--text-muted)" }}>{authenticated ? "凭据已保存" : "未保存凭据"}</small></div><button type="button" onClick={() => {
                invalidateDraftRequests();
                setConfig((current) => ({ providers: Object.fromEntries(Object.entries(current.providers).filter(([id]) => id !== providerId)) }));
                if (authenticated) setCredentials((current) => ({ ...current, [providerId]: { apiKey: "", remove: true } }));
              }} style={{ ...buttonStyle, borderColor: "transparent", background: "transparent", color: "#ef4444" }}>删除</button></header>
              <div style={{ display: "grid", gridTemplateColumns: isMobile ? "1fr" : "repeat(2, minmax(0, 1fr))", gap: 10 }}>
                <Field label="Base URL" span><input value={provider.baseUrl ?? ""} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, baseUrl: event.target.value || undefined })); }} style={inputStyle} /></Field>
                <Field label="API 类型"><select value={provider.api ?? "openai-completions"} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, api: event.target.value })); }} style={inputStyle}>{apiOptions(provider.api ?? "openai-completions").map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></Field>
                <Field label="API Key"><input type="password" autoComplete="off" value={credential.apiKey} disabled={credential.remove} onChange={(event) => { invalidateDraftRequests(); setCredentials((current) => ({ ...current, [providerId]: { apiKey: event.target.value, remove: false } })); }} placeholder={authenticated ? "已保存，留空不修改" : "API Key"} style={inputStyle} /></Field>
                <Field label="Headers" span><textarea rows={3} value={formatHeaders(provider.headers)} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, headers: parseHeaders(event.target.value) })); }} placeholder="Header-Name: value" style={{ ...inputStyle, minHeight: 78, resize: "vertical" }} /></Field>
                <Field label="模型列表" span><textarea rows={5} value={formatModels(provider.models)} onChange={(event) => { invalidateDraftRequests(); updateProvider(providerId, (current) => ({ ...current, models: parseModels(event.target.value) })); }} placeholder="model-id | 显示名称" style={{ ...inputStyle, minHeight: 112, resize: "vertical", fontFamily: "var(--font-mono)" }} /></Field>
              </div>
              <div style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", flexWrap: "wrap", gap: 8, marginTop: 11 }}>
                <label style={{ display: "flex", alignItems: "center", gap: 6, marginRight: "auto", color: "var(--text-muted)", fontSize: 11 }}><input type="checkbox" checked={credential.remove} onChange={(event) => { invalidateDraftRequests(); setCredentials((current) => ({ ...current, [providerId]: { apiKey: "", remove: event.target.checked } })); }} />移除已保存凭据</label>
                <button type="button" disabled={!provider.baseUrl || busy !== null} onClick={() => void fetchDraft(`fetch:${providerId}`, { providerId, baseUrl: provider.baseUrl, api: provider.api, headers: provider.headers, apiKey: credential.apiKey || undefined }, (result) => {
                  const selectedModelId = result.recommendedModel ?? result.models[0]?.id ?? "";
                  const concreteApi = concreteApiForModel(result.models.find((model) => model.id === selectedModelId), provider.api);
                  updateProvider(providerId, (current) => ({ ...current, ...(concreteApi ? { api: concreteApi } : {}), models: mergeModels(current.models, result.models) }));
                })} style={buttonStyle}>{busy === `fetch:${providerId}` ? "获取中…" : "获取模型"}</button>
                <button type="button" title={provider.api === "auto" ? "请选择具体 API 类型后再测试" : draftTestKeyMissing ? "草稿已修改，请重新输入 API Key" : undefined} disabled={!firstModel || provider.api === "auto" || draftTestKeyMissing || busy !== null} onClick={() => void (canUseSavedTest ? testSaved(`test:${providerId}`, providerId, firstModel) : testDraft(`test:${providerId}`, { providerId, baseUrl: provider.baseUrl, api: provider.api, headers: provider.headers, apiKey: credential.apiKey || undefined }, firstModel))} style={buttonStyle}>{busy === `test:${providerId}` ? "测试中…" : "测试首个模型"}</button>
              </div>
            </section>;
          })}
          <section style={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) auto", gap: 8 }}><input value={newProviderId} onChange={(event) => setNewProviderId(event.target.value)} placeholder="new-provider-id" style={inputStyle} /><button type="button" disabled={!newProviderId.trim() || !!config.providers[newProviderId.trim()]} onClick={addProvider} style={buttonStyle}>新增 Provider</button></section>
        </div>}
      </main>

      <footer style={{ display: "flex", justifyContent: "flex-end", alignItems: "center", gap: 8, padding: "11px 16px", borderTop: "1px solid var(--border)", flexWrap: "wrap" }}>
        <button type="button" disabled={busy !== null} onClick={() => void load()} style={buttonStyle}>刷新</button>
        {mode === "advanced" && <button type="button" title={configContainsAuto ? "仍有 Provider 使用 Auto，请选择具体 API 类型" : undefined} disabled={busy !== null || configContainsAuto || (advancedSetDefault && !advancedDefault)} onClick={() => void applyAdvanced()} style={{ ...buttonStyle, background: "var(--accent)", borderColor: "var(--accent)", color: "#fff" }}>{busy === "apply" ? "保存中…" : "保存全部"}</button>}
        <button type="button" onClick={onClose} style={buttonStyle}>完成</button>
      </footer>
    </section>
  </div>;
}
