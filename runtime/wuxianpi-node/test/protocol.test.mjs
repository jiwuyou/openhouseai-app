import assert from "node:assert/strict";
import test from "node:test";
import { parseRequest, stringifyMessage } from "../dist/protocol.js";

test("parses the request envelope", () => {
  assert.deepEqual(parseRequest('{"id":"1","type":"runtime.status","payload":{}}'),
    { id: "1", type: "runtime.status", payload: {} });
});
test("rejects malformed envelopes", () => {
  assert.throws(() => parseRequest('{"type":"runtime.status"}'), /Request id/);
});
test("serializes runtime values safely", () => {
  const value = { count: 2n, error: new Error("failed") };
  value.self = value;
  const parsed = JSON.parse(stringifyMessage(value));
  assert.equal(parsed.count, "2"); assert.equal(parsed.error.message, "failed"); assert.equal(parsed.self, "[Circular]");
});
test("preserves shared non-cyclic SDK event references", () => {
  const content = [{ type: "text", text: "hello" }];
  const event = { partial: { content }, message: { content } };
  const parsed = JSON.parse(stringifyMessage(event));
  assert.deepEqual(parsed.partial.content, content);
  assert.deepEqual(parsed.message.content, content);
});
