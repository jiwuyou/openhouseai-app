import assert from "node:assert/strict";
import test from "node:test";
import { PiSdkAdapter } from "../dist/pi-sdk-adapter.js";

test("accepted prompt returns the newly appended user entry id instead of the previous leaf", async () => {
  let received;
  const registry = {
    prompt: async (sessionId, input) => {
      received = { sessionId, input };
      return { accepted: true, userEntryId: "new-user-entry", sessionId, cwd: "/tmp", isRunning: false, isIdle: true };
    },
  };
  const adapter = new PiSdkAdapter(registry);
  const result = await adapter.dispatch({
    id: "prompt", type: "session.prompt", sessionId: "session-a", payload: { message: "hello" },
  });
  assert.deepEqual(received, { sessionId: "session-a", input: { message: "hello", images: undefined, streamingBehavior: undefined, source: "rpc" } });
  assert.equal(result.accepted, true);
  assert.equal(result.userEntryId, "new-user-entry");
  assert.notEqual(result.userEntryId, "previous-leaf");
});
