import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createRuntimeServer } from "../dist/server.js";

test("AI Web contract covers session controls and complete snapshots", { timeout: 30_000 }, async (t) => {
  const fixture = await startFixture(t, "session-contract");
  const created = await jsonFetch(`${fixture.base}/api/web/v1/sessions`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ cwd: fixture.root, toolNames: ["read"], thinkingLevel: "low" }),
  });
  const sessionId = created.data.sessionId;
  const slot = await fixture.server.registry.getOrOpen(sessionId);
  slot.runtime.session.sessionManager.appendMessage({ role: "user", content: "seed", timestamp: Date.now() });
  const userEntryId = slot.runtime.session.sessionManager.getLeafId();
  assert.ok(userEntryId);
  slot.runtime.session.sessionManager.appendMessage({
    role: "assistant", content: [{ type: "text", text: "seed response" }], api: "openai-responses",
    provider: "openai", model: "seed", usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } }, stopReason: "stop", timestamp: Date.now(),
  });
  const leafId = slot.runtime.session.sessionManager.getLeafId();
  assert.ok(leafId);

  let snapshot = await jsonFetch(`${fixture.base}/api/web/v1/sessions/${sessionId}/snapshot`);
  assert.deepEqual(snapshot.data.entries, [userEntryId, leafId]);
  assert.equal(snapshot.data.sessionEntries.some((entry) => entry.id === leafId), true);
  assert.equal(Array.isArray(snapshot.data.tree), true);
  assert.equal(Array.isArray(snapshot.data.state.tools), true);
  assert.equal(Array.isArray(snapshot.data.state.extensionStatuses), true);
  assert.equal(Array.isArray(snapshot.data.state.extensionWidgets), true);
  assert.deepEqual(snapshot.data.state.activeToolNames, ["read"]);
  assert.equal(Array.isArray(snapshot.data.state.slashCommands.commands), true);
  assert.equal(typeof snapshot.data.state.sessionStats, "object");

  const updatedTools = await jsonFetch(`${fixture.base}/api/web/v1/sessions/${sessionId}/tools`, {
    method: "PATCH", headers: jsonHeaders, body: JSON.stringify({ toolNames: ["read", "pi:read", "bash"] }),
  });
  assert.deepEqual(updatedTools.data.activeToolNames, ["read", "bash"]);
  assert.equal(Array.isArray(updatedTools.data.warnings), true);
  assert.equal(updatedTools.data.warnings.length, 1);
  assert.equal(updatedTools.data.warnings[0].code, "unknown_tool");
  assert.equal(updatedTools.data.warnings[0].message, "Unknown tool name(s) ignored: pi:read");
  assert.deepEqual(updatedTools.data.warnings[0].unknown, ["pi:read"]);
  assert.equal(updatedTools.data.warnings[0].available.includes("read"), true);
  assert.equal(updatedTools.data.warnings[0].available.includes("bash"), true);

  await jsonFetch(`${fixture.base}/api/web/v1/sessions/${sessionId}`, {
    method: "PATCH", headers: jsonHeaders, body: JSON.stringify({ name: "Renamed" }),
  });
  snapshot = await jsonFetch(`${fixture.base}/api/web/v1/sessions/${sessionId}/snapshot?leafId=${leafId}`);
  assert.equal(snapshot.data.state.sessionName, "Renamed");

  for (const endpoint of ["tree", "commands", "stats", "entries", "tools"]) {
    const response = await fetch(`${fixture.base}/api/web/v1/sessions/${sessionId}/${endpoint}`);
    assert.equal(response.ok, true, `${endpoint} returned ${response.status}`);
  }
  await jsonFetch(`${fixture.base}/api/web/v1/sessions/${sessionId}/navigate`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ targetId: leafId }),
  });
  let extensionRequestId;
  const unsubscribe = fixture.server.registry.subscribe((event) => {
    if (event.payload?.type === "extension_ui_request") extensionRequestId = event.payload.requestId;
  });
  const confirmation = slot.ui.context.confirm("Confirm", "Continue?");
  assert.ok(extensionRequestId);
  await jsonFetch(`${fixture.base}/api/web/v1/sessions/${sessionId}/extension-ui-responses`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ id: extensionRequestId, cancelled: true }),
  });
  assert.equal(await confirmation, false);
  unsubscribe();
  const fork = await jsonFetch(`${fixture.base}/api/web/v1/sessions/${sessionId}/fork`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ entryId: userEntryId }),
  });
  assert.equal(fork.data.newSessionId, fork.data.sessionId);
});

test("assistant lifecycle, capabilities, TTS, MCP adapter status, and extension bridge match the UI contract", { timeout: 30_000 }, async (t) => {
  const fixture = await startFixture(t, "resource-contract");
  const extensionRoot = join(fixture.agentDir, "wuxianpi", "extensions", "demo");
  await mkdir(extensionRoot, { recursive: true });
  await writeFile(join(extensionRoot, "wuxianpi-extension.json"), JSON.stringify({
    schemaVersion: 1, apiVersion: "1", id: "demo", name: "Demo", version: "1.0.0", entry: "index.html",
    permissions: ["assistant.read", "storage.read", "storage.write", "ui.notify"],
  }));
  await writeFile(join(extensionRoot, "index.html"), "demo");

  const assistants = await jsonFetch(`${fixture.base}/api/web/v1/assistants`);
  assert.equal(assistants.data.assistants.some((assistant) => assistant.id === "wuxianpi"), true);
  const copied = await jsonFetch(`${fixture.base}/api/web/v1/assistants/wuxianpi/copy`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ targetId: "copied" }),
  });
  assert.equal(copied.data.assistant.id, "copied");
  const detail = await jsonFetch(`${fixture.base}/api/web/v1/assistants/copied`);
  assert.equal(detail.data.assistant.id, "copied");
  assert.equal(typeof detail.data.files.agents, "string");

  const exported = await fetch(`${fixture.base}/api/web/v1/assistants/copied/export`);
  assert.equal(exported.headers.get("content-type"), "application/zip");
  const form = new FormData();
  form.set("id", "imported");
  form.set("file", new File([await exported.arrayBuffer()], "copied.wuxianpi.zip", { type: "application/zip" }));
  const imported = await jsonFetch(`${fixture.base}/api/web/v1/assistants/import`, { method: "POST", body: form });
  assert.equal(imported.data.assistant.id, "imported");

  const config = await jsonFetch(`${fixture.base}/api/web/v1/capabilities/config`, {
    method: "PATCH", headers: jsonHeaders, body: JSON.stringify({
      mcpServers: [{ id: "local", name: "Local", transport: "stdio", command: "node", args: ["server.js"], enabled: true }],
      ttsProfiles: [{ id: "browser:test", name: "Browser", provider: "browser-speech", enabled: true }],
    }),
  });
  assert.equal(config.data.mcpServers[0].id, "local");
  assert.match(await readFile(join(fixture.agentDir, "mcp.json"), "utf8"), /"local"/);
  const capabilities = await jsonFetch(`${fixture.base}/api/web/v1/capabilities`);
  assert.equal(Array.isArray(capabilities.data.catalog.capabilities), true);
  assert.equal(capabilities.data.config.ttsProfiles[0].id, "browser:test");
  const permissions = await jsonFetch(`${fixture.base}/api/web/v1/capabilities/permissions`);
  assert.deepEqual(permissions.data.pending, []);
  await jsonFetch(`${fixture.base}/api/web/v1/capabilities/permissions`, {
    method: "POST", headers: jsonHeaders,
    body: JSON.stringify({ action: "revoke", request: { assistantId: "copied", capabilityId: "pi:bash" } }),
  });
  const mcp = await jsonFetch(`${fixture.base}/api/web/v1/capabilities/mcp`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ action: "test", serverId: "local" }),
  });
  assert.equal(mcp.data.configPath, join(fixture.agentDir, "mcp.json"));
  const tts = await fetch(`${fixture.base}/api/web/v1/capabilities/tts`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ profileId: "browser:test", text: "hello" }),
  }).then((response) => response.json());
  assert.equal(tts.kind, "client");

  const extensions = await jsonFetch(`${fixture.base}/api/web/v1/extensions`);
  assert.equal(extensions.data.extensions[0].manifest.id, "demo");
  const nonce = await jsonFetch(`${fixture.base}/api/web/v1/extensions/nonce`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({ extensionId: "demo", assistantId: "copied" }),
  });
  const bridge = await jsonFetch(`${fixture.base}/api/web/v1/extensions/bridge`, {
    method: "POST", headers: jsonHeaders, body: JSON.stringify({
      type: "wuxianpi_bridge_request", requestId: "bridge-1", extensionId: "demo", nonce: nonce.data.nonce,
      method: "assistant.get", params: {},
    }),
  });
  assert.equal(bridge.data.ok, true);
  assert.equal(bridge.data.result.id, "copied");
});

const jsonHeaders = { "content-type": "application/json" };

async function startFixture(t, name) {
  const root = await mkdtemp(join(tmpdir(), `wuxianpi-${name}-`));
  const agentDir = join(root, "agent");
  const server = createRuntimeServer({ host: "127.0.0.1", port: 0, agentDir, idleTimeoutMs: 0 });
  const address = await server.start();
  t.after(async () => {
    await server.stop().catch(() => undefined);
    await rm(root, { recursive: true, force: true });
  });
  return { root, agentDir, server, base: `http://127.0.0.1:${address.port}` };
}

async function jsonFetch(url, init) {
  const response = await fetch(url, init);
  const body = await response.json();
  assert.equal(response.ok, true, JSON.stringify(body));
  return body;
}
