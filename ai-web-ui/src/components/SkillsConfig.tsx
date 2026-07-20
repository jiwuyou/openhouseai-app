import { useCallback, useEffect, useState } from "react";
import { webApi } from "@/lib/web-api-client";

type InstalledSkill = { name: string; description?: string; filePath?: string; source?: string };
type PackageSearchRow = { name: string; version?: string; description?: string; publisher?: unknown; date?: string };

/** Adapted from the original WuxianPi SkillsConfig for the Runtime Pi Package API. */
export function SkillsConfig({ cwd, onChanged }: { cwd?: string; onChanged?: () => void }) {
  const [skills, setSkills] = useState<InstalledSkill[]>([]);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<PackageSearchRow[]>([]);
  const [scope, setScope] = useState<"user" | "project">("user");
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ type: "success" | "error"; message: string } | null>(null);

  const load = useCallback(async () => {
    setBusy("load");
    try {
      const data = await webApi.skills(cwd);
      setSkills(Array.isArray(data.skills) ? data.skills as InstalledSkill[] : []);
    } catch (reason) {
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
    } finally { setBusy(null); }
  }, [cwd]);
  useEffect(() => { void load(); }, [load]);

  const search = async () => {
    if (!query.trim()) return;
    setBusy("search"); setNotice(null);
    try {
      const data = await webApi.searchPackages(query.trim());
      setResults(Array.isArray(data.packages) ? data.packages as PackageSearchRow[] : []);
    } catch (reason) {
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
    } finally { setBusy(null); }
  };

  const install = async (source: string) => {
    setBusy(`install:${source}`); setNotice(null);
    try {
      const data = await webApi.installPackage(source, { cwd, local: scope === "project" });
      const installedPath = typeof data.installedPath === "string" ? `：${data.installedPath}` : "";
      setNotice({ type: "success", message: `${source} 已安装${installedPath}` });
      await load();
      onChanged?.();
    } catch (reason) {
      setNotice({ type: "error", message: reason instanceof Error ? reason.message : String(reason) });
    } finally { setBusy(null); }
  };

  const installedNames = new Set(skills.map((skill) => skill.name));
  return <div className="settings-stack">
    <section className="settings-card">
      <header><div><strong>已安装 Skills / Pi Packages</strong><small>Runtime 会在安装完成后重新加载活动会话。</small></div><button type="button" disabled={busy !== null} onClick={() => void load()}>{busy === "load" ? "刷新中…" : "刷新"}</button></header>
      <div className="default-picker-grid">{skills.map((skill) => <div key={skill.filePath || skill.name}><span><strong>{skill.name}</strong><small>{skill.description || skill.filePath || "Pi Skill"}</small></span><em className="status-pill success">已安装</em></div>)}</div>
      {skills.length === 0 && busy !== "load" && <p className="muted-copy">尚未发现已安装 Skill。</p>}
    </section>
    <section className="settings-card">
      <header><div><strong>搜索 Pi Package</strong><small>搜索 npm package；执行和工具生命周期仍由 Pi 扩展系统负责。</small></div></header>
      <div className="form-grid compact">
        <label className="span-2">包名或关键词<input value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") void search(); }} placeholder="例如 pi-mcp-adapter" /></label>
        <label>安装范围<select value={scope} onChange={(event) => setScope(event.target.value as "user" | "project")}><option value="user">用户全局</option><option value="project" disabled={!cwd}>当前助手项目</option></select></label>
        <button type="button" className="primary-button" disabled={busy !== null || !query.trim()} onClick={() => void search()}>{busy === "search" ? "搜索中…" : "搜索"}</button>
      </div>
      {notice && <div className={`wuxianpi-state ${notice.type === "error" ? "error" : "success"}`}><span>{notice.message}</span><button type="button" onClick={() => setNotice(null)}>关闭</button></div>}
      <div className="model-list-compact">{results.map((item) => <div key={`${item.name}:${item.version ?? ""}`} className="conversation-row"><span><strong>{item.name}</strong><small>{item.version ? `v${item.version} · ` : ""}{item.description || "Pi package"}</small></span><button type="button" disabled={busy !== null || installedNames.has(item.name)} onClick={() => void install(item.name)}>{installedNames.has(item.name) ? "已安装" : busy === `install:${item.name}` ? "安装中…" : "安装"}</button></div>)}</div>
      {results.length === 0 && query && busy !== "search" && <p className="muted-copy">没有搜索结果，或尚未执行搜索。</p>}
    </section>
  </div>;
}
