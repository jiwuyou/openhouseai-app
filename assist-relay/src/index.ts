import { createRelayServer, DEFAULT_PORT } from "./relay-server.js";

function readPositiveInteger(name: string, fallback: number): number {
  const rawValue = process.env[name];
  if (rawValue === undefined) {
    return fallback;
  }

  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}

const relay = createRelayServer({
  host: process.env.HOST ?? "0.0.0.0",
  port: readPositiveInteger("PORT", DEFAULT_PORT),
  maxFrameBytes: readPositiveInteger("MAX_FRAME_BYTES", 1_048_576),
  maxBufferedBytes: readPositiveInteger("MAX_BUFFERED_BYTES", 4_194_304),
  pingIntervalMs: readPositiveInteger("PING_INTERVAL_MS", 30_000),
  emptyRoomTtlMs: readPositiveInteger("EMPTY_ROOM_TTL_MS", 60_000),
});

const address = await relay.listen();
console.log(`WuxianPi Assist Relay listening on ${address.address}:${address.port}`);

let shuttingDown = false;
async function shutdown(): Promise<void> {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;
  await relay.close();
}

process.once("SIGINT", () => {
  void shutdown().then(() => process.exit(0));
});
process.once("SIGTERM", () => {
  void shutdown().then(() => process.exit(0));
});
