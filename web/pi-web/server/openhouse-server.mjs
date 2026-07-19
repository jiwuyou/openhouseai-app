import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import next from "next";
import { WebSocket, WebSocketServer } from "ws";

const hostname = process.env.HOSTNAME || process.env.PI_WEB_HOST || "127.0.0.1";
const port = Number(process.env.PORT || process.env.PI_WEB_PORT || 30141);
const runtimeOrigin = (process.env.OPENHOUSE_PI_RUNTIME_ORIGIN || "http://127.0.0.1:8765").replace(/\/$/, "");

async function runtimeToken() {
  const inline = process.env.OPENHOUSE_PI_RUNTIME_TOKEN?.trim();
  if (inline) return inline;
  const tokenFile = process.env.OPENHOUSE_PI_RUNTIME_TOKEN_FILE?.trim();
  if (!tokenFile) return "";
  try { return (await readFile(tokenFile, "utf8")).trim(); } catch { return ""; }
}

const app = next({ dev: false, hostname, port });
await app.prepare();
const requestHandler = app.getRequestHandler();
const server = createServer((request, response) => requestHandler(request, response));
const browserWs = new WebSocketServer({ noServer: true });

server.on("upgrade", async (request, socket, head) => {
  const url = new URL(request.url || "/", `http://${request.headers.host || `${hostname}:${port}`}`);
  if (url.pathname !== "/ws/pi") {
    socket.destroy();
    return;
  }
  const leaseId = url.searchParams.get("leaseId");
  if (!leaseId) {
    socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
    return;
  }
  const token = await runtimeToken();
  const target = new URL(runtimeOrigin.replace(/^http:/, "ws:").replace(/^https:/, "wss:"));
  target.pathname = `/ws/rpc/${encodeURIComponent(leaseId)}`;
  target.search = "";

  browserWs.handleUpgrade(request, socket, head, (client) => {
    const upstream = new WebSocket(target, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
    const queued = [];
    client.on("message", (data, isBinary) => {
      if (isBinary) return;
      if (upstream.readyState === WebSocket.OPEN) upstream.send(data, { binary: false });
      else if (upstream.readyState === WebSocket.CONNECTING) queued.push(data.toString());
    });
    upstream.on("open", () => {
      for (const frame of queued.splice(0)) upstream.send(frame);
    });
    upstream.on("message", (data, isBinary) => {
      if (!isBinary && client.readyState === WebSocket.OPEN) client.send(data, { binary: false });
    });
    upstream.on("close", (code, reason) => {
      if (client.readyState === WebSocket.OPEN) client.close(code || 1011, reason.toString().slice(0, 123));
    });
    upstream.on("error", () => {
      if (client.readyState === WebSocket.OPEN) client.close(1011, "Pi runtime unavailable");
    });
    client.on("close", () => upstream.close(1000, "browser disconnected"));
    client.on("error", () => upstream.close(1011, "browser WebSocket error"));
  });
});

server.listen(port, hostname, () => {
  process.stdout.write(`pi-web listening on http://${hostname}:${port}\n`);
});
