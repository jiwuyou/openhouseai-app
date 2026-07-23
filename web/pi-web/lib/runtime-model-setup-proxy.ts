import { piRuntimeHeaders, piRuntimeOrigin } from "@/lib/runtime-server";

const MODEL_SETUP_PREFIX = "/api/web/v1/models";

export async function requestRuntimeModelSetup(path: "setup" | "fetch" | "test" | "apply", init: RequestInit = {}): Promise<Response> {
  const json = init.body != null;
  const headers = new Headers(await piRuntimeHeaders(json));
  new Headers(init.headers).forEach((value, key) => headers.set(key, value));
  try {
    return await fetch(`${piRuntimeOrigin()}${MODEL_SETUP_PREFIX}/${path}`, {
      ...init,
      headers,
      cache: "no-store",
    });
  } catch (error) {
    return Response.json({ error: { message: error instanceof Error ? error.message : String(error) } }, { status: 502 });
  }
}

export async function proxyRuntimeModelSetup(request: Request, path: "setup" | "fetch" | "test" | "apply", method = request.method): Promise<Response> {
  const body = method === "GET" || method === "HEAD" ? undefined : await request.text();
  const upstream = await requestRuntimeModelSetup(path, { method, ...(body ? { body } : {}) });
  return new Response(upstream.body, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("content-type") ?? "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}
