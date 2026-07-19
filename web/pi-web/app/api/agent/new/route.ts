import { NextResponse } from "next/server";

export async function POST() {
  return NextResponse.json(
    { error: "Session creation moved to /admin/v1/sessions and Pi native JSON RPC." },
    { status: 410 },
  );
}
