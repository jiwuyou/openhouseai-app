import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createRuntimeServer } from "../dist/server.js";

test("web API serves static UI and core resource endpoints", { timeout: 20_000 }, async (t) => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-web-api-"));
  const agentDir = join(root, "agent");
  const webRoot = join(root, "web");
  const extensionRoot = join(agentDir, "wuxianpi", "extensions", "demo");
  await mkdir(webRoot, { recursive: true });
  await mkdir(extensionRoot, { recursive: true });
  await writeFile(join(webRoot, "index.html"), "<main>WuxianPi AI</main>");
  await writeFile(join(extensionRoot, "wuxianpi-extension.json"), JSON.stringify({
    schemaVersion: 1, apiVersion: "1", id: "demo", name: "Demo", version: "1.0.0", entry: "index.html",
  }));
  await writeFile(join(extensionRoot, "index.html"), "<button>demo</button>");
  const sourceFile = join(root, "note.txt");
  await writeFile(sourceFile, "hello file");
  const server = createRuntimeServer({ host: "127.0.0.1", port: 0, agentDir, webRoot, idleTimeoutMs: 0 });
  const address = await server.start();
  const base = `http://127.0.0.1:${address.port}`;
  t.after(async () => {
    await server.stop().catch(() => undefined);
    await rm(root, { recursive: true, force: true });
  });

  const page = await fetch(`${base}/`);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /WuxianPi AI/);

  const status = await jsonFetch(`${base}/api/web/v1/status`);
  assert.equal(status.ok, true);
  assert.equal(status.eventTransport, "snapshot-sse-v1");
  assert.equal(status.capabilities.staticWebUi, 1);

  const health = await jsonFetch(`${base}/health`);
  assert.equal(health.capabilities.staticWebUi, 1);
  assert.equal(health.uiMetadataPath, "/v1/ui/metadata");

  const ui = await jsonFetch(`${base}/v1/ui/metadata`);
  assert.equal(ui.preferred.url, "http://127.0.0.1:25808/");
  assert.equal(ui.fallback.url, `${base}/`);
  assert.equal(ui.fallback.available, true);
  assert.equal(ui.webApiUrl, `${base}/api/web/v1`);

  const created = await jsonFetch(`${base}/api/web/v1/sessions`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ cwd: root }),
  });
  assert.equal(typeof created.data.sessionId, "string");
  const snapshot = await jsonFetch(`${base}/api/web/v1/sessions/${created.data.sessionId}/snapshot`);
  assert.equal(snapshot.data.type, "snapshot");
  assert.deepEqual(snapshot.data.history, []);

  const assistants = await jsonFetch(`${base}/api/web/v1/assistants`);
  assert.equal(assistants.data.assistants.some((assistant) => assistant.id === "wuxianpi"), true);

  const file = await jsonFetch(`${base}/api/web/v1/files?path=${encodeURIComponent(sourceFile)}`);
  assert.equal(file.data.content, "hello file");
  const extensions = await jsonFetch(`${base}/api/web/v1/extensions`);
  assert.equal(extensions.data.extensions.some((extension) => extension.id === "demo" && extension.kind === "wuxianpi"), true);
  const asset = await fetch(`${base}/api/web/v1/extensions/demo/assets/index.html`);
  assert.equal(await asset.text(), "<button>demo</button>");
});

test("SSE atomically emits snapshot before post-snapshot agent events", { timeout: 20_000 }, async (t) => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-sse-race-"));
  const agentDir = join(root, "agent");
  const server = createRuntimeServer({ host: "127.0.0.1", port: 0, agentDir, idleTimeoutMs: 0 });
  const address = await server.start();
  t.after(async () => {
    await server.stop().catch(() => undefined);
    await rm(root, { recursive: true, force: true });
  });
  const identity = await server.registry.create(root);
  const slot = await server.registry.getOrOpen(identity.sessionId);
  const original = server.registry.snapshotAndSubscribe.bind(server.registry);
  server.registry.snapshotAndSubscribe = async (...args) => {
    const subscription = await original(...args);
    return {
      ...subscription,
      activate() {
        server.registry.emitPromptCompleted(slot);
        subscription.activate();
      },
    };
  };

  const response = await fetch(`http://127.0.0.1:${address.port}/api/web/v1/sessions/${identity.sessionId}/events`);
  assert.equal(response.status, 200);
  const events = await readSse(response, 2);
  assert.equal(events[0].type, "snapshot");
  assert.equal(events[1].type, "agent");
  assert.equal(events[1].payload.type, "prompt_completed");
});

test("atomic registry subscription never replays an event already represented by its snapshot", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-snapshot-no-duplicate-"));
  const registryServer = createRuntimeServer({ host: "127.0.0.1", port: 0, agentDir: join(root, "agent"), idleTimeoutMs: 0 });
  try {
    await registryServer.start();
    const identity = await registryServer.registry.create(root);
    const slot = await registryServer.registry.getOrOpen(identity.sessionId);
    slot.runtime.session.sessionManager.appendMessage({ role: "user", content: "before subscribe", timestamp: Date.now() });
    const delivered = [];
    const subscription = await registryServer.registry.snapshotAndSubscribe(identity.sessionId, (event) => delivered.push(event));
    assert.equal(subscription.snapshot.history.length, 1);
    subscription.activate();
    assert.deepEqual(delivered, []);
    registryServer.registry.emitPromptCompleted(slot);
    assert.equal(delivered.length, 1);
    subscription.unsubscribe();
  } finally {
    await registryServer.stop().catch(() => undefined);
    await rm(root, { recursive: true, force: true });
  }
});

test("SSE converts runtime errors and runtime stop closes open streams", { timeout: 20_000 }, async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-sse-close-"));
  const server = createRuntimeServer({ host: "127.0.0.1", port: 0, agentDir: join(root, "agent"), idleTimeoutMs: 0 });
  const address = await server.start();
  try {
    const identity = await server.registry.create(root);
    const slot = await server.registry.getOrOpen(identity.sessionId);
    const original = server.registry.snapshotAndSubscribe.bind(server.registry);
    server.registry.snapshotAndSubscribe = async (...args) => {
      const subscription = await original(...args);
      return { ...subscription, activate() {
        server.registry.emitRuntimeError(slot, "test", new Error("expected failure"));
        subscription.activate();
      } };
    };
    const response = await fetch(`http://127.0.0.1:${address.port}/api/web/v1/sessions/${identity.sessionId}/events`);
    const events = await readSse(response, 2, false);
    assert.equal(events[1].type, "runtime-error");
    assert.equal(events[1].error.message, "expected failure");
    const reader = response.body.getReader();
    await server.stop();
    assert.equal((await reader.read()).done, true);
    await reader.cancel().catch(() => undefined);
  } finally {
    await server.stop().catch(() => undefined);
    await rm(root, { recursive: true, force: true });
  }
});

async function jsonFetch(url, init) {
  const response = await fetch(url, init);
  const body = await response.json();
  assert.equal(response.ok, true, JSON.stringify(body));
  return body;
}

async function readSse(response, count, cancel = true) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let text = "";
  const output = [];
  while (output.length < count) {
    const { done, value } = await reader.read();
    if (done) break;
    text += decoder.decode(value, { stream: true });
    let boundary;
    while ((boundary = text.indexOf("\n\n")) >= 0) {
      const frame = text.slice(0, boundary);
      text = text.slice(boundary + 2);
      const data = frame.split("\n").find((line) => line.startsWith("data: "));
      if (data) output.push(JSON.parse(data.slice(6)));
    }
  }
  if (cancel) await reader.cancel();
  else reader.releaseLock();
  return output;
}
