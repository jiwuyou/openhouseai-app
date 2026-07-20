import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { WebSocket } from "ws";
import { createRuntimeServer } from "../dist/server.js";

test("protocol v2 routes interleaved event streams to multi-session sockets", { timeout: 20_000 }, async (t) => {
  const agentDir = await mkdtemp(join(tmpdir(), "wuxianpi-stream-server-"));
  const server = createRuntimeServer({ host: "127.0.0.1", port: 0, agentDir, idleTimeoutMs: 0 });
  const address = await server.start();
  const first = new WebSocket(`ws://127.0.0.1:${address.port}/v1/ws`);
  const second = new WebSocket(`ws://127.0.0.1:${address.port}/v1/ws`);
  const firstInbox = inbox(first);
  const secondInbox = inbox(second);
  t.after(async () => {
    first.terminate(); second.terminate();
    await server.stop().catch(() => undefined);
    await rm(agentDir, { recursive: true, force: true });
  });

  const firstReady = await firstInbox.waitFor((message) => message.type === "runtime.ready");
  const secondReady = await secondInbox.waitFor((message) => message.type === "runtime.ready");
  assert.equal(firstReady.protocolVersion, 2);
  assert.deepEqual(firstReady.capabilities, {
    eventAck: 2, eventStreamId: 1, persistentDiagnostics: 1, multiSessionSubscriptions: 1,
  });

  const sessionA = (await request(first, firstInbox, "create-a", "session.create", { cwd: agentDir })).result;
  const sessionB = (await request(first, firstInbox, "create-b", "session.create", { cwd: agentDir })).result;
  assert.notEqual(sessionA.eventStreamId, sessionB.eventStreamId);
  await request(second, secondInbox, "open-a", "session.open", { sessionId: sessionA.sessionId });

  const slotA = await server.registry.getOrOpen(sessionA.sessionId);
  const slotB = await server.registry.getOrOpen(sessionB.sessionId);
  server.registry.emitPromptCompleted(slotA);
  server.registry.emitPromptCompleted(slotB);
  server.registry.emitPromptCompleted(slotA);

  const firstEvents = [
    await firstInbox.waitFor(isAgentEvent), await firstInbox.waitFor(isAgentEvent), await firstInbox.waitFor(isAgentEvent),
  ];
  const secondEvents = [await secondInbox.waitFor(isAgentEvent), await secondInbox.waitFor(isAgentEvent)];
  assert.deepEqual(firstEvents.map((event) => event.sessionId), [sessionA.sessionId, sessionB.sessionId, sessionA.sessionId]);
  assert.deepEqual(secondEvents.map((event) => event.sessionId), [sessionA.sessionId, sessionA.sessionId]);
  assert.deepEqual(firstEvents.map((event) => event.sequence), [1, 1, 2]);
  assert.equal(firstEvents[0].eventStreamId, sessionA.eventStreamId);
  assert.equal(firstEvents[1].eventStreamId, sessionB.eventStreamId);
  assert.equal(firstEvents[0].connectionId, firstReady.connectionId);
  assert.equal(secondEvents[0].connectionId, secondReady.connectionId);
  assert.equal(secondEvents[0].eventStreamId, firstEvents[0].eventStreamId);

  const firstAck = await acknowledge(first, firstInbox, "ack-a", firstEvents[0]);
  assert.equal(firstAck.result.duplicate, false);
  assert.equal(firstAck.result.eventStreamId, sessionA.eventStreamId);
  const sameSequenceOtherStream = await acknowledge(first, firstInbox, "ack-b", firstEvents[1]);
  assert.equal(sameSequenceOtherStream.result.duplicate, false);
  first.send(JSON.stringify({
    id: "ack-a-new-stream", type: "event.ack", sessionId: firstEvents[0].sessionId,
    payload: {
      connectionId: firstEvents[0].connectionId, eventStreamId: "replacement-stream",
      sequence: firstEvents[0].sequence, eventType: firstEvents[0].payload.type,
    },
  }));
  const sameSessionNewStream = await firstInbox.waitFor((message) => message.id === "ack-a-new-stream");
  assert.equal(sameSessionNewStream.result.duplicate, false);
  const duplicateOldStream = await acknowledge(first, firstInbox, "ack-a-again", firstEvents[0]);
  assert.equal(duplicateOldStream.result.duplicate, true);

  first.send(JSON.stringify({
    id: "ack-missing-stream", type: "event.ack", sessionId: sessionA.sessionId,
    payload: { connectionId: firstReady.connectionId, sequence: 1, eventType: "prompt_completed" },
  }));
  const missingStream = await firstInbox.waitFor((message) => message.id === "ack-missing-stream");
  assert.equal(missingStream.ok, false);
  assert.equal(missingStream.error.code, "invalid_payload");
});

function isAgentEvent(message) { return message.type === "agent.event" && message.payload?.type === "prompt_completed"; }

async function request(websocket, messages, id, type, payload, sessionId) {
  websocket.send(JSON.stringify({ id, type, ...(sessionId ? { sessionId } : {}), payload }));
  return messages.waitFor((message) => message.id === id);
}

async function acknowledge(websocket, messages, id, event) {
  websocket.send(JSON.stringify({
    id, type: "event.ack", sessionId: event.sessionId,
    payload: {
      connectionId: event.connectionId, eventStreamId: event.eventStreamId,
      sequence: event.sequence, eventType: event.payload.type,
    },
  }));
  return messages.waitFor((message) => message.id === id);
}

function inbox(websocket) {
  const queue = [];
  const waiters = [];
  websocket.on("message", (data) => {
    queue.push(JSON.parse(data.toString("utf8")));
    pump();
  });
  function pump() {
    for (let waiterIndex = 0; waiterIndex < waiters.length; waiterIndex++) {
      const waiter = waiters[waiterIndex];
      const messageIndex = queue.findIndex(waiter.predicate);
      if (messageIndex < 0) continue;
      waiters.splice(waiterIndex, 1);
      waiter.resolve(queue.splice(messageIndex, 1)[0]);
      waiterIndex--;
    }
  }
  return {
    waitFor(predicate) {
      const index = queue.findIndex(predicate);
      if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0]);
      return new Promise((resolve) => { waiters.push({ predicate, resolve }); pump(); });
    },
  };
}
