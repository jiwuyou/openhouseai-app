import { NextResponse } from "next/server";

export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json(
    {
      adminBaseUrl: "/api/runtime",
      wsUrl: "/ws/pi",
      protocol: "pi-jsonl-rpc-v1",
    },
    {
      headers: {
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer",
      },
    },
  );
}
