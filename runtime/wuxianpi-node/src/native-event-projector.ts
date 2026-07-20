import { randomUUID } from "node:crypto";
import type { RuntimeAgentEventEnvelope } from "./protocol.js";
import type { RegistrySessionEvent, RuntimeSlot, SessionRegistry } from "./session-registry.js";

interface NativeStreamState {
  eventStreamId: string;
  sequence: number;
}

/**
 * Adds the protocol-v2 stream coordinates required by remote Android clients.
 * The core registry intentionally knows nothing about connections, ACKs, or wire
 * sequence numbers; one active RuntimeSlot maps to one native event stream.
 */
export class NativeEventProjector {
  private readonly streams = new WeakMap<RuntimeSlot, NativeStreamState>();

  constructor(private readonly registry: SessionRegistry) {}

  project(event: RegistrySessionEvent): RuntimeAgentEventEnvelope {
    const stream = this.streamFor(event.runtime);
    return {
      type: "agent.event",
      sessionId: event.sessionId,
      sessionPath: event.sessionPath,
      eventStreamId: stream.eventStreamId,
      sequence: ++stream.sequence,
      payload: event.payload,
    };
  }

  async decorateResult(result: unknown): Promise<unknown> {
    if (!result || typeof result !== "object" || Array.isArray(result)) return result;
    const value = result as Record<string, unknown>;
    if (typeof value.sessionId === "string") {
      const slot = await this.registry.getOrOpen(value.sessionId);
      return { ...value, eventStreamId: this.streamFor(slot).eventStreamId };
    }
    if (Array.isArray(value.activeSessions)) {
      const activeSessions = await Promise.all(value.activeSessions.map(async (identity) => {
        if (!identity || typeof identity !== "object" || typeof (identity as { sessionId?: unknown }).sessionId !== "string") {
          return identity;
        }
        const sessionId = (identity as { sessionId: string }).sessionId;
        const slot = await this.registry.getOrOpen(sessionId);
        return { ...(identity as Record<string, unknown>), eventStreamId: this.streamFor(slot).eventStreamId };
      }));
      return { ...value, activeSessions };
    }
    return result;
  }

  identity(slot: RuntimeSlot): { eventStreamId: string; sequence: number } {
    const stream = this.streamFor(slot);
    return { eventStreamId: stream.eventStreamId, sequence: stream.sequence };
  }

  private streamFor(slot: RuntimeSlot): NativeStreamState {
    const current = this.streams.get(slot);
    if (current) return current;
    const created = { eventStreamId: randomUUID(), sequence: 0 };
    this.streams.set(slot, created);
    return created;
  }
}
