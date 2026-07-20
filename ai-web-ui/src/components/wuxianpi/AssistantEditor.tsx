"use client";

import { useEffect, useMemo, useState } from "react";
import type {
  AssistantFiles,
  AssistantManifestV1,
  AssistantSummary,
  CapabilityCatalog,
  CapabilityDescriptor,
  GlobalWuxianPiConfigV1,
} from "@/lib/wuxianpi/contracts";
import { WUXIANPI_SCHEMA_VERSION } from "@/lib/wuxianpi/contracts";
import { createAssistant, getAssistant, updateAssistant } from "./api";

interface Props {
  assistant?: AssistantSummary | null;
  catalog?: CapabilityCatalog | null;
  config?: GlobalWuxianPiConfigV1 | null;
  onClose: () => void;
  onSaved: (assistant: AssistantSummary) => void;
}

type EditorTab = "identity" | "role" | "capabilities" | "voice";

const EMPTY_FILES: AssistantFiles = { agents: "", memory: "", workspaces: "" };

function defaultManifest(): AssistantManifestV1 {
  return {
    schemaVersion: WUXIANPI_SCHEMA_VERSION,
    name: "",
    description: "",
    greeting: "你好，今天想聊些什么？",
    starterPrompts: [],
    model: "inherit",
    thinkingLevel: "inherit",
    tools: "inherit",
    skills: "inherit",
    mcpServers: "inherit",
    webExtensions: "inherit",
    tts: "inherit",
  };
}

function listValue(value: string[] | "inherit" | undefined): string[] {
  return Array.isArray(value) ? value : [];
}

function capabilityField(source: CapabilityDescriptor["source"]): "tools" | "skills" | "mcpServers" | "webExtensions" | null {
  if (source === "skill") return "skills";
  if (source === "mcp") return "mcpServers";
  if (source === "web-extension") return "webExtensions";
  if (source === "tts") return null;
  return "tools";
}

function capabilitySelectionId(capability: CapabilityDescriptor): string {
  if (capability.source === "skill") return capability.id.replace(/^skill:/, "");
  if (capability.source === "mcp") return capability.id.replace(/^mcp:/, "");
  if (capability.source === "web-extension") return capability.id.replace(/^web-extension:/, "");
  return capability.id;
}

function humanSource(source: CapabilityDescriptor["source"]): string {
  return ({
    "pi-builtin": "Pi 内置",
    "pi-extension": "Pi 扩展",
    skill: "Skills",
    mcp: "MCP",
    tts: "语音",
    "web-extension": "WebUI 扩展",
    ubuntu: "Ubuntu Worker",
  })[source];
}

export function AssistantEditor({ assistant, catalog, config, onClose, onSaved }: Props) {
  const [tab, setTab] = useState<EditorTab>("identity");
  const [id, setId] = useState(assistant?.id ?? "");
  const [manifest, setManifest] = useState<AssistantManifestV1>(assistant?.manifest ?? defaultManifest());
  const [files, setFiles] = useState<AssistantFiles>(EMPTY_FILES);
  const [loading, setLoading] = useState(!!assistant);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!assistant) return;
    let cancelled = false;
    setLoading(true);
    getAssistant(assistant.id)
      .then((detail) => {
        if (cancelled) return;
        setManifest(detail.assistant.manifest);
        setFiles(detail.files);
      })
      .catch((reason) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [assistant]);

  const grouped = useMemo(() => {
    const groups = new Map<CapabilityDescriptor["source"], CapabilityDescriptor[]>();
    for (const capability of catalog?.capabilities ?? []) {
      if (!capability.assistantSelectable || capability.source === "tts" || capability.source === "ubuntu") continue;
      const current = groups.get(capability.source) ?? [];
      current.push(capability);
      groups.set(capability.source, current);
    }
    return [...groups.entries()];
  }, [catalog]);

  const patchManifest = <K extends keyof AssistantManifestV1>(key: K, value: AssistantManifestV1[K]) => {
    setManifest((current) => ({ ...current, [key]: value }));
  };

  const toggleCapability = (capability: CapabilityDescriptor) => {
    const field = capabilityField(capability.source);
    if (!field) return;
    setManifest((current) => {
      const selected = new Set(listValue(current[field]));
      const selectionId = capabilitySelectionId(capability);
      if (selected.has(selectionId)) selected.delete(selectionId);
      else selected.add(selectionId);
      return { ...current, [field]: [...selected] };
    });
  };

  const save = async () => {
    const normalizedId = id.trim().toLowerCase();
    if (!assistant && !/^[a-z0-9][a-z0-9_-]{1,63}$/.test(normalizedId)) {
      setError("助手 ID 需为 2–64 位小写字母、数字、- 或 _");
      return;
    }
    if (!manifest.name.trim()) {
      setError("请填写助手名称");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const saved = assistant
        ? await updateAssistant(assistant.id, { manifest, files })
        : await createAssistant({ id: normalizedId, manifest, files });
      onSaved(saved);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setSaving(false);
    }
  };

  const selectedTts = manifest.tts === "inherit" ? {} : (manifest.tts ?? {});

  return (
    <div className="wuxianpi-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="wuxianpi-editor" role="dialog" aria-modal="true" aria-label={assistant ? "编辑助手" : "创建助手"}>
        <header className="wuxianpi-editor-header">
          <div><span className="eyebrow">ASSISTANT</span><h2>{assistant ? "编辑助手" : "创建助手"}</h2></div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="关闭">×</button>
        </header>
        <nav className="wuxianpi-segmented" aria-label="助手设置分组">
          {(["identity", "role", "capabilities", "voice"] as EditorTab[]).map((item) => (
            <button key={item} type="button" className={tab === item ? "active" : ""} onClick={() => setTab(item)}>
              {item === "identity" ? "资料" : item === "role" ? "角色与记忆" : item === "capabilities" ? "能力" : "声音"}
            </button>
          ))}
        </nav>
        <div className="wuxianpi-editor-body">
          {loading ? <div className="wuxianpi-state">正在读取助手目录…</div> : null}
          {!loading && tab === "identity" && (
            <div className="form-grid">
              <label>助手 ID<input value={id} disabled={!!assistant} onChange={(event) => setId(event.target.value)} placeholder="writing-partner" /></label>
              <label>显示名称<input value={manifest.name} onChange={(event) => patchManifest("name", event.target.value)} placeholder="写作搭档" /></label>
              <label className="span-2">简介<textarea value={manifest.description ?? ""} onChange={(event) => patchManifest("description", event.target.value)} rows={2} /></label>
              <label>头像路径或 URL<input value={manifest.avatar ?? ""} onChange={(event) => patchManifest("avatar", event.target.value)} placeholder="avatar.png" /></label>
              <label>开场白<input value={manifest.greeting ?? ""} onChange={(event) => patchManifest("greeting", event.target.value)} /></label>
              <label className="span-2">开场问题（每行一个）<textarea value={(manifest.starterPrompts ?? []).join("\n")} onChange={(event) => patchManifest("starterPrompts", event.target.value.split("\n").map((line) => line.trim()).filter(Boolean))} rows={4} /></label>
              <label>模型 Provider<input value={manifest.model === "inherit" ? "" : (manifest.model?.provider ?? "")} onChange={(event) => patchManifest("model", event.target.value ? { provider: event.target.value, modelId: manifest.model === "inherit" ? "" : (manifest.model?.modelId ?? "") } : "inherit")} placeholder="留空则继承" /></label>
              <label>模型 ID<input value={manifest.model === "inherit" ? "" : (manifest.model?.modelId ?? "")} onChange={(event) => patchManifest("model", event.target.value ? { provider: manifest.model === "inherit" ? "" : (manifest.model?.provider ?? ""), modelId: event.target.value } : "inherit")} placeholder="留空则继承" /></label>
              <label>思考等级<select value={manifest.thinkingLevel ?? "inherit"} onChange={(event) => patchManifest("thinkingLevel", event.target.value)}><option value="inherit">继承全局</option><option value="off">关闭</option><option value="low">低</option><option value="medium">中</option><option value="high">高</option><option value="xhigh">极高</option></select></label>
            </div>
          )}
          {!loading && tab === "role" && (
            <div className="role-file-stack">
              <label><span>AGENTS.md <small>身份、行为和边界</small></span><textarea value={files.agents} onChange={(event) => setFiles((current) => ({ ...current, agents: event.target.value }))} rows={11} placeholder="# 身份\n\n你是…" /></label>
              <label><span>MEMORY.md <small>跨会话长期记忆</small></span><textarea value={files.memory} onChange={(event) => setFiles((current) => ({ ...current, memory: event.target.value }))} rows={7} /></label>
              <label><span>WORKSPACES.md <small>用文字说明外部工作区路径与规则</small></span><textarea value={files.workspaces} onChange={(event) => setFiles((current) => ({ ...current, workspaces: event.target.value }))} rows={7} placeholder="## 小说项目\n路径：/data/data/…" /></label>
            </div>
          )}
          {!loading && tab === "capabilities" && (
            <div className="capability-picker">
              {!catalog && <div className="wuxianpi-state warning">能力目录暂不可用。保存角色资料不受影响。</div>}
              {grouped.map(([source, items]) => (
                <section key={source}>
                  <h3>{humanSource(source)}</h3>
                  <div className="capability-list">
                    {items.map((capability) => {
                      const field = capabilityField(source);
                      const selected = field ? listValue(manifest[field]).includes(capabilitySelectionId(capability)) : false;
                      return (
                        <label key={capability.id} className={`capability-row ${capability.status !== "available" ? "unavailable" : ""}`}>
                          <input type="checkbox" checked={selected} disabled={capability.status !== "available"} onChange={() => toggleCapability(capability)} />
                          <span><strong>{capability.name}</strong><small>{capability.description ?? capability.id}</small></span>
                          <em>{capability.risk.join(" · ") || "safe"}</em>
                        </label>
                      );
                    })}
                  </div>
                </section>
              ))}
            </div>
          )}
          {!loading && tab === "voice" && (
            <div className="form-grid">
              <label className="span-2">声音<select value={selectedTts.profileId ?? ""} onChange={(event) => patchManifest("tts", event.target.value ? { ...selectedTts, profileId: event.target.value } : "inherit")}><option value="">继承全局</option>{(config?.ttsProfiles ?? []).filter((profile) => profile.enabled !== false).map((profile) => <option key={profile.id} value={profile.id}>{profile.name} · {profile.provider}</option>)}</select></label>
              <label>语速<input type="range" min="0.5" max="2" step="0.05" value={selectedTts.rate ?? 1} onChange={(event) => patchManifest("tts", { ...selectedTts, rate: Number(event.target.value) })} /><output>{selectedTts.rate ?? 1}×</output></label>
              <label>音调<input type="range" min="0.5" max="2" step="0.05" value={selectedTts.pitch ?? 1} onChange={(event) => patchManifest("tts", { ...selectedTts, pitch: Number(event.target.value) })} /><output>{selectedTts.pitch ?? 1}</output></label>
              <label className="check-row"><input type="checkbox" checked={selectedTts.autoSpeak ?? false} onChange={(event) => patchManifest("tts", { ...selectedTts, autoSpeak: event.target.checked })} />回复完成后自动朗读</label>
              <label className="check-row"><input type="checkbox" checked={selectedTts.readCode ?? false} onChange={(event) => patchManifest("tts", { ...selectedTts, readCode: event.target.checked })} />朗读代码块</label>
            </div>
          )}
        </div>
        {error && <div className="wuxianpi-form-error">{error}</div>}
        <footer className="wuxianpi-editor-footer"><button type="button" className="secondary-button" onClick={onClose}>取消</button><button type="button" className="primary-button" disabled={saving || loading} onClick={() => void save()}>{saving ? "保存中…" : "保存助手"}</button></footer>
      </section>
    </div>
  );
}
