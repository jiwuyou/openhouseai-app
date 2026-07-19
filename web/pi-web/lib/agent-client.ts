import {
  bindPiTransportSession,
  getPiTransport,
  openPiTransport,
  type LeaseRequest,
  type PiWebSocketTransport,
} from "@/lib/pi-websocket-transport";

export async function connectAgentSession(request: LeaseRequest): Promise<PiWebSocketTransport> {
  return openPiTransport(request);
}

export function bindAgentSession(sessionId: string, transport: PiWebSocketTransport): void {
  bindPiTransportSession(sessionId, transport);
}

export async function sendAgentCommand<T = unknown>(
  sessionId: string,
  command: Record<string, unknown>,
): Promise<T> {
  const transport = getPiTransport(sessionId) || await openPiTransport({ sessionId });
  if (typeof command.type !== "string") throw new Error("Pi RPC command type is required");
  return transport.send<T>(command as { type: string; [key: string]: unknown });
}
