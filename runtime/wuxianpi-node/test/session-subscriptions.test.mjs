import assert from "node:assert/strict";
import test from "node:test";
import { SessionSubscriptions } from "../dist/server.js";

test("one socket retains A-B-A subscriptions and receives interleaved sessions", () => {
  const subscriptions = new SessionSubscriptions();
  const client = {};
  assert.equal(subscriptions.subscribe(client, "session-a"), true);
  assert.equal(subscriptions.subscribe(client, "session-b"), true);
  assert.equal(subscriptions.subscribe(client, "session-a"), false);
  assert.deepEqual(subscriptions.all(client), ["session-a", "session-b"]);
  assert.equal(subscriptions.targets("session-a").has(client), true);
  assert.equal(subscriptions.targets("session-b").has(client), true);
});

test("two sockets may subscribe to the same session without cross-session leakage", () => {
  const subscriptions = new SessionSubscriptions();
  const first = {};
  const second = {};
  subscriptions.subscribe(first, "session-a");
  subscriptions.subscribe(second, "session-a");
  subscriptions.subscribe(second, "session-b");
  assert.deepEqual([...subscriptions.targets("session-a")], [first, second]);
  assert.deepEqual([...subscriptions.targets("session-b")], [second]);
  assert.deepEqual(subscriptions.remove(second), ["session-a", "session-b"]);
  assert.deepEqual([...subscriptions.targets("session-a")], [first]);
});
