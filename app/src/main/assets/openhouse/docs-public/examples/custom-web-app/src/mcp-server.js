#!/usr/bin/env node

const readline = require("readline");
const { addTask, deleteTask, health, listTasks, readState } = require("./state");

const SERVER_INFO = {
  name: "hello-openhouse",
  version: "0.1.0",
};

const TOOLS = [
  {
    name: "hello_openhouse_health",
    description: "Check the local Hello OpenHouse app module.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "hello_openhouse_state",
    description: "Read the current Hello OpenHouse app state.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "hello_openhouse_list_tasks",
    description: "List Hello OpenHouse tasks.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "hello_openhouse_add_task",
    description: "Add a task to the Hello OpenHouse app.",
    inputSchema: {
      type: "object",
      properties: {
        title: { type: "string", description: "Task title." },
      },
      required: ["title"],
      additionalProperties: false,
    },
  },
  {
    name: "hello_openhouse_delete_task",
    description: "Delete a task from the Hello OpenHouse app.",
    inputSchema: {
      type: "object",
      properties: {
        id: { type: "string", description: "Task id." },
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
    case "hello_openhouse_health":
      return asToolContent(health("mcp"));
    case "hello_openhouse_state":
      return asToolContent(await readState());
    case "hello_openhouse_list_tasks":
      return asToolContent(await listTasks());
    case "hello_openhouse_add_task":
      return asToolContent(await addTask(args && args.title));
    case "hello_openhouse_delete_task":
      return asToolContent(await deleteTask(args && args.id));
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
