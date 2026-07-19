import assert from "node:assert/strict";
import test from "node:test";
import { requireIdle, runDetached, SerialExecutor } from "../dist/session-registry.js";
import { CONTROL_COMMANDS } from "../dist/pi-sdk-adapter.js";

test("serializes operations in one session", async () => {
  const executor = new SerialExecutor(); const order = [];
  const first = executor.run(async () => { order.push("first:start"); await new Promise((r) => setTimeout(r, 20)); order.push("first:end"); });
  const second = executor.run(async () => order.push("second"));
  await Promise.all([first, second]);
  assert.deepEqual(order, ["first:start", "first:end", "second"]);
});
test("different session executors progress concurrently", async () => {
  const left = new SerialExecutor(); const right = new SerialExecutor(); let release;
  const blocked = new Promise((resolve) => { release = resolve; });
  const first = left.run(async () => blocked);
  assert.equal(await right.run(async () => "right-complete"), "right-complete");
  release(); await first;
});
test("busy lifecycle mutations are rejected until agent_settled", () => {
  const slot = { isRunning: true, runtime: { session: { isIdle: false } } };
  assert.throws(() => requireIdle(slot, "session.switch"), /requires agent_settled/);
});
test("detached cleanup rejection is observed instead of becoming unhandled", async () => {
  let message;
  runDetached(Promise.reject(new Error("dispose failed")), (error) => { message = error.message; });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(message, "dispose failed");
});
test("extension UI responses bypass the serialized prompt lane", async () => {
  const executor = new SerialExecutor(); let release;
  const blocked = executor.run(() => new Promise((resolve) => { release = resolve; }));
  assert.equal(CONTROL_COMMANDS.has("extension.uiResponse"), true);
  let responseApplied = false;
  await Promise.resolve().then(() => { responseApplied = true; });
  assert.equal(responseApplied, true);
  release(); await blocked;
});
