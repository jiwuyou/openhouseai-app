export const dynamic = "force-dynamic";

export async function GET() {
  return new Response("Agent SSE was replaced by PiWebSocketTransport", { status: 410 });
}
