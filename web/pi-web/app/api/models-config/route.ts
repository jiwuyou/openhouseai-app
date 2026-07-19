import { NextResponse } from "next/server";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "fs";
import { join, dirname } from "path";
import { getAgentDir } from "@earendil-works/pi-coding-agent";

export const dynamic = "force-dynamic";

function getModelsPath(): string {
  return join(getAgentDir(), "models.json");
}

function readModelsJson(): Record<string, unknown> {
  const path = getModelsPath();
  if (!existsSync(path)) return { providers: {} };
  try {
    return JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
  } catch {
    return { providers: {} };
  }
}

function writeModelsJson(data: Record<string, unknown>): void {
  const path = getModelsPath();
  const dir = dirname(path);
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  writeFileSync(path, JSON.stringify(data, null, 2), "utf8");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasNonEmptyRecord(value: unknown): boolean {
  return isRecord(value) && Object.keys(value).length > 0;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function pruneEmptyProviders(data: Record<string, unknown>): Record<string, unknown> {
  if (!isRecord(data.providers)) return data;
  const providers: Record<string, unknown> = {};
  for (const [name, rawProvider] of Object.entries(data.providers)) {
    if (!isRecord(rawProvider)) {
      providers[name] = rawProvider;
      continue;
    }

    const models = Array.isArray(rawProvider.models)
      ? rawProvider.models.filter((model) => isRecord(model) && optionalString(model.id))
      : undefined;
    const provider: Record<string, unknown> = {
      ...rawProvider,
      ...(models ? { models: models.length ? models : undefined } : {}),
    };
    const hasMeaningfulConfig =
      !!optionalString(provider.baseUrl)
      || hasNonEmptyRecord(provider.headers)
      || hasNonEmptyRecord(provider.compat)
      || hasNonEmptyRecord(provider.modelOverrides)
      || (Array.isArray(provider.models) && provider.models.length > 0);

    if (hasMeaningfulConfig) providers[name] = provider;
  }
  return { ...data, providers };
}

export async function GET() {
  return NextResponse.json(readModelsJson());
}

export async function PUT(req: Request) {
  try {
    const body = await req.json() as Record<string, unknown>;
    const sanitized = pruneEmptyProviders(body);
    writeModelsJson(sanitized);
    // Model registry refreshes on each /api/models request (no local cache to invalidate)
    return NextResponse.json({ success: true, config: sanitized });
  } catch (error) {
    return NextResponse.json({ error: String(error) }, { status: 500 });
  }
}
