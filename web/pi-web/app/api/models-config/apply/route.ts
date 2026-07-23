import { proxyRuntimeModelSetup } from "@/lib/runtime-model-setup-proxy";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  return proxyRuntimeModelSetup(request, "apply", "POST");
}
