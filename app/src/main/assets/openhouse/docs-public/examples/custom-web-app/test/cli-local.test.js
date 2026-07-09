const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const APP_ROOT = path.resolve(__dirname, "..");
const CLI = path.join(APP_ROOT, "bin", "hello-openhouse.js");

function runCli(dataDir, args) {
  const result = spawnSync(process.execPath, [CLI, ...args], {
    cwd: APP_ROOT,
    env: {
      ...process.env,
      OPENHOUSE_CUSTOM_APP_DATA_DIR: dataDir,
    },
    encoding: "utf8",
  });

  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
}

test("CLI local mode reads, adds, lists, and deletes tasks as JSON", () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "hello-openhouse-cli-local-"));
  try {
    const health = runCli(dataDir, ["health"]);
    assert.equal(health.ok, true);
    assert.equal(health.mode, "local");

    const initial = runCli(dataDir, ["state"]);
    assert.equal(initial.app, "hello-openhouse");
    assert.equal(Array.isArray(initial.tasks), true);

    const added = runCli(dataDir, ["add", "local task from test"]);
    assert.equal(added.tasks[0].title, "local task from test");

    const listed = runCli(dataDir, ["list"]);
    assert.equal(listed.ok, true);
    assert.equal(listed.tasks[0].title, "local task from test");

    const deleted = runCli(dataDir, ["delete", added.tasks[0].id]);
    assert.equal(deleted.tasks.some((task) => task.id === added.tasks[0].id), false);
  } finally {
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
