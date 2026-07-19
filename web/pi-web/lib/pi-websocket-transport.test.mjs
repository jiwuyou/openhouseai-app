import assert from "node:assert/strict";
import test from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { mapCommand } = await jiti.import("./pi-websocket-transport.ts");

test("maps legacy UI commands to the WuxianPi SDK protocol", () => {
  assert.deepEqual(mapCommand({ type: "prompt", message: "hello" }), {
    type: "session.prompt",
    payload: { message: "hello" },
  });
  assert.deepEqual(mapCommand({ type: "get_state" }), { type: "session.state" });
  assert.deepEqual(mapCommand({ type: "follow_up", message: "later" }), {
    type: "session.followUp",
    payload: { message: "later" },
  });
});

test("keeps extension request identity inside the command payload", () => {
  assert.deepEqual(mapCommand({ type: "extension_ui_response", id: "dialog-1", confirmed: true }), {
    type: "extension.uiResponse",
    payload: { confirmed: true, requestId: "dialog-1" },
  });
});

test("rejects commands that are not part of the SDK host protocol", () => {
  assert.throws(() => mapCommand({ type: "unknown_legacy_command" }), /Unsupported WuxianPi command/);
});
