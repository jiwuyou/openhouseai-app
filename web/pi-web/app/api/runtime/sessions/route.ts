import { NextResponse } from "next/server";
import { piRuntimeHeaders, piRuntimeOrigin } from "@/lib/runtime-server";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  const body = await request.text();
  const upstream = await fetch(`${piRuntimeOrigin()}/admin/v1/sessions`, {
    method: "POST",
    headers: await piRuntimeHeaders(true),
    body,
    cache: "no-store",
  }).catch((error) => new Response(JSON.stringify({ error: String(error) }), { status: 502 }));
  return new NextResponse(await upstream.text(), {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("content-type") || "application/json", "Cache-Control": "no-store" },
  });
}
