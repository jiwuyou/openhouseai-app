#!/usr/bin/env node

const readline = require("readline");
const { addMemo, deleteMemo, health, listMemos, readState } = require("./state");

const SERVER_INFO = {
  name: "memo-openhouse",
  version: "0.1.0",
};

const TOOLS = [
  {
    name: "memo_openhouse_health",
    description: "Check the local OpenHouse Memo app module.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "memo_openhouse_state",
    description: "Read the current OpenHouse Memo app state.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "memo_openhouse_list_memos",
    description: "List saved OpenHouse Memo entries.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "memo_openhouse_add_memo",
    description: "Add a memo to the OpenHouse Memo app.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "Memo text." },
      },
      required: ["text"],
      additionalProperties: false,
    },
  },
  {
    name: "memo_openhouse_delete_memo",
    description: "Delete a memo from the OpenHouse Memo app.",
    inputSchema: {
      type: "object",
      properties: {
        id: { type: "string", description: "Memo id." },
      },
      required: ["id"],
      additionalProperties: false,
    },
  },
];

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function result(id, value) {
  send({ jsonrpc: "2.0", id, result: value });
}

function error(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

function asToolContent(value) {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(value, null, 2),
      },
    ],
  };
}

async function callTool(name, args) {
  switch (name) {
    case "memo_openhouse_health":
      return asToolContent(health("mcp"));
    case "memo_openhouse_state":
      return asToolContent(await readState());
    case "memo_openhouse_list_memos":
      return asToolContent(await listMemos());
    case "memo_openhouse_add_memo":
      return asToolContent(await addMemo(args && args.text));
    case "memo_openhouse_delete_memo":
      return asToolContent(await deleteMemo(args && args.id));
    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}

async function handle(message) {
  if (!message || message.jsonrpc !== "2.0") {
    return;
  }

  const { id, method, params } = message;
  const isNotification = id === undefined || id === null;

  try {
    switch (method) {
      case "initialize":
        result(id, {
          protocolVersion: (params && params.protocolVersion) || "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: SERVER_INFO,
        });
        return;

      case "notifications/initialized":
        return;

      case "tools/list":
        result(id, { tools: TOOLS });
        return;

      case "tools/call":
        result(id, await callTool(params && params.name, params && params.arguments));
        return;

      case "resources/list":
        result(id, { resources: [] });
        return;

      case "prompts/list":
        result(id, { prompts: [] });
        return;

      default:
        if (!isNotification) {
          error(id, -32601, `Method not found: ${method}`);
        }
    }
  } catch (err) {
    if (!isNotification) {
      error(id, -32000, err instanceof Error ? err.message : String(err));
    }
  }
}

const rl = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

let queue = Promise.resolve();

rl.on("line", (line) => {
  if (!line.trim()) {
    return;
  }
  let message;
  try {
    message = JSON.parse(line);
  } catch (err) {
    error(null, -32700, err instanceof Error ? err.message : String(err));
    return;
  }
  queue = queue.then(() => handle(message));
});
