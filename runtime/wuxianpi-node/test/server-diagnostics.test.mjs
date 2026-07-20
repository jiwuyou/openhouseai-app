import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { WebSocket } from "ws";
import { createRuntimeServer } from "../dist/server.js";

test("runtime identifies connections, acknowledges events idempotently, and exports diagnostics", async (t) => {
  const agentDir = await mkdtemp(join(tmpdir(), "wuxianpi-server-"));
  const server = createRuntimeServer({ host: "127.0.0.1", port: 0, agentDir, idleTimeoutMs: 0 });
  const address = await server.start();
  const websocket = new WebSocket(`ws://127.0.0.1:${address.port}/v1/ws`);
  t.after(async () => {
    if (websocket.readyState === WebSocket.OPEN || websocket.readyState === WebSocket.CONNECTING) websocket.terminate();
    await server.stop().catch(() => undefined);
    await rm(agentDir, { recursive: true, force: true });
  });
  const messages = messageReader(websocket);

  const ready = await messages.next();
  assert.equal(ready.type, "runtime.ready");
  assert.equal(typeof ready.connectionId, "string");
  assert.equal(ready.protocolVersion, 2);
  assert.deepEqual(ready.capabilities, {
    eventAck: 2, eventStreamId: 1, persistentDiagnostics: 1, multiSessionSubscriptions: 1,
  });

  websocket.send(JSON.stringify({ id: "status", type: "runtime.status" }));
  const status = await messages.next();
  assert.equal(status.ok, true);
  assert.equal(status.connectionId, ready.connectionId);

  const acknowledgement = {
    id: "ack-1",
    type: "event.ack",
    sessionId: "session-a",
    payload: {
      connectionId: ready.connectionId,
      eventStreamId: "stream-a",
      sequence: 42,
      eventType: "agent_settled",
      receivedAt: Date.now(),
      promptGateOccupied: false,
    },
  };
  websocket.send(JSON.stringify(acknowledgement));
  const firstAck = await messages.next();
  assert.equal(firstAck.ok, true);
  assert.equal(firstAck.result.duplicate, false);
  assert.equal(firstAck.result.eventStreamId, "stream-a");
  websocket.send(JSON.stringify({ ...acknowledgement, id: "ack-2" }));
  const duplicateAck = await messages.next();
  assert.equal(duplicateAck.ok, true);
  assert.equal(duplicateAck.result.duplicate, true);

  websocket.send(JSON.stringify({
    ...acknowledgement,
    id: "ack-wrong-connection",
    payload: { ...acknowledgement.payload, connectionId: "another-connection" },
  }));
  const wrongConnection = await messages.next();
  assert.equal(wrongConnection.ok, false);
  assert.equal(wrongConnection.error.code, "connection_mismatch");

  websocket.send(JSON.stringify({
    id: "detail",
    type: "diagnostics.detail",
    payload: { enabled: true, durationMs: 999_999 },
  }));
  const detail = await messages.next();
  assert.equal(detail.ok, true);
  assert.equal(detail.result.detailed, true);
  assert.equal(detail.result.maxDetailDurationMs, 120_000);

  websocket.send(JSON.stringify({
    id: "redaction-probe",
    type: "not.a.command",
    payload: { apiKey: "sk-server-secret-123456", message: "private prompt body" },
  }));
  assert.equal((await messages.next()).ok, false);

  websocket.send(JSON.stringify({ id: "export", type: "diagnostics.export" }));
  const exported = await messages.next();
  assert.equal(exported.ok, true);
  assert.equal(typeof exported.result.path, "string");
  assert.equal(typeof exported.result.content, "string");
  assert.match(exported.result.content, /websocket\.connected/);
  assert.match(exported.result.content, /event\.ack/);
  assert.doesNotMatch(exported.result.content, /sk-server-secret-123456/);
  assert.doesNotMatch(exported.result.content, /private prompt body/);

  websocket.close(1000, "test complete");
  await messages.closed();
});

function messageReader(websocket) {
  const queue = [];
  const waiters = [];
  let closedResolve;
  const closedPromise = new Promise((resolve) => { closedResolve = resolve; });
  websocket.on("message", (data) => {
    const value = JSON.parse(data.toString("utf8"));
    const waiter = waiters.shift();
    if (waiter) waiter(value);
    else queue.push(value);
  });
  websocket.on("close", () => closedResolve());
  return {
    next() {
      const value = queue.shift();
      if (value) return Promise.resolve(value);
      return new Promise((resolve) => waiters.push(resolve));
    },
    closed() { return closedPromise; },
  };
}
