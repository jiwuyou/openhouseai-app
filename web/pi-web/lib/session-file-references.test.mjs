import assert from "node:assert/strict";
import test from "node:test";

async function loadSubject() {
  return import("./session-file-references-core.ts");
}

test("detects exact external file paths referenced in session entries", async () => {
  const { isFilePathReferencedByEntries } = await loadSubject();
  const entries = [
    {
      type: "message",
      id: "entry-1",
      parentId: null,
      timestamp: "2026-01-01T00:00:00.000Z",
      message: {
        role: "assistant",
        content: [
          {
            type: "text",
            text: "See [/home/me/.codex/config.toml:12](/home/me/.codex/config.toml:12)",
          },
        ],
      },
    },
  ];

  assert.equal(isFilePathReferencedByEntries("/home/me/.codex/config.toml", entries), true);
});

test("does not authorize sibling files by prefix match", async () => {
  const { isFilePathReferencedByEntries } = await loadSubject();
  const entries = [
    {
      type: "message",
      id: "entry-1",
      parentId: null,
      timestamp: "2026-01-01T00:00:00.000Z",
      message: {
        role: "assistant",
        content: [
          {
            type: "text",
            text: "See /home/me/.codex/config.toml.bak",
          },
        ],
      },
    },
  ];

  assert.equal(isFilePathReferencedByEntries("/home/me/.codex/config.toml", entries), false);
});

test("validates session ids before resolving session paths", async () => {
  const { isValidSessionId } = await loadSubject();

  assert.equal(isValidSessionId("not-a-session-id"), false);
  assert.equal(isValidSessionId("../../sessions/foo"), false);
  assert.equal(isValidSessionId("550e8400-e29b-41d4-a716-446655440000"), true);
});
