#!/usr/bin/env node
import { createRuntimeServer } from "./server.js";

function readOption(name: string): string | undefined {
  const index = process.argv.indexOf(name); return index >= 0 ? process.argv[index + 1] : undefined;
}
function parseListen(value: string): { host: string; port: number } {
  const lastColon = value.lastIndexOf(":");
  if (lastColon < 0) return { host: "127.0.0.1", port: Number(value) };
  return { host: value.slice(0, lastColon) || "127.0.0.1", port: Number(value.slice(lastColon + 1)) };
}
if (process.argv.includes("--help") || process.argv.includes("-h")) {
  process.stdout.write("Usage: wuxianpi-node [--listen HOST:PORT] [--agent-dir PATH] [--idle-timeout-ms N] [--web-root PATH] [--preferred-web-ui-url URL]\n");
  process.exit(0);
}
const listen = parseListen(readOption("--listen") ?? process.env.OPENHOUSE_PI_LISTEN ?? "127.0.0.1:8765");
if (!Number.isInteger(listen.port) || listen.port < 1 || listen.port > 65535) throw new Error(`Invalid listen port: ${listen.port}`);
const idleTimeoutMs = Number(readOption("--idle-timeout-ms") ?? process.env.OPENHOUSE_PI_IDLE_TIMEOUT_MS ?? "300000");
const agentDir = readOption("--agent-dir") ?? process.env.PI_CODING_AGENT_DIR;
const webRoot = readOption("--web-root") ?? process.env.WUXIANPI_WEB_ROOT;
const preferredWebUiUrl = readOption("--preferred-web-ui-url") ?? process.env.OPENHOUSE_AIONUI_ORIGIN;
const server = createRuntimeServer({ ...listen, idleTimeoutMs, agentDir, webRoot, preferredWebUiUrl });
await server.start();
process.stdout.write(`WuxianPi Node runtime listening on http://${listen.host}:${listen.port}\n`);
let stopping = false;
async function stop() { if (stopping) return; stopping = true; await server.stop(); }
for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"] as const) {
  process.on(signal, () => void stop().finally(() => process.exit(signal === "SIGHUP" ? 129 : 0)));
}
