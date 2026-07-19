import assert from "node:assert/strict";
import test from "node:test";
import { SessionSubscriptions } from "../dist/server.js";

test("routes session A events only to session A clients", () => {
  const subscriptions = new SessionSubscriptions();
  const clientA = {}; const clientB = {};
  subscriptions.setCurrent(clientA, "session-a"); subscriptions.setCurrent(clientB, "session-b");
  assert.deepEqual([...subscriptions.targets("session-a")], [clientA]);
  assert.deepEqual([...subscriptions.targets("session-b")], [clientB]);
});
test("switching A to B removes the former A subscription", () => {
  const subscriptions = new SessionSubscriptions(); const client = {};
  subscriptions.setCurrent(client, "session-a"); subscriptions.setCurrent(client, "session-b");
  assert.equal(subscriptions.targets("session-a").has(client), false);
  assert.equal(subscriptions.targets("session-b").has(client), true);
});
