import { NextResponse } from "next/server";
import { getWebModelRuntime } from "@/lib/model-runtime";

export const dynamic = "force-dynamic";

type Params = { params: Promise<{ provider: string }> };

// GET /api/auth/api-key/[provider] — returns auth status (never returns the actual key)
export async function GET(_req: Request, { params }: Params) {
  const { provider } = await params;
  const runtime = await getWebModelRuntime();
  const status = runtime.getProviderAuthStatus(provider);
  const displayName = runtime.getProvider(provider)?.name ?? provider;
  const models = runtime.getModels(provider).length;
  return NextResponse.json({ provider, displayName, configured: status.configured, source: status.source, models });
}

// POST /api/auth/api-key/[provider]  body: { apiKey: string }
export async function POST(req: Request, { params }: Params) {
  const { provider } = await params;
  try {
    const { apiKey } = await req.json() as { apiKey?: string };
    if (!apiKey || typeof apiKey !== "string" || !apiKey.trim()) {
      return NextResponse.json({ error: "apiKey is required" }, { status: 400 });
    }
    const runtime = await getWebModelRuntime();
    const providerInfo = runtime.getProvider(provider);
    if (!providerInfo?.auth.apiKey?.login) {
      return NextResponse.json({ error: `Provider does not support API-key login: ${provider}` }, { status: 400 });
    }
    await runtime.login(provider, "api_key", {
      prompt: async () => apiKey.trim(),
      notify: () => {},
    });
    return NextResponse.json({ success: true });
  } catch (error) {
    return NextResponse.json({ error: String(error) }, { status: 500 });
  }
}

// DELETE /api/auth/api-key/[provider] — removes stored API key
export async function DELETE(_req: Request, { params }: Params) {
  const { provider } = await params;
  try {
    const runtime = await getWebModelRuntime();
    await runtime.logout(provider);
    return NextResponse.json({ success: true });
  } catch (error) {
    return NextResponse.json({ error: String(error) }, { status: 500 });
  }
}
