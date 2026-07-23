import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import test from "node:test";
import { ModelRuntime, SettingsManager } from "@earendil-works/pi-coding-agent";
import { ModelSetupService } from "../dist/model-setup-service.js";
import { RequestError } from "../dist/protocol.js";
import { SessionRegistry } from "../dist/session-registry.js";

function customProvider(models, extra = {}) {
  return {
    name: "Test Provider",
    baseUrl: "https://example.invalid/v1",
    api: "openai-completions",
    models: models.map((id) => ({ id, name: id })),
    ...extra,
  };
}

async function runtimeFixture(config = { providers: {} }) {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-model-setup-"));
  const agentDir = join(root, "agent");
  await mkdir(agentDir, { recursive: true });
  await writeFile(join(agentDir, "models.json"), `${JSON.stringify(config, null, 2)}\n`);
  const runtime = await ModelRuntime.create({
    authPath: join(agentDir, "auth.json"),
    modelsPath: join(agentDir, "models.json"),
    modelsStorePath: join(agentDir, "models-store.json"),
    allowModelNetwork: false,
  });
  const settings = SettingsManager.create(root, agentDir);
  let reloads = 0;
  const service = new ModelSetupService({
    agentDir,
    modelRuntime: async () => runtime,
    settingsManager: settings,
    reload: async () => { reloads++; await runtime.reloadConfig(); await settings.reload(); },
  });
  return {
    root, agentDir, runtime, settings, service,
    reloads: () => reloads,
    cleanup: () => rm(root, { recursive: true, force: true }),
  };
}

test("setup redacts models.json API keys while reporting authentication state", async () => {
  const fixture = await runtimeFixture({
    providers: { private: customProvider(["model-a"], {
      apiKey: "setup-secret-value",
      headers: { Authorization: "Bearer setup-secret-value", "X-Test": "visible" },
    }) },
  });
  try {
    const setup = await fixture.service.setup();
    assert.equal(JSON.stringify(setup).includes("setup-secret-value"), false);
    assert.equal("apiKey" in setup.config.providers.private, false);
    assert.equal("Authorization" in setup.config.providers.private.headers, false);
    assert.equal(setup.config.providers.private.headers["X-Test"], "visible");
    assert.equal(setup.presets.every((preset) => preset.baseUrl !== undefined && preset.apiType === preset.api), true);
    assert.equal(setup.providers.find((provider) => provider.id === "private")?.authenticated, true);
    assert.match(setup.revision, /^[a-f0-9]{64}$/);
  } finally {
    await fixture.cleanup();
  }
});

test("draft tests use an isolated temporary runtime and never write authoritative files", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-model-draft-"));
  const agentDir = join(root, "agent");
  await mkdir(agentDir, { recursive: true });
  const modelsPath = join(agentDir, "models.json");
  const authPath = join(agentDir, "auth.json");
  const settingsPath = join(agentDir, "settings.json");
  await writeFile(modelsPath, "models-before");
  await writeFile(authPath, "auth-before");
  await writeFile(settingsPath, "settings-before");
  const model = {
    provider: "draft", id: "draft-model", name: "Draft", api: "openai-responses",
    baseUrl: "https://example.invalid/v1", reasoning: false, input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096,
  };
  const temporaryRuntime = {
    getError: () => undefined,
    getModel: (provider, modelId) => provider === model.provider && modelId === model.id ? model : undefined,
    login: async () => {},
    completeSimple: async (_model, _context, options) => {
      options.onResponse?.({ status: 200 });
      return { stopReason: "stop", content: [{ type: "text", text: "OK" }] };
    },
  };
  const service = new ModelSetupService({
    agentDir,
    modelRuntime: async () => { throw new Error("shared runtime must not be used for a draft test"); },
    settingsManager: {},
    reload: async () => {},
    createModelRuntime: async (options) => {
      const draftConfig = JSON.parse(await readFile(options.modelsPath, "utf8"));
      assert.equal(draftConfig.providers.draft.api, "openai-responses");
      assert.equal(draftConfig.providers.draft.models[0].api, "openai-responses");
      return temporaryRuntime;
    },
  });
  try {
    const result = await service.testModel({
      providerName: "draft",
      apiType: "gpt",
      provider: { baseUrl: model.baseUrl, api: "openai-completions", apiKey: "draft-secret" },
      model: { id: model.id },
    });
    assert.equal(result.ok, true);
    assert.equal(result.text, "OK");
    assert.equal(result.resolvedApi, "openai-responses");
    assert.equal(await readFile(modelsPath, "utf8"), "models-before");
    assert.equal(await readFile(authPath, "utf8"), "auth-before");
    assert.equal(await readFile(settingsPath, "utf8"), "settings-before");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("apply atomically stores config, credentials, default model, and reloads the shared runtime", async () => {
  const fixture = await runtimeFixture();
  try {
    const before = await fixture.service.setup();
    const applied = await fixture.service.apply({
      revision: before.revision,
      changes: [{
        providerId: "custom",
        action: "upsert",
        provider: customProvider(["model-a"], {
          apiKey: "stored-secret",
          headers: { Authorization: "Bearer transport-header", "X-Custom": "value" },
        }),
      }],
      setGlobalDefault: true,
      defaultModel: { provider: "custom", modelId: "model-a" },
    });
    const modelsFile = await readFile(join(fixture.agentDir, "models.json"), "utf8");
    const authFile = await readFile(join(fixture.agentDir, "auth.json"), "utf8");
    assert.equal(modelsFile.includes("stored-secret"), false);
    assert.equal(modelsFile.includes("transport-header"), true);
    assert.equal(authFile.includes("stored-secret"), true);
    assert.ok(fixture.runtime.getModel("custom", "model-a"));
    assert.deepEqual(applied.defaultModel, { provider: "custom", modelId: "model-a" });
    assert.equal(applied.providers.find((provider) => provider.id === "custom")?.authenticated, true);
    assert.equal(JSON.stringify(applied).includes("transport-header"), false);
    assert.ok(fixture.reloads() >= 1);
  } finally {
    await fixture.cleanup();
  }
});

test("failed apply restores models, credentials, settings, and runtime state", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider(["model-a"]) } });
  try {
    const beforeSetup = await fixture.service.setup();
    const beforeModels = await readFile(join(fixture.agentDir, "models.json"), "utf8");
    await assert.rejects(() => fixture.service.apply({
      revision: beforeSetup.revision,
      changes: [{
        providerId: "custom",
        action: "upsert",
        provider: customProvider(["model-b"]),
        credential: { action: "set" },
      }],
    }), (error) => error.code === "invalid_payload");
    assert.equal(await readFile(join(fixture.agentDir, "models.json"), "utf8"), beforeModels);
    assert.ok(fixture.runtime.getModel("custom", "model-a"));
    assert.equal(fixture.runtime.getModel("custom", "model-b"), undefined);
  } finally {
    await fixture.cleanup();
  }
});

test("apply rejects stale revisions before writing", async () => {
  const fixture = await runtimeFixture();
  try {
    const setup = await fixture.service.setup();
    await writeFile(join(fixture.agentDir, "settings.json"), JSON.stringify({ quietStartup: true }));
    const modelsBefore = await readFile(join(fixture.agentDir, "models.json"), "utf8");
    await assert.rejects(() => fixture.service.apply({
      revision: setup.revision,
      changes: [{ providerId: "custom", provider: customProvider(["model-a"]) }],
    }), (error) => error.code === "model_revision_conflict");
    assert.equal(await readFile(join(fixture.agentDir, "models.json"), "utf8"), modelsBefore);
  } finally {
    await fixture.cleanup();
  }
});

test("session model selection isolates only the model default and preserves project settings", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider(["model-a", "model-b"]) } });
  const projectConfigDir = join(fixture.root, ".pi");
  const projectExtension = join(fixture.root, "project-extension.ts");
  await mkdir(projectConfigDir, { recursive: true });
  await writeFile(projectExtension, "export default function () {}\n");
  await writeFile(join(projectConfigDir, "settings.json"), JSON.stringify({
    shellPath: "/bin/project-shell",
    extensions: [projectExtension],
  }));
  const registry = new SessionRegistry(undefined, {
    agentDir: fixture.agentDir,
    idleTimeoutMs: 0,
    modelRuntime: fixture.runtime,
    settingsManager: fixture.settings,
  });
  try {
    await fixture.runtime.login("custom", "api_key", { prompt: async () => "session-secret", notify: () => {} });
    fixture.settings.setDefaultModelAndProvider("custom", "model-a");
    await fixture.settings.flush();
    const identity = await registry.create(fixture.root);
    const slot = await registry.getOrOpen(identity.sessionId);
    assert.equal(slot.runtime.services.settingsManager.getDefaultModel(), "model-a");
    assert.deepEqual(slot.runtime.services.settingsManager.getProjectSettings(), {
      shellPath: "/bin/project-shell",
      extensions: [projectExtension],
    });
    await registry.setDefaultModel("custom", "model-b", identity.sessionId, false);
    assert.equal(fixture.settings.getDefaultModel(), "model-a");
    assert.equal(slot.runtime.services.settingsManager.getDefaultModel(), "model-b");
    assert.deepEqual(slot.runtime.services.settingsManager.getProjectSettings().extensions, [projectExtension]);
    await slot.runtime.services.settingsManager.reload();
    assert.equal(slot.runtime.services.settingsManager.getDefaultModel(), "model-b");
    assert.deepEqual(slot.runtime.services.settingsManager.getProjectSettings().extensions, [projectExtension]);
    slot.runtime.services.settingsManager.setSteeringMode("all");
    await slot.runtime.services.settingsManager.flush();
    assert.equal(slot.runtime.services.settingsManager.drainErrors().length, 0);
    const persistedSettings = JSON.parse(await readFile(join(fixture.agentDir, "settings.json"), "utf8"));
    assert.equal(persistedSettings.defaultModel, "model-a");
    assert.equal(persistedSettings.steeringMode, "all");
    await registry.setDefaultModel("custom", "model-b", identity.sessionId);
    assert.equal(fixture.settings.getDefaultModel(), "model-a");
    assert.equal(slot.runtime.services.settingsManager.getDefaultModel(), "model-b");
    assert.equal(slot.runtime.session.sessionManager.getEntries().some((entry) => entry.type === "model_change"), true);
    await registry.setDefaultModel("custom", "model-b", identity.sessionId, true);
    assert.equal(fixture.settings.getDefaultModel(), "model-b");
  } finally {
    await registry.dispose().catch(() => undefined);
    await fixture.cleanup();
  }
});

test("models.json accepts SDK-compatible comments and trailing commas", async () => {
  const fixture = await runtimeFixture();
  try {
    await writeFile(join(fixture.agentDir, "models.json"), `{
      // Pi accepts JSON with comments.
      "providers": {
        "custom": ${JSON.stringify(customProvider(["model-a"]))},
      },
    }\n`);
    await fixture.runtime.reloadConfig();
    const setup = await fixture.service.setup();
    assert.equal(setup.config.providers.custom.models[0].id, "model-a");
  } finally {
    await fixture.cleanup();
  }
});

test("failed apply restores the live AuthStorage cache as well as auth.json", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider(["model-a"]) } });
  try {
    await fixture.runtime.login("custom", "api_key", { prompt: async () => "original-key", notify: () => {} });
    const setup = await fixture.service.setup();
    await assert.rejects(() => fixture.service.apply({
      revision: setup.revision,
      changes: [{
        providerId: "custom",
        action: "upsert",
        provider: customProvider(["model-a"]),
        credential: { action: "set", apiKey: "attempted-key" },
      }],
      setGlobalDefault: true,
      defaultModel: { provider: "custom", modelId: "missing-model" },
    }), (error) => error.code === "model_not_found");
    const auth = await fixture.runtime.getAuth(fixture.runtime.getModel("custom", "model-a"));
    assert.equal(auth.auth.apiKey, "original-key");
    const authFile = await readFile(join(fixture.agentDir, "auth.json"), "utf8");
    assert.equal(authFile.includes("original-key"), true);
    assert.equal(authFile.includes("attempted-key"), false);
  } finally {
    await fixture.cleanup();
  }
});

test("settings flush errors fail apply and roll back persisted and live defaults", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider(["model-a", "model-b"]) } });
  try {
    fixture.settings.setDefaultModelAndProvider("custom", "model-a");
    await fixture.settings.flush();
    assert.equal(fixture.settings.drainErrors().length, 0);
    const before = await fixture.service.setup();
    const settingsPath = join(fixture.agentDir, "settings.json");
    const settingsFile = await readFile(settingsPath, "utf8");
    const realFlush = fixture.settings.flush.bind(fixture.settings);
    const realDrain = fixture.settings.drainErrors.bind(fixture.settings);
    let injectFailure = false;
    fixture.settings.flush = async () => { await realFlush(); injectFailure = true; };
    fixture.settings.drainErrors = () => {
      if (injectFailure) {
        injectFailure = false;
        return [{ scope: "global", error: new Error("simulated settings failure") }];
      }
      return realDrain();
    };

    await assert.rejects(() => fixture.service.apply({
      revision: before.revision,
      config: before.config,
      setGlobalDefault: true,
      defaultModel: { provider: "custom", modelId: "model-b" },
    }), (error) => error.code === "settings_persist_failed");
    assert.equal(await readFile(settingsPath, "utf8"), settingsFile);
    assert.equal(fixture.settings.getDefaultModel(), "model-a");
  } finally {
    await fixture.cleanup();
  }
});

test("rollback does not overwrite a newer external models.json write", async () => {
  const fixture = await runtimeFixture();
  const modelsPath = join(fixture.agentDir, "models.json");
  const external = { providers: { external: customProvider(["external-model"]) } };
  const service = new ModelSetupService({
    agentDir: fixture.agentDir,
    modelRuntime: async () => fixture.runtime,
    settingsManager: fixture.settings,
    reload: async () => {
      await writeFile(modelsPath, `${JSON.stringify(external, null, 2)}\n`);
      throw new Error("reload failed after external write");
    },
  });
  try {
    const before = await service.setup();
    await assert.rejects(() => service.apply({
      revision: before.revision,
      changes: [{ providerId: "custom", action: "upsert", provider: customProvider(["model-a"]) }],
    }), (error) => error.code === "model_concurrent_write");
    assert.deepEqual(JSON.parse(await readFile(modelsPath, "utf8")), external);
  } finally {
    await fixture.cleanup();
  }
});

test("cross-service concurrent applies serialize and reject the stale revision", async () => {
  const fixture = await runtimeFixture();
  const second = new ModelSetupService({
    agentDir: fixture.agentDir,
    modelRuntime: async () => fixture.runtime,
    settingsManager: fixture.settings,
    reload: async () => { await fixture.runtime.reloadConfig(); await fixture.settings.reload(); },
  });
  try {
    const before = await fixture.service.setup();
    const results = await Promise.allSettled([
      fixture.service.apply({
        revision: before.revision,
        changes: [{ providerId: "first", action: "upsert", provider: customProvider(["model-a"]) }],
      }),
      second.apply({
        revision: before.revision,
        changes: [{ providerId: "second", action: "upsert", provider: customProvider(["model-b"]) }],
      }),
    ]);
    assert.equal(results.filter((result) => result.status === "fulfilled").length, 1);
    const rejected = results.find((result) => result.status === "rejected");
    assert.equal(rejected.reason.code, "model_revision_conflict");
  } finally {
    await fixture.cleanup();
  }
});

test("lock compromise is detected inside the transaction and rolls back committed files", async () => {
  const fixture = await runtimeFixture();
  const before = await fixture.service.setup();
  const modelsPath = join(fixture.agentDir, "models.json");
  const originalModels = await readFile(modelsPath, "utf8");
  let compromise = () => {};
  let reloadCount = 0;
  const service = new ModelSetupService({
    agentDir: fixture.agentDir,
    modelRuntime: async () => fixture.runtime,
    settingsManager: fixture.settings,
    acquireLock: async (_path, options) => {
      compromise = options.onCompromised;
      return async () => {};
    },
    reload: async () => {
      reloadCount++;
      await fixture.runtime.reloadConfig();
      await fixture.settings.reload();
      if (reloadCount === 1) compromise(new Error("simulated compromised lock"));
    },
  });
  try {
    await assert.rejects(() => service.apply({
      revision: before.revision,
      changes: [{ providerId: "custom", action: "upsert", provider: customProvider(["model-a"]) }],
    }), (error) => error.code === "model_lock_compromised");
    assert.equal(await readFile(modelsPath, "utf8"), originalModels);
    assert.equal(fixture.runtime.getModel("custom", "model-a"), undefined);
  } finally {
    await fixture.cleanup();
  }
});

test("lock release failure is reported and rolls back the transaction", async () => {
  const fixture = await runtimeFixture();
  const before = await fixture.service.setup();
  const modelsPath = join(fixture.agentDir, "models.json");
  const originalModels = await readFile(modelsPath, "utf8");
  const service = new ModelSetupService({
    agentDir: fixture.agentDir,
    modelRuntime: async () => fixture.runtime,
    settingsManager: fixture.settings,
    acquireLock: async () => async () => { throw new Error("simulated release failure"); },
    reload: async () => { await fixture.runtime.reloadConfig(); await fixture.settings.reload(); },
  });
  try {
    await assert.rejects(() => service.apply({
      revision: before.revision,
      changes: [{ providerId: "custom", action: "upsert", provider: customProvider(["model-a"]) }],
    }), (error) => error.code === "model_lock_release_failed");
    assert.equal(await readFile(modelsPath, "utf8"), originalModels);
    assert.equal(fixture.runtime.getModel("custom", "model-a"), undefined);
  } finally {
    await fixture.cleanup();
  }
});

test("setup waits for apply and never exposes an intermediate auth/config snapshot", async () => {
  const fixture = await runtimeFixture();
  const before = await fixture.service.setup();
  let markReloadStarted;
  let resumeReload;
  const reloadStarted = new Promise((resolve) => { markReloadStarted = resolve; });
  const resume = new Promise((resolve) => { resumeReload = resolve; });
  const writer = new ModelSetupService({
    agentDir: fixture.agentDir,
    modelRuntime: async () => fixture.runtime,
    settingsManager: fixture.settings,
    reload: async () => {
      await fixture.runtime.reloadConfig();
      markReloadStarted();
      await resume;
      await fixture.settings.reload();
    },
  });
  try {
    const applying = writer.apply({
      revision: before.revision,
      changes: [{
        providerId: "custom",
        action: "upsert",
        provider: customProvider(["model-a"]),
        credential: { action: "set", apiKey: "consistent-secret" },
      }],
    });
    await reloadStarted;
    let setupSettled = false;
    const reading = fixture.service.setup().then((value) => { setupSettled = true; return value; });
    await delay(75);
    assert.equal(setupSettled, false);
    resumeReload();
    const [applied, setup] = await Promise.all([applying, reading]);
    assert.equal(applied.providers.find((provider) => provider.id === "custom")?.authenticated, true);
    assert.equal(setup.config.providers.custom.models[0].id, "model-a");
    assert.equal(setup.providers.find((provider) => provider.id === "custom")?.authenticated, true);
    assert.equal(setup.revision, applied.revision);
  } finally {
    resumeReload?.();
    await fixture.cleanup();
  }
});

test("fetch uses stored provider auth even when the provider has no configured models", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider([]) } });
  await fixture.runtime.login("custom", "api_key", { prompt: async () => "stored-zero-model-key", notify: () => {} });
  let discoveryInput;
  const service = new ModelSetupService({
    agentDir: fixture.agentDir,
    modelRuntime: async () => fixture.runtime,
    settingsManager: fixture.settings,
    reload: async () => {},
    discover: async (input) => {
      discoveryInput = input;
      return { ok: true, models: [{ id: "found-model" }], candidates: [], modeResults: [] };
    },
  });
  try {
    const result = await service.fetchModels({
      providerId: "custom",
      baseUrl: "https://example.invalid/v1",
      apiType: "openai",
    });
    assert.equal(result.models[0].id, "found-model");
    assert.equal(discoveryInput.apiKey, "stored-zero-model-key");
  } finally {
    await fixture.cleanup();
  }
});

test("draft test uses stored provider auth with zero models and returns resolvedApi", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider([]) } });
  await fixture.runtime.login("custom", "api_key", { prompt: async () => "stored-draft-key", notify: () => {} });
  let loginKey;
  const service = new ModelSetupService({
    agentDir: fixture.agentDir,
    modelRuntime: async () => fixture.runtime,
    settingsManager: fixture.settings,
    reload: async () => {},
    createModelRuntime: async (options) => {
      const config = JSON.parse(await readFile(options.modelsPath, "utf8"));
      const model = {
        provider: "custom", id: "draft-model", name: "Draft", api: config.providers.custom.api,
        baseUrl: config.providers.custom.baseUrl, reasoning: false, input: ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096,
      };
      return {
        getError: () => undefined,
        getModel: () => model,
        login: async (_provider, _type, interaction) => { loginKey = await interaction.prompt(); },
        completeSimple: async () => ({ stopReason: "stop", content: [{ type: "text", text: "OK" }] }),
      };
    },
  });
  try {
    const result = await service.testModel({
      providerId: "custom",
      provider: { baseUrl: "https://example.invalid/v1" },
      model: { id: "draft-model" },
      apiType: "openai",
    });
    assert.equal(loginKey, "stored-draft-key");
    assert.equal(result.resolvedApi, "openai-completions");
  } finally {
    await fixture.cleanup();
  }
});

test("auto draft test reports the concrete successful API and redacts failed attempt details", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-model-auto-test-"));
  const agentDir = join(root, "agent");
  await mkdir(agentDir, { recursive: true });
  const secret = "draft-auto-secret";
  const service = new ModelSetupService({
    agentDir,
    modelRuntime: async () => { throw new Error("shared runtime must not be used with an explicit draft key"); },
    settingsManager: {},
    reload: async () => {},
    createModelRuntime: async (options) => {
      const config = JSON.parse(await readFile(options.modelsPath, "utf8"));
      const api = config.providers.custom.api;
      const model = {
        provider: "custom", id: "draft-model", name: "Draft", api,
        baseUrl: config.providers.custom.baseUrl, reasoning: false, input: ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096,
      };
      return {
        getError: () => undefined,
        getModel: () => model,
        login: async () => {},
        completeSimple: async () => {
          if (api === "anthropic-messages") {
            throw new RequestError("upstream_failed", `upstream rejected ${secret}`, { nested: { token: secret } });
          }
          return { stopReason: "stop", content: [{ type: "text", text: "OK" }] };
        },
      };
    },
  });
  try {
    const result = await service.testModel({
      providerId: "custom",
      provider: { baseUrl: "https://example.invalid/v1", apiKey: secret },
      model: { id: "draft-model" },
      apiType: "auto",
    });
    assert.equal(result.resolvedApi, "openai-responses");
    assert.deepEqual(result.modeResults.map((item) => [item.api, item.ok]), [
      ["anthropic-messages", false],
      ["openai-responses", true],
    ]);
    assert.equal(JSON.stringify(result).includes(secret), false);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("explicit draft test redacts the draft API key from message and nested details", async () => {
  const root = await mkdtemp(join(tmpdir(), "wuxianpi-model-redaction-test-"));
  const agentDir = join(root, "agent");
  await mkdir(agentDir, { recursive: true });
  const secret = "draft-error-secret";
  const service = new ModelSetupService({
    agentDir,
    modelRuntime: async () => { throw new Error("shared runtime must not be used with an explicit draft key"); },
    settingsManager: {},
    reload: async () => {},
    createModelRuntime: async () => ({
      getError: () => undefined,
      getModel: () => ({
        provider: "custom", id: "draft-model", name: "Draft", api: "openai-completions",
        baseUrl: "https://example.invalid/v1", reasoning: false, input: ["text"],
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096,
      }),
      login: async () => {},
      completeSimple: async () => {
        throw new RequestError("upstream_failed", `failed with ${secret}`, { nested: [secret, { secret }] });
      },
    }),
  });
  try {
    await assert.rejects(() => service.testModel({
      providerId: "custom",
      provider: { baseUrl: "https://example.invalid/v1", apiKey: secret },
      model: { id: "draft-model" },
      apiType: "openai",
    }), (error) => error.code === "upstream_failed" && !JSON.stringify({ message: error.message, details: error.details }).includes(secret));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("active sessions refresh their model object after model configuration reload", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider(["model-a"], { baseUrl: "https://old.invalid/v1" }) } });
  const registry = new SessionRegistry(undefined, {
    agentDir: fixture.agentDir,
    idleTimeoutMs: 0,
    modelRuntime: fixture.runtime,
    settingsManager: fixture.settings,
  });
  try {
    await fixture.runtime.login("custom", "api_key", { prompt: async () => "session-refresh-key", notify: () => {} });
    fixture.settings.setDefaultModelAndProvider("custom", "model-a");
    await fixture.settings.flush();
    const identity = await registry.create(fixture.root);
    const slot = await registry.getOrOpen(identity.sessionId);
    const previous = slot.runtime.session.model;
    await writeFile(join(fixture.agentDir, "models.json"), `${JSON.stringify({
      providers: { custom: customProvider(["model-a"], { baseUrl: "https://new.invalid/v1" }) },
    }, null, 2)}\n`);
    await registry.reloadModelConfiguration();
    assert.notEqual(slot.runtime.session.model, previous);
    assert.equal(slot.runtime.session.model.baseUrl, "https://new.invalid/v1");
    assert.equal((await registry.state(identity.sessionId)).modelStatus.state, "ready");
  } finally {
    await registry.dispose().catch(() => undefined);
    await fixture.cleanup();
  }
});

test("removed active session model becomes invalid and cannot use the stale endpoint", async () => {
  const fixture = await runtimeFixture({ providers: { custom: customProvider(["model-a", "model-b"]) } });
  const registry = new SessionRegistry(undefined, {
    agentDir: fixture.agentDir,
    idleTimeoutMs: 0,
    modelRuntime: fixture.runtime,
    settingsManager: fixture.settings,
  });
  try {
    await fixture.runtime.login("custom", "api_key", { prompt: async () => "session-invalid-key", notify: () => {} });
    fixture.settings.setDefaultModelAndProvider("custom", "model-a");
    await fixture.settings.flush();
    const identity = await registry.create(fixture.root);
    await writeFile(join(fixture.agentDir, "models.json"), `${JSON.stringify({
      providers: { custom: customProvider(["model-b"]) },
    }, null, 2)}\n`);
    await registry.reloadModelConfiguration();
    const state = await registry.state(identity.sessionId);
    assert.equal(state.modelStatus.state, "invalid");
    assert.equal(state.modelStatus.code, "session_model_removed");
    const slot = await registry.getOrOpen(identity.sessionId);
    await assert.rejects(() => registry.prompt(identity.sessionId, { message: "must not run" }),
      (error) => error.code === "session_model_invalid");
    await assert.rejects(() => registry.compact(identity.sessionId),
      (error) => error.code === "session_model_invalid");
    await assert.rejects(() => registry.steer(identity.sessionId, "must not steer"),
      (error) => error.code === "session_model_invalid");
    await assert.rejects(() => registry.followUp(identity.sessionId, "must not follow up"),
      (error) => error.code === "session_model_invalid");
    slot.modelStatus = { state: "pending", provider: "custom", modelId: "model-a" };
    await assert.rejects(() => registry.steer(identity.sessionId, "must wait for refresh"),
      (error) => error.code === "session_model_refresh_pending");
    await assert.rejects(() => registry.followUp(identity.sessionId, "must wait for refresh"),
      (error) => error.code === "session_model_refresh_pending");
    await registry.setModel(identity.sessionId, "custom", "model-b");
    assert.equal((await registry.state(identity.sessionId)).modelStatus.state, "ready");
  } finally {
    await registry.dispose().catch(() => undefined);
    await fixture.cleanup();
  }
});
