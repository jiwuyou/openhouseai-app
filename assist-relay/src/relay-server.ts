import { createServer, type IncomingMessage, type Server as HttpServer } from "node:http";
import type { AddressInfo } from "node:net";
import type { Duplex } from "node:stream";
import { WebSocket, WebSocketServer, type RawData } from "ws";

export const RELAY_PROTOCOL_VERSION = "1";
export const DEFAULT_PORT = 20_876;
export const DEFAULT_MAX_FRAME_BYTES = 1_048_576;
export const DEFAULT_MAX_BUFFERED_BYTES = 4_194_304;

const ROOM_ID_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;
const DUPLICATE_ROLE_CLOSE_CODE = 4_409;
const BACKPRESSURE_CLOSE_CODE = 4_429;

export type RelayRole = "HOST" | "ASSIST";

interface RelayPeer {
  readonly role: RelayRole;
  readonly socket: WebSocket;
  alive: boolean;
}

interface RelayRoom {
  readonly id: string;
  host?: RelayPeer;
  assist?: RelayPeer;
  lastActivityAt: number;
}

interface RelayControlMessage {
  relay: 1;
  type: "peer_status" | "peer_left";
  status?: "waiting" | "connected";
  role?: RelayRole;
}

export interface RelayServerOptions {
  host?: string;
  port?: number;
  maxFrameBytes?: number;
  maxBufferedBytes?: number;
  pingIntervalMs?: number;
  emptyRoomTtlMs?: number;
}

export interface RelayServer {
  readonly httpServer: HttpServer;
  readonly webSocketServer: WebSocketServer;
  listen(): Promise<AddressInfo>;
  close(): Promise<void>;
  getRoomCount(): number;
  cleanupRooms(now?: number): void;
}

function isRelayRole(value: string | null): value is RelayRole {
  return value === "HOST" || value === "ASSIST";
}

function peerForRole(room: RelayRoom, role: RelayRole): RelayPeer | undefined {
  return role === "HOST" ? room.host : room.assist;
}

function oppositePeer(room: RelayRoom, role: RelayRole): RelayPeer | undefined {
  return role === "HOST" ? room.assist : room.host;
}

function setPeer(room: RelayRoom, role: RelayRole, peer: RelayPeer | undefined): void {
  if (role === "HOST") {
    room.host = peer;
  } else {
    room.assist = peer;
  }
}

function isConnected(peer: RelayPeer | undefined): peer is RelayPeer {
  return peer?.socket.readyState === WebSocket.OPEN;
}

function isOccupied(peer: RelayPeer | undefined): boolean {
  return peer !== undefined &&
    (peer.socket.readyState === WebSocket.OPEN || peer.socket.readyState === WebSocket.CONNECTING);
}

function rawDataLength(data: RawData): number {
  if (Array.isArray(data)) {
    return data.reduce((total, part) => total + part.byteLength, 0);
  }
  return data.byteLength;
}

function sendControl(peer: RelayPeer | undefined, message: RelayControlMessage): void {
  if (!isConnected(peer)) {
    return;
  }

  peer.socket.send(JSON.stringify(message), { binary: false }, (error) => {
    if (error && peer.socket.readyState === WebSocket.OPEN) {
      peer.socket.terminate();
    }
  });
}

function rejectUpgrade(socket: Duplex, statusCode: number, statusText: string): void {
  const body = `${statusText}\n`;
  socket.end(
    `HTTP/1.1 ${statusCode} ${statusText}\r\n` +
      "Connection: close\r\n" +
      "Content-Type: text/plain; charset=utf-8\r\n" +
      `Content-Length: ${Buffer.byteLength(body)}\r\n` +
      "\r\n" +
      body,
  );
}

function parseConnectionRequest(request: IncomingMessage):
  | { roomId: string; role: RelayRole }
  | { statusCode: number; statusText: string } {
  let url: URL;
  try {
    url = new URL(request.url ?? "", `http://${request.headers.host ?? "localhost"}`);
  } catch {
    return { statusCode: 400, statusText: "Bad Request" };
  }

  if (url.pathname !== "/relay") {
    return { statusCode: 404, statusText: "Not Found" };
  }

  if (url.searchParams.get("v") !== RELAY_PROTOCOL_VERSION) {
    return { statusCode: 400, statusText: "Unsupported Protocol Version" };
  }

  const roomId = url.searchParams.get("room");
  if (roomId === null || !ROOM_ID_PATTERN.test(roomId)) {
    return { statusCode: 400, statusText: "Invalid Room" };
  }

  const role = url.searchParams.get("role");
  if (!isRelayRole(role)) {
    return { statusCode: 400, statusText: "Invalid Role" };
  }

  return { roomId, role };
}

export function createRelayServer(options: RelayServerOptions = {}): RelayServer {
  const host = options.host ?? "0.0.0.0";
  const port = options.port ?? DEFAULT_PORT;
  const maxFrameBytes = options.maxFrameBytes ?? DEFAULT_MAX_FRAME_BYTES;
  const maxBufferedBytes = options.maxBufferedBytes ?? DEFAULT_MAX_BUFFERED_BYTES;
  const pingIntervalMs = options.pingIntervalMs ?? 30_000;
  const emptyRoomTtlMs = options.emptyRoomTtlMs ?? 60_000;
  const rooms = new Map<string, RelayRoom>();

  const httpServer = createServer((request, response) => {
    const requestUrl = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    if (request.method === "GET" && requestUrl.pathname === "/health") {
      const body = JSON.stringify({
        status: "ok",
        protocolVersion: Number(RELAY_PROTOCOL_VERSION),
        rooms: rooms.size,
      });
      response.writeHead(200, {
        "content-type": "application/json; charset=utf-8",
        "content-length": Buffer.byteLength(body),
        "cache-control": "no-store",
      });
      response.end(body);
      return;
    }

    response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    response.end("Not Found\n");
  });

  const webSocketServer = new WebSocketServer({
    noServer: true,
    maxPayload: maxFrameBytes,
    perMessageDeflate: false,
  });

  function cleanupRooms(now = Date.now()): void {
    for (const [roomId, room] of rooms) {
      const hostConnected = isOccupied(room.host);
      const assistConnected = isOccupied(room.assist);
      if (!hostConnected && !assistConnected && now - room.lastActivityAt >= emptyRoomTtlMs) {
        rooms.delete(roomId);
      }
    }
  }

  function removePeer(room: RelayRoom, peer: RelayPeer): void {
    if (peerForRole(room, peer.role)?.socket !== peer.socket) {
      return;
    }

    setPeer(room, peer.role, undefined);
    room.lastActivityAt = Date.now();
    const remainingPeer = oppositePeer(room, peer.role);
    sendControl(remainingPeer, { relay: 1, type: "peer_left", role: peer.role });

    if (room.host === undefined && room.assist === undefined) {
      rooms.delete(room.id);
    }
  }

  function attachPeer(socket: WebSocket, roomId: string, role: RelayRole): void {
    const now = Date.now();
    const room = rooms.get(roomId) ?? { id: roomId, lastActivityAt: now };
    const existing = peerForRole(room, role);
    if (isOccupied(existing)) {
      socket.close(DUPLICATE_ROLE_CLOSE_CODE, "duplicate_role");
      return;
    }

    const peer: RelayPeer = { role, socket, alive: true };
    setPeer(room, role, peer);
    room.lastActivityAt = now;
    rooms.set(roomId, room);

    socket.on("pong", () => {
      peer.alive = true;
    });

    socket.on("message", (data, isBinary) => {
      room.lastActivityAt = Date.now();
      const destination = oppositePeer(room, role);
      if (!isConnected(destination)) {
        sendControl(peer, { relay: 1, type: "peer_status", status: "waiting" });
        return;
      }

      const frameBytes = rawDataLength(data);
      if (destination.socket.bufferedAmount + frameBytes > maxBufferedBytes) {
        destination.socket.close(BACKPRESSURE_CLOSE_CODE, "backpressure_limit");
        return;
      }

      destination.socket.send(data, { binary: isBinary }, (error) => {
        if (error && destination.socket.readyState === WebSocket.OPEN) {
          destination.socket.terminate();
        }
      });
    });

    socket.once("close", () => removePeer(room, peer));
    socket.on("error", () => {
      // The close event owns room cleanup; errors are connection-local.
    });

    const destination = oppositePeer(room, role);
    if (isConnected(destination)) {
      sendControl(peer, { relay: 1, type: "peer_status", status: "connected", role: destination.role });
      sendControl(destination, { relay: 1, type: "peer_status", status: "connected", role });
    } else {
      sendControl(peer, { relay: 1, type: "peer_status", status: "waiting" });
    }
  }

  httpServer.on("upgrade", (request, socket, head) => {
    const parsed = parseConnectionRequest(request);
    if ("statusCode" in parsed) {
      rejectUpgrade(socket, parsed.statusCode, parsed.statusText);
      return;
    }

    webSocketServer.handleUpgrade(request, socket, head, (webSocket) => {
      attachPeer(webSocket, parsed.roomId, parsed.role);
    });
  });

  const maintenanceTimer = setInterval(() => {
    for (const socket of webSocketServer.clients) {
      const peer = [...rooms.values()]
        .flatMap((room) => [room.host, room.assist])
        .find((candidate) => candidate?.socket === socket);
      if (peer === undefined) {
        if (socket.readyState === WebSocket.OPEN) {
          socket.terminate();
        }
        continue;
      }

      if (!peer.alive) {
        socket.terminate();
        continue;
      }

      peer.alive = false;
      if (socket.readyState === WebSocket.OPEN) {
        socket.ping();
      }
    }
    cleanupRooms();
  }, pingIntervalMs);
  maintenanceTimer.unref();

  return {
    httpServer,
    webSocketServer,
    listen: () => new Promise<AddressInfo>((resolve, reject) => {
      const onError = (error: Error): void => reject(error);
      httpServer.once("error", onError);
      httpServer.listen(port, host, () => {
        httpServer.off("error", onError);
        const address = httpServer.address();
        if (address === null || typeof address === "string") {
          reject(new Error("Relay did not receive a TCP address"));
          return;
        }
        resolve(address);
      });
    }),
    close: async () => {
      clearInterval(maintenanceTimer);
      for (const client of webSocketServer.clients) {
        client.terminate();
      }
      rooms.clear();

      await new Promise<void>((resolve) => {
        webSocketServer.close(() => resolve());
      });
      await new Promise<void>((resolve, reject) => {
        if (!httpServer.listening) {
          resolve();
          return;
        }
        httpServer.close((error) => error ? reject(error) : resolve());
      });
    },
    getRoomCount: () => rooms.size,
    cleanupRooms,
  };
}
