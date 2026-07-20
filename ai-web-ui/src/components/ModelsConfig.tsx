import { useCallback, useEffect, useMemo, useState } from "react";
import { webApi, type NormalizedModels } from "@/lib/web-api-client";

interface ModelsConfigProps { onClose: () => void; onModelsChanged?: () => void }
type ProviderRow = { id: string; name?: string; authenticated?: boolean; authLabel?: string; authSource?: string };

export function ModelsConfig({ onClose, onModelsChanged }: ModelsConfigProps) {
  const [payload, setPayload] = useState<NormalizedModels | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [apiKeys, setApiKeys] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<{ type: "success" | "error"; message: string } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try { setPayload(await webApi.models()); }
    catch (reason) { setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) }); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const providers = (payload?.providers ?? []) as ProviderRow[];
  const groups = useMemo(() => {
    const result = new Map<string, NormalizedModels["modelList"]>();
    for (const model of payload?.modelList ?? []) result.set(model.provider, [...(result.get(model.provider) ?? []), model]);
    return result;
  }, [payload]);

  const run = async (key: string, operation: () => Promise<unknown>, success: string) => {
    setBusy(key); setNotice(null);
    try {
      await operation();
      setNotice({ type: "success", message: success });
      await load();
      onModelsChanged?.();
    } catch (reason) {
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
    } finally { setBusy(null); }
  };

  return <div className="wuxianpi-modal-backdrop" role="dialog" aria-modal="true" aria-label="模型服务">
    <section className="wuxianpi-modal models-modal">
      <header className="wuxianpi-modal-header"><div><span className="eyebrow">MODELS</span><h2>模型服务</h2></div><button type="button" className="icon-button" onClick={onClose}>×</button></header>
      <div className="wuxianpi-modal-body">
        <p className="settings-hint">Provider 凭据、默认模型和测试结果由 Termux 中的 Pi Runtime 持久化。本版本允许直接填写 API Key。</p>
        {loading && <div className="wuxianpi-state">正在读取模型…</div>}
        {notice && <div className={`wuxianpi-state ${notice.type === "error" ? "error" : "success"}`}><span>{notice.message}</span><button type="button" onClick={() => setNotice(null)}>关闭</button></div>}
        {!loading && providers.length === 0 && <div className="wuxianpi-state warning">Runtime 没有返回可配置的 Provider。</div>}
        <div className="settings-stack">
          {providers.map((provider) => <section className="settings-card" key={provider.id}>
            <header><div><strong>{provider.name || provider.id}</strong><small>{provider.authenticated ? `已登录${provider.authLabel ? ` · ${provider.authLabel}` : ""}` : "尚未登录"}</small></div><span className={`status-pill ${provider.authenticated ? "success" : "warning"}`}>{provider.authenticated ? "已配置" : "未配置"}</span></header>
            <div className="form-grid compact">
              <label className="span-2">API Key<input type="password" autoComplete="off" value={apiKeys[provider.id] ?? ""} onChange={(event) => setApiKeys((current) => ({ ...current, [provider.id]: event.target.value }))} placeholder="输入后保存到 Pi Runtime" /></label>
            </div>
            <div className="inline-actions">
              <button type="button" disabled={busy !== null || !(apiKeys[provider.id] ?? "").trim()} onClick={() => void run(`login:${provider.id}`, () => webApi.loginModel(provider.id, apiKeys[provider.id]!.trim()), `${provider.name || provider.id} 凭据已持久化`)}>{busy === `login:${provider.id}` ? "保存中…" : "保存并登录"}</button>
              <button type="button" className="danger-link" disabled={busy !== null || !provider.authenticated} onClick={() => void run(`logout:${provider.id}`, () => webApi.logoutModel(provider.id), `${provider.name || provider.id} 已登出`)}>{busy === `logout:${provider.id}` ? "登出中…" : "登出"}</button>
            </div>
            <div className="model-list-compact">{(groups.get(provider.id) ?? []).map((model) => {
              const isDefault = payload?.defaultModel?.provider === model.provider && payload.defaultModel.modelId === model.id;
              return <div key={`${model.provider}:${model.id}`} className="conversation-row">
                <span><strong>{model.name || model.id}</strong><small>{model.id} · {model.available === false ? "不可用" : "可用"}</small></span>
                <div className="inline-actions">
                  {isDefault ? <em>默认</em> : <button type="button" disabled={busy !== null} onClick={() => void run(`default:${model.provider}:${model.id}`, () => webApi.setDefaultModel(model.provider, model.id), `默认模型已设为 ${model.name}`)}>设为默认</button>}
                  <button type="button" disabled={busy !== null || model.available === false} onClick={() => void run(`test:${model.provider}:${model.id}`, () => webApi.testModel(model.provider, model.id), `${model.name} 连通测试通过`)}>{busy === `test:${model.provider}:${model.id}` ? "测试中…" : "测试"}</button>
                </div>
              </div>;
            })}</div>
          </section>)}
        </div>
      </div>
      <footer className="wuxianpi-modal-footer"><button type="button" className="secondary-button" disabled={busy !== null} onClick={() => void load()}>刷新</button><button type="button" className="primary-button" onClick={onClose}>完成</button></footer>
    </section>
  </div>;
}
