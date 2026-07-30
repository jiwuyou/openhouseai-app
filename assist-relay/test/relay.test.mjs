import assert from "node:assert/strict";
import test from "node:test";
import WebSocket from "ws";
import { createRelayServer } from "../dist/relay-server.js";

const ROOM_A = "room_0123456789abcdef";
const ROOM_B = "room_fedcba9876543210";

async function startRelay(options = {}) {
  const relay = createRelayServer({ host: "127.0.0.1", port: 0, pingIntervalMs: 60_000, ...options });
  const address = await relay.listen();
  return {
    relay,
    httpUrl: `http://127.0.0.1:${address.port}`,
    wsUrl: `ws://127.0.0.1:${address.port}`,
  };
}

function connect(baseUrl, room, role) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${baseUrl}/relay?v=1&room=${room}&role=${role}`);
    const onError = (error) => reject(error);
    socket.once("error", onError);
    socket.once("open", () => {
      socket.off("error", onError);
      socket.on("error", () => {});
      resolve(socket);
    });
  });
}

function waitForMessage(socket, predicate, timeoutMs = 2_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off("message", onMessage);
      reject(new Error("Timed out waiting for WebSocket message"));
    }, timeoutMs);
    const onMessage = (data, isBinary) => {
      if (!predicate(data, isBinary)) {
        return;
      }
      clearTimeout(timer);
      socket.off("message", onMessage);
      resolve({ data, isBinary });
    };
    socket.on("message", onMessage);
  });
}

function waitForControl(socket, type, timeoutMs = 2_000) {
  return waitForMessage(socket, (data, isBinary) => {
    if (isBinary) {
      return false;
    }
    try {
      const value = JSON.parse(data.toString());
      return value.relay === 1 && value.type === type;
    } catch {
      return false;
    }
  }, timeoutMs).then(({ data }) => JSON.parse(data.toString()));
}

function waitForClose(socket, timeoutMs = 2_000) {
  return new Promise((resolve, reject) => {
    if (socket.readyState === WebSocket.CLOSED) {
      resolve({ code: 1006, reason: "" });
      return;
    }
    const timer = setTimeout(() => reject(new Error("Timed out waiting for WebSocket close")), timeoutMs);
    socket.once("close", (code, reason) => {
      clearTimeout(timer);
      resolve({ code, reason: reason.toString() });
    });
  });
}

async function closeSocket(socket) {
  if (socket.readyState === WebSocket.CLOSED) {
    return;
  }
  const closed = waitForClose(socket);
  socket.close();
  await closed;
}

async function eventually(predicate, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.ok(predicate(), "condition did not become true before timeout");
}

test("GET /health reports relay status", async () => {
  const { relay, httpUrl } = await startRelay();
  try {
    const response = await fetch(`${httpUrl}/health`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: "ok", protocolVersion: 1, rooms: 0 });
  } finally {
    await relay.close();
  }
});

test("HOST and ASSIST exchange opaque text and binary frames", async () => {
  const { relay, wsUrl } = await startRelay();
  const sockets = [];
  try {
    const host = await connect(wsUrl, ROOM_A, "HOST");
    const assist = await connect(wsUrl, ROOM_A, "ASSIST");
    sockets.push(host, assist);

    const textReceived = waitForMessage(assist, (data, isBinary) => !isBinary && data.toString() === "opaque-text");
    host.send("opaque-text");
    const textFrame = await textReceived;
    assert.equal(textFrame.isBinary, false);

    const expectedBinary = Buffer.from([0, 1, 2, 254, 255]);
    const binaryReceived = waitForMessage(host, (data, isBinary) => isBinary && data.equals(expectedBinary));
    assist.send(expectedBinary);
    const binaryFrame = await binaryReceived;
    assert.equal(binaryFrame.isBinary, true);
  } finally {
    await Promise.all(sockets.map(closeSocket));
    await relay.close();
  }
});

test("a third connection using an occupied role is explicitly closed", async () => {
  const { relay, wsUrl } = await startRelay();
  const sockets = [];
  try {
    const host = await connect(wsUrl, ROOM_A, "HOST");
    sockets.push(host);
    const duplicate = await connect(wsUrl, ROOM_A, "HOST");
    const closed = await waitForClose(duplicate);
    assert.equal(closed.code, 4409);
    assert.equal(closed.reason, "duplicate_role");
  } finally {
    await Promise.all(sockets.map(closeSocket));
    await relay.close();
  }
});

test("frames never cross room boundaries", async () => {
  const { relay, wsUrl } = await startRelay();
  const sockets = [];
  try {
    const hostA = await connect(wsUrl, ROOM_A, "HOST");
    const assistA = await connect(wsUrl, ROOM_A, "ASSIST");
    const hostB = await connect(wsUrl, ROOM_B, "HOST");
    const assistB = await connect(wsUrl, ROOM_B, "ASSIST");
    sockets.push(hostA, assistA, hostB, assistB);

    let leaked = false;
    assistB.on("message", (data, isBinary) => {
      if (!isBinary && data.toString() === "room-a-only") {
        leaked = true;
      }
    });
    const delivered = waitForMessage(assistA, (data, isBinary) => !isBinary && data.toString() === "room-a-only");
    hostA.send("room-a-only");
    await delivered;
    await new Promise((resolve) => setTimeout(resolve, 75));
    assert.equal(leaked, false);
  } finally {
    await Promise.all(sockets.map(closeSocket));
    await relay.close();
  }
});

test("disconnecting a peer emits peer_left to the remaining peer", async () => {
  const { relay, wsUrl } = await startRelay();
  const sockets = [];
  try {
    const host = await connect(wsUrl, ROOM_A, "HOST");
    const assist = await connect(wsUrl, ROOM_A, "ASSIST");
    sockets.push(assist);
    const notification = waitForControl(assist, "peer_left");
    await closeSocket(host);
    const message = await notification;
    assert.deepEqual(message, { relay: 1, type: "peer_left", role: "HOST" });
  } finally {
    await Promise.all(sockets.map(closeSocket));
    await relay.close();
  }
});

test("a room is deleted immediately after both peers disconnect", async () => {
  const { relay, wsUrl } = await startRelay({ emptyRoomTtlMs: 25 });
  try {
    const host = await connect(wsUrl, ROOM_A, "HOST");
    const assist = await connect(wsUrl, ROOM_A, "ASSIST");
    assert.equal(relay.getRoomCount(), 1);
    await Promise.all([closeSocket(host), closeSocket(assist)]);
    await eventually(() => relay.getRoomCount() === 0);
    relay.cleanupRooms(Date.now() + 100);
    assert.equal(relay.getRoomCount(), 0);
  } finally {
    await relay.close();
  }
});

test("an oversized frame is rejected without being forwarded", async () => {
  const { relay, wsUrl } = await startRelay({ maxFrameBytes: 64 });
  const sockets = [];
  try {
    const host = await connect(wsUrl, ROOM_A, "HOST");
    const assist = await connect(wsUrl, ROOM_A, "ASSIST");
    sockets.push(assist);

    let forwarded = false;
    assist.on("message", (data, isBinary) => {
      if (isBinary && data.byteLength === 65) {
        forwarded = true;
      }
    });
    const closed = waitForClose(host);
    host.send(Buffer.alloc(65, 7));
    const result = await closed;
    assert.equal(result.code, 1009);
    await new Promise((resolve) => setTimeout(resolve, 75));
    assert.equal(forwarded, false);
  } finally {
    await Promise.all(sockets.map(closeSocket));
    await relay.close();
  }
});
