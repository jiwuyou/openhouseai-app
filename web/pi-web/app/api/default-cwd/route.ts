import { NextResponse } from "next/server";
import { mkdirSync } from "fs";
import { allowFileRoot } from "@/lib/file-access";
import { getDefaultCwd } from "@/lib/runtime-paths";

async function resolveDefaultCwd() {
  try {
    const defaultCwd = getDefaultCwd();
    mkdirSync(defaultCwd, { recursive: true });
    allowFileRoot(defaultCwd);
    return NextResponse.json({ cwd: defaultCwd });
  } catch (error) {
    return NextResponse.json({ error: String(error) }, { status: 500 });
  }
}

// GET/POST /api/default-cwd
// Returns PI_WEB_DEFAULT_CWD, falling back to os.homedir().
export const GET = resolveDefaultCwd;
export const POST = resolveDefaultCwd;
