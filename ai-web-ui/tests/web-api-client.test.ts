import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import { sendAgentCommand } from "@/lib/agent-client";
import { LatestRequestGate } from "@/lib/latest-request-gate";
import { MODEL_API_OPTIONS, WebApiClient, WebApiError, concreteApiForModel, configHasAutoApi, modelApiLabel, normalizeModelDraftResult, toRuntimeModelSetupApplyRequest } from "@/lib/web-api-client";
import { bridgeExtension, getCapabilityCatalog, issueExtensionNonce, listWebExtensions, updateGlobalConfig } from "@/components/wuxianpi/api";

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json" },
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("WebApiClient Runtime contract", () => {
  it("keeps Base URL and API type editable in novice mode", () => {
    const source = readFileSync(new URL("../src/components/ModelsConfig.tsx", import.meta.url), "utf8");
    expect(source).toContain('<label className="span-2">Base URL');
    expect(source).toContain("<label>API 类型");
    expect(source).not.toContain("showEasyEndpoint");
  });

  it("exposes the Runtime API modes with the product labels", () => {
    expect(MODEL_API_OPTIONS).toEqual([
      { value: "auto", label: "Auto（多协议）" },
      { value: "anthropic-messages", label: "Claude / Anthropic" },
      { value: "openai-responses", label: "GPT" },
      { value: "openai-completions", label: "OpenAI" },
      { value: "google-generative-ai", label: "Gemini" },
    ]);
    expect(modelApiLabel("openai-responses")).toBe("GPT");
  });

  it("normalizes auto discovery aliases into a deduplicated model list with source modes", () => {
    const result = normalizeModelDraftResult({
      success: true,
      attempts: [
        { protocol: "anthropic-messages", status: "success", models: ["shared", "claude-only"] },
        { mode: "openai-responses", ok: true, models: [{ id: "shared" }, { id: "gpt-only" }] },
        { api: "openai-completions", success: false, error: { message: "not found" } },
      ],
    });
    expect(result.ok).toBe(true);
    expect(result.models.map((model) => model.id)).toEqual(["shared", "claude-only", "gpt-only"]);
    expect(result.models[0]?.sourceApis).toEqual(["anthropic-messages", "openai-responses"]);
    expect(result.modeResults[2]).toMatchObject({ api: "openai-completions", ok: false, error: "not found" });
  });

  it("converges auto to the selected model source and rejects unresolved manual auto drafts", async () => {
    expect(concreteApiForModel({ sourceApis: ["openai-responses", "openai-completions"] }, "auto")).toBe("openai-responses");
    expect(concreteApiForModel(undefined, "auto")).toBeUndefined();
    expect(configHasAutoApi({ providers: { custom: { api: "auto" } } })).toBe(true);

    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const client = new WebApiClient();
    await expect(client.testModelDraft({ providerId: "custom", baseUrl: "https://example.com/v1", api: "auto", modelId: "manual-model" }))
      .rejects.toThrow("请选择具体 API 类型");
    await expect(client.applyModelSetup({ revision: "r1", config: { providers: { custom: { api: "auto", models: [{ id: "manual-model" }] } } } }))
      .rejects.toThrow("仍包含 Auto API 类型");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("preserves all-failed auto mode details from HTTP errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ error: {
      message: "all modes failed",
      details: { modeResults: [
        { api: "anthropic-messages", ok: false, error: "unauthorized" },
        { api: "google-generative-ai", ok: false, error: "not found" },
      ] },
    } }, 400)));
    try {
      await new WebApiClient().fetchModelDraft({ providerId: "custom", baseUrl: "https://example.com", api: "auto" });
      expect.fail("expected fetch to fail");
    } catch (reason) {
      expect(reason).toBeInstanceOf(WebApiError);
      const result = normalizeModelDraftResult((reason as WebApiError).details);
      expect(result.ok).toBe(false);
      expect(result.modeResults).toEqual([
        { api: "anthropic-messages", label: "Claude / Anthropic", ok: false, modelCount: 0, models: [], error: "unauthorized" },
        { api: "google-generative-ai", label: "Gemini", ok: false, modelCount: 0, models: [], error: "not found" },
      ]);
    }
  });

  it("debounces draft work and invalidates stale request versions", async () => {
    vi.useFakeTimers();
    const gate = new LatestRequestGate();
    const stale = gate.schedule(500);
    const latest = gate.schedule(500);
    await expect(stale).resolves.toBeNull();
    await vi.advanceTimersByTimeAsync(500);
    const version = await latest;
    expect(version).not.toBeNull();
    expect(gate.isCurrent(version!)).toBe(true);
    gate.invalidate();
    expect(gate.isCurrent(version!)).toBe(false);
  });

  it("renders stable auto progress and model-list semantics without claiming protocol verification", () => {
    const source = readFileSync(new URL("../src/components/ModelsConfig.tsx", import.meta.url), "utf8");
    expect(source).toContain("需要探测多种模式，请耐心等待");
    expect(source).toContain("进行中 · 正在尝试模型列表 URL/auth 规则");
    expect(source).toContain("模型列表获取成功");
    expect(source).toContain("模型列表获取失败");
    expect(source).toContain("去重模型列表");
    expect(source).toContain("这里只验证模型列表获取；生成协议需选择具体 API 类型后点击测试。");
    expect(source).toContain("isCurrentDraftRequest(requestVersion)");
    expect(source).not.toContain("生成协议已验证");
  });

  it("keeps fetch and test failure paths separate from apply", () => {
    const source = readFileSync(new URL("../src/components/ModelsConfig.tsx", import.meta.url), "utf8");
    const draftHandlers = source.slice(source.indexOf("const runDraft"), source.indexOf("const apply ="));
    expect(draftHandlers).not.toContain("apply(");
  });

  it("uses explicit advanced default selection and omits default changes when unchecked", () => {
    const source = readFileSync(new URL("../src/components/ModelsConfig.tsx", import.meta.url), "utf8");
    expect(source).toContain("advancedSetDefault");
    expect(source).toContain("保存时设为全局默认");
    const request = toRuntimeModelSetupApplyRequest({
      revision: "r1",
      config: { providers: { p: { api: "openai-completions", models: [{ id: "m" }] } } },
      setGlobalDefault: false,
    });
    expect(request.setGlobalDefault).toBe(false);
    expect(request).not.toHaveProperty("defaultModel");
  });

  it("keeps Web endpoints under /api/web/v1 and never uses Native RPC", () => {
    const client = new WebApiClient();
    expect(client.url("/sessions/s1/snapshot")).toBe("/api/web/v1/sessions/s1/snapshot");
    expect(client.url("/models", { cwd: "/tmp/a b" })).toBe("/api/web/v1/models?cwd=%2Ftmp%2Fa+b");
    expect(client.url("/sessions/s1/events")).not.toContain("/v1/ws");
  });

  it("normalizes Runtime session rows and resolves parent path to UI id", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ok: true, data: { sessions: [
      { sessionId: "parent", sessionPath: "/sessions/parent.jsonl", cwd: "/tmp", createdAt: "2026-01-01", modifiedAt: "2026-01-02", messageCount: 2, firstMessage: "one" },
      { sessionId: "child", sessionPath: "/sessions/child.jsonl", parentSessionPath: "/sessions/parent.jsonl", cwd: "/tmp", createdAt: "2026-01-03", modifiedAt: "2026-01-04", messageCount: 1 },
    ] } })));
    const sessions = await new WebApiClient().listSessions();
    expect(sessions[0]).toMatchObject({ id: "parent", path: "/sessions/parent.jsonl", created: "2026-01-01", modified: "2026-01-02" });
    expect(sessions[1]).toMatchObject({ id: "child", parentSessionId: "parent", firstMessage: "新对话" });
  });

  it("forwards the complete create request and normalizes Runtime identity", async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ ok: true, data: {
      sessionId: "s1", sessionPath: "/sessions/s1.jsonl", cwd: "/assistant", createdAt: "2026-01-01", modifiedAt: "2026-01-01",
    } }, 201));
    vi.stubGlobal("fetch", fetchMock);
    const created = await new WebApiClient().createSession({ assistantId: "wuxianpi", cwd: "/assistant", provider: "deepseek", modelId: "chat", thinkingLevel: "high", toolNames: ["read"] });
    expect(created).toMatchObject({ sessionId: "s1", session: { id: "s1", path: "/sessions/s1.jsonl" } });
    expect(JSON.parse(String((fetchMock.mock.calls[0]?.[1] as RequestInit).body))).toMatchObject({ assistantId: "wuxianpi", provider: "deepseek", modelId: "chat", thinkingLevel: "high", toolNames: ["read"] });
  });

  it("maps snapshot entry objects to message entry ids and preserves tree/state", async () => {
    const tree = [{ entry: { type: "message", id: "e1", parentId: null, timestamp: "", message: { role: "user", content: "hello" } }, children: [] }];
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ok: true, data: {
      type: "snapshot", sessionId: "s1", filePath: "/sessions/s1.jsonl",
      state: { thinkingLevel: "high", tools: [{ name: "read" }], activeToolNames: ["read"], slashCommands: { commands: [{ name: "compact" }] }, sessionStats: { totalMessages: 1 } },
      history: [{ role: "user", content: "hello" }],
      entries: [{ type: "model_change", id: "m1" }, { type: "message", id: "e1", message: { role: "user", content: "hello" } }],
      leafId: "e1", tree,
    } })));
    const snapshot = await new WebApiClient().snapshot("s1");
    expect(snapshot.entries).toEqual(["e1"]);
    expect(snapshot.sessionEntries).toHaveLength(2);
    expect(snapshot.tree).toEqual(tree);
    expect(snapshot.state?.activeToolNames).toEqual(["read"]);
  });

  it("normalizes Runtime model arrays for ChatInput and ModelsConfig", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ok: true, data: {
      providers: [{ id: "deepseek", name: "DeepSeek", authenticated: true }],
      models: [{ provider: "deepseek", id: "deepseek-chat", name: "DeepSeek Chat", available: true, reasoning: false }],
      defaultModel: { provider: "deepseek", modelId: "deepseek-chat" },
    } })));
    const models = await new WebApiClient().models();
    expect(models.models).toEqual({ "deepseek:deepseek-chat": "DeepSeek Chat" });
    expect(models.modelList[0]).toMatchObject({ provider: "deepseek", id: "deepseek-chat", name: "DeepSeek Chat" });
    expect(models.defaultModel).toEqual({ provider: "deepseek", modelId: "deepseek-chat" });
  });

  it("uses the Model Setup endpoints and never retains returned API keys", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ok: true, data: {
        revision: "r1",
        presets: [{ id: "deepseek", name: "DeepSeek", providerName: "deepseek", baseUrl: "https://api.deepseek.com/v1", api: "openai-completions", keyRequired: true, defaultModels: ["deepseek-chat"] }],
        config: { providers: { deepseek: { baseUrl: "https://api.deepseek.com/v1", api: "openai-completions", apiKey: "must-not-survive", compat: { custom: true }, models: [{ id: "deepseek-chat", apiKey: "also-secret", contextWindow: 64000 }] } } },
        providers: [{ id: "deepseek", configured: true, credentialSource: "stored", apiKey: "hidden" }],
        models: [{ provider: "deepseek", id: "deepseek-chat", name: "DeepSeek Chat" }],
        defaultModel: { provider: "deepseek", modelId: "deepseek-chat" },
      } }))
      .mockResolvedValueOnce(json({ ok: true, data: { ok: true, models: [{ id: "deepseek-chat" }] } }))
      .mockResolvedValueOnce(json({ ok: true, data: { ok: true, latencyMs: 12 } }))
      .mockResolvedValueOnce(json({ ok: true, data: { revision: "r2", presets: [], config: { providers: {} }, providers: [], models: [], defaultModel: null } }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new WebApiClient();

    const setup = await client.modelSetup();
    expect(setup.presets[0]).toMatchObject({ label: "DeepSeek", providerId: "deepseek", requiresApiKey: true });
    expect(setup.providers[0]).toMatchObject({ id: "deepseek", authenticated: true, authLabel: "stored" });
    expect(JSON.stringify(setup)).not.toContain("must-not-survive");
    expect(JSON.stringify(setup)).not.toContain("also-secret");
    expect(JSON.stringify(setup)).not.toContain("hidden");
    expect(setup.config.providers.deepseek.compat).toEqual({ custom: true });
    expect(setup.config.providers.deepseek.models?.[0].contextWindow).toBe(64000);

    await client.fetchModelDraft({ providerId: "deepseek", apiKey: "draft-secret" });
    await client.testModelDraft({ providerId: "deepseek", modelId: "deepseek-chat", apiKey: "draft-secret", headers: { "X-Test": "yes" } });
    await client.applyModelSetup({ revision: "r1", config: { providers: { deepseek: { baseUrl: "https://api.deepseek.com/v1", models: [{ id: "deepseek-chat" }] } } }, credentials: { deepseek: { action: "set", apiKey: "draft-secret" } } });
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/api/web/v1/models/setup",
      "/api/web/v1/models/fetch",
      "/api/web/v1/models/test",
      "/api/web/v1/models/apply",
    ]);
    expect(JSON.parse(String((fetchMock.mock.calls[2]?.[1] as RequestInit).body))).toMatchObject({
      providerId: "deepseek",
      modelId: "deepseek-chat",
      provider: { apiKey: "draft-secret", headers: { "X-Test": "yes" } },
    });
    expect(JSON.parse(String((fetchMock.mock.calls[3]?.[1] as RequestInit).body))).toMatchObject({
      revision: "r1",
      changes: [{ providerId: "deepseek", action: "upsert", credential: { action: "set", apiKey: "draft-secret" } }],
    });
  });

  it("surfaces revision conflicts from apply", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ error: { message: "Model configuration changed" } }, 409)));
    await expect(new WebApiClient().applyModelSetup({ revision: "stale", config: { providers: {} } }))
      .rejects.toMatchObject({ status: 409, message: "Model configuration changed" });
  });

  it("uses Runtime model login, logout, default and test endpoints", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ok: true, data: { provider: "deepseek", authenticated: true } }))
      .mockResolvedValueOnce(json({ ok: true, data: { provider: "deepseek", authenticated: false } }))
      .mockResolvedValueOnce(json({ ok: true, data: { provider: "deepseek", modelId: "deepseek-chat" } }))
      .mockResolvedValueOnce(json({ ok: true, data: { ok: true, latencyMs: 120, text: "OK" } }));
    vi.stubGlobal("fetch", fetchMock);
    const client = new WebApiClient();
    await client.loginModel("deepseek", "secret-key");
    await client.logoutModel("deepseek");
    await client.setDefaultModel("deepseek", "deepseek-chat");
    await client.testModel("deepseek", "deepseek-chat");
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/api/web/v1/models/login", "/api/web/v1/models/logout", "/api/web/v1/models/default", "/api/web/v1/models/test",
    ]);
    expect(JSON.parse(String((fetchMock.mock.calls[0]?.[1] as RequestInit).body))).toEqual({ provider: "deepseek", apiKey: "secret-key" });
  });

  it("uses real tools, commands, stats, tree, navigation and name endpoints", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ok: true, data: { tools: [{ name: "read", description: "Read" }], activeToolNames: ["read"] } }))
      .mockResolvedValueOnce(json({ ok: true, data: { commands: [{ name: "compact" }] } }))
      .mockResolvedValueOnce(json({ ok: true, data: { totalMessages: 2 } }))
      .mockResolvedValueOnce(json({ ok: true, data: { tree: [], leafId: "e1" } }))
      .mockResolvedValueOnce(json({ ok: true, data: { cancelled: false } }))
      .mockResolvedValueOnce(json({ ok: true, data: {} }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await sendAgentCommand("s1", { type: "get_tools" })).toEqual([{ name: "read", description: "Read", active: true }]);
    expect(await sendAgentCommand("s1", { type: "get_commands" })).toEqual({ commands: [{ name: "compact" }] });
    expect(await sendAgentCommand("s1", { type: "get_session_stats" })).toEqual({ totalMessages: 2 });
    expect(await new WebApiClient().tree("s1")).toEqual({ tree: [], leafId: "e1" });
    await sendAgentCommand("s1", { type: "navigate_tree", targetId: "e1" });
    await sendAgentCommand("s1", { type: "set_session_name", name: "Renamed" });
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/api/web/v1/sessions/s1/tools", "/api/web/v1/sessions/s1/commands", "/api/web/v1/sessions/s1/stats",
      "/api/web/v1/sessions/s1/tree", "/api/web/v1/sessions/s1/navigate", "/api/web/v1/sessions/s1",
    ]);
  });

  it("normalizes fork id and sends extension UI requestId", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ok: true, data: { cancelled: false, sessionId: "forked" } }, 201))
      .mockResolvedValueOnce(json({ ok: true, data: {} }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await sendAgentCommand("s1", { type: "fork", entryId: "e1" })).toMatchObject({ sessionId: "forked", newSessionId: "forked" });
    await sendAgentCommand("s1", { type: "extension_ui_response", id: "request-1", value: "yes" });
    expect(JSON.parse(String((fetchMock.mock.calls[1]?.[1] as RequestInit).body))).toEqual({ requestId: "request-1", value: "yes" });
  });

  it("normalizes capability and package-extension fixtures", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ok: true, data: { catalog: { generatedAt: "now", capabilities: [], diagnostics: [] }, config: { schemaVersion: 1, defaults: {}, mcpServers: [], ttsProfiles: [], permissions: [] } } }))
      .mockResolvedValueOnce(json({ ok: true, data: { extensions: [{ id: "pi-mcp-adapter", name: "pi-mcp-adapter", kind: "pi", enabled: true }] } }));
    vi.stubGlobal("fetch", fetchMock);
    expect((await getCapabilityCatalog()).generatedAt).toBe("now");
    const extensions = await listWebExtensions();
    expect(extensions[0]?.manifest).toMatchObject({ id: "pi-mcp-adapter", name: "pi-mcp-adapter", apiVersion: "1" });
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["/api/web/v1/capabilities", "/api/web/v1/extensions"]);
  });

  it("uses the Runtime nonce and bridge endpoints required by ExtensionHost", async () => {
    const bridgeResponse = { type: "wuxianpi_bridge_response", requestId: "r1", extensionId: "calendar", nonce: "n1", ok: true, result: {} } as const;
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ok: true, data: { extensionId: "calendar", assistantId: "wuxianpi", nonce: "n1" } }))
      .mockResolvedValueOnce(json({ ok: true, data: bridgeResponse }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await issueExtensionNonce("calendar", "wuxianpi")).toBe("n1");
    expect(await bridgeExtension("calendar", { type: "wuxianpi_bridge_request", requestId: "r1", extensionId: "calendar", nonce: "n1", method: "assistant.get" })).toEqual(bridgeResponse);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(["/api/web/v1/extensions/nonce", "/api/web/v1/extensions/bridge"]);
  });

  it("lists, searches and installs Pi packages through the Skills API", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ok: true, data: { skills: [{ name: "existing" }] } }))
      .mockResolvedValueOnce(json({ ok: true, data: { packages: [{ name: "pi-mcp-adapter", version: "1.0.0" }] } }))
      .mockResolvedValueOnce(json({ ok: true, data: { source: "pi-mcp-adapter", installedPath: "/agent/packages/pi-mcp-adapter" } }, 201));
    vi.stubGlobal("fetch", fetchMock);
    const client = new WebApiClient();
    expect(await client.skills("/assistant")).toMatchObject({ skills: [{ name: "existing" }] });
    expect(await client.searchPackages("mcp")).toMatchObject({ packages: [{ name: "pi-mcp-adapter" }] });
    await client.installPackage("pi-mcp-adapter", { cwd: "/assistant", local: true });
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      "/api/web/v1/skills?cwd=%2Fassistant", "/api/web/v1/skills/search?q=mcp", "/api/web/v1/skills/install",
    ]);
    expect(JSON.parse(String((fetchMock.mock.calls[2]?.[1] as RequestInit).body))).toEqual({ source: "pi-mcp-adapter", cwd: "/assistant", local: true });
  });

  it("persists MCP CRUD edits only through capabilities/config", async () => {
    const config = { schemaVersion: 1 as const, defaults: {}, mcpServers: [{ id: "docs", name: "Docs", transport: "stdio" as const, command: "npx", args: ["server"], enabled: true }], ttsProfiles: [], permissions: [] };
    const fetchMock = vi.fn().mockResolvedValue(json({ ok: true, data: config }));
    vi.stubGlobal("fetch", fetchMock);
    await updateGlobalConfig(config);
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/web/v1/capabilities/config");
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit;
    expect(request.method).toBe("PATCH");
    expect(JSON.parse(String(request.body))).toMatchObject({ mcpServers: [{ id: "docs", command: "npx" }] });
  });

  it("surfaces unavailable endpoints instead of silently succeeding", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ok: false, error: { code: "not_found", message: "missing endpoint" } }, 404)));
    await expect(new WebApiClient().commands("s1")).rejects.toMatchObject({ message: "missing endpoint", status: 404 });
  });
});
