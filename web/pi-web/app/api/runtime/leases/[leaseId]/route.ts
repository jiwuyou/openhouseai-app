import { NextResponse } from "next/server";
import { piRuntimeHeaders, piRuntimeOrigin } from "@/lib/runtime-server";

export const dynamic = "force-dynamic";

export async function DELETE(
  _request: Request,
  { params }: { params: Promise<{ leaseId: string }> },
) {
  const { leaseId } = await params;
  const upstream = await fetch(`${piRuntimeOrigin()}/admin/v1/leases/${encodeURIComponent(leaseId)}`, {
    method: "DELETE",
    headers: await piRuntimeHeaders(),
    cache: "no-store",
  }).catch((error) => new Response(JSON.stringify({ error: String(error) }), { status: 502 }));
  return new NextResponse(await upstream.text(), {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("content-type") || "application/json", "Cache-Control": "no-store" },
  });
}
