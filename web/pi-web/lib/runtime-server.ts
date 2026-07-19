import { readFile } from "node:fs/promises";

export const DEFAULT_PI_RUNTIME_ORIGIN = "http://127.0.0.1:8765";

export function piRuntimeOrigin(): string {
  return (process.env.OPENHOUSE_PI_RUNTIME_ORIGIN || DEFAULT_PI_RUNTIME_ORIGIN).replace(/\/$/, "");
}

export async function piRuntimeToken(): Promise<string> {
  const inline = process.env.OPENHOUSE_PI_RUNTIME_TOKEN?.trim();
  if (inline) return inline;
  const tokenFile = process.env.OPENHOUSE_PI_RUNTIME_TOKEN_FILE?.trim();
  if (!tokenFile) return "";
  try {
    return (await readFile(tokenFile, "utf8")).trim();
  } catch {
    return "";
  }
}

export async function piRuntimeHeaders(json = false): Promise<HeadersInit> {
  const token = await piRuntimeToken();
  return {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(json ? { "Content-Type": "application/json" } : {}),
  };
}
