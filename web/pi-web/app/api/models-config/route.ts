import { proxyRuntimeModelSetup } from "@/lib/runtime-model-setup-proxy";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  return proxyRuntimeModelSetup(request, "setup", "GET");
}

export async function POST(request: Request) {
  return proxyRuntimeModelSetup(request, "apply", "POST");
}
