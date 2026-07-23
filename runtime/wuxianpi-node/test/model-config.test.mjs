import assert from "node:assert/strict";
import { access, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { PiSdkAdapter } from "../dist/pi-sdk-adapter.js";
import { SessionRegistry } from "../dist/session-registry.js";

function fixture() {
  const model = {
    provider: "openai", id: "gpt-test", name: "GPT Test", api: "openai-responses",
    baseUrl: "https://example.invalid", reasoning: true, input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096,
  };
  let authenticated = false;
  const calls = { loginKey: undefined, test: undefined, reloads: 0, setDefault: undefined };
  const runtime = {
    getProviders: () => [{ id: "openai", name: "OpenAI" }],
    getProvider: (id) => id === "openai" ? { id, name: "OpenAI" } : undefined,
    getModels: (id) => !id || id === "openai" ? [model] : [],
    getModel: (provider, modelId) => provider === "openai" && modelId === "gpt-test" ? model : undefined,
    getAvailable: async () => authenticated ? [model] : [],
    getProviderAuthStatus: () => ({ configured: authenticated, source: authenticated ? "stored" : undefined }),
    checkAuth: async () => authenticated ? { type: "api_key", source: "stored" } : undefined,
    login: async (_provider, _type, interaction) => { calls.loginKey = await interaction.prompt({ type: "secret", message: "key" }); authenticated = true; },
    logout: async () => { authenticated = false; },
    completeSimple: async (_model, context, options) => {
      calls.test = { context, options };
      options.onResponse?.({ status: 200 });
      return {
        role: "assistant", content: [{ type: "text", text: "OK" }], api: "openai-responses",
        provider: "openai", model: "gpt-test", usage: {
          input: 1, output: 1, cacheRead: 0, cacheWrite: 0,
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
        }, stopReason: "stop", timestamp: Date.now(),
      };
    },
  };
  const settings = {
    getDefaultProvider: () => "openai",
    getDefaultModel: () => "gpt-test",
  };
  const setupService = {
    setup: async () => {
      const providers = runtime.getProviders();
      return {
        revision: "test", presets: [], config: { providers: {} },
        providers: providers.map((provider) => ({ ...provider, authenticated })),
        models: runtime.getModels().map((item) => ({ ...item, available: authenticated })),
        defaultModel: { provider: settings.getDefaultProvider(), modelId: settings.getDefaultModel() },
      };
    },
    login: async (provider, apiKey) => {
      await runtime.login(provider, "api_key", { prompt: async () => apiKey, notify: () => {} });
      return { provider, authenticated: true };
    },
    logout: async (provider) => { await runtime.logout(provider); return { provider, authenticated: false }; },
    testModel: async (payload) => {
      const result = await runtime.completeSimple(model, {
        messages: [{ role: "user", content: "Reply with OK only.", timestamp: Date.now() }],
      }, { maxTokens: 16, timeoutMs: payload.timeoutMs, maxRetries: 0, cacheRetention: "none" });
      return { ok: true, provider: model.provider, modelId: model.id, status: 200, text: result.content[0].text };
    },
    reload: async () => { calls.reloads++; return setupService.setup(); },
  };
  const registry = {
    modelSetup: () => setupService,
    setDefaultModel: async (provider, modelId, sessionId, setGlobalDefault) => {
      calls.setDefault = { provider, modelId, sessionId, setGlobalDefault };
      return { provider, modelId, appliedSessionIds: sessionId ? [sessionId] : [] };
    },
  };
  return { adapter: new PiSdkAdapter(registry), calls };
}

test("model login/status/logout use SDK auth without returning the API key", async () => {
  const { adapter, calls } = fixture();
  await assert.rejects(() => adapter.dispatch({
    id: "oauth", type: "model.login", payload: { provider: "openai", method: "oauth", apiKey: "ignored" },
  }), (error) => error.code === "unsupported_auth_type");
  const login = await adapter.dispatch({
    id: "login", type: "model.login", payload: { provider: "openai", method: "api_key", apiKey: "secret-key" },
  });
  assert.equal(calls.loginKey, "secret-key");
  assert.equal(login.authenticated, true);
  assert.equal(JSON.stringify(login).includes("secret-key"), false);
  const status = await adapter.dispatch({ id: "status", type: "model.status", payload: {} });
  assert.equal(status.providers[0].authenticated, true);
  assert.equal(status.models[0].available, true);
  assert.deepEqual(status.defaultModel, { provider: "openai", modelId: "gpt-test" });
  const logout = await adapter.dispatch({ id: "logout", type: "model.logout", payload: { provider: "openai" } });
  assert.deepEqual(logout, { provider: "openai", authenticated: false });
});

test("model test uses low-token no-retry completeSimple and preserves the service protocol", async () => {
  const { adapter, calls } = fixture();
  await adapter.dispatch({ id: "login", type: "model.login", payload: { provider: "openai", apiKey: "key" } });
  const result = await adapter.dispatch({
    id: "test", type: "model.test", payload: { provider: "openai", modelId: "gpt-test", timeoutMs: 5000 },
  });
  assert.equal(result.ok, true);
  assert.equal(result.status, 200);
  assert.equal(result.text, "OK");
  assert.equal(calls.test.options.maxTokens, 16);
  assert.equal(calls.test.options.maxRetries, 0);
  assert.equal(calls.test.options.cacheRetention, "none");
  assert.equal(calls.test.context.messages[0].role, "user");
});

test("reload and setDefault delegate to the shared registry with active session identity", async () => {
  const { adapter, calls } = fixture();
  const reloaded = await adapter.dispatch({ id: "reload", type: "model.reload", payload: {} });
  assert.equal(calls.reloads, 1);
  assert.equal(reloaded.providers[0].id, "openai");
  const result = await adapter.dispatch({
    id: "default", type: "model.setDefault", sessionId: "session-1",
    payload: { provider: "openai", modelId: "gpt-test" },
  });
  assert.deepEqual(calls.setDefault, {
    provider: "openai", modelId: "gpt-test", sessionId: "session-1", setGlobalDefault: undefined,
  });
  assert.deepEqual(result.appliedSessionIds, ["session-1"]);

  await adapter.dispatch({
    id: "default-global", type: "model.setDefault", sessionId: "session-1",
    payload: { provider: "openai", modelId: "gpt-test", setGlobalDefault: true },
  });
  assert.deepEqual(calls.setDefault, {
    provider: "openai", modelId: "gpt-test", sessionId: "session-1", setGlobalDefault: true,
  });
});

test("SDK login and default model persist in agentDir and restore after restart", { timeout: 15_000 }, async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-model-persist-"));
  const agentDir = join(root, "agent");
  const first = new SessionRegistry(() => {}, { agentDir, idleTimeoutMs: 0 });
  try {
    const runtime = await first.models();
    await runtime.login("openai", "api_key", { prompt: async () => "persisted-test-key", notify: () => {} });
    const model = runtime.getModels("openai")[0];
    assert.ok(model);
    await first.setDefaultModel(model.provider, model.id);
    await access(join(agentDir, "auth.json"));
    await access(join(agentDir, "settings.json"));
    await first.dispose();

    const second = new SessionRegistry(() => {}, { agentDir, idleTimeoutMs: 0 });
    try {
      const restoredRuntime = await second.models();
      assert.equal(restoredRuntime.getProviderAuthStatus("openai").configured, true);
      assert.equal(second.settings().getDefaultProvider(), model.provider);
      assert.equal(second.settings().getDefaultModel(), model.id);
    } finally {
      await second.dispose();
    }
  } finally {
    await first.dispose().catch(() => {});
    await rm(root, { recursive: true, force: true });
  }
});
