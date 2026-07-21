import assert from "node:assert/strict";
import test from "node:test";
import { ExtensionUiBridge } from "../dist/extension-ui.js";

test("extension UI response resolves the matching dialog", async () => {
  const emitted = [];
  const bridge = new ExtensionUiBridge((event) => emitted.push(event));
  const selected = bridge.context.select("Choose", ["one", "two"]);
  bridge.respond({ requestId: emitted[0].requestId, value: "two" });
  assert.equal(await selected, "two");
  bridge.dispose();
});

test("extension UI abort returns the dialog fallback", async () => {
  const controller = new AbortController();
  const bridge = new ExtensionUiBridge(() => {});
  const confirmed = bridge.context.confirm("Confirm", "Continue?", { signal: controller.signal });
  controller.abort();
  assert.equal(await confirmed, false);
  bridge.dispose();
});

test("extension UI state is snapshotted as arrays", () => {
  const emitted = [];
  const bridge = new ExtensionUiBridge((event) => emitted.push(event));
  bridge.context.setStatus("build", "Running");
  bridge.context.setWidget("progress", ["one", "two"], { placement: "belowEditor" });
  assert.deepEqual(bridge.state(), {
    statuses: [{ key: "build", text: "Running" }],
    widgets: [{ key: "progress", lines: ["one", "two"], placement: "belowEditor" }],
  });
  bridge.context.setStatus("build");
  bridge.context.setWidget("progress", undefined);
  assert.deepEqual(bridge.state(), { statuses: [], widgets: [] });
  assert.equal(emitted.length, 4);
  bridge.dispose();
});
