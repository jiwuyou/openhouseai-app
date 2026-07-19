import { AsyncLocalStorage } from "node:async_hooks";
import { createServer, type IncomingMessage, type ServerResponse as HttpResponse } from "node:http";
import { WebSocket, WebSocketServer } from "ws";
import { PiSdkAdapter } from "./pi-sdk-adapter.js";
import { type AgentEventEnvelope, failure, parseRequest, PROTOCOL_NAME, RUNTIME_VERSION, stringifyMessage, success } from "./protocol.js";
import { SessionRegistry } from "./session-registry.js";

export interface RuntimeServerOptions { host: string; port: number; idleTimeoutMs?: number; agentDir?: string }

export class SessionSubscriptions<T extends object> {
  private readonly sessions = new Map<T, string>();
  setCurrent(client: T, sessionId: string): void { this.sessions.set(client, sessionId); }
  remove(client: T): void { this.sessions.delete(client); }
  targets(sessionId: string): Set<T> {
    return new Set([...this.sessions].filter(([, id]) => id === sessionId).map(([client]) => client));
  }
}

export function createRuntimeServer(options: RuntimeServerOptions) {
  const clients = new Set<WebSocket>();
  const subscriptions = new SessionSubscriptions<WebSocket>();
  const requestOwner = new AsyncLocalStorage<WebSocket>();
  const inFlightRequests = new Map<WebSocket, number>();
  const registry = new SessionRegistry((event) => routeEvent(event), {
    idleTimeoutMs: options.idleTimeoutMs, agentDir: options.agentDir,
  });
  const adapter = new PiSdkAdapter(registry);
  const websocketServer = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 * 1024 });
  const httpServer = createServer((request, response) => handleHttp(request, response));

  function routeEvent(event: AgentEventEnvelope): void {
    const serialized = stringifyMessage(event);
    const targets = subscriptions.targets(event.sessionId);
    const owner = requestOwner.getStore();
    if (owner && (inFlightRequests.get(owner) ?? 0) > 0) targets.add(owner);
    for (const client of targets) if (client.readyState === WebSocket.OPEN) client.send(serialized);
  }
  function json(response: HttpResponse, status: number, body: unknown): void {
    const encoded = stringifyMessage(body);
    response.writeHead(status, { "content-type": "application/json; charset=utf-8",
      "content-length": Buffer.byteLength(encoded), "cache-control": "no-store" });
    response.end(encoded);
  }
  function handleHttp(request: IncomingMessage, response: HttpResponse): void {
    const path = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`).pathname;
    if (request.method === "GET" && (path === "/health" || path === "/admin/v1/health")) {
      json(response, 200, { ok: true, protocol: PROTOCOL_NAME, version: RUNTIME_VERSION, activeSessions: registry.size });
    } else if (request.method === "GET" && (path === "/" || path === "/v1/status")) {
      json(response, 200, { ok: true, version: RUNTIME_VERSION, ...registry.status(), websocketPath: "/v1/ws" });
    } else json(response, 404, { ok: false, error: { code: "not_found", message: "Not found" } });
  }

  httpServer.on("upgrade", (request, socket, head) => {
    const path = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`).pathname;
    if (path !== "/v1/ws") {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"); socket.destroy(); return;
    }
    websocketServer.handleUpgrade(request, socket, head,
      (websocket) => websocketServer.emit("connection", websocket, request));
  });

  websocketServer.on("connection", (websocket) => {
    clients.add(websocket);
    websocket.send(stringifyMessage({ type: "runtime.ready", version: RUNTIME_VERSION, ...registry.status() }));
    websocket.on("message", (data, isBinary) => {
      if (isBinary) { websocket.send(stringifyMessage(failure("", new Error("Binary messages are not supported")))); return; }
      let requestId = "";
      inFlightRequests.set(websocket, (inFlightRequests.get(websocket) ?? 0) + 1);
      void requestOwner.run(websocket, async () => {
        try {
          const request = parseRequest(data.toString("utf8")); requestId = request.id;
          if (request.sessionId) subscriptions.setCurrent(websocket, request.sessionId);
          const result = await adapter.dispatch(request);
          if (result && typeof result === "object" && "sessionId" in result && typeof result.sessionId === "string") {
            subscriptions.setCurrent(websocket, result.sessionId);
          }
          if (websocket.readyState === WebSocket.OPEN) websocket.send(stringifyMessage(success(request.id, result)));
        } catch (error) {
          if (websocket.readyState === WebSocket.OPEN) websocket.send(stringifyMessage(failure(requestId, error)));
        } finally {
          const remaining = (inFlightRequests.get(websocket) ?? 1) - 1;
          if (remaining > 0) inFlightRequests.set(websocket, remaining); else inFlightRequests.delete(websocket);
        }
      });
    });
    websocket.on("close", () => { clients.delete(websocket); subscriptions.remove(websocket); inFlightRequests.delete(websocket); });
    websocket.on("error", () => { clients.delete(websocket); subscriptions.remove(websocket); inFlightRequests.delete(websocket); });
  });

  return {
    registry,
    async start() {
      await new Promise<void>((resolve, reject) => {
        httpServer.once("error", reject);
        httpServer.listen(options.port, options.host, () => { httpServer.off("error", reject); resolve(); });
      });
      const address = httpServer.address();
      return { host: options.host, port: typeof address === "object" && address ? address.port : options.port };
    },
    async stop(): Promise<void> {
      for (const client of clients) client.close(1001, "runtime stopping");
      await registry.dispose();
      await new Promise<void>((resolve, reject) => httpServer.close((error) => error ? reject(error) : resolve()));
      websocketServer.close();
    },
  };
}
