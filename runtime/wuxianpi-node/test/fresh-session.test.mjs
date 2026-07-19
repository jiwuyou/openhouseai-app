import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
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
