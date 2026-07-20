import assert from "node:assert/strict";
import test from "node:test";
import { PiSdkAdapter } from "../dist/pi-sdk-adapter.js";

test("accepted prompt returns the newly appended user entry id instead of the previous leaf", async () => {
  let leafId = "previous-leaf";
  const session = {
    sessionManager: { getLeafId: () => leafId },
    prompt: async (_message, options) => {
      leafId = "new-user-entry";
      options.preflightResult(true);
    },
  };
  const slot = {};
  const registry = {
    run: async (_sessionId, operation) => operation(slot),
    control: async (_sessionId, operation) => operation(slot),
    session: () => session,
    agentStartCount: () => 0,
    describe: () => ({
      sessionId: "session-a", sessionPath: "/tmp/session-a.jsonl", eventStreamId: "stream-a",
      cwd: "/tmp", isRunning: false, isIdle: true,
    }),
    emitRuntimeError: () => {},
    emitPromptCompleted: () => {},
  };
  const adapter = new PiSdkAdapter(registry);
  const result = await adapter.dispatch({
    id: "prompt", type: "session.prompt", sessionId: "session-a", payload: { message: "hello" },
  });
  assert.equal(result.accepted, true);
  assert.equal(result.userEntryId, "new-user-entry");
  assert.notEqual(result.userEntryId, "previous-leaf");
});
