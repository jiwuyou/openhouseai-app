import { modelSetupHealth, normalizeModelSetup } from "@/lib/model-setup-contract";
import { requestRuntimeModelSetup } from "@/lib/runtime-model-setup-proxy";

export const dynamic = "force-dynamic";

export async function GET() {
  const response = await requestRuntimeModelSetup("setup", { method: "GET" });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) return Response.json(payload, { status: response.status });
  return Response.json(modelSetupHealth(normalizeModelSetup(payload)), { headers: { "Cache-Control": "no-store" } });
}
