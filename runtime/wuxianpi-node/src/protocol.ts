export const PROTOCOL_NAME = "wuxianpi-sdk-v1" as const;
export const PROTOCOL_VERSION = 2 as const;
export const RUNTIME_VERSION = "0.1.0" as const;

export interface ClientRequest {
  id: string;
  type: string;
  sessionId?: string;
  payload?: Record<string, unknown>;
}

export interface ProtocolError {
  code: string;
  message: string;
  details?: unknown;
}

export interface SuccessResponse {
  id: string;
  ok: true;
  result: unknown;
  connectionId?: string;
}

export interface ErrorResponse {
  id: string;
  ok: false;
  error: ProtocolError;
  connectionId?: string;
}

export type ServerResponse = SuccessResponse | ErrorResponse;

export interface AgentEventEnvelope {
  type: "agent.event";
  connectionId: string;
  sessionId: string;
  sessionPath?: string;
  eventStreamId: string;
  sequence: number;
  payload: unknown;
}

export type RuntimeAgentEventEnvelope = Omit<AgentEventEnvelope, "connectionId">;

export class RequestError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
    this.name = "RequestError";
  }
}

export function parseRequest(raw: string): ClientRequest {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new RequestError("invalid_json", "Request must be valid JSON");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new RequestError("invalid_request", "Request must be a JSON object");
  }
  const candidate = value as Partial<ClientRequest>;
  if (typeof candidate.id !== "string" || candidate.id.trim() === "") {
    throw new RequestError("invalid_request", "Request id must be a non-empty string");
  }
  if (typeof candidate.type !== "string" || candidate.type.trim() === "") {
    throw new RequestError("invalid_request", "Request type must be a non-empty string");
  }
  if (candidate.sessionId !== undefined && typeof candidate.sessionId !== "string") {
    throw new RequestError("invalid_request", "sessionId must be a string");
  }
  if (
    candidate.payload !== undefined &&
    (!candidate.payload || typeof candidate.payload !== "object" || Array.isArray(candidate.payload))
  ) {
    throw new RequestError("invalid_request", "payload must be an object");
  }
  return candidate as ClientRequest;
}

export function success(id: string, result: unknown = {}, connectionId?: string): SuccessResponse {
  return { id, ok: true, result, ...(connectionId ? { connectionId } : {}) };
}

export function failure(id: string, error: unknown, connectionId?: string): ErrorResponse {
  if (error instanceof RequestError) {
    return {
      id,
      ok: false,
      error: { code: error.code, message: error.message, details: error.details },
      ...(connectionId ? { connectionId } : {}),
    };
  }
  const message = error instanceof Error ? error.message : String(error);
  return { id, ok: false, error: { code: "runtime_error", message }, ...(connectionId ? { connectionId } : {}) };
}

export function stringifyMessage(value: unknown): string {
  const ancestors: object[] = [];
  return JSON.stringify(value, function (_key, item: unknown) {
    if (typeof item === "bigint") return item.toString();
    if (item instanceof Error) {
      return { name: item.name, message: item.message, stack: item.stack };
    }
    if (item instanceof Date) return item.toISOString();
    if (item && typeof item === "object") {
      while (ancestors.length > 0 && ancestors.at(-1) !== this) ancestors.pop();
      if (ancestors.includes(item)) return "[Circular]";
      ancestors.push(item);
    }
    return item;
  });
}

export function requireString(payload: Record<string, unknown>, name: string): string {
  const value = payload[name];
  if (typeof value !== "string" || value.trim() === "") {
    throw new RequestError("invalid_payload", `${name} must be a non-empty string`);
  }
  return value;
}

export function optionalString(payload: Record<string, unknown>, name: string): string | undefined {
  const value = payload[name];
  if (value === undefined || value === null || value === "") return undefined;
  if (typeof value !== "string") {
    throw new RequestError("invalid_payload", `${name} must be a string`);
  }
  return value;
}

export function boundedInteger(
  payload: Record<string, unknown>,
  name: string,
  fallback: number,
  maximum: number,
): number {
  const value = payload[name];
  if (value === undefined) return fallback;
  if (!Number.isInteger(value) || (value as number) < 0) {
    throw new RequestError("invalid_payload", `${name} must be a non-negative integer`);
  }
  return Math.min(value as number, maximum);
}
