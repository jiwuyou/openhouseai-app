const assert = require("node:assert/strict");
const { spawn, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const APP_ROOT = path.resolve(__dirname, "..");
const CLI = path.join(APP_ROOT, "bin", "memo-openhouse.js");
const SERVER = path.join(APP_ROOT, "src", "server.js");

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
    server.on("error", reject);
  });
}

async function waitForHealth(url) {
  const started = Date.now();
  while (Date.now() - started < 5000) {
    try {
      const response = await fetch(`${url}/health`);
      if (response.ok) {
        return;
      }
    } catch {
      // Keep waiting for the child server.
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`server did not become healthy: ${url}`);
}

function runCli(dataDir, url, args) {
  const result = spawnSync(process.execPath, [CLI, "--url", url, ...args], {
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

test("CLI HTTP mode uses the running Web API on a temporary port", async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "memo-openhouse-cli-http-"));
  const port = await freePort();
  const url = `http://127.0.0.1:${port}`;
  const server = spawn(process.execPath, [SERVER], {
    cwd: APP_ROOT,
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      OPENHOUSE_CUSTOM_APP_DATA_DIR: dataDir,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });

  try {
    await waitForHealth(url);

    const health = runCli(dataDir, url, ["health"]);
    assert.equal(health.ok, true);
    assert.equal(health.mode, "http");

    const added = runCli(dataDir, url, ["add", "http memo from test"]);
    assert.equal(added.memos[0].text, "http memo from test");

    const listed = runCli(dataDir, url, ["list"]);
    assert.equal(listed.ok, true);
    assert.equal(listed.memos[0].text, "http memo from test");

    const deleted = runCli(dataDir, url, ["delete", added.memos[0].id]);
    assert.equal(deleted.memos.some((memo) => memo.id === added.memos[0].id), false);
  } finally {
    server.kill("SIGTERM");
    await new Promise((resolve) => server.once("exit", resolve));
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
