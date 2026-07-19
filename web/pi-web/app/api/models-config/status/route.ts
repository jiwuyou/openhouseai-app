import { NextResponse } from "next/server";
import { getModelConfigHealth } from "@/lib/model-config-health";
import { getDefaultCwd } from "@/lib/runtime-paths";

export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const url = new URL(req.url);
  const cwd = url.searchParams.get("cwd") || getDefaultCwd();
  const status = await getModelConfigHealth({ cwd });
  return NextResponse.json(status);
}
