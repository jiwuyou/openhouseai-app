import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { PersistentDiagnostics } from "../dist/diagnostics.js";

test("persistent diagnostics rotate, redact fields, and export bounded JSONL", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-diagnostics-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  let nowMs = Date.parse("2026-07-19T00:00:00.000Z");
  const diagnostics = new PersistentDiagnostics({
    directory: root,
    maxFileBytes: 4096,
    maxFiles: 2,
    now: () => new Date(nowMs),
  });
  t.after(() => diagnostics.close());

  diagnostics.record("request.normal", {
    requestType: "session.prompt",
    message: "ordinary-message-must-not-be-recorded",
    apiKey: "sk-ordinary-secret",
  });
  const detailed = diagnostics.setDetailed(true, 999_999);
  assert.equal(detailed.detailed, true);
  assert.equal(Date.parse(detailed.detailedUntil) - nowMs, 120_000);
  diagnostics.record("request.detailed", { requestType: "test" }, {
    safeDiagnosticField: "kept-for-diagnosis",
    content: "detailed-body-must-not-be-recorded",
    authorization: "Bearer detailed-secret",
  });
  for (let index = 0; index < 160; index++) {
    diagnostics.record("rotation.test", { index, marker: `record-${index}` });
  }
  await diagnostics.flush();

  const rollingFiles = (await readdir(root)).filter((name) => /^diagnostics-\d+\.jsonl$/.test(name));
  assert.ok(rollingFiles.length <= 2);
  const exported = await diagnostics.exportSnapshot();
  assert.equal(exported.content, await readFile(exported.path, "utf8"));
  assert.equal(exported.sizeBytes, Buffer.byteLength(exported.content));
  assert.doesNotMatch(exported.content, /ordinary-message-must-not-be-recorded/);
  assert.doesNotMatch(exported.content, /sk-ordinary-secret/);
  assert.doesNotMatch(exported.content, /detailed-body-must-not-be-recorded/);
  assert.doesNotMatch(exported.content, /detailed-secret/);

  nowMs += 120_001;
  assert.equal(diagnostics.status().detailed, false);
  diagnostics.record("after.expiry", {}, { shouldNotAppear: "expired-detail-field" });
  await diagnostics.flush();
  const afterExpiry = await diagnostics.exportSnapshot();
  assert.doesNotMatch(afterExpiry.content, /expired-detail-field/);
});

test("diagnostic write failures are contained", async () => {
  const diagnostics = new PersistentDiagnostics({ directory: "/dev/null/not-a-directory" });
  diagnostics.record("business.must.continue", { ok: true });
  await diagnostics.flush();
  assert.equal(diagnostics.status().writeErrors, 1);
  await diagnostics.close();
});

test("diagnostic queue is strictly bounded and reports dropped records", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-bounded-queue-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const diagnostics = new PersistentDiagnostics({ directory: root, maxQueuedRecords: 8 });
  t.after(() => diagnostics.close());

  for (let index = 0; index < 1_000; index++) diagnostics.record("queue.pressure", { index });
  const saturated = diagnostics.status();
  assert.ok(saturated.queuedRecords <= saturated.maxQueuedRecords);
  assert.ok(saturated.droppedRecords > 0);
  await diagnostics.flush();
  assert.equal(diagnostics.status().queuedRecords, 0);
});

test("normal mode aggregates streaming events and detailed mode records sanitized structure", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-stream-summary-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const diagnostics = new PersistentDiagnostics({ directory: root, streamSummaryIntervalMs: 60_000 });
  t.after(() => diagnostics.close());

  for (let sequence = 1; sequence <= 100; sequence++) {
    diagnostics.recordStream({
      stage: "send",
      connectionId: "connection-a",
      sessionId: "session-a",
      eventType: "text_delta",
      sequence,
      bytes: 20,
    }, { payload: { type: "message_update", delta: `private-${sequence}` } });
  }
  assert.equal(diagnostics.status().aggregatedStreams, 1);
  diagnostics.flushStreamSummaries("agent_settled", { sessionId: "session-a" });
  await diagnostics.flush();
  let exported = await diagnostics.exportSnapshot();
  let entries = exported.content.trim().split("\n").filter(Boolean).map(JSON.parse);
  const summaries = entries.filter((entry) => entry.event === "stream.summary");
  assert.equal(summaries.length, 1);
  assert.equal(summaries[0].count, 100);
  assert.equal(summaries[0].totalBytes, 2_000);
  assert.equal(summaries[0].firstSequence, 1);
  assert.equal(summaries[0].lastSequence, 100);
  assert.equal(entries.filter((entry) => entry.event === "stream.event").length, 0);
  assert.doesNotMatch(exported.content, /private-/);

  diagnostics.setDetailed(true, 2_000);
  diagnostics.recordStream({
    stage: "send",
    connectionId: "connection-a",
    sessionId: "session-a",
    eventType: "thinking_delta",
    sequence: 101,
  }, { payload: { delta: "detailed-private-thinking" } });
  diagnostics.recordStream({
    stage: "send",
    connectionId: "connection-a",
    sessionId: "session-a",
    eventType: "tool_execution_update",
    sequence: 102,
  }, { payload: {
    args: { command: "detailed-private-tool-arguments" },
    partialResult: "detailed-private-tool-output",
  } });
  await diagnostics.flush();
  exported = await diagnostics.exportSnapshot();
  entries = exported.content.trim().split("\n").filter(Boolean).map(JSON.parse);
  assert.equal(entries.filter((entry) => entry.event === "stream.event").length, 2);
  assert.doesNotMatch(exported.content, /detailed-private-thinking/);
  assert.doesNotMatch(exported.content, /detailed-private-tool-output/);
  assert.doesNotMatch(exported.content, /detailed-private-tool-arguments/);
});

test("send-stage aggregation isolates identical sequences from different event streams", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-stream-isolation-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const diagnostics = new PersistentDiagnostics({ directory: root, streamSummaryIntervalMs: 60_000 });
  t.after(() => diagnostics.close());

  for (const eventStreamId of ["stream-old", "stream-new"]) {
    diagnostics.recordStream({
      stage: "send",
      connectionId: "connection-a",
      sessionId: "session-a",
      eventStreamId,
      eventType: "text_delta",
      sequence: 1,
      bytes: 20,
    });
  }
  assert.equal(diagnostics.status().aggregatedStreams, 2);
  diagnostics.flushStreamSummaries("test");
  await diagnostics.flush();
  const exported = await diagnostics.exportSnapshot();
  const summaries = exported.content.trim().split("\n").filter(Boolean).map(JSON.parse)
    .filter((entry) => entry.event === "stream.summary");
  assert.equal(summaries.length, 2);
  assert.deepEqual(new Set(summaries.map((entry) => entry.eventStreamId)), new Set(["stream-old", "stream-new"]));
  assert.deepEqual(summaries.map((entry) => entry.firstSequence), [1, 1]);
});
