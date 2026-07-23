import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { discoverModels } from "../dist/model-discovery.js";
import { ModelSetupService } from "../dist/model-setup-service.js";

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function protocolFetch({ anthropic = 200, openai = 200, gemini = 200 } = {}) {
  return async (_url, init = {}) => {
    const headers = new Headers(init.headers);
    if (headers.has("x-goog-api-key")) {
      return gemini === 200
        ? jsonResponse({ models: [
            { name: "models/gemini-only", displayName: "Gemini only", supportedGenerationMethods: ["generateContent"] },
            { name: "models/shared", supportedGenerationMethods: ["generateContent"] },
          ] })
        : jsonResponse({ error: { message: "gemini failed" } }, gemini);
    }
    if (headers.has("x-api-key")) {
      return anthropic === 200
        ? jsonResponse({ data: [{ id: "claude-only" }, { id: "shared" }] })
        : jsonResponse({ error: { message: "anthropic failed" } }, anthropic);
    }
    return openai === 200
      ? jsonResponse({ data: [{ id: "openai-only" }, { id: "shared" }] })
      : jsonResponse({ error: { message: "openai failed" } }, openai);
  };
}

test("auto discovery returns all four mode results and deduplicates models with source modes", async () => {
  const result = await discoverModels({
    providerId: "custom",
    baseUrl: "https://gateway.example/v1/chat/completions",
    apiType: "auto",
    apiKey: "test-key",
  }, protocolFetch());

  assert.deepEqual(result.modeResults.map((mode) => mode.api), [
    "anthropic-messages",
    "openai-responses",
    "openai-completions",
    "google-generative-ai",
  ]);
  assert.equal(result.modeResults.every((mode) => mode.ok && mode.source === "model-list"), true);
  assert.deepEqual(result.models.map((model) => model.id), ["claude-only", "gemini-only", "openai-only", "shared"]);
  assert.deepEqual(result.models.find((model) => model.id === "shared")?.sources, [
    "anthropic-messages",
    "openai-responses",
    "openai-completions",
    "google-generative-ai",
  ]);
  assert.match(result.message, /generation compatibility is verified by the model test/);
});

test("auto discovery keeps successful model lists when other modes fail", async () => {
  const result = await discoverModels({
    providerId: "custom",
    baseUrl: "https://gateway.example",
    api: "auto",
    apiKey: "test-key",
  }, protocolFetch({ anthropic: 401, gemini: 500 }));

  assert.equal(result.modeResults.length, 4);
  assert.deepEqual(result.modeResults.map((mode) => [mode.api, mode.ok]), [
    ["anthropic-messages", false],
    ["openai-responses", true],
    ["openai-completions", true],
    ["google-generative-ai", false],
  ]);
  assert.deepEqual(result.models.map((model) => model.id), ["openai-only", "shared"]);
});

test("auto discovery reports every mode when all model-list attempts fail", async () => {
  await assert.rejects(() => discoverModels({
    providerId: "custom",
    baseUrl: "https://gateway.example/v1",
    api: "auto",
    apiKey: "test-key",
  }, protocolFetch({ anthropic: 404, openai: 404, gemini: 404 })), (error) => {
    assert.equal(error.code, "model_discovery_failed");
    assert.equal(error.details.modeResults.length, 4);
    assert.equal(error.details.modeResults.every((mode) => mode.ok === false && mode.error), true);
    return true;
  });
});

test("discovery normalizes pasted generation endpoints and never exposes Gemini keys", async () => {
  const requested = [];
  const result = await discoverModels({
    providerId: "custom",
    baseUrl: "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent?alt=sse",
    apiType: "gemini",
    apiKey: "secret-gemini-key",
  }, async (url) => {
    requested.push(String(url));
    return jsonResponse({ models: [{ name: "models/gemini-test", supportedGenerationMethods: ["generateContent"] }] });
  });

  assert.match(requested[0], /^https:\/\/generativelanguage\.googleapis\.com\/v1beta\/models\?key=/);
  assert.equal(result.candidates.some((candidate) => candidate.includes("secret-gemini-key")), false);
  assert.equal(result.modeResults[0].endpoint.includes("secret-gemini-key"), false);
});

test("explicit draft Base URL and API type override provider preset defaults", async () => {
  const requested = [];
  const result = await discoverModels({
    providerId: "openai",
    baseUrl: "https://custom.example/messages",
    apiType: "claude",
    apiKey: "draft-key",
  }, async (url, init = {}) => {
    requested.push({ url: String(url), headers: new Headers(init.headers) });
    return jsonResponse({ data: [{ id: "claude-custom" }] });
  });

  assert.equal(result.resolvedApi, "anthropic-messages");
  assert.equal(requested[0].url, "https://custom.example/v1/models");
  assert.equal(requested[0].headers.get("x-api-key"), "draft-key");
  assert.equal(requested[0].headers.has("authorization"), false);
});

test("model-list URL normalization accepts roots, versions, and pasted generation paths", async () => {
  const cases = [
    "https://gateway.example",
    "https://gateway.example/v1",
    "https://gateway.example/models",
    "https://gateway.example/chat/completions",
    "https://gateway.example/responses",
    "https://gateway.example/messages",
  ];
  for (const baseUrl of cases) {
    const requested = [];
    await discoverModels({ providerId: "custom", baseUrl, apiType: "openai", apiKey: "test-key" }, async (url) => {
      requested.push(String(url));
      return jsonResponse({ data: [{ id: "model-a" }] });
    });
    assert.equal(requested[0], "https://gateway.example/v1/models");
  }
});

test("an explicitly cleared Base URL is not replaced by a provider preset", async () => {
  await assert.rejects(() => discoverModels({
    providerId: "openai",
    baseUrl: " ",
    apiType: "openai",
    apiKey: "test-key",
  }, protocolFetch()), (error) => error.code === "invalid_base_url");
});

test("service draft fetch is zero-write for models, auth, and settings", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-model-fetch-"));
  const agentDir = join(root, "agent");
  await mkdir(agentDir, { recursive: true });
  const paths = ["models.json", "auth.json", "settings.json"].map((name) => join(agentDir, name));
  await Promise.all(paths.map((path, index) => writeFile(path, `before-${index}`)));
  const service = new ModelSetupService({
    agentDir,
    modelRuntime: async () => ({ getModels: () => [] }),
    settingsManager: {},
    reload: async () => {},
    discover: (input) => discoverModels(input, protocolFetch()),
  });
  try {
    const result = await service.fetchModels({
      providerId: "custom",
      apiType: "auto",
      apiKey: "draft-key",
      provider: { baseUrl: "https://gateway.example/responses", api: "openai-completions" },
    });
    assert.equal(result.modeResults.length, 4);
    assert.deepEqual(await Promise.all(paths.map((path) => readFile(path, "utf8"))), ["before-0", "before-1", "before-2"]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
