import { afterEach, describe, expect, it, vi } from "vitest";
import { sendAgentCommand } from "@/lib/agent-client";
import { WebApiClient } from "@/lib/web-api-client";
import { bridgeExtension, getCapabilityCatalog, issueExtensionNonce, listWebExtensions, updateGlobalConfig } from "@/components/wuxianpi/api";

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { "content-type": "application/json" },
});

afterEach(() => vi.unstubAllGlobals());

describe("WebApiClient Runtime contract", () => {
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
