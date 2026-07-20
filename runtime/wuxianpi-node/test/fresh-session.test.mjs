import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { NativeEventProjector } from "../dist/native-event-projector.js";
import { SessionRegistry } from "../dist/session-registry.js";

test("fresh unsaved session supports history, list, and reconnect open", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-fresh-"));
  const registry = new SessionRegistry(() => {}, { agentDir: join(root, "agent"), idleTimeoutMs: 0 });
  try {
    const created = await registry.create(root);
    const history = await registry.history(created.sessionId, 0, 100);
    assert.equal(history.sessionId, created.sessionId);
    assert.deepEqual(history.messages, []);
    const listed = await registry.list({ all: true, offset: 0, limit: 100 });
    assert.equal(listed.sessions.some((session) => session.sessionId === created.sessionId), true);
    const reopened = await registry.open(created.sessionId);
    assert.equal(reopened.sessionId, created.sessionId);
    assert.equal(registry.size, 1);
  } finally {
    await registry.dispose();
    await rm(root, { recursive: true, force: true });
  }
});

test("active sessions share one service-level ModelRuntime", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-model-runtime-"));
  const registry = new SessionRegistry(() => {}, { agentDir: join(root, "agent"), idleTimeoutMs: 0 });
  try {
    const first = await registry.create(root);
    const second = await registry.create(root);
    const firstSlot = await registry.getOrOpen(first.sessionId);
    const secondSlot = await registry.getOrOpen(second.sessionId);
    assert.equal(firstSlot.runtime.services.modelRuntime, secondSlot.runtime.services.modelRuntime);
    assert.equal(firstSlot.runtime.services.modelRuntime, await registry.models());
  } finally {
    await registry.dispose();
    await rm(root, { recursive: true, force: true });
  }
});

test("native projection creates a new event stream after runtime reclaim", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-event-stream-"));
  const events = [];
  const registry = new SessionRegistry(undefined, {
    agentDir: join(root, "agent"), idleTimeoutMs: 20,
  });
  const projection = new NativeEventProjector(registry);
  registry.subscribe((event) => events.push(projection.project(event)));
  try {
    const created = await registry.create(root);
    const createdNative = await projection.decorateResult(created);
    const firstSlot = await registry.getOrOpen(created.sessionId);
    firstSlot.runtime.session.sessionManager.appendMessage({
      role: "assistant", content: [{ type: "text", text: "seed" }], api: "openai-responses",
      provider: "openai", model: "seed", usage: {
        input: 0, output: 0, cacheRead: 0, cacheWrite: 0,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      }, stopReason: "stop", timestamp: Date.now(),
    });
    const sessionPath = firstSlot.runtime.session.sessionFile;
    assert.ok(sessionPath);
    registry.emitPromptCompleted(firstSlot);
    await waitUntil(() => registry.size === 0);

    const reopened = await registry.open(sessionPath);
    const reopenedNative = await projection.decorateResult(reopened);
    const secondSlot = await registry.getOrOpen(reopened.sessionId);
    assert.notEqual(reopenedNative.eventStreamId, createdNative.eventStreamId);
    assert.equal(projection.identity(secondSlot).sequence, 0);
    registry.emitPromptCompleted(secondSlot);

    const terminalEvents = events.filter((event) => event.payload?.type === "prompt_completed");
    assert.equal(terminalEvents.length, 2);
    assert.equal(terminalEvents[0].sequence, 1);
    assert.equal(terminalEvents[1].sequence, 1);
    assert.equal(terminalEvents[0].eventStreamId, createdNative.eventStreamId);
    assert.equal(terminalEvents[1].eventStreamId, reopenedNative.eventStreamId);
  } finally {
    await registry.dispose();
    await rm(root, { recursive: true, force: true });
  }
});

async function waitUntil(predicate, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("condition timed out");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
