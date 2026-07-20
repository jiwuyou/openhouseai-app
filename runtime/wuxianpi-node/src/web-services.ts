import { createReadStream } from "node:fs";
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { access, lstat, mkdir, readFile, readdir, realpath, rename, rm, stat, writeFile } from "node:fs/promises";
import { basename, dirname, extname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { promisify } from "node:util";
import { DefaultPackageManager, DefaultResourceLoader } from "@earendil-works/pi-coding-agent";
import { strFromU8, unzipSync, zipSync } from "fflate";
import { RequestError } from "./protocol.js";
import type { SessionRegistry } from "./session-registry.js";

const SAFE_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const DEFAULT_ASSISTANT = {
  schemaVersion: 1,
  name: "WuxianPi",
  description: "默认个人助手",
  greeting: "你好，我是 WuxianPi。今天想聊些什么？",
  starterPrompts: ["帮我整理今天的待办", "解释一个概念", "帮我写一段文字"],
  model: "inherit",
  thinkingLevel: "inherit",
  tools: "inherit",
  skills: "inherit",
  webExtensions: "inherit",
};
const DEFAULT_CONFIG = {
  schemaVersion: 1,
  defaults: {
    thinkingLevel: "medium",
    tools: ["read", "bash", "edit", "write", "grep", "find", "ls"],
    skills: [], mcpServers: [], webExtensions: [], maxLiveSessions: 2, idleSessionMs: 120_000,
  },
  mcpServers: [],
  ttsProfiles: [
    { id: "browser:default", name: "Browser speech", provider: "browser-speech", enabled: true },
    { id: "termux:default", name: "Android system voice", provider: "termux-api", enabled: true },
  ],
  permissions: [],
  ubuntu: { enabled: false, distro: "ubuntu", nodePath: "node", idleTimeoutMs: 300_000 },
};
const execFileAsync = promisify(execFile);

export interface WebServicesOptions {
  agentDir: string;
  registry: SessionRegistry;
}

export class WebServices {
  readonly assistantsRoot: string;
  readonly legacyExtensionsRoot: string;
  private readonly nonces = new Map<string, { extensionId: string; assistantId: string; expiresAt: number }>();
  private readonly pendingPermissions = new Map<string, Record<string, unknown>>();

  constructor(private readonly options: WebServicesOptions) {
    this.assistantsRoot = join(options.agentDir, "assistants");
    this.legacyExtensionsRoot = join(options.agentDir, "wuxianpi", "extensions");
  }

  async listAssistants(includeArchived = false) {
    await this.ensureDefaultAssistant();
    const sessions = (await this.options.registry.list({ all: true, offset: 0, limit: 1000 })).sessions;
    const entries = await readdir(this.assistantsRoot, { withFileTypes: true });
    const rows: Array<{
      id: string; path: string; manifest: Record<string, unknown>; sessionCount: number;
      lastActiveAt?: string; diagnostics: Array<Record<string, unknown>>; files?: Record<string, string>;
    }> = [];
    for (const entry of entries) {
      if (!entry.isDirectory() || !SAFE_ID.test(entry.name)) continue;
      try {
        const row = await this.getAssistant(entry.name, sessions);
        if (includeArchived || row.manifest.archived !== true) rows.push(row);
      } catch (error) {
        rows.push({
          id: entry.name,
          path: join(this.assistantsRoot, entry.name),
          manifest: { schemaVersion: 1, name: entry.name },
          sessionCount: 0,
          diagnostics: [{ level: "error", code: "assistant.invalid", message: errorMessage(error) }],
        });
      }
    }
    return rows.sort((left, right) => (right.lastActiveAt ?? "").localeCompare(left.lastActiveAt ?? "") ||
      String(left.manifest.name).localeCompare(String(right.manifest.name)));
  }

  async getAssistant(id: string, knownSessions?: Awaited<ReturnType<SessionRegistry["list"]>>["sessions"]) {
    assertSafeId(id, "assistant id");
    const directory = join(this.assistantsRoot, id);
    const info = await lstat(directory);
    if (!info.isDirectory() || info.isSymbolicLink()) throw new RequestError("assistant_not_found", `Assistant not found: ${id}`);
    const manifest = JSON.parse(await readFile(join(directory, "assistant.json"), "utf8")) as Record<string, unknown>;
    validateAssistantManifest(manifest);
    const sessions = knownSessions ?? (await this.options.registry.list({ all: true, offset: 0, limit: 1000 })).sessions;
    const owned = sessions.filter((session) => resolve(session.cwd) === resolve(directory));
    return {
      id,
      path: directory,
      manifest,
      files: {
        agents: await readOptional(join(directory, "AGENTS.md"), ""),
        memory: await readOptional(join(directory, "MEMORY.md"), ""),
        workspaces: await readOptional(join(directory, "WORKSPACES.md"), ""),
      },
      sessionCount: owned.length,
      lastActiveAt: owned.map((session) => session.modifiedAt).sort().at(-1),
      diagnostics: [],
    };
  }

  async createAssistant(body: Record<string, unknown>) {
    const id = String(body.id ?? "");
    assertSafeId(id, "assistant id");
    const manifest = asRecord(body.manifest, "manifest");
    validateAssistantManifest(manifest);
    const directory = join(this.assistantsRoot, id);
    await mkdir(this.assistantsRoot, { recursive: true, mode: 0o700 });
    await mkdir(directory, { mode: 0o700 });
    try {
      const files = body.files && typeof body.files === "object" ? body.files as Record<string, unknown> : {};
      await Promise.all([
        writeJson(join(directory, "assistant.json"), manifest),
        writeFile(join(directory, "AGENTS.md"), stringOr(files.agents, "# Identity\n\nYou are a helpful assistant.\n"), { mode: 0o600 }),
        writeFile(join(directory, "MEMORY.md"), stringOr(files.memory, "# Long-term memory\n"), { mode: 0o600 }),
        writeFile(join(directory, "WORKSPACES.md"), stringOr(files.workspaces, "# External workspaces\n"), { mode: 0o600 }),
        mkdir(join(directory, ".pi", "skills"), { recursive: true, mode: 0o700 }),
        mkdir(join(directory, ".pi", "extensions"), { recursive: true, mode: 0o700 }),
      ]);
      return this.getAssistant(id);
    } catch (error) {
      await rm(directory, { recursive: true, force: true });
      throw error;
    }
  }

  async updateAssistant(id: string, body: Record<string, unknown>) {
    const current = await this.getAssistant(id);
    if (body.manifest !== undefined) {
      const updates = asRecord(body.manifest, "manifest");
      const manifest = { ...current.manifest, ...updates, schemaVersion: 1 };
      validateAssistantManifest(manifest);
      await writeJson(join(current.path, "assistant.json"), manifest);
    }
    if (body.files && typeof body.files === "object") {
      const files = body.files as Record<string, unknown>;
      for (const [field, filename] of [["agents", "AGENTS.md"], ["memory", "MEMORY.md"], ["workspaces", "WORKSPACES.md"]] as const) {
        if (typeof files[field] === "string") await writeFile(join(current.path, filename), files[field], { mode: 0o600 });
      }
    }
    return this.getAssistant(id);
  }

  async deleteAssistant(id: string, permanent: boolean): Promise<void> {
    const current = await this.getAssistant(id);
    if (permanent) {
      if (current.sessionCount > 0) throw new RequestError("assistant_has_sessions", "Cannot delete an assistant with sessions");
      await rm(current.path, { recursive: true, force: true });
    } else {
      await this.updateAssistant(id, { manifest: { archived: true } });
    }
  }

  async cloneAssistant(id: string, targetId: string, name?: string) {
    const source = await this.getAssistant(id);
    const created = await this.createAssistant({
      id: targetId,
      manifest: { ...source.manifest, name: name?.trim() || `${String(source.manifest.name)} copy`, archived: false },
      files: source.files,
    });
    try {
      await copyDirectoryExtras(source.path, created.path);
      return this.getAssistant(targetId);
    } catch (error) {
      await rm(created.path, { recursive: true, force: true });
      throw error;
    }
  }

  async exportAssistant(id: string): Promise<Uint8Array> {
    const assistant = await this.getAssistant(id);
    const files: Record<string, Uint8Array> = {};
    await collectFiles(assistant.path, assistant.path, files);
    return zipSync(files, { level: 6 });
  }

  async importAssistant(id: string, bytes: Uint8Array) {
    assertSafeId(id, "assistant id");
    if (bytes.byteLength > 20 * 1024 * 1024) throw new RequestError("payload_too_large", "Assistant archive exceeds 20 MiB");
    const archive = unzipSync(bytes, {
      filter: (entry) => {
        if (!entry.name || entry.name.startsWith("/") || entry.name.includes("..") || entry.name.includes("\\")) {
          throw new RequestError("invalid_archive", "Assistant archive contains an unsafe path");
        }
        return !entry.name.endsWith("/");
      },
    });
    const manifestBytes = archive["assistant.json"];
    if (!manifestBytes) throw new RequestError("invalid_archive", "assistant.json is required");
    const manifest = JSON.parse(strFromU8(manifestBytes)) as Record<string, unknown>;
    validateAssistantManifest(manifest);
    const created = await this.createAssistant({
      id,
      manifest,
      files: {
        agents: archive["AGENTS.md"] ? strFromU8(archive["AGENTS.md"]) : undefined,
        memory: archive["MEMORY.md"] ? strFromU8(archive["MEMORY.md"]) : undefined,
        workspaces: archive["WORKSPACES.md"] ? strFromU8(archive["WORKSPACES.md"]) : undefined,
      },
    });
    try {
      for (const [name, data] of Object.entries(archive)) {
        if (["assistant.json", "AGENTS.md", "MEMORY.md", "WORKSPACES.md"].includes(name)) continue;
        const target = resolve(created.path, name);
        if (!isInside(created.path, target)) throw new RequestError("invalid_archive", "Assistant archive escaped destination");
        await mkdir(dirname(target), { recursive: true, mode: 0o700 });
        await writeFile(target, data, { mode: 0o600 });
      }
      return this.getAssistant(id);
    } catch (error) {
      await rm(created.path, { recursive: true, force: true });
      throw error;
    }
  }

  async fileInfo(filePath: string) {
    const target = resolveWebPath(filePath);
    const info = await stat(target);
    if (info.isDirectory()) {
      const children = await readdir(target, { withFileTypes: true });
      return {
        path: target,
        name: basename(target),
        kind: "directory",
        entries: children.filter((entry) => !IGNORED_NAMES.has(entry.name)).map((entry) => ({
          name: entry.name,
          path: join(target, entry.name),
          kind: entry.isDirectory() ? "directory" : entry.isFile() ? "file" : "other",
        })).sort((left, right) => left.kind.localeCompare(right.kind) || left.name.localeCompare(right.name)),
      };
    }
    const extension = extname(target).toLowerCase();
    const mime = contentType(target);
    const result: Record<string, unknown> = {
      path: target,
      name: basename(target),
      kind: "file",
      size: info.size,
      modifiedAt: info.mtime.toISOString(),
      mime,
      language: languageFor(extension, basename(target)),
      rawUrl: `/api/web/v1/files/raw?path=${encodeURIComponent(target)}`,
    };
    if (isTextMime(mime) && info.size <= 512 * 1024) result.content = await readFile(target, "utf8");
    return result;
  }

  async writeFile(filePath: string, content: string, encoding: "utf8" | "base64" = "utf8") {
    const target = resolveWebPath(filePath);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, encoding === "base64" ? Buffer.from(content, "base64") : content);
    return this.fileInfo(target);
  }

  async listSkills(cwd: string) {
    const loader = new DefaultResourceLoader({ cwd: resolve(cwd), agentDir: this.options.agentDir });
    await loader.reload();
    return loader.getSkills();
  }

  async installPiPackage(source: string, cwd = process.cwd(), local = false): Promise<Record<string, unknown>> {
    const manager = new DefaultPackageManager({ cwd: resolve(cwd), agentDir: this.options.agentDir, settingsManager: this.options.registry.settings() });
    await manager.installAndPersist(source, { local });
    await Promise.all(this.options.registry.status().activeSessions.map((session) =>
      this.options.registry.reloadSession(session.sessionId).catch(() => undefined)));
    return { source, local, installedPath: manager.getInstalledPath(source, local ? "project" : "user"), packages: manager.listConfiguredPackages() };
  }

  async searchPiPackages(query: string) {
    const { stdout } = await execFileAsync("npm", ["search", query, "--json"], { timeout: 30_000, maxBuffer: 5 * 1024 * 1024 });
    const rows = JSON.parse(stdout || "[]") as Array<Record<string, unknown>>;
    return rows.slice(0, 50).map((row) => ({
      name: row.name, version: row.version, description: row.description, keywords: row.keywords,
      publisher: row.publisher, date: row.date, links: row.links,
    }));
  }

  async listExtensions(cwd = process.cwd()) {
    const output = new Map<string, Record<string, unknown>>();
    const loader = new DefaultResourceLoader({ cwd: resolve(cwd), agentDir: this.options.agentDir });
    try {
      await loader.reload();
      const loaded = loader.getExtensions();
      for (const extension of loaded.extensions) {
        const extensionPath = extension.path;
        output.set(extensionPath, {
          id: extensionPath,
          name: basename(extensionPath),
          kind: "pi",
          path: extensionPath,
          enabled: true,
          tools: [...extension.tools.keys()],
        });
      }
      for (const failure of loaded.errors) output.set(failure.path, {
        id: failure.path,
        name: basename(failure.path),
        kind: "pi",
        path: failure.path,
        enabled: false,
        diagnostics: [{ level: "error", code: "extension.load_failed", message: String(failure.error) }],
      });
    } catch (error) {
      output.set("runtime-discovery", {
        id: "runtime-discovery", name: "Pi extensions", kind: "pi", enabled: false,
        diagnostics: [{ level: "error", code: "extension.discovery_failed", message: errorMessage(error) }],
      });
    }
    for (const candidate of await this.findWebManifests()) {
      if (typeof candidate.id === "string") output.set(candidate.id, candidate);
    }
    return [...output.values()];
  }

  async listWebExtensions(cwd = process.cwd()) {
    return (await this.listExtensions(cwd)).filter((extension) => extension.kind === "wuxianpi" && extension.manifest);
  }

  async readExtensionAsset(extensionId: string, assetPath: string) {
    const extensions = await this.listExtensions();
    const extension = extensions.find((item) => item.id === extensionId);
    if (!extension || typeof extension.root !== "string") throw new RequestError("extension_not_found", `Extension not found: ${extensionId}`);
    const target = resolve(extension.root, assetPath);
    if (!isInside(extension.root, target)) throw new RequestError("invalid_path", "Extension asset escapes its root");
    const [rootReal, targetReal] = await Promise.all([realpath(extension.root), realpath(target)]);
    if (!isInside(rootReal, targetReal)) throw new RequestError("invalid_path", "Extension asset resolves outside its root");
    const info = await stat(targetReal);
    if (!info.isFile()) throw new RequestError("not_file", "Extension asset is not a file");
    return { path: targetReal, size: info.size, contentType: contentType(targetReal) };
  }

  async capabilities(cwd = process.cwd()) {
    const [skills, extensions, config] = await Promise.all([
      this.listSkills(cwd).catch((error) => ({ skills: [], diagnostics: [{ message: errorMessage(error) }] })),
      this.listExtensions(cwd),
      this.readConfig(),
    ]);
    const capabilities: Array<Record<string, unknown>> = [
      ["read", "Read files", ["read"]], ["grep", "Search files", ["read"]],
      ["find", "Find files", ["read"]], ["ls", "List directories", ["read"]],
      ["write", "Write files", ["write"]], ["edit", "Edit files", ["write"]],
      ["bash", "Run shell commands", ["execute", "write", "network"]],
    ].map(([id, description, risk]) => ({
      id: `pi:${id}`, name: id, description, risk, source: "pi-builtin", status: "available", assistantSelectable: true,
    }));
    for (const skill of skills.skills) capabilities.push({
      id: `skill:${skill.name}`, name: skill.name, description: skill.description,
      source: "skill", risk: ["read"], status: "available", assistantSelectable: true,
    });
    for (const extension of extensions) capabilities.push({
      id: `extension:${extension.id}`, name: extension.name, source: extension.kind === "wuxianpi" ? "web-extension" : "pi-extension",
      risk: ["execute"], status: extension.enabled === false ? "error" : "available", assistantSelectable: true,
    });
    for (const server of config.mcpServers as Array<Record<string, unknown>>) capabilities.push({
      id: `mcp:${server.id}`, name: server.name ?? server.id, description: `${server.transport ?? "stdio"} via pi-mcp-adapter`,
      source: "mcp", risk: ["external", "network"], status: server.enabled === false ? "unavailable" : "available", assistantSelectable: true,
    });
    for (const profile of config.ttsProfiles as Array<Record<string, unknown>>) capabilities.push({
      id: `tts:${profile.id}`, name: profile.name ?? profile.id, description: profile.provider,
      source: "tts", risk: ["audio"], status: profile.enabled === false ? "unavailable" : "available", assistantSelectable: true,
    });
    return { generatedAt: new Date().toISOString(), capabilities, diagnostics: skills.diagnostics };
  }

  async readConfig(): Promise<Record<string, any>> {
    const path = join(this.options.agentDir, "wuxianpi", "config.json");
    let raw: Record<string, any> = {};
    try { raw = JSON.parse(await readFile(path, "utf8")) as Record<string, any>; } catch { /* defaults */ }
    return {
      ...DEFAULT_CONFIG,
      ...raw,
      schemaVersion: 1,
      defaults: { ...DEFAULT_CONFIG.defaults, ...(raw.defaults ?? {}) },
      mcpServers: Array.isArray(raw.mcpServers) ? raw.mcpServers : [],
      ttsProfiles: Array.isArray(raw.ttsProfiles) ? raw.ttsProfiles : DEFAULT_CONFIG.ttsProfiles,
      permissions: Array.isArray(raw.permissions) ? raw.permissions : [],
      ubuntu: { ...DEFAULT_CONFIG.ubuntu, ...(raw.ubuntu ?? {}) },
    };
  }

  async patchConfig(patch: Record<string, unknown>): Promise<Record<string, any>> {
    const current = await this.readConfig();
    const next: Record<string, any> = {
      ...current,
      ...patch,
      schemaVersion: 1,
      defaults: { ...current.defaults, ...(isRecord(patch.defaults) ? patch.defaults : {}) },
      permissions: current.permissions,
    };
    if (patch.mcpServers !== undefined && !Array.isArray(patch.mcpServers)) throw new RequestError("invalid_payload", "mcpServers must be an array");
    if (patch.ttsProfiles !== undefined && !Array.isArray(patch.ttsProfiles)) throw new RequestError("invalid_payload", "ttsProfiles must be an array");
    await writeJson(join(this.options.agentDir, "wuxianpi", "config.json"), next);
    await this.writeStandardMcpConfig(next.mcpServers);
    return next;
  }

  async permissionState(assistantId?: string) {
    const config = await this.readConfig();
    const now = Date.now();
    for (const [id, request] of this.pendingPermissions) {
      if (typeof request.expiresAt === "number" && request.expiresAt <= now) this.pendingPermissions.delete(id);
    }
    return {
      pending: [...this.pendingPermissions.values()].filter((request) => !assistantId || request.assistantId === assistantId),
      grants: config.permissions.filter((grant: Record<string, unknown>) => !assistantId || grant.assistantId === assistantId),
    };
  }

  async mutatePermission(body: Record<string, unknown>) {
    const action = body.action;
    const request = asRecord(body.request, "request");
    const config = await this.readConfig();
    if (action === "revoke") {
      const assistantId = requireRecordString(request, "assistantId");
      const capabilityId = requireRecordString(request, "capabilityId");
      config.permissions = config.permissions.filter((grant: Record<string, unknown>) =>
        grant.assistantId !== assistantId || grant.capabilityId !== capabilityId);
    } else if (action === "decide") {
      const requestId = requireRecordString(request, "requestId");
      const pending = this.pendingPermissions.get(requestId);
      if (!pending) throw new RequestError("permission_request_not_found", "Permission request not found or expired");
      const decision = requireRecordString(request, "decision");
      if (!["once", "assistant", "deny"].includes(decision)) throw new RequestError("invalid_payload", "Invalid permission decision");
      this.pendingPermissions.delete(requestId);
      if (decision !== "once") {
        config.permissions = config.permissions.filter((grant: Record<string, unknown>) =>
          grant.assistantId !== pending.assistantId || grant.capabilityId !== pending.capabilityId);
        config.permissions.push({
          assistantId: pending.assistantId, capabilityId: pending.capabilityId, decision, updatedAt: new Date().toISOString(),
        });
      }
    } else throw new RequestError("invalid_payload", "action must be decide or revoke");
    await writeJson(join(this.options.agentDir, "wuxianpi", "config.json"), config);
    return this.permissionState();
  }

  async mcpAction(body: Record<string, unknown>) {
    const action = requireRecordString(body, "action");
    const serverId = requireRecordString(body, "serverId");
    const config = await this.readConfig();
    const server = (config.mcpServers as Array<Record<string, unknown>>).find((item) => item.id === serverId);
    if (!server) throw new RequestError("mcp_server_not_found", `MCP server not found: ${serverId}`);
    const adapter = (await this.listExtensions()).find((item) => String(item.path ?? item.id).includes("pi-mcp-adapter"));
    const diagnostics = [{
      level: adapter ? "info" : "warning",
      code: adapter ? "mcp.adapter_available" : "mcp.adapter_missing",
      message: adapter
        ? "MCP execution is provided by the pi-mcp-adapter extension"
        : "Install pi-mcp-adapter as a Pi package before using MCP tools",
    }];
    if (action === "test" || action === "listTools") {
      return { serverId, ...(action === "listTools" ? { tools: [] } : {}), diagnostics,
        adapterInstalled: !!adapter, configPath: join(this.options.agentDir, "mcp.json") };
    }
    throw new RequestError("mcp_owned_by_pi_extension", `${action} must be executed through the pi-mcp-adapter tool inside Pi`);
  }

  async speak(body: Record<string, unknown>): Promise<Record<string, unknown>> {
    const config = await this.readConfig();
    const profileId = requireRecordString(body, "profileId");
    const profile = (config.ttsProfiles as Array<Record<string, unknown>>).find((item) => item.id === profileId);
    if (!profile || profile.enabled === false) throw new RequestError("tts_profile_not_found", `TTS profile not found: ${profileId}`);
    const text = requireRecordString(body, "text").replace(/```[\s\S]*?```/g, "（代码块已省略）").trim();
    const rate = typeof body.rate === "number" ? body.rate : profile.rate;
    const pitch = typeof body.pitch === "number" ? body.pitch : profile.pitch;
    if (profile.provider === "browser-speech") return {
      kind: "client", instruction: { kind: "browser-speech", text, voice: profile.voice, rate, pitch },
    };
    if (profile.provider === "termux-api") {
      const args = [text];
      if (typeof profile.voice === "string") args.unshift("-v", profile.voice);
      if (rate !== undefined) args.unshift("-r", String(rate));
      if (pitch !== undefined) args.unshift("-p", String(pitch));
      await execFileAsync("termux-tts-speak", args, { timeout: 120_000, maxBuffer: 1024 * 1024 });
      return { kind: "completed", provider: "termux-api" };
    }
    if (typeof profile.baseUrl !== "string") throw new RequestError("invalid_tts_profile", `TTS profile ${profileId} requires baseUrl`);
    const target = profile.provider === "openai-compatible"
      ? new URL("audio/speech", profile.baseUrl.endsWith("/") ? profile.baseUrl : `${profile.baseUrl}/`).href
      : profile.baseUrl;
    const cloud = await fetch(target, {
      method: "POST", headers: { "content-type": "application/json", ...(isRecord(profile.headers) ? profile.headers as Record<string, string> : {}) },
      body: JSON.stringify(profile.provider === "openai-compatible"
        ? { model: profile.model ?? "tts-1", voice: profile.voice ?? "alloy", input: text, speed: rate ?? 1 }
        : { text, voice: profile.voice ?? "", model: profile.model ?? "", rate: rate ?? 1, pitch: pitch ?? 1 }),
      signal: AbortSignal.timeout(120_000),
    });
    if (!cloud.ok) throw new RequestError("tts_failed", `TTS provider returned ${cloud.status}`);
    return { kind: "audio", mimeType: cloud.headers.get("content-type")?.split(";")[0] ?? "audio/mpeg",
      data: Buffer.from(await cloud.arrayBuffer()).toString("base64") };
  }

  async issueExtensionNonce(extensionId: string, assistantId: string): Promise<string> {
    await Promise.all([this.getAssistant(assistantId), this.requireWebExtension(extensionId)]);
    const nonce = randomUUID();
    this.nonces.set(nonce, { extensionId, assistantId, expiresAt: Date.now() + 30 * 60_000 });
    return nonce;
  }

  async bridgeExtension(body: Record<string, unknown>): Promise<Record<string, unknown>> {
    const requestId = requireRecordString(body, "requestId");
    const extensionId = requireRecordString(body, "extensionId");
    const nonce = requireRecordString(body, "nonce");
    const method = requireRecordString(body, "method");
    const grant = this.nonces.get(nonce);
    if (!grant || grant.extensionId !== extensionId || grant.expiresAt <= Date.now()) {
      this.nonces.delete(nonce);
      throw new RequestError("invalid_extension_nonce", "Invalid or expired extension bridge nonce");
    }
    const params = isRecord(body.params) ? body.params : {};
    let result: unknown = {};
    if (method === "assistant.get") result = await this.getAssistant(grant.assistantId);
    else if (method === "storage.get") {
      const data = await this.readExtensionStorage(extensionId, grant.assistantId);
      result = data[requireRecordString(params, "key")];
    } else if (method === "storage.set") {
      const key = requireRecordString(params, "key");
      const data = await this.readExtensionStorage(extensionId, grant.assistantId);
      data[key] = params.value;
      await writeJson(this.extensionStoragePath(extensionId, grant.assistantId), data);
    } else if (method === "tts.speak") result = await this.speak(params);
    else if (["ui.notify", "ui.resize", "ui.close"].includes(method)) result = { handled: true };
    else if (method === "tools.call") throw new RequestError("tools_owned_by_pi", "Web extensions must invoke tools through their Pi extension counterpart");
    else throw new RequestError("unknown_bridge_method", `Unknown extension bridge method: ${method}`);
    return { type: "wuxianpi_bridge_response", requestId, extensionId, nonce, ok: true, result };
  }

  createReadStream(filePath: string, start?: number, end?: number) {
    return createReadStream(resolveWebPath(filePath), start === undefined ? undefined : { start, end });
  }

  private async writeStandardMcpConfig(servers: Array<Record<string, unknown>>): Promise<void> {
    const mcpServers: Record<string, unknown> = {};
    for (const server of servers) {
      if (typeof server.id !== "string" || !server.id) throw new RequestError("invalid_mcp_config", "Every MCP server requires an id");
      const { id, name: _name, runtime: _runtime, enabled, ...standard } = server;
      if (enabled !== false) mcpServers[id] = standard;
    }
    await writeJson(join(this.options.agentDir, "mcp.json"), { mcpServers });
  }

  private async requireWebExtension(extensionId: string): Promise<Record<string, unknown>> {
    const extension = (await this.listWebExtensions()).find((item) => item.id === extensionId);
    if (!extension) throw new RequestError("extension_not_found", `Web extension not found: ${extensionId}`);
    return extension;
  }

  private extensionStoragePath(extensionId: string, assistantId: string): string {
    return join(this.options.agentDir, "wuxianpi", "extension-storage", encodeURIComponent(extensionId), `${assistantId}.json`);
  }

  private async readExtensionStorage(extensionId: string, assistantId: string): Promise<Record<string, unknown>> {
    try { return JSON.parse(await readFile(this.extensionStoragePath(extensionId, assistantId), "utf8")) as Record<string, unknown>; }
    catch { return {}; }
  }

  private async ensureDefaultAssistant(): Promise<void> {
    await mkdir(this.assistantsRoot, { recursive: true, mode: 0o700 });
    const directory = join(this.assistantsRoot, "wuxianpi");
    try {
      await access(join(directory, "assistant.json"));
      return;
    } catch { /* create below */ }
    try {
      await this.createAssistant({ id: "wuxianpi", manifest: DEFAULT_ASSISTANT });
    } catch (error) {
      await access(join(directory, "assistant.json")).catch(() => { throw error; });
    }
  }

  private async findWebManifests(): Promise<Array<Record<string, unknown>>> {
    const roots = [
      join(this.options.agentDir, "extensions"),
      join(this.options.agentDir, "packages"),
      this.legacyExtensionsRoot,
    ];
    const manifests: Array<Record<string, unknown>> = [];
    for (const root of roots) await scanDirectories(root, 3, async (directory) => {
      const legacyPath = join(directory, "wuxianpi-extension.json");
      const packagePath = join(directory, "package.json");
      let raw: Record<string, unknown> | undefined;
      let packageName: string | undefined;
      try { raw = JSON.parse(await readFile(legacyPath, "utf8")) as Record<string, unknown>; }
      catch { /* optional */ }
      if (!raw) {
        try {
          const packageJson = JSON.parse(await readFile(packagePath, "utf8")) as Record<string, unknown>;
          packageName = typeof packageJson.name === "string" ? packageJson.name : undefined;
          const contribution = packageJson.wuxianpi;
          if (contribution && typeof contribution === "object" && !Array.isArray(contribution)) {
            raw = { ...(contribution as Record<string, unknown>), id: packageName, name: packageName, version: packageJson.version };
          }
        } catch { /* optional */ }
      }
      if (!raw) return;
      const id = typeof raw.id === "string" && raw.id ? raw.id : packageName ?? basename(directory);
      const entry = typeof raw.entry === "string" ? raw.entry : undefined;
      const manifest = {
        schemaVersion: 1,
        apiVersion: "1",
        ...raw,
        id,
        name: typeof raw.name === "string" ? raw.name : id,
        version: typeof raw.version === "string" ? raw.version : "0.0.0",
      };
      manifests.push({
        id,
        name: manifest.name,
        version: manifest.version,
        kind: "wuxianpi",
        path: directory,
        root: directory,
        manifest,
        enabled: true,
        diagnostics: [],
        resourceBaseUrl: `/api/web/v1/extensions/${encodeURIComponent(id)}/assets/`,
        ...(entry ? { entryUrl: `/api/web/v1/extensions/${encodeURIComponent(id)}/assets/${entry.split("/").map(encodeURIComponent).join("/")}` } : {}),
      });
    });
    return manifests;
  }
}

const IGNORED_NAMES = new Set(["node_modules", ".git", ".next", "dist", "build", "target", "__pycache__"]);

function validateAssistantManifest(manifest: Record<string, unknown>): void {
  if (manifest.schemaVersion !== 1) throw new RequestError("invalid_assistant", "Unsupported assistant schema");
  if (typeof manifest.name !== "string" || !manifest.name.trim()) throw new RequestError("invalid_assistant", "Assistant name is required");
}

function assertSafeId(id: string, label: string): void {
  if (!SAFE_ID.test(id)) throw new RequestError("invalid_id", `${label} is invalid`);
}

function asRecord(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new RequestError("invalid_payload", `${name} must be an object`);
  return value as Record<string, unknown>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function requireRecordString(value: Record<string, unknown>, name: string): string {
  const item = value[name];
  if (typeof item !== "string" || !item.trim()) throw new RequestError("invalid_payload", `${name} must be a non-empty string`);
  return item;
}

function stringOr(value: unknown, fallback: string): string { return typeof value === "string" ? value : fallback; }
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
async function readOptional(path: string, fallback: string): Promise<string> { try { return await readFile(path, "utf8"); } catch { return fallback; } }

async function writeJson(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  const temporary = `${path}.${process.pid}.${Date.now()}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await rename(temporary, path);
}

function resolveWebPath(value: string): string {
  if (!value) throw new RequestError("invalid_path", "path is required");
  return resolve(isAbsolute(value) ? value : join(process.cwd(), value));
}

function isInside(root: string, candidate: string): boolean {
  const nested = relative(resolve(root), resolve(candidate));
  return nested === "" || (!nested.startsWith(`..${sep}`) && nested !== ".." && !isAbsolute(nested));
}

async function scanDirectories(root: string, depth: number, visit: (directory: string) => Promise<void>): Promise<void> {
  let entries;
  try { entries = await readdir(root, { withFileTypes: true }); } catch { return; }
  await visit(root);
  if (depth <= 0) return;
  for (const entry of entries) {
    if (!entry.isDirectory() || entry.isSymbolicLink() || IGNORED_NAMES.has(entry.name)) continue;
    await scanDirectories(join(root, entry.name), depth - 1, visit);
  }
}

function contentType(path: string): string {
  return ({
    ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8", ".md": "text/markdown; charset=utf-8",
    ".txt": "text/plain; charset=utf-8", ".ts": "text/plain; charset=utf-8", ".tsx": "text/plain; charset=utf-8",
    ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp",
    ".gif": "image/gif", ".pdf": "application/pdf", ".mp3": "audio/mpeg", ".wav": "audio/wav", ".woff2": "font/woff2",
  } as Record<string, string>)[extname(path).toLowerCase()] ?? "application/octet-stream";
}

function isTextMime(mime: string): boolean {
  return mime.startsWith("text/") || mime.includes("json") || mime.includes("javascript");
}

function languageFor(extension: string, name: string): string {
  if (name === "Dockerfile") return "dockerfile";
  return ({
    ".ts": "typescript", ".tsx": "typescript", ".js": "javascript", ".jsx": "javascript", ".json": "json",
    ".md": "markdown", ".py": "python", ".rs": "rust", ".kt": "kotlin", ".java": "java", ".sh": "bash",
    ".css": "css", ".html": "html", ".yaml": "yaml", ".yml": "yaml", ".toml": "toml",
  } as Record<string, string>)[extension] ?? "text";
}

export { contentType, errorMessage };

async function collectFiles(root: string, current: string, output: Record<string, Uint8Array>): Promise<void> {
  for (const entry of await readdir(current, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) continue;
    const absolute = join(current, entry.name);
    if (entry.isDirectory()) await collectFiles(root, absolute, output);
    else if (entry.isFile()) output[relative(root, absolute).split(sep).join("/")] = new Uint8Array(await readFile(absolute));
  }
}

async function copyDirectoryExtras(source: string, destination: string): Promise<void> {
  const files: Record<string, Uint8Array> = {};
  await collectFiles(source, source, files);
  for (const [name, data] of Object.entries(files)) {
    if (["assistant.json", "AGENTS.md", "MEMORY.md", "WORKSPACES.md"].includes(name)) continue;
    const target = join(destination, name);
    await mkdir(dirname(target), { recursive: true, mode: 0o700 });
    await writeFile(target, data, { mode: 0o600 });
  }
}
