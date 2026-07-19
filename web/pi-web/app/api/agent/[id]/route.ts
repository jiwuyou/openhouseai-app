import { NextResponse } from "next/server";

const removed = () => NextResponse.json(
  { error: "The Next.js AgentSession bridge has been removed. Use PiWebSocketTransport." },
  { status: 410 },
);

export async function POST() { return removed(); }
export async function GET() { return removed(); }
