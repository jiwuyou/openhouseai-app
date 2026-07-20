"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  CapabilityCatalog,
  CapabilityDescriptor,
  GlobalWuxianPiConfigV1,
  McpServerConfig,
  PermissionStateData,
  TtsClientInstruction,
  TtsProfile,
  WebExtensionSummary,
} from "@/lib/wuxianpi/contracts";
import { WUXIANPI_SCHEMA_VERSION } from "@/lib/wuxianpi/contracts";
import { getPermissionState, mutatePermission, speakText, updateGlobalConfig } from "./api";
import { ExtensionHost } from "./ExtensionHost";
import { SkillsConfig } from "../SkillsConfig";

type Tab = "defaults" | "packages" | "mcp" | "tts" | "extensions" | "permissions" | "runtime";
type DefaultListField = "tools" | "skills" | "mcpServers" | "webExtensions";

interface Props {
  catalog: CapabilityCatalog | null;
  config: GlobalWuxianPiConfigV1 | null;
  extensions: WebExtensionSummary[];
  loading?: boolean;
  error?: string | null;
  onReload: () => void;
  onConfigChanged: (config: GlobalWuxianPiConfigV1) => void;
  onOpenModels: () => void;
  hostAssistantId?: string;
  hostAssistantPath?: string;
}

function emptyConfig(): GlobalWuxianPiConfigV1 {
  return { schemaVersion: WUXIANPI_SCHEMA_VERSION, defaults: { maxLiveSessions: 2, idleSessionMs: 120_000 }, mcpServers: [], ttsProfiles: [], permissions: [], ubuntu: { enabled: false, distro: "ubuntu", idleTimeoutMs: 300_000 } };
}

function sourceField(source: CapabilityDescriptor["source"]): DefaultListField | null {
  if (source === "skill") return "skills";
  if (source === "mcp") return "mcpServers";
  if (source === "web-extension") return "webExtensions";
  if (source === "tts") return null;
  return "tools";
}

function labelForSource(source: CapabilityDescriptor["source"]): string {
  return ({ "pi-builtin": "Pi 内置工具", "pi-extension": "Pi 扩展工具", skill: "Skills", mcp: "MCP 工具", tts: "TTS", "web-extension": "WebUI 扩展", ubuntu: "Ubuntu 工具" })[source];
}

function capabilitySelectionId(capability: CapabilityDescriptor): string {
  return capability.source === "skill" ? capability.id.replace(/^skill:/, "") : capability.id;
}

function browserSpeak(instruction: TtsClientInstruction): void {
  speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(instruction.text);
  utterance.rate = instruction.rate ?? 1;
  utterance.pitch = instruction.pitch ?? 1;
  if (instruction.voice) utterance.voice = speechSynthesis.getVoices().find((voice) => voice.name === instruction.voice || voice.voiceURI === instruction.voice) ?? null;
  speechSynthesis.speak(utterance);
}

export function CapabilityCenter({ catalog, config, extensions, loading, error, onReload, onConfigChanged, onOpenModels, hostAssistantId, hostAssistantPath }: Props) {
  const [tab, setTab] = useState<Tab>("defaults");
  const [draft, setDraft] = useState<GlobalWuxianPiConfigV1>(() => config ?? emptyConfig());
  const [saving, setSaving] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [permissionState, setPermissionState] = useState<PermissionStateData>({ pending: [], grants: [] });
  const [previewing, setPreviewing] = useState<string | null>(null);
  const [openPanel, setOpenPanel] = useState<{ extension: WebExtensionSummary; title: string; entry: string } | null>(null);
  const previewAudioRef = useRef<HTMLAudioElement | null>(null);

  useEffect(() => { if (config) setDraft(config); }, [config]);
  const reloadPermissions = useCallback(() => {
    void getPermissionState().then(setPermissionState).catch((reason) => setActionError(reason instanceof Error ? reason.message : String(reason)));
  }, []);
  useEffect(reloadPermissions, [reloadPermissions]);
  useEffect(() => () => { previewAudioRef.current?.pause(); if ("speechSynthesis" in window) speechSynthesis.cancel(); }, []);

  const grouped = useMemo(() => {
    const groups = new Map<CapabilityDescriptor["source"], CapabilityDescriptor[]>();
    for (const capability of catalog?.capabilities ?? []) {
      if (!capability.assistantSelectable || capability.source === "tts" || capability.source === "mcp" || capability.source === "web-extension" || capability.source === "ubuntu") continue;
      groups.set(capability.source, [...(groups.get(capability.source) ?? []), capability]);
    }
    return [...groups.entries()];
  }, [catalog]);

  const toggleDefault = (field: DefaultListField, id: string) => {
    setDraft((current) => {
      const values = new Set(current.defaults[field] ?? []);
      if (values.has(id)) values.delete(id); else values.add(id);
      return { ...current, defaults: { ...current.defaults, [field]: [...values] } };
    });
  };

  const save = async () => {
    setSaving(true); setActionError(null);
    try { const next = await updateGlobalConfig(draft); setDraft(next); onConfigChanged(next); }
    catch (reason) { setActionError(reason instanceof Error ? reason.message : String(reason)); }
    finally { setSaving(false); }
  };

  const persistDraft = async () => {
    const next = await updateGlobalConfig(draft);
    setDraft(next);
    onConfigChanged(next);
    return next;
  };

  const previewTts = async (profile: TtsProfile) => {
    setPreviewing(profile.id); setActionError(null); previewAudioRef.current?.pause();
    try {
      await persistDraft();
      const result = await speakText({ profileId: profile.id, text: "你好，我是 WuxianPi 的声音助手。", rate: profile.rate, pitch: profile.pitch, preview: true });
      if (result instanceof Blob) { const url = URL.createObjectURL(result); const audio = new Audio(url); previewAudioRef.current = audio; audio.onended = () => URL.revokeObjectURL(url); await audio.play(); }
      else if (result && "speechSynthesis" in window) browserSpeak(result);
    } catch (reason) { setActionError(reason instanceof Error ? reason.message : String(reason)); }
    finally { setPreviewing(null); }
  };

  const revoke = async (assistantId: string, capabilityId: string) => {
    try { setPermissionState(await mutatePermission({ action: "revoke", request: { assistantId, capabilityId } })); }
    catch (reason) { setActionError(reason instanceof Error ? reason.message : String(reason)); }
  };

  const decide = async (requestId: string, decision: "once" | "assistant" | "deny") => {
    try { setPermissionState(await mutatePermission({ action: "decide", request: { requestId, decision } })); }
    catch (reason) { setActionError(reason instanceof Error ? reason.message : String(reason)); }
  };

  const tabs: Array<[Tab, string]> = [["defaults", "默认能力"], ["packages", "Pi Packages"], ["mcp", "MCP 配置"], ["tts", "语音"], ["extensions", "WebUI"], ["permissions", "权限"], ["runtime", "运行时"]];
  const showSave = tab !== "permissions";
  const mcpAdapterInstalled = (catalog?.capabilities ?? []).some((item) => `${item.id} ${item.name}`.includes("pi-mcp-adapter") && item.status === "available");

  return (
    <div className="wuxianpi-page capability-center-page">
      <header className="wuxianpi-page-header"><div><span className="eyebrow">CAPABILITY CENTER</span><h1>能力中心</h1><p>全局注册、测试并选择默认能力；助手可继续单独覆盖。</p></div><button className="secondary-button" onClick={onReload}>刷新</button></header>
      <nav className="wuxianpi-segmented capability-tabs">{tabs.map(([id, label]) => <button key={id} className={tab === id ? "active" : ""} onClick={() => setTab(id)}>{label}</button>)}</nav>
      {loading && <div className="wuxianpi-state">正在发现全局能力…</div>}
      {(error || actionError) && <div className="wuxianpi-state error"><span>{actionError ?? error}</span><button onClick={() => setActionError(null)}>关闭</button></div>}

      {!loading && tab === "defaults" && <div className="settings-stack">
        <button type="button" className="settings-card model-settings-button" onClick={onOpenModels}><span><strong>模型服务与默认模型</strong><small>Provider、API Key、模型与思考等级</small></span><em>›</em></button>
        {grouped.map(([source, capabilities]) => {
          const field = sourceField(source); if (!field) return null;
          return <section className="settings-card" key={source}><header><div><strong>{labelForSource(source)}</strong><small>选中后成为新助手的默认候选</small></div></header><div className="default-picker-grid">{capabilities.map((capability) => { const selectionId = capabilitySelectionId(capability); return <label key={capability.id} className={capability.status !== "available" ? "unavailable" : ""}><input type="checkbox" disabled={capability.status !== "available"} checked={(draft.defaults[field] ?? []).includes(selectionId)} onChange={() => toggleDefault(field, selectionId)} /><span><strong>{capability.name}</strong><small>{capability.description ?? capability.id}</small></span></label>; })}</div></section>;
        })}
        <section className="settings-card"><header><div><strong>默认 MCP 服务</strong><small>注册后还需在这里选择</small></div></header><div className="default-picker-grid">{draft.mcpServers.map((server) => <label key={server.id}><input type="checkbox" checked={(draft.defaults.mcpServers ?? []).includes(server.id)} onChange={() => toggleDefault("mcpServers", server.id)} /><span><strong>{server.name}</strong><small>{server.id}</small></span></label>)}</div></section>
        <section className="settings-card"><header><div><strong>默认声音</strong><small>助手选择“继承”时使用</small></div></header><select className="wide-select" value={draft.defaults.tts?.profileId ?? ""} onChange={(event) => setDraft((current) => ({ ...current, defaults: { ...current.defaults, tts: event.target.value ? { ...current.defaults.tts, profileId: event.target.value } : undefined } }))}><option value="">浏览器后备语音</option>{draft.ttsProfiles.filter((profile) => profile.enabled !== false).map((profile) => <option key={profile.id} value={profile.id}>{profile.name}</option>)}</select></section>
        <section className="settings-card"><header><div><strong>默认思考等级</strong><small>助手选择“继承”时使用</small></div></header><select className="wide-select" value={draft.defaults.thinkingLevel ?? ""} onChange={(event) => setDraft((current) => ({ ...current, defaults: { ...current.defaults, thinkingLevel: event.target.value || undefined } }))}><option value="">跟随 Pi</option><option value="off">关闭</option><option value="low">低</option><option value="medium">中</option><option value="high">高</option><option value="xhigh">极高</option></select></section>
      </div>}

      {!loading && tab === "packages" && <><SkillsConfig cwd={hostAssistantPath} onChanged={onReload} /><section className="settings-card"><header><div><strong>已发现 Package 能力</strong><small>工具、MCP、浏览器、记忆和增强 Web UI 都由 Pi package 提供。</small></div></header><div className="default-picker-grid">{(catalog?.capabilities ?? []).filter((item) => item.source !== "pi-builtin").map((item) => <div key={item.id} className={item.status !== "available" ? "unavailable" : ""}><span><strong>{item.name}</strong><small>{labelForSource(item.source)} · {item.description ?? item.id}</small></span><em className={`status-pill ${item.status === "available" ? "success" : "warning"}`}>{item.status}</em></div>)}</div></section></>}

      {!loading && tab === "mcp" && <div className="settings-stack">
        <section className="settings-card"><header><div><strong>标准 MCP 配置</strong><small>保存后写入 Pi 标准 mcp.json；实际工具发现与执行由 pi-mcp-adapter 负责。</small></div><span className={`status-pill ${mcpAdapterInstalled ? "success" : "warning"}`}>{mcpAdapterInstalled ? "adapter 已安装" : "需安装 pi-mcp-adapter"}</span></header></section>
        {draft.mcpServers.map((server, index) => <section key={`${server.id}-${index}`} className="settings-card">
          <header><div><strong>{server.name || server.id || `MCP ${index + 1}`}</strong><small>{server.transport} · {server.enabled === false ? "已停用" : "启用"}</small></div><button type="button" className="danger-link" onClick={() => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.filter((_, itemIndex) => itemIndex !== index) }))}>删除</button></header>
          <div className="form-grid compact">
            <label>ID<input value={server.id} onChange={(event) => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.map((item, itemIndex) => itemIndex === index ? { ...item, id: event.target.value } : item) }))} /></label>
            <label>名称<input value={server.name} onChange={(event) => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.map((item, itemIndex) => itemIndex === index ? { ...item, name: event.target.value } : item) }))} /></label>
            <label>传输<select value={server.transport} onChange={(event) => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.map((item, itemIndex) => itemIndex === index ? { ...item, transport: event.target.value as McpServerConfig["transport"] } : item) }))}><option value="stdio">stdio</option><option value="streamable-http">streamable-http</option></select></label>
            <label>状态<select value={server.enabled === false ? "disabled" : "enabled"} onChange={(event) => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.map((item, itemIndex) => itemIndex === index ? { ...item, enabled: event.target.value === "enabled" } : item) }))}><option value="enabled">启用</option><option value="disabled">停用</option></select></label>
            {server.transport === "stdio" ? <><label className="span-2">命令<input value={server.command ?? ""} onChange={(event) => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.map((item, itemIndex) => itemIndex === index ? { ...item, command: event.target.value } : item) }))} /></label><label className="span-2">参数（每行一个）<textarea rows={3} value={(server.args ?? []).join("\n")} onChange={(event) => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.map((item, itemIndex) => itemIndex === index ? { ...item, args: event.target.value.split("\n").map((value) => value.trim()).filter(Boolean) } : item) }))} /></label></> : <label className="span-2">URL<input value={server.url ?? ""} onChange={(event) => setDraft((current) => ({ ...current, mcpServers: current.mcpServers.map((item, itemIndex) => itemIndex === index ? { ...item, url: event.target.value } : item) }))} /></label>}
          </div>
        </section>)}
        <button type="button" className="add-card-button" onClick={() => setDraft((current) => ({ ...current, mcpServers: [...current.mcpServers, { id: `mcp-${current.mcpServers.length + 1}`, name: `MCP ${current.mcpServers.length + 1}`, transport: "stdio", command: "", args: [], enabled: true }] }))}>＋ 新增 MCP Server</button>
      </div>}

      {!loading && tab === "tts" && <div className="settings-stack">{draft.ttsProfiles.map((profile, index) => <section key={`${profile.id}-${index}`} className="settings-card"><header><div><strong>{profile.name}</strong><small>{profile.provider}</small></div><div className="inline-actions"><button disabled={previewing !== null} onClick={() => void previewTts(profile)}>{previewing === profile.id ? "试听中…" : "试听"}</button><button className="danger-link" onClick={() => setDraft((current) => ({ ...current, ttsProfiles: current.ttsProfiles.filter((_, i) => i !== index) }))}>移除</button></div></header><div className="form-grid compact"><label>ID<input value={profile.id} onChange={(event) => setDraft((current) => ({ ...current, ttsProfiles: current.ttsProfiles.map((item, i) => i === index ? { ...item, id: event.target.value } : item) }))} /></label><label>名称<input value={profile.name} onChange={(event) => setDraft((current) => ({ ...current, ttsProfiles: current.ttsProfiles.map((item, i) => i === index ? { ...item, name: event.target.value } : item) }))} /></label><label>Provider<select value={profile.provider} onChange={(event) => setDraft((current) => ({ ...current, ttsProfiles: current.ttsProfiles.map((item, i) => i === index ? { ...item, provider: event.target.value as TtsProfile["provider"] } : item) }))}><option value="browser-speech">浏览器</option><option value="termux-api">Termux API</option><option value="openai-compatible">OpenAI-compatible</option><option value="http">HTTP</option></select></label><label>Voice<input value={profile.voice ?? ""} onChange={(event) => setDraft((current) => ({ ...current, ttsProfiles: current.ttsProfiles.map((item, i) => i === index ? { ...item, voice: event.target.value } : item) }))} /></label>{!(["browser-speech", "termux-api"] as string[]).includes(profile.provider) && <label className="span-2">Base URL<input value={profile.baseUrl ?? ""} onChange={(event) => setDraft((current) => ({ ...current, ttsProfiles: current.ttsProfiles.map((item, i) => i === index ? { ...item, baseUrl: event.target.value } : item) }))} /></label>}</div></section>)}<button className="add-card-button" onClick={() => setDraft((current) => ({ ...current, ttsProfiles: [...current.ttsProfiles, { id: `voice-${current.ttsProfiles.length + 1}`, name: `声音 ${current.ttsProfiles.length + 1}`, provider: "browser-speech", rate: 1, pitch: 1, enabled: true }] }))}>＋ 添加声音</button></div>}

      {!loading && tab === "extensions" && <div className="extension-grid">{extensions.length === 0 && <div className="wuxianpi-state">暂无 WebUI 扩展。</div>}{extensions.map((extension) => { const enabledByDefault = (draft.defaults.webExtensions ?? []).includes(extension.id); return <article key={extension.id} className="extension-card"><div className="extension-card-icon">⌁</div><div><strong>{extension.manifest.name}</strong><small>{extension.manifest.description ?? extension.id}</small><span>v{extension.manifest.version} · {extension.enabled ? "可用" : "不可用"}</span></div><label className="extension-default-toggle"><input type="checkbox" disabled={!extension.enabled} checked={enabledByDefault} onChange={() => toggleDefault("webExtensions", extension.id)} />新助手默认启用</label><div className="extension-card-actions">{extension.manifest.contributes?.settingsPanels?.map((panel) => <button key={panel.id} disabled={!hostAssistantId} onClick={() => setOpenPanel({ extension, title: panel.title, entry: panel.entry })}>{panel.title}</button>)}{extension.manifest.contributes?.fullPages?.map((page) => <button key={page.id} disabled={!hostAssistantId} onClick={() => setOpenPanel({ extension, title: page.title, entry: page.entry })}>{page.title}</button>)}</div></article>; })}</div>}

      {!loading && tab === "permissions" && <div className="settings-stack"><section className="settings-card"><header><div><strong>待确认请求</strong><small>在对应助手聊天中也会弹出确认</small></div><span className="status-pill warning">{permissionState.pending.length}</span></header>{permissionState.pending.map((request) => <div key={request.id} className="permission-row"><span><strong>{request.title}</strong><small>{request.assistantId} · {request.capabilityId}</small></span><div><button onClick={() => void decide(request.id, "deny")}>拒绝</button><button onClick={() => void decide(request.id, "once")}>仅一次</button><button onClick={() => void decide(request.id, "assistant")}>允许助手</button></div></div>)}{permissionState.pending.length === 0 && <p className="muted-copy">没有待确认请求。</p>}</section><section className="settings-card"><header><div><strong>长期授权</strong><small>可以随时撤销 assistant/deny 决策</small></div></header>{permissionState.grants.map((grant) => <div key={`${grant.assistantId}:${grant.capabilityId}`} className="permission-row"><span><strong>{grant.capabilityId}</strong><small>{grant.assistantId} · {grant.decision}</small></span><button className="danger-link" onClick={() => void revoke(grant.assistantId, grant.capabilityId)}>撤销</button></div>)}{permissionState.grants.length === 0 && <p className="muted-copy">尚无长期授权。</p>}</section></div>}

      {!loading && tab === "runtime" && <div className="settings-stack"><section className="settings-card"><header><div><strong>Termux 原生运行时</strong><small>WuxianPi、Pi Runtime 与 Android Bridge</small></div><span className="status-pill success">默认</span></header></section><section className="settings-card"><header><div><strong>Ubuntu Tool Worker</strong><small>只在 glibc、Chromium 或重型 Python 需要时启动</small></div><label className="switch"><input type="checkbox" checked={draft.ubuntu?.enabled ?? false} onChange={(event) => setDraft((current) => ({ ...current, ubuntu: { ...current.ubuntu, enabled: event.target.checked } }))} /><span /></label></header><div className="form-grid compact"><label>发行版<input value={draft.ubuntu?.distro ?? "ubuntu"} onChange={(event) => setDraft((current) => ({ ...current, ubuntu: { enabled: current.ubuntu?.enabled ?? false, ...current.ubuntu, distro: event.target.value } }))} /></label><label>空闲退出（毫秒）<input type="number" value={draft.ubuntu?.idleTimeoutMs ?? 300000} onChange={(event) => setDraft((current) => ({ ...current, ubuntu: { enabled: current.ubuntu?.enabled ?? false, ...current.ubuntu, idleTimeoutMs: Number(event.target.value) } }))} /></label><label>最大活动会话<input type="number" min="1" max="8" value={draft.defaults.maxLiveSessions ?? 2} onChange={(event) => setDraft((current) => ({ ...current, defaults: { ...current.defaults, maxLiveSessions: Number(event.target.value) } }))} /></label><label>会话空闲回收<input type="number" value={draft.defaults.idleSessionMs ?? 120000} onChange={(event) => setDraft((current) => ({ ...current, defaults: { ...current.defaults, idleSessionMs: Number(event.target.value) } }))} /></label></div></section></div>}

      {showSave && <div className="sticky-save-bar"><span>保存后应用于新会话。</span><button className="primary-button" disabled={saving} onClick={() => void save()}>{saving ? "保存中…" : "保存全局配置"}</button></div>}
      {openPanel && <div className="wuxianpi-modal-backdrop"><div className="extension-modal"><ExtensionHost extension={openPanel.extension} entry={openPanel.entry} assistantId={hostAssistantId} title={openPanel.title} onClose={() => setOpenPanel(null)} fallback={<p>扩展的通用工具仍可使用。</p>} /></div></div>}
    </div>
  );
}
