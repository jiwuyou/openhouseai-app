import { stat } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { basename } from "node:path";
import { Readable } from "node:stream";
import { boundedInteger, RequestError, stringifyMessage } from "./protocol.js";
import type { RegistrySessionEvent, SessionRegistry } from "./session-registry.js";
import { contentType, errorMessage, WebServices } from "./web-services.js";

const API_ROOT = "/api/web/v1";
const MAX_BODY_BYTES = 16 * 1024 * 1024;
const SSE_HEARTBEAT_MS = 15_000;

export interface WebApiOptions {
  registry: SessionRegistry;
  services: WebServices;
  status: () => Record<string, unknown>;
}

export class WebApi {
  private readonly eventStreams = new Set<ServerResponse>();

  constructor(private readonly options: WebApiOptions) {}

  close(): void {
    for (const response of this.eventStreams) response.end();
    this.eventStreams.clear();
  }

  async handle(request: IncomingMessage, response: ServerResponse): Promise<boolean> {
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    if (!url.pathname.startsWith(API_ROOT)) return false;
    try {
      await this.route(request, response, url);
    } catch (error) {
      if (response.headersSent) {
        response.end();
        return true;
      }
      const status = error instanceof RequestError
        ? error.code === "model_revision_conflict" || error.code === "model_concurrent_write" ? 409
          : error.code.endsWith("_not_found") || error.code === "session_not_found" ? 404 : 400
        : 500;
      json(response, status, {
        ok: false,
        error: {
          code: error instanceof RequestError ? error.code : "runtime_error",
          message: errorMessage(error),
          ...(error instanceof RequestError && error.details !== undefined ? { details: error.details } : {}),
        },
      });
    }
    return true;
  }

  private async route(request: IncomingMessage, response: ServerResponse, url: URL): Promise<void> {
    const method = request.method ?? "GET";
    const path = url.pathname.slice(API_ROOT.length) || "/";
    if (method === "OPTIONS") {
      response.writeHead(204, corsHeaders()); response.end(); return;
    }
    if (method === "GET" && (path === "/" || path === "/status")) {
      json(response, 200, { ok: true, ...this.options.status() }); return;
    }
    if (path === "/sessions" && method === "GET") {
      const payload = queryObject(url);
      json(response, 200, { ok: true, data: await this.options.registry.list({
        cwd: url.searchParams.get("cwd") ?? undefined,
        all: url.searchParams.get("all") !== "false",
        offset: boundedInteger(payload, "offset", 0, Number.MAX_SAFE_INTEGER),
        limit: boundedInteger(payload, "limit", 100, 1000),
      }) }); return;
    }
    if (path === "/sessions" && method === "POST") {
      const body = await readJsonBody(request);
      let cwd = optionalString(body, "cwd");
      const assistantId = optionalString(body, "assistantId");
      const assistant = assistantId ? await this.options.services.getAssistant(assistantId) : undefined;
      if (assistant) cwd = assistant.path;
      const config = await this.options.services.readConfig();
      const manifest = assistant?.manifest ?? {};
      const configuredTools = Array.isArray(body.toolNames) ? body.toolNames
        : Array.isArray(manifest.tools) ? manifest.tools
          : Array.isArray(config.defaults?.tools) ? config.defaults.tools : undefined;
      const configuredModel = isRecord(body.model) ? body.model
        : isRecord(manifest.model) ? manifest.model
          : isRecord(config.defaults?.model) ? config.defaults.model : undefined;
      const configuredProvider = optionalString(body, "provider") ?? (typeof configuredModel?.provider === "string" ? configuredModel.provider : undefined);
      const configuredModelId = optionalString(body, "modelId") ?? (typeof configuredModel?.modelId === "string" ? configuredModel.modelId : undefined);
      const configuredThinking = optionalString(body, "thinkingLevel")
        ?? (typeof manifest.thinkingLevel === "string" && manifest.thinkingLevel !== "inherit" ? manifest.thinkingLevel : undefined)
        ?? (typeof config.defaults?.thinkingLevel === "string" ? config.defaults.thinkingLevel : undefined);
      const created = await this.options.registry.create(cwd);
      let toolWarnings: unknown[] = [];
      try {
        if (body.toolNames !== undefined && (!Array.isArray(body.toolNames) || !body.toolNames.every((name) => typeof name === "string"))) {
            throw new RequestError("invalid_payload", "toolNames must be an array of strings");
        }
        if (configuredTools && configuredTools.every((name: unknown) => typeof name === "string")) {
          const toolResult = await this.options.registry.setTools(created.sessionId, configuredTools as string[]);
          if (isRecord(toolResult) && Array.isArray(toolResult.warnings)) toolWarnings = toolResult.warnings;
        }
        if ((configuredProvider && !configuredModelId) || (!configuredProvider && configuredModelId)) {
          throw new RequestError("invalid_payload", "provider and modelId must be supplied together");
        }
        if (configuredProvider && configuredModelId) await this.options.registry.setModel(created.sessionId, configuredProvider, configuredModelId);
        if (configuredThinking) await this.options.registry.setThinkingLevel(created.sessionId, configuredThinking);
        json(response, 201, { ok: true, data: { ...created, ...(toolWarnings.length > 0 ? { warnings: toolWarnings } : {}) } });
      } catch (error) {
        await this.options.registry.close(created.sessionId).catch(() => undefined);
        throw error;
      }
      return;
    }
    const sessionRoute = /^\/sessions\/([^/]+)(?:\/(.*))?$/.exec(path);
    if (sessionRoute) {
      const sessionId = decodeURIComponent(sessionRoute[1]!);
      const action = sessionRoute[2] ?? "";
      await this.routeSession(request, response, sessionId, action);
      return;
    }
    if (path === "/models" && method === "GET") {
      const setup = await this.options.registry.modelSetup().setup(url.searchParams.get("provider") ?? undefined);
      json(response, 200, { ok: true, data: {
        providers: setup.providers,
        models: setup.models,
        defaultModel: setup.defaultModel,
        ...(setup.availabilityError ? { availabilityError: setup.availabilityError } : {}),
      } }); return;
    }
    if (path === "/models/setup" && method === "GET") {
      json(response, 200, { ok: true, data: await this.options.registry.modelSetup().setup() }); return;
    }
    if (path === "/models/fetch" && method === "POST") {
      json(response, 200, { ok: true, data: await this.options.registry.modelSetup().fetchModels(await readJsonBody(request)) }); return;
    }
    if (path === "/models/apply" && method === "POST") {
      json(response, 200, { ok: true, data: await this.options.registry.modelSetup().apply(await readJsonBody(request)) }); return;
    }
    if (path === "/models/login" && method === "POST") {
      const body = await readJsonBody(request);
      const provider = requireString(body, "provider");
      const apiKey = requireString(body, "apiKey");
      json(response, 200, { ok: true, data: await this.options.registry.modelSetup().login(provider, apiKey) }); return;
    }
    if (path === "/models/logout" && method === "POST") {
      const body = await readJsonBody(request);
      const provider = requireString(body, "provider");
      json(response, 200, { ok: true, data: await this.options.registry.modelSetup().logout(provider) }); return;
    }
    if (path === "/models/default" && method === "PATCH") {
      const body = await readJsonBody(request);
      if (body.setGlobalDefault !== undefined && typeof body.setGlobalDefault !== "boolean") {
        throw new RequestError("invalid_payload", "setGlobalDefault must be a boolean");
      }
      json(response, 200, { ok: true, data: await this.options.registry.setDefaultModel(
        requireString(body, "provider"), requireString(body, "modelId"), optionalString(body, "sessionId"),
        body.setGlobalDefault as boolean | undefined,
      ) }); return;
    }
    if (path === "/models/test" && method === "POST") {
      json(response, 200, { ok: true, data: await this.options.registry.modelSetup().testModel(await readJsonBody(request)) }); return;
    }
    if (path === "/assistants" && method === "GET") {
      json(response, 200, { ok: true, data: { assistants: await this.options.services.listAssistants(url.searchParams.get("includeArchived") === "true") } }); return;
    }
    if (path === "/assistants" && method === "POST") {
      json(response, 201, { ok: true, data: { assistant: await this.options.services.createAssistant(await readJsonBody(request)) } }); return;
    }
    if (path === "/assistants/import" && method === "POST") {
      const form = await readFormData(request);
      const id = form.get("id");
      const file = form.get("file");
      if (typeof id !== "string" || !id) throw new RequestError("invalid_payload", "id is required");
      if (!file || typeof file === "string") throw new RequestError("invalid_payload", "file is required");
      const assistant = await this.options.services.importAssistant(id, new Uint8Array(await file.arrayBuffer()));
      json(response, 201, { ok: true, data: { assistant } }); return;
    }
    const assistantActionRoute = /^\/assistants\/([^/]+)\/(copy|export)$/.exec(path);
    if (assistantActionRoute) {
      const id = decodeURIComponent(assistantActionRoute[1]!);
      const action = assistantActionRoute[2]!;
      if (action === "copy" && method === "POST") {
        const body = await readJsonBody(request);
        const assistant = await this.options.services.cloneAssistant(id, requireString(body, "targetId"), optionalString(body, "name"));
        json(response, 201, { ok: true, data: { assistant } }); return;
      }
      if (action === "export" && method === "GET") {
        const archive = await this.options.services.exportAssistant(id);
        response.writeHead(200, {
          ...corsHeaders(),
          "content-type": "application/zip",
          "content-length": archive.byteLength,
          "content-disposition": `attachment; filename*=UTF-8''${encodeURIComponent(id)}.wuxianpi.zip`,
        });
        response.end(archive); return;
      }
      throw new RequestError("method_not_allowed", `Method not allowed: ${method}`);
    }
    const assistantRoute = /^\/assistants\/([^/]+)$/.exec(path);
    if (assistantRoute) {
      const id = decodeURIComponent(assistantRoute[1]!);
      if (method === "GET") {
        const detail = await this.options.services.getAssistant(id);
        const { files, ...assistant } = detail;
        json(response, 200, { ok: true, data: { assistant, files } });
      }
      else if (method === "PATCH") json(response, 200, { ok: true, data: { assistant: await this.options.services.updateAssistant(id, await readJsonBody(request)) } });
      else if (method === "DELETE") {
        await this.options.services.deleteAssistant(id, url.searchParams.get("permanent") === "true");
        json(response, 200, { ok: true, data: { id } });
      } else throw new RequestError("method_not_allowed", `Method not allowed: ${method}`);
      return;
    }
    if (path === "/files" && method === "GET") {
      json(response, 200, { ok: true, data: await this.options.services.fileInfo(requireQuery(url, "path")) }); return;
    }
    if (path === "/files" && (method === "PUT" || method === "PATCH")) {
      const body = await readJsonBody(request);
      json(response, 200, { ok: true, data: await this.options.services.writeFile(
        requireString(body, "path"), requireString(body, "content"), body.encoding === "base64" ? "base64" : "utf8",
      ) }); return;
    }
    if (path === "/files/raw" && method === "GET") {
      await this.streamRawFile(request, response, requireQuery(url, "path")); return;
    }
    if (path === "/skills" && method === "GET") {
      json(response, 200, { ok: true, data: await this.options.services.listSkills(url.searchParams.get("cwd") ?? process.cwd()) }); return;
    }
    if (path === "/skills/search" && method === "GET") {
      json(response, 200, { ok: true, data: { packages: await this.options.services.searchPiPackages(requireQuery(url, "q")) } }); return;
    }
    if ((path === "/skills/install" || path === "/skills") && method === "POST") {
      const body = await readJsonBody(request);
      json(response, 201, { ok: true, data: await this.options.services.installPiPackage(
        requireString(body, "source"), optionalString(body, "cwd") ?? process.cwd(), body.local === true,
      ) }); return;
    }
    if (path === "/extensions" && method === "GET") {
      json(response, 200, { ok: true, data: { extensions: await this.options.services.listWebExtensions(url.searchParams.get("cwd") ?? process.cwd()) } }); return;
    }
    if (path === "/extensions/nonce" && method === "POST") {
      const body = await readJsonBody(request);
      const extensionId = requireString(body, "extensionId");
      const assistantId = requireString(body, "assistantId");
      json(response, 200, { ok: true, data: {
        extensionId, assistantId, nonce: await this.options.services.issueExtensionNonce(extensionId, assistantId),
      } }); return;
    }
    if (path === "/extensions/bridge" && method === "POST") {
      json(response, 200, { ok: true, data: await this.options.services.bridgeExtension(await readJsonBody(request)) }); return;
    }
    const assetRoute = /^\/extensions\/([^/]+)\/assets\/(.+)$/.exec(path);
    if (assetRoute && method === "GET") {
      const id = decodeURIComponent(assetRoute[1]!);
      const asset = assetRoute[2]!.split("/").map(decodeURIComponent).join("/");
      const resolved = await this.options.services.readExtensionAsset(id, asset);
      response.writeHead(200, { ...corsHeaders(), "content-type": resolved.contentType, "content-length": resolved.size, "cache-control": "no-cache" });
      this.options.services.createReadStream(resolved.path).pipe(response); return;
    }
    if (path === "/capabilities" && method === "GET") {
      const [catalog, config] = await Promise.all([
        this.options.services.capabilities(url.searchParams.get("cwd") ?? process.cwd()),
        this.options.services.readConfig(),
      ]);
      json(response, 200, { ok: true, data: { catalog, config } }); return;
    }
    if (path === "/capabilities/config" && method === "GET") {
      json(response, 200, { ok: true, data: await this.options.services.readConfig() }); return;
    }
    if (path === "/capabilities/config" && method === "PATCH") {
      json(response, 200, { ok: true, data: await this.options.services.patchConfig(await readJsonBody(request)) }); return;
    }
    if (path === "/capabilities/permissions" && method === "GET") {
      json(response, 200, { ok: true, data: await this.options.services.permissionState(url.searchParams.get("assistantId") ?? undefined) }); return;
    }
    if (path === "/capabilities/permissions" && method === "POST") {
      json(response, 200, { ok: true, data: await this.options.services.mutatePermission(await readJsonBody(request)) }); return;
    }
    if (path === "/capabilities/mcp" && method === "POST") {
      json(response, 200, { ok: true, data: await this.options.services.mcpAction(await readJsonBody(request)) }); return;
    }
    if (path === "/capabilities/tts" && method === "POST") {
      json(response, 200, await this.options.services.speak(await readJsonBody(request))); return;
    }
    throw new RequestError("not_found", `Web API route not found: ${method} ${path}`);
  }

  private async routeSession(request: IncomingMessage, response: ServerResponse, sessionId: string, action: string): Promise<void> {
    const method = request.method ?? "GET";
    const registry = this.options.registry;
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    const requestedLeafId = url.searchParams.has("leafId") ? url.searchParams.get("leafId") : undefined;
    if (!action && method === "GET") { json(response, 200, { ok: true, data: await registry.snapshot(sessionId, requestedLeafId) }); return; }
    if (!action && method === "DELETE") { json(response, 200, { ok: true, data: await registry.close(sessionId) }); return; }
    if (!action && method === "PATCH") {
      const body = await readJsonBody(request);
      await registry.setName(sessionId, requireString(body, "name"));
      json(response, 200, { ok: true, data: await registry.snapshot(sessionId) }); return;
    }
    if (action === "snapshot" && method === "GET") { json(response, 200, { ok: true, data: await registry.snapshot(sessionId, requestedLeafId) }); return; }
    if (action === "events" && method === "GET") { await this.openEventStream(request, response, sessionId); return; }
    if (action === "tree" && method === "GET") { json(response, 200, { ok: true, data: await registry.tree(sessionId) }); return; }
    if (action === "commands" && method === "GET") { json(response, 200, { ok: true, data: await registry.commands(sessionId) }); return; }
    if (action === "stats" && method === "GET") { json(response, 200, { ok: true, data: await registry.stats(sessionId) }); return; }
    if (action === "entries" && method === "GET") { json(response, 200, { ok: true, data: await registry.entries(sessionId, url.searchParams.get("since") ?? undefined) }); return; }
    if (action === "prompt" && method === "POST") {
      const body = await readJsonBody(request);
      json(response, 202, { ok: true, data: await registry.prompt(sessionId, {
        message: requireString(body, "message"), images: body.images,
        streamingBehavior: body.streamingBehavior === "steer" || body.streamingBehavior === "followUp" ? body.streamingBehavior : undefined,
        source: "rpc",
      }) }); return;
    }
    if (action === "abort" && method === "POST") { await registry.abort(sessionId); json(response, 200, { ok: true, data: {} }); return; }
    if (action === "compact" && method === "POST") {
      const body = await readJsonBody(request);
      json(response, 200, { ok: true, data: await registry.compact(sessionId, optionalString(body, "customInstructions")) }); return;
    }
    if (action === "fork" && method === "POST") {
      const body = await readJsonBody(request);
      const position = body.position === "at" ? "at" : "before";
      const result = await registry.fork(sessionId, requireString(body, "entryId"), position) as Record<string, unknown>;
      json(response, 201, { ok: true, data: { ...result, newSessionId: result.sessionId } }); return;
    }
    if (action === "navigate" && method === "POST") {
      const body = await readJsonBody(request);
      json(response, 200, { ok: true, data: await registry.navigateTree(sessionId, requireString(body, "targetId"), {
        summarize: body.summarize === true,
        customInstructions: optionalString(body, "customInstructions"),
        replaceInstructions: body.replaceInstructions === true,
        label: optionalString(body, "label"),
      }) }); return;
    }
    if (action === "model" && method === "PATCH") {
      const body = await readJsonBody(request);
      json(response, 200, { ok: true, data: await registry.setModel(sessionId, requireString(body, "provider"), requireString(body, "modelId")) }); return;
    }
    if (action === "thinking-level" && method === "PATCH") {
      const body = await readJsonBody(request);
      json(response, 200, { ok: true, data: await registry.setThinkingLevel(sessionId, requireString(body, "level")) }); return;
    }
    if (action === "tools" && method === "GET") { json(response, 200, { ok: true, data: await registry.tools(sessionId) }); return; }
    if (action === "tools" && method === "PATCH") {
      const body = await readJsonBody(request);
      if (!Array.isArray(body.toolNames) || !body.toolNames.every((name) => typeof name === "string")) {
        throw new RequestError("invalid_payload", "toolNames must be an array of strings");
      }
      json(response, 200, { ok: true, data: await registry.setTools(sessionId, body.toolNames) }); return;
    }
    if (action === "extension-ui-responses" && method === "POST") {
      const body = await readJsonBody(request);
      const requestId = optionalString(body, "requestId") ?? optionalString(body, "id");
      if (!requestId) throw new RequestError("invalid_payload", "requestId or id is required");
      await registry.extensionUiResponse(sessionId, {
        requestId,
        value: optionalString(body, "value"),
        confirmed: typeof body.confirmed === "boolean" ? body.confirmed : undefined,
        cancelled: body.cancelled === true,
      });
      json(response, 200, { ok: true, data: {} }); return;
    }
    throw new RequestError("not_found", `Session route not found: ${method} ${action}`);
  }

  private async openEventStream(request: IncomingMessage, response: ServerResponse, sessionId: string): Promise<void> {
    response.writeHead(200, {
      ...corsHeaders(),
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
      "x-accel-buffering": "no",
    });
    response.flushHeaders();
    this.eventStreams.add(response);
    let closed = false;
    const emitAgent = (event: RegistrySessionEvent) => {
      if (event.sessionId !== sessionId || closed) return;
      writeSse(response, envelopeFor(event));
    };
    let subscription: Awaited<ReturnType<SessionRegistry["snapshotAndSubscribe"]>> | undefined;
    const heartbeat = setInterval(() => {
      if (!closed) writeSse(response, { type: "heartbeat", at: new Date().toISOString() });
    }, SSE_HEARTBEAT_MS);
    heartbeat.unref();
    const cleanup = () => {
      if (closed) return;
      closed = true;
      clearInterval(heartbeat);
      subscription?.unsubscribe();
      this.eventStreams.delete(response);
    };
    request.once("close", cleanup);
    response.once("close", cleanup);
    try {
      subscription = await this.options.registry.snapshotAndSubscribe(sessionId, emitAgent);
      if (closed) return;
      writeSse(response, subscription.snapshot);
      subscription.activate();
    } catch (error) {
      writeSse(response, { type: "runtime-error", sessionId, error: { code: "snapshot_failed", message: errorMessage(error) } });
      cleanup();
      response.end();
    }
  }

  private async streamRawFile(request: IncomingMessage, response: ServerResponse, filePath: string): Promise<void> {
    const info = await stat(filePath);
    if (!info.isFile()) throw new RequestError("not_file", "Path is not a file");
    const range = request.headers.range ? /^bytes=(\d*)-(\d*)$/.exec(request.headers.range) : undefined;
    let start: number | undefined;
    let end: number | undefined;
    let status = 200;
    if (range) {
      start = range[1] ? Number(range[1]) : 0;
      end = range[2] ? Number(range[2]) : info.size - 1;
      if (!range[1] && range[2]) { start = Math.max(0, info.size - Number(range[2])); end = info.size - 1; }
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start! < 0 || end! < start! || start! >= info.size) {
        response.writeHead(416, { "content-range": `bytes */${info.size}` }); response.end(); return;
      }
      end = Math.min(end!, info.size - 1); status = 206;
    }
    response.writeHead(status, {
      ...corsHeaders(), "content-type": contentType(filePath), "accept-ranges": "bytes",
      "content-disposition": `inline; filename*=UTF-8''${encodeURIComponent(basename(filePath))}`,
      "content-length": start === undefined ? info.size : end! - start + 1,
      ...(start === undefined ? {} : { "content-range": `bytes ${start}-${end}/${info.size}` }),
    });
    this.options.services.createReadStream(filePath, start, end).pipe(response);
  }
}

function envelopeFor(event: RegistrySessionEvent): Record<string, unknown> {
  if (event.payload && typeof event.payload === "object" && (event.payload as { type?: unknown }).type === "runtime_error") {
    return { type: "runtime-error", sessionId: event.sessionId, error: event.payload };
  }
  return { type: "agent", sessionId: event.sessionId, payload: event.payload };
}

function writeSse(response: ServerResponse, value: unknown): void {
  response.write(`data: ${stringifyMessage(value)}\n\n`);
}

function json(response: ServerResponse, status: number, body: unknown): void {
  const encoded = stringifyMessage(body);
  response.writeHead(status, { ...corsHeaders(), "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(encoded), "cache-control": "no-store" });
  response.end(encoded);
}

function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,PATCH,PUT,DELETE,OPTIONS",
    "access-control-allow-headers": "content-type,last-event-id",
  };
}

async function readJsonBody(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let bytes = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    bytes += buffer.length;
    if (bytes > MAX_BODY_BYTES) throw new RequestError("payload_too_large", "Request body exceeds 16 MiB");
    chunks.push(buffer);
  }
  if (bytes === 0) return {};
  let value: unknown;
  try { value = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new RequestError("invalid_json", "Request body must be valid JSON"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new RequestError("invalid_payload", "Request body must be an object");
  return value as Record<string, unknown>;
}

async function readFormData(request: IncomingMessage): Promise<FormData> {
  const body = Readable.toWeb(request) as ReadableStream<Uint8Array>;
  const headers = new Headers();
  for (const [name, value] of Object.entries(request.headers)) {
    if (Array.isArray(value)) for (const item of value) headers.append(name, item);
    else if (value !== undefined) headers.set(name, value);
  }
  const webRequest = new Request(`http://${request.headers.host ?? "localhost"}${request.url ?? "/"}`, {
    method: request.method,
    headers,
    body,
    duplex: "half",
  } as RequestInit & { duplex: "half" });
  return webRequest.formData();
}

function queryObject(url: URL): Record<string, unknown> { return Object.fromEntries(url.searchParams); }
function isRecord(value: unknown): value is Record<string, unknown> { return !!value && typeof value === "object" && !Array.isArray(value); }
function requireQuery(url: URL, name: string): string { const value = url.searchParams.get(name); if (!value) throw new RequestError("invalid_payload", `${name} is required`); return value; }
function requireString(body: Record<string, unknown>, name: string): string { const value = body[name]; if (typeof value !== "string" || !value.trim()) throw new RequestError("invalid_payload", `${name} must be a non-empty string`); return value; }
function optionalString(body: Record<string, unknown>, name: string): string | undefined { const value = body[name]; if (value === undefined || value === null || value === "") return undefined; if (typeof value !== "string") throw new RequestError("invalid_payload", `${name} must be a string`); return value; }
