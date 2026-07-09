#!/usr/bin/env node

const path = require("path");
const { addMemo, deleteMemo, health, listMemos, readState } = require("../src/state");

const DEFAULT_HTTP_URL = "http://127.0.0.1:23110";

function printJson(value) {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

function commandName() {
  return path.basename(process.argv[1] || "demo-memo-openhouse");
}

function usage() {
  const name = commandName();
  process.stdout.write(`${name} CLI

Usage:
  ${name} health
  ${name} state
  ${name} list
  ${name} add <memo-text>
  ${name} delete <memo-id>
  ${name} --url ${DEFAULT_HTTP_URL} state
  ${name} --url ${DEFAULT_HTTP_URL} add <memo-text>

Without --url, commands use the local app data directory directly.
With --url, commands use the running HTTP service.
The CLI prints JSON by default so AI agents, scripts, and humans can all use the same commands.
`);
}

function parseArgs(argv) {
  const rest = [];
  let url = "";

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--url" || arg === "--http-url") {
      url = argv[index + 1] || "";
      index += 1;
    } else if (arg.startsWith("--url=")) {
      url = arg.slice("--url=".length);
    } else if (arg.startsWith("--http-url=")) {
      url = arg.slice("--http-url=".length);
    } else {
      rest.push(arg);
    }
  }

  return { url: url.replace(/\/+$/, ""), args: rest };
}

async function requestJson(baseUrl, route, options = {}) {
  if (typeof fetch !== "function") {
    throw new Error("HTTP mode requires Node.js with fetch support.");
  }

  const response = await fetch(`${baseUrl}${route}`, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {}),
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || `HTTP ${response.status}`);
  }
  return data;
}

function requireValue(value, label) {
  const clean = String(value || "").trim();
  if (!clean) {
    throw new Error(`${label} is required`);
  }
  return clean;
}

async function runHttp(url, command, args) {
  switch (command) {
    case "health":
      printJson(await requestJson(url, "/health"));
      return;

    case "state":
      printJson(await requestJson(url, "/api/state"));
      return;

    case "list": {
      const state = await requestJson(url, "/api/state");
      printJson({ ok: true, memos: state.memos || [], updatedAt: state.updatedAt });
      return;
    }

    case "add":
      printJson(
        await requestJson(url, "/api/memos", {
          method: "POST",
          body: JSON.stringify({ text: requireValue(args.join(" "), "memo text") }),
        })
      );
      return;

    case "delete":
      printJson(
        await requestJson(url, `/api/memos/${encodeURIComponent(requireValue(args[0], "memo id"))}`, {
          method: "DELETE",
        })
      );
      return;

    default:
      process.stderr.write(`Unknown command: ${command}\n`);
      usage();
      process.exitCode = 2;
  }
}

async function runLocal(command, args) {
  switch (command) {
    case "health":
      printJson(health("local"));
      return;

    case "state":
      printJson(await readState());
      return;

    case "list": {
      printJson(await listMemos());
      return;
    }

    case "add":
      printJson(await addMemo(requireValue(args.join(" "), "memo text")));
      return;

    case "delete":
      printJson(await deleteMemo(requireValue(args[0], "memo id")));
      return;

    default:
      process.stderr.write(`Unknown command: ${command}\n`);
      usage();
      process.exitCode = 2;
  }
}

async function main() {
  const parsed = parseArgs(process.argv.slice(2));
  const [command, ...args] = parsed.args;

  switch (command) {
    case undefined:
    case "":
    case "help":
    case "--help":
    case "-h":
      usage();
      return;
  }

  if (parsed.url) {
    await runHttp(parsed.url, command, args);
  } else {
    await runLocal(command, args);
  }
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
