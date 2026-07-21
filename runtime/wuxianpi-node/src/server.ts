import { AsyncLocalStorage } from "node:async_hooks";
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse as HttpResponse } from "node:http";
import { join } from "node:path";
import { getAgentDir } from "@earendil-works/pi-coding-agent";
import { WebSocket, WebSocketServer } from "ws";
import { PersistentDiagnostics } from "./diagnostics.js";
import { NativeEventProjector } from "./native-event-projector.js";
import { PiSdkAdapter } from "./pi-sdk-adapter.js";
import {
  boundedInteger,
  failure,
  parseRequest,
  PROTOCOL_NAME,
  PROTOCOL_VERSION,
  RequestError,
  requireString,
  type RuntimeAgentEventEnvelope,
  RUNTIME_VERSION,
  stringifyMessage,
  success,
} from "./protocol.js";
import { SessionRegistry } from "./session-registry.js";
import { StaticFiles } from "./static-files.js";
import { WebApi } from "./web-api.js";
import { WebServices } from "./web-services.js";

export interface RuntimeServerOptions {
  host: string;
  port: number;
  idleTimeoutMs?: number;
  agentDir?: string;
  diagnosticsMaxFileBytes?: number;
  diagnosticsMaxFiles?: number;
  webRoot?: string;
  preferredWebUiUrl?: string;
}

interface ConnectionContext {
  id: string;
  connectedAt: number;
  remoteAddress?: string;
  acknowledgements: Set<string>;
  acknowledgementOrder: string[];
  cleanedUp: boolean;
}

const CAPABILITIES = {
  eventAck: 2,
  eventStreamId: 1,
  persistentDiagnostics: 1,
  multiSessionSubscriptions: 1,
} as const;
const MAX_ACKNOWLEDGEMENTS_PER_CONNECTION = 4096;

export class SessionSubscriptions<T extends object> {
  private readonly sessions = new Map<T, Set<string>>();
  subscribe(client: T, sessionId: string): boolean {
    const current = this.sessions.get(client) ?? new Set<string>();
    const added = !current.has(sessionId);
    current.add(sessionId);
    this.sessions.set(client, current);
    return added;
  }
  all(client: T): string[] { return [...(this.sessions.get(client) ?? [])]; }
  remove(client: T): string[] {
    const previous = this.all(client);
    this.sessions.delete(client);
    return previous;
  }
  targets(sessionId: string): Set<T> {
    return new Set([...this.sessions].filter(([, ids]) => ids.has(sessionId)).map(([client]) => client));
  }
}

export function createRuntimeServer(options: RuntimeServerOptions) {
  const agentDir = options.agentDir ?? getAgentDir();
  const diagnostics = new PersistentDiagnostics({
    directory: join(agentDir, "logs", "wuxianpi-diagnostics"),
    maxFileBytes: options.diagnosticsMaxFileBytes,
    maxFiles: options.diagnosticsMaxFiles,
  });
  const clients = new Set<WebSocket>();
  const connections = new Map<WebSocket, ConnectionContext>();
  const subscriptions = new SessionSubscriptions<WebSocket>();
  const requestOwner = new AsyncLocalStorage<WebSocket>();
  const inFlightRequests = new Map<WebSocket, number>();
  const registry = new SessionRegistry(undefined, {
    idleTimeoutMs: options.idleTimeoutMs,
    agentDir,
    diagnostics,
  });
  const nativeEvents = new NativeEventProjector(registry);
  registry.subscribe((event) => routeEvent(nativeEvents.project(event)));
  const adapter = new PiSdkAdapter(registry);
  const webServices = new WebServices({ agentDir, registry });
  const staticFiles = new StaticFiles(options.webRoot);
  const runtimeCapabilities = {
    ...CAPABILITIES,
    webApi: 1,
    snapshotSse: 1,
    staticWebUi: staticFiles.enabled ? 1 : 0,
  } as const;
  const preferredWebUiUrl = options.preferredWebUiUrl ?? "http://127.0.0.1:25808/";
  const webApi = new WebApi({
    registry,
    services: webServices,
    status: () => ({
      version: RUNTIME_VERSION,
      protocolVersion: PROTOCOL_VERSION,
      protocol: PROTOCOL_NAME,
      ...registry.status(),
      eventTransport: "snapshot-sse-v1",
      nativeWebsocketPath: "/v1/ws",
      capabilities: runtimeCapabilities,
    }),
  });
  const websocketServer = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 * 1024 });
  const httpServer = createServer((request, response) => {
    void handleHttp(request, response).catch((error) => {
      diagnostics.record("http.request.failed", {
        method: request.method,
        path: request.url,
        errorName: error instanceof Error ? error.name : typeof error,
      }, { error });
      if (!response.headersSent) json(response, 500, { ok: false, error: { code: "runtime_error", message: "Internal server error" } });
      else response.end();
    });
  });

  function routeEvent(event: RuntimeAgentEventEnvelope): void {
    const diagnosticType = eventType(event.payload);
    const highFrequency = isHighFrequencyEvent(event.payload);
    const targets = subscriptions.targets(event.sessionId);
    const owner = requestOwner.getStore();
    const ownerAdded = !!owner && (inFlightRequests.get(owner) ?? 0) > 0 && !targets.has(owner);
    if (owner && (inFlightRequests.get(owner) ?? 0) > 0) targets.add(owner);
    if (highFrequency) {
      diagnostics.recordStream({
        stage: "targets",
        sessionId: event.sessionId,
        eventStreamId: event.eventStreamId,
        sequence: event.sequence,
        eventType: diagnosticType,
        targetCount: targets.size,
      });
    } else {
      diagnostics.record("event.targets", {
        sessionId: event.sessionId,
        eventStreamId: event.eventStreamId,
        sequence: event.sequence,
        eventType: diagnosticType,
        targetCount: targets.size,
        ownerAdded,
        connectionIds: [...targets].map((client) => connections.get(client)?.id).filter(Boolean),
      });
    }
    for (const client of targets) {
      const connection = connections.get(client);
      if (!connection) continue;
      if (client.readyState !== WebSocket.OPEN) {
        const skipped = {
          connectionId: connection.id,
          sessionId: event.sessionId,
          eventStreamId: event.eventStreamId,
          sequence: event.sequence,
          eventType: diagnosticType,
          readyState: client.readyState,
        };
        if (highFrequency) {
          diagnostics.recordStream({
            stage: "send_skipped",
            connectionId: connection.id,
            sessionId: event.sessionId,
            eventStreamId: event.eventStreamId,
            sequence: event.sequence,
            eventType: diagnosticType,
          }, skipped);
        } else diagnostics.record("event.send.skipped", skipped);
        continue;
      }
      send(client, { ...event, connectionId: connection.id }, {
        kind: "agent.event",
        sessionId: event.sessionId,
        eventStreamId: event.eventStreamId,
        sequence: event.sequence,
        eventType: diagnosticType,
      }, highFrequency);
    }
  }

  function send(
    websocket: WebSocket,
    value: unknown,
    metadata: Record<string, unknown>,
    highFrequency = false,
  ): void {
    const connectionId = connections.get(websocket)?.id;
    const serialized = stringifyMessage(value);
    const bytes = Buffer.byteLength(serialized);
    if (highFrequency) {
      diagnostics.recordStream({
        stage: "send",
        connectionId,
        sessionId: typeof metadata.sessionId === "string" ? metadata.sessionId : undefined,
        eventStreamId: typeof metadata.eventStreamId === "string" ? metadata.eventStreamId : undefined,
        sequence: typeof metadata.sequence === "number" ? metadata.sequence : undefined,
        eventType: typeof metadata.eventType === "string" ? metadata.eventType : "unknown",
        bytes,
      });
    } else {
      diagnostics.record("websocket.send", { connectionId, bytes, ...metadata });
    }
    try {
      websocket.send(serialized, (error) => {
        if (error) {
          diagnostics.record("websocket.send.failed", {
            connectionId,
            errorName: error.name,
            ...metadata,
          }, { error });
        } else if (!highFrequency) {
          diagnostics.record("websocket.send.completed", { connectionId, ...metadata });
        }
      });
    } catch (error) {
      diagnostics.record("websocket.send.failed", {
        connectionId,
        errorName: error instanceof Error ? error.name : typeof error,
        ...metadata,
      }, { error });
    }
  }

  function json(response: HttpResponse, status: number, body: unknown): void {
    const encoded = stringifyMessage(body);
    response.writeHead(status, {
      "content-type": "application/json; charset=utf-8",
      "content-length": Buffer.byteLength(encoded),
      "cache-control": "no-store",
    });
    response.end(encoded);
  }

  async function handleHttp(request: IncomingMessage, response: HttpResponse): Promise<void> {
    const path = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`).pathname;
    const runtimeOrigin = `http://${request.headers.host ?? "127.0.0.1:8765"}`;
    if (await webApi.handle(request, response)) return;
    if (request.method === "GET" && (path === "/health" || path === "/admin/v1/health")) {
      json(response, 200, {
        ok: true,
        protocol: PROTOCOL_NAME,
        protocolVersion: PROTOCOL_VERSION,
        version: RUNTIME_VERSION,
        activeSessions: registry.size,
        capabilities: runtimeCapabilities,
        uiMetadataPath: "/v1/ui/metadata",
      });
    } else if (request.method === "GET" && path === "/v1/ui/metadata") {
      json(response, 200, {
        ok: true,
        schemaVersion: 1,
        preferred: { id: "aionui", url: preferredWebUiUrl },
        fallback: {
          id: "wuxianpi-builtin",
          url: `${runtimeOrigin}/`,
          available: staticFiles.enabled,
        },
        webApiUrl: `${runtimeOrigin}/api/web/v1`,
        capabilities: runtimeCapabilities,
      });
    } else if (request.method === "GET" && path === "/v1/status") {
      json(response, 200, {
        ok: true,
        version: RUNTIME_VERSION,
        protocolVersion: PROTOCOL_VERSION,
        protocol: PROTOCOL_NAME,
        ...registry.status(),
        websocketPath: "/v1/ws",
        capabilities: runtimeCapabilities,
        uiMetadataPath: "/v1/ui/metadata",
        diagnostics: diagnostics.status(),
      });
    } else if (await staticFiles.serve(request, response, path)) return;
    else if (request.method === "GET" && path === "/") {
      json(response, 200, {
        ok: true,
        version: RUNTIME_VERSION,
        protocolVersion: PROTOCOL_VERSION,
        protocol: PROTOCOL_NAME,
        ...registry.status(),
        websocketPath: "/v1/ws",
        webApiPath: "/api/web/v1",
        capabilities: runtimeCapabilities,
        uiMetadataPath: "/v1/ui/metadata",
        diagnostics: diagnostics.status(),
      });
    } else json(response, 404, { ok: false, error: { code: "not_found", message: "Not found" } });
  }

  httpServer.on("upgrade", (request, socket, head) => {
    const path = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`).pathname;
    if (path !== "/v1/ws") {
      diagnostics.record("websocket.upgrade.rejected", { path, remoteAddress: request.socket.remoteAddress });
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    websocketServer.handleUpgrade(request, socket, head,
      (websocket) => websocketServer.emit("connection", websocket, request));
  });

  websocketServer.on("connection", (websocket, request) => {
    const connection: ConnectionContext = {
      id: randomUUID(),
      connectedAt: Date.now(),
      remoteAddress: request.socket.remoteAddress,
      acknowledgements: new Set(),
      acknowledgementOrder: [],
      cleanedUp: false,
    };
    clients.add(websocket);
    connections.set(websocket, connection);
    diagnostics.record("websocket.connected", {
      connectionId: connection.id,
      remoteAddress: connection.remoteAddress,
      clientCount: clients.size,
    }, {
      headers: request.headers,
    });
    void nativeEvents.decorateResult(registry.status()).then((nativeStatus) => send(websocket, {
      type: "runtime.ready",
      version: RUNTIME_VERSION,
      protocolVersion: PROTOCOL_VERSION,
      connectionId: connection.id,
      protocol: PROTOCOL_NAME,
      capabilities: CAPABILITIES,
      ...(nativeStatus as object),
    }, { kind: "runtime.ready" }));

    websocket.on("message", (data, isBinary) => {
      diagnostics.record("websocket.frame.received", {
        connectionId: connection.id,
        binary: isBinary,
        bytes: rawDataBytes(data),
      });
      if (isBinary) {
        send(websocket, failure("", new RequestError("unsupported_frame", "Binary messages are not supported"), connection.id), {
          kind: "response",
          requestId: "",
          ok: false,
        });
        return;
      }
      let requestId = "";
      inFlightRequests.set(websocket, (inFlightRequests.get(websocket) ?? 0) + 1);
      void requestOwner.run(websocket, async () => {
        let requestType = "unknown";
        try {
          const request = parseRequest(data.toString("utf8"));
          requestId = request.id;
          requestType = request.type;
          diagnostics.record("request.received", {
            connectionId: connection.id,
            requestId: request.id,
            requestType: request.type,
            sessionId: request.sessionId,
            payloadKeys: Object.keys(request.payload ?? {}).sort(),
          }, { payload: request.payload });

          let result: unknown;
          if (request.type === "event.ack") {
            result = acknowledgeEvent(connection, request.sessionId, request.payload ?? {});
          } else if (request.type.startsWith("diagnostics.")) {
            if (request.sessionId) {
              throw new RequestError("non_session_command", `${request.type} must not include sessionId`);
            }
            result = await dispatchDiagnostics(request.type, request.payload ?? {});
          } else {
            if (request.sessionId) updateSubscription(websocket, request.sessionId, request.type);
            result = await nativeEvents.decorateResult(await adapter.dispatch(request));
            if (result && typeof result === "object" && "sessionId" in result && typeof result.sessionId === "string") {
              updateSubscription(websocket, result.sessionId, `${request.type}:result`);
            }
          }
          diagnostics.record("request.completed", {
            connectionId: connection.id,
            requestId: request.id,
            requestType: request.type,
            ok: true,
            resultType: result === null ? "null" : Array.isArray(result) ? "array" : typeof result,
          });
          if (websocket.readyState === WebSocket.OPEN) {
            send(websocket, success(request.id, result, connection.id), {
              kind: "response",
              requestId: request.id,
              requestType: request.type,
              ok: true,
            });
          }
        } catch (error) {
          diagnostics.record("request.failed", {
            connectionId: connection.id,
            requestId,
            requestType,
            errorName: error instanceof Error ? error.name : typeof error,
            errorCode: error instanceof RequestError ? error.code : undefined,
          }, { error });
          if (websocket.readyState === WebSocket.OPEN) {
            send(websocket, failure(requestId, error, connection.id), {
              kind: "response",
              requestId,
              requestType,
              ok: false,
            });
          }
        } finally {
          const remaining = (inFlightRequests.get(websocket) ?? 1) - 1;
          if (remaining > 0) inFlightRequests.set(websocket, remaining);
          else inFlightRequests.delete(websocket);
        }
      });
    });

    websocket.on("close", (code, reason) => {
      diagnostics.flushStreamSummaries("connection_closed", { connectionId: connection.id });
      diagnostics.record("websocket.closed", {
        connectionId: connection.id,
        code,
        reasonBytes: reason.length,
        lifetimeMs: Date.now() - connection.connectedAt,
        subscriptions: subscriptions.all(websocket),
      }, { reason: reason.toString("utf8") });
      cleanupConnection(websocket, connection, "close");
    });
    websocket.on("error", (error) => {
      diagnostics.flushStreamSummaries("connection_error", { connectionId: connection.id });
      diagnostics.record("websocket.error", {
        connectionId: connection.id,
        errorName: error.name,
        lifetimeMs: Date.now() - connection.connectedAt,
        subscriptions: subscriptions.all(websocket),
      }, { error });
      cleanupConnection(websocket, connection, "error");
    });
  });

  function updateSubscription(websocket: WebSocket, sessionId: string, source: string): void {
    if (!subscriptions.subscribe(websocket, sessionId)) return;
    diagnostics.record("subscription.added", {
      connectionId: connections.get(websocket)?.id,
      sessionId,
      source,
      subscriptions: subscriptions.all(websocket),
    });
  }

  function cleanupConnection(websocket: WebSocket, connection: ConnectionContext, trigger: string): void {
    if (connection.cleanedUp) return;
    connection.cleanedUp = true;
    clients.delete(websocket);
    connections.delete(websocket);
    const sessionIds = subscriptions.remove(websocket);
    inFlightRequests.delete(websocket);
    diagnostics.record("websocket.cleanup", {
      connectionId: connection.id,
      trigger,
      sessionIds,
      clientCount: clients.size,
    });
  }

  function acknowledgeEvent(
    connection: ConnectionContext,
    topLevelSessionId: string | undefined,
    payload: Record<string, unknown>,
  ): unknown {
    const suppliedConnectionId = requireString(payload, "connectionId");
    if (suppliedConnectionId !== connection.id) {
      throw new RequestError("connection_mismatch", "event.ack connectionId does not match this WebSocket connection", {
        expectedConnectionId: connection.id,
      });
    }
    const sessionId = topLevelSessionId ?? requireString(payload, "sessionId");
    const eventStreamId = requireString(payload, "eventStreamId");
    const sequence = payload.sequence;
    if (!Number.isSafeInteger(sequence) || (sequence as number) < 0) {
      throw new RequestError("invalid_payload", "sequence must be a non-negative safe integer");
    }
    const eventTypeValue = requireString(payload, "eventType");
    const key = `${sessionId}\u0000${eventStreamId}\u0000${sequence}\u0000${eventTypeValue}`;
    const duplicate = connection.acknowledgements.has(key);
    if (!duplicate) {
      connection.acknowledgements.add(key);
      connection.acknowledgementOrder.push(key);
      if (connection.acknowledgementOrder.length > MAX_ACKNOWLEDGEMENTS_PER_CONNECTION) {
        const oldest = connection.acknowledgementOrder.shift();
        if (oldest) connection.acknowledgements.delete(oldest);
      }
    }
    diagnostics.record("event.ack", {
      connectionId: connection.id,
      sessionId,
      eventStreamId,
      sequence,
      eventType: eventTypeValue,
      duplicate,
      receivedAt: typeof payload.receivedAt === "string" || typeof payload.receivedAt === "number"
        ? payload.receivedAt : undefined,
      promptGateOccupied: typeof payload.promptGateOccupied === "boolean" ? payload.promptGateOccupied : undefined,
    });
    return {
      acknowledged: true,
      duplicate,
      connectionId: connection.id,
      sessionId,
      eventStreamId,
      sequence,
      eventType: eventTypeValue,
    };
  }

  async function dispatchDiagnostics(type: string, payload: Record<string, unknown>): Promise<unknown> {
    switch (type) {
      case "diagnostics.status":
        return diagnostics.status();
      case "diagnostics.detail": {
        if (typeof payload.enabled !== "boolean") {
          throw new RequestError("invalid_payload", "enabled must be a boolean");
        }
        const durationMs = boundedInteger(payload, "durationMs", 120_000, 120_000);
        return diagnostics.setDetailed(payload.enabled, durationMs);
      }
      case "diagnostics.export":
        return diagnostics.exportSnapshot();
      default:
        throw new RequestError("unknown_command", `Unknown command: ${type}`);
    }
  }

  return {
    registry,
    nativeEvents,
    diagnostics,
    async start() {
      await new Promise<void>((resolve, reject) => {
        httpServer.once("error", reject);
        httpServer.listen(options.port, options.host, () => {
          httpServer.off("error", reject);
          resolve();
        });
      });
      const address = httpServer.address();
      const listeningPort = typeof address === "object" && address ? address.port : options.port;
      diagnostics.record("runtime.started", {
        host: options.host,
        port: listeningPort,
        protocol: PROTOCOL_NAME,
        version: RUNTIME_VERSION,
      });
      return { host: options.host, port: listeningPort };
    },
    async stop(): Promise<void> {
      diagnostics.record("runtime.stop.requested", { clients: clients.size, activeSessions: registry.size });
      for (const client of clients) client.close(1001, "runtime stopping");
      webApi.close();
      await registry.dispose();
      await new Promise<void>((resolve, reject) => httpServer.close((error) => error ? reject(error) : resolve()));
      websocketServer.close();
      diagnostics.record("runtime.stopped", { clients: clients.size, activeSessions: registry.size });
      await diagnostics.close();
    },
  };
}

function eventType(payload: unknown): string {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return typeof payload;
  const value = payload as { type?: unknown; assistantMessageEvent?: { type?: unknown } };
  if (value.type === "message_update") {
    const nestedType = value.assistantMessageEvent?.type;
    if (nestedType === "text_delta" || nestedType === "thinking_delta") return nestedType;
  }
  return typeof value.type === "string" ? value.type : "unknown";
}

function isHighFrequencyEvent(payload: unknown): boolean {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return false;
  const value = payload as { type?: unknown; assistantMessageEvent?: { type?: unknown } };
  return value.type === "message_update" || value.type === "tool_execution_update" ||
    value.type === "text_delta" || value.type === "thinking_delta" ||
    value.assistantMessageEvent?.type === "text_delta" || value.assistantMessageEvent?.type === "thinking_delta";
}

function rawDataBytes(data: WebSocket.RawData): number {
  if (data instanceof ArrayBuffer) return data.byteLength;
  if (Array.isArray(data)) return data.reduce((total, item) => total + item.byteLength, 0);
  return data.byteLength;
}
