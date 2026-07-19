import { getWebModelRuntime } from "@/lib/model-runtime";

export const dynamic = "force-dynamic";

export async function GET() {
  const runtime = await getWebModelRuntime();
  const credentials = await runtime.listCredentials();
  const oauthProviders = new Set(credentials.filter((entry) => entry.type === "oauth").map((entry) => entry.providerId));
  const providers = runtime.getProviders().filter((provider) => provider.auth.oauth);

  const EXCLUDED = new Set(["anthropic"]);
  const DISPLAY_NAMES: Record<string, string> = {
    "openai-codex": "ChatGPT Plus/Pro",
    "github-copilot": "GitHub Copilot",
  };

  const result = await Promise.all(
    providers
      .filter((p) => !EXCLUDED.has(p.id))
      .map(async (p) => {
        return {
          id: p.id,
          name: DISPLAY_NAMES[p.id] ?? p.name,
          usesCallbackServer: false,
          loggedIn: oauthProviders.has(p.id),
        };
      })
  );

  return Response.json({ providers: result });
}
