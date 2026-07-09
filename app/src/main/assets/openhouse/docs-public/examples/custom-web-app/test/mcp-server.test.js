const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const readline = require("node:readline");
const test = require("node:test");

const APP_ROOT = path.resolve(__dirname, "..");
const MCP_SERVER = path.join(APP_ROOT, "src", "mcp-server.js");

function parseToolContent(result) {
  const text = result.content.find((item) => item.type === "text").text;
  return JSON.parse(text);
}

test("MCP stdio supports initialize, tools/list, and tools/call", async () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "memo-openhouse-mcp-"));
  const child = spawn(process.execPath, [MCP_SERVER], {
    cwd: APP_ROOT,
    env: {
      ...process.env,
      OPENHOUSE_CUSTOM_APP_DATA_DIR: dataDir,
    },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const rl = readline.createInterface({ input: child.stdout, crlfDelay: Infinity });
  const pending = new Map();

  rl.on("line", (line) => {
    const message = JSON.parse(line);
    const resolve = pending.get(message.id);
    if (resolve) {
      pending.delete(message.id);
      resolve(message);
    }
  });

  let nextId = 1;
  function request(method, params = {}) {
    const id = nextId;
    nextId += 1;
    const promise = new Promise((resolve) => pending.set(id, resolve));
    child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
    return promise;
  }

  try {
    const initialized = await request("initialize", { protocolVersion: "2024-11-05" });
    assert.equal(initialized.result.serverInfo.name, "memo-openhouse");

    const listed = await request("tools/list");
    const toolNames = listed.result.tools.map((tool) => tool.name);
    assert.equal(toolNames.includes("memo_openhouse_health"), true);
    assert.equal(toolNames.includes("memo_openhouse_list_memos"), true);
    assert.equal(toolNames.includes("memo_openhouse_add_memo"), true);
    assert.equal(toolNames.includes("memo_openhouse_delete_memo"), true);

    const added = await request("tools/call", {
      name: "memo_openhouse_add_memo",
      arguments: { text: "mcp memo from test" },
    });
    const addedState = parseToolContent(added.result);
    assert.equal(addedState.memos[0].text, "mcp memo from test");

    const state = await request("tools/call", { name: "memo_openhouse_state", arguments: {} });
    const currentState = parseToolContent(state.result);
    assert.equal(currentState.memos[0].text, "mcp memo from test");
  } finally {
    rl.close();
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
});
