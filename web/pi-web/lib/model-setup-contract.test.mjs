import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { LatestRequestGate } from "./latest-request-gate.ts";
import { MODEL_API_OPTIONS, concreteApiForModel, configHasAutoApi, modelApiLabel, modelSetupHealth, normalizeModelDraftResult, normalizeModelSetup, toRuntimeModelSetupApplyRequest } from "./model-setup-contract.ts";

test("keeps Base URL and API type editable in novice mode", () => {
  const source = readFileSync(new URL("../components/ModelsConfig.tsx", import.meta.url), "utf8");
  assert.match(source, /<Field label="Base URL" span>/);
  assert.match(source, /<Field label="API 类型">/);
  assert.equal(source.includes("showEasyEndpoint"), false);
});

test("uses the exact Runtime API type mapping", () => {
  assert.deepEqual(MODEL_API_OPTIONS, [
    { value: "auto", label: "Auto（多协议）" },
    { value: "anthropic-messages", label: "Claude / Anthropic" },
    { value: "openai-responses", label: "GPT" },
    { value: "openai-completions", label: "OpenAI" },
    { value: "google-generative-ai", label: "Gemini" },
  ]);
  assert.equal(modelApiLabel("google-generative-ai"), "Gemini");
});

test("normalizes partial auto success, deduplicates models, and preserves source modes", () => {
  const result = normalizeModelDraftResult({ ok: true, data: {
    protocols: [
      { protocol: "anthropic-messages", status: "success", models: ["shared", "claude-only"] },
      { mode: "openai-responses", success: true, models: [{ id: "shared" }, { id: "gpt-only" }] },
      { api: "google-generative-ai", ok: false, error: { message: "unauthorized" } },
    ],
  } });
  assert.equal(result.ok, true);
  assert.deepEqual(result.models.map((model) => model.id), ["shared", "claude-only", "gpt-only"]);
  assert.deepEqual(result.models[0].sourceApis, ["anthropic-messages", "openai-responses"]);
  assert.deepEqual(result.modeResults[2], {
    api: "google-generative-ai", label: "Gemini", ok: false, modelCount: 0, models: [], error: "unauthorized",
  });
});

test("converges auto from model sources and blocks unresolved manual auto payloads", () => {
  assert.equal(concreteApiForModel({ sourceApis: ["openai-completions", "openai-responses"] }, "auto"), "openai-completions");
  assert.equal(concreteApiForModel(undefined, "auto"), undefined);
  assert.equal(configHasAutoApi({ providers: { custom: { api: "auto" } } }), true);
  assert.throws(() => toRuntimeModelSetupApplyRequest({
    revision: "r1",
    config: { providers: { custom: { api: "auto", models: [{ id: "manual-model" }] } } },
  }), /仍包含 Auto API 类型/);
  const source = readFileSync(new URL("../components/ModelsConfig.tsx", import.meta.url), "utf8");
  assert.match(source, /easyApiUnresolved/);
  assert.match(source, /请选择具体 API 类型后再测试模型/);
});

test("normalizes all-failed HTTP error mode details without flattening them", () => {
  const result = normalizeModelDraftResult({ modeResults: [
    { api: "anthropic-messages", ok: false, error: "unauthorized" },
    { api: "google-generative-ai", ok: false, error: "not found" },
  ] });
  assert.equal(result.ok, false);
  assert.deepEqual(result.modeResults, [
    { api: "anthropic-messages", label: "Claude / Anthropic", ok: false, modelCount: 0, models: [], error: "unauthorized" },
    { api: "google-generative-ai", label: "Gemini", ok: false, modelCount: 0, models: [], error: "not found" },
  ]);
  const source = readFileSync(new URL("../components/ModelsConfig.tsx", import.meta.url), "utf8");
  assert.match(source, /error\.details \?\? root\.details/);
  assert.match(source, /errorDraftResult\(reason\)/);
});

test("debounces draft requests and rejects stale versions", async () => {
  const gate = new LatestRequestGate();
  const stale = gate.schedule(1_000);
  const latest = gate.schedule(0);
  assert.equal(await stale, null);
  const version = await latest;
  assert.equal(typeof version, "number");
  assert.equal(gate.isCurrent(version), true);
  gate.invalidate();
  assert.equal(gate.isCurrent(version), false);
});

test("shows per-mode model-list progress without claiming generation protocol verification", () => {
  const source = readFileSync(new URL("../components/ModelsConfig.tsx", import.meta.url), "utf8");
  assert.match(source, /需要探测多种模式，请耐心等待/);
  assert.match(source, /进行中 · 正在尝试模型列表 URL\/auth 规则/);
  assert.match(source, /模型列表获取成功/);
  assert.match(source, /模型列表获取失败/);
  assert.match(source, /去重模型列表/);
  assert.match(source, /这里只验证模型列表获取；生成协议需选择具体 API 类型后点击测试。/);
  assert.match(source, /isCurrentDraftRequest\(requestVersion\)/);
  assert.equal(source.includes("生成协议已验证"), false);
});

test("keeps fetch and test failure paths separate from apply", () => {
  const source = readFileSync(new URL("../components/ModelsConfig.tsx", import.meta.url), "utf8");
  const draftHandlers = source.slice(source.indexOf("const fetchDraft"), source.indexOf("const apply ="));
  assert.equal(draftHandlers.includes("apply("), false);
});

test("uses explicit advanced default selection and omits default changes when unchecked", () => {
  const source = readFileSync(new URL("../components/ModelsConfig.tsx", import.meta.url), "utf8");
  assert.match(source, /advancedSetDefault/);
  assert.match(source, /保存时设为全局默认/);
  const request = toRuntimeModelSetupApplyRequest({
    revision: "r1",
    config: { providers: { p: { api: "openai-completions", models: [{ id: "m" }] } } },
    setGlobalDefault: false,
  });
  assert.equal(request.setGlobalDefault, false);
  assert.equal("defaultModel" in request, false);
});

test("normalizes Runtime setup without retaining API keys", () => {
  const setup = normalizeModelSetup({ ok: true, data: {
    revision: "r1",
    presets: [{ id: "deepseek", name: "DeepSeek", providerName: "deepseek", keyRequired: true, defaultModels: ["deepseek-chat"] }],
    config: { providers: { deepseek: { baseUrl: "https://api.deepseek.com/v1", apiKey: "secret", compat: { custom: true }, models: [{ id: "deepseek-chat", apiKey: "nested", contextWindow: 64000 }] } } },
    providers: [{ id: "deepseek", configured: true, credentialSource: "stored", apiKey: "hidden" }],
    models: [{ provider: "deepseek", id: "deepseek-chat", available: true }],
    defaultModel: { provider: "deepseek", modelId: "deepseek-chat" },
  } });

  assert.equal(setup.presets[0].providerId, "deepseek");
  assert.equal(setup.providers[0].authenticated, true);
  assert.equal(JSON.stringify(setup).includes("secret"), false);
  assert.equal(JSON.stringify(setup).includes("nested"), false);
  assert.equal(JSON.stringify(setup).includes("hidden"), false);
  assert.deepEqual(setup.config.providers.deepseek.compat, { custom: true });
  assert.equal(setup.config.providers.deepseek.models[0].contextWindow, 64000);
  assert.equal(modelSetupHealth(setup).hasUsableModel, true);
});

test("reports missing and unavailable models", () => {
  const health = modelSetupHealth(normalizeModelSetup({
    revision: "r1",
    presets: [],
    config: { providers: {} },
    providers: [],
    models: [{ provider: "p", id: "m", available: false }],
    defaultModel: { provider: "p", modelId: "m" },
  }));
  assert.equal(health.hasUsableModel, false);
  assert.deepEqual(health.missingReasons, ["no_providers", "no_usable_models", "default_model_not_usable"]);
});

test("maps credential drafts to Runtime provider changes", () => {
  assert.deepEqual(toRuntimeModelSetupApplyRequest({
    revision: "r1",
    config: { providers: { kept: { baseUrl: "https://example.com/v1", models: [{ id: "m" }] } } },
    credentials: {
      kept: { action: "set", apiKey: "secret" },
      removed: { action: "remove" },
    },
  }).changes, [
    { providerId: "kept", action: "upsert", provider: { baseUrl: "https://example.com/v1", models: [{ id: "m" }] }, credential: { action: "set", apiKey: "secret" } },
    { providerId: "removed", action: "remove", credential: { action: "remove" } },
  ]);
});
