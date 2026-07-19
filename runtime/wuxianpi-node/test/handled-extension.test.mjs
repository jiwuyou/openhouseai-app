import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { PiSdkAdapter } from "../dist/pi-sdk-adapter.js";
import { SessionRegistry } from "../dist/session-registry.js";

test("handled extension prompt completes without agent_settled and UI response does not deadlock", { timeout: 15_000 }, async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-extension-"));
  const agentDir = join(root, "agent");
  const extensions = join(agentDir, "extensions");
  await mkdir(extensions, { recursive: true });
  await writeFile(join(extensions, "handled.ts"), `
export default function (pi) {
  pi.registerCommand("handled", {
    description: "handled by test extension",
    handler: async (_args, ctx) => { await ctx.ui.confirm("Test", "Continue?"); }
  });
}
`);
  const events = [];
  let adapter;
  const registry = new SessionRegistry((event) => {
    events.push(event);
    if (event.payload?.type === "extension_ui_request") {
      queueMicrotask(() => void adapter.dispatch({
        id: "ui-response", type: "extension.uiResponse", sessionId: event.sessionId,
        payload: { requestId: event.payload.requestId, confirmed: true },
      }));
    }
  }, { agentDir, idleTimeoutMs: 0 });
  adapter = new PiSdkAdapter(registry);
  try {
    const identity = await registry.create(root);
    const result = await adapter.dispatch({
      id: "prompt", type: "session.prompt", sessionId: identity.sessionId, payload: { message: "/handled" },
    });
    assert.equal(result.accepted, true);
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(events.some((event) => event.payload?.type === "agent_start"), false);
    assert.equal(events.some((event) => event.payload?.type === "agent_settled"), false);
    assert.equal(events.some((event) => event.payload?.type === "prompt_completed"), true);
  } finally {
    await registry.dispose();
    await rm(root, { recursive: true, force: true });
  }
});
