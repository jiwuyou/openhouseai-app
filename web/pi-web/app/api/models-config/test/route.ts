import { NextResponse } from "next/server";
import { mkdtempSync, rmSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { completeSimple, type AssistantMessage } from "@earendil-works/pi-ai/compat";
import { AuthStorage, ModelRegistry } from "@earendil-works/pi-coding-agent";
import { providerAllowsMissingApiKey } from "@/lib/model-provider-presets";

export const dynamic = "force-dynamic";

const TEST_TIMEOUT_MS = 20_000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function redactProviderSecrets(message: string, provider?: Record<string, unknown>): string {
  if (!provider) return message;
  const secrets: string[] = [];
  if (typeof provider.apiKey === "string") secrets.push(provider.apiKey);
  if (isRecord(provider.headers)) {
    for (const value of Object.values(provider.headers)) {
      if (typeof value === "string") secrets.push(value);
    }
  }

  return secrets
    .filter((secret) => secret.length >= 8 && !secret.startsWith("$") && !secret.startsWith("!"))
    .reduce((output, secret) => output.split(secret).join("[api-key]"), message);
}

function friendlyFailure(error: string, status?: number): { message: string; hint: string } {
  const lower = error.toLowerCase();
  if (status === 401 || status === 403 || /unauthorized|forbidden|invalid api key|invalid x-api-key|authentication/.test(lower)) {
    return {
      message: "API Key 无效或没有访问权限",
      hint: "重新复制 API Key，确认账号余额、模型权限和供应商区域是否正确。",
    };
  }
  if (/no api key|api key.*required|missing api key/.test(lower)) {
    return {
      message: "缺少 API Key",
      hint: "云模型需要先填写 API Key；Ollama/LM Studio 这类本地模型可以使用本地供应商预设。",
    };
  }
  if (status === 404 || /model not found|not found|does not exist/.test(lower)) {
    return {
      message: "模型或接口地址不存在",
      hint: "检查模型 ID 是否拼写正确，Base URL 可以填根地址或 /v1 地址，不要填 /chat/completions。",
    };
  }
  if (status === 429 || /rate limit|quota|insufficient|billing|balance/.test(lower)) {
    return {
      message: "额度不足或请求过于频繁",
      hint: "检查供应商账单、余额和限流状态，稍后再试。",
    };
  }
  if (/timed out|timeout|aborted/.test(lower)) {
    return {
      message: "连接超时",
      hint: "检查网络、代理和 Base URL；本地模型请确认服务已启动。",
    };
  }
  if (/fetch failed|econnrefused|enotfound|network|socket|connection/.test(lower)) {
    return {
      message: "地址无法连接",
      hint: "检查 Base URL 是否能从当前服务器访问，本地模型请确认端口正在监听。",
    };
  }
  return {
    message: "连接测试失败",
    hint: "检查 API Key、Base URL、API 类型和模型 ID 是否匹配。",
  };
}

function failureResponse(
  payload: { ok: false; error: string; latencyMs?: number; status?: number },
  responseStatus?: number,
  provider?: Record<string, unknown>,
) {
  const error = redactProviderSecrets(payload.error, provider);
  const friendly = friendlyFailure(error, payload.status);
  return NextResponse.json({
    ...payload,
    error,
    message: friendly.message,
    hint: friendly.hint,
  }, responseStatus ? { status: responseStatus } : undefined);
}

function getAssistantText(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "text")
    .map((block) => block.text)
    .join("");
}

export async function POST(req: Request) {
  let tempDir: string | undefined;
  let redactionProvider: Record<string, unknown> | undefined;

  try {
    const body = await req.json() as { providerName?: unknown; provider?: unknown; model?: unknown };
    if (isRecord(body.provider)) redactionProvider = body.provider;

    const providerName = typeof body.providerName === "string" ? body.providerName.trim() : "";
    if (!providerName) return failureResponse({ ok: false, error: "providerName is required" }, 400, redactionProvider);
    if (!isRecord(body.provider)) return failureResponse({ ok: false, error: "provider is required" }, 400, redactionProvider);
    if (!isRecord(body.model)) return failureResponse({ ok: false, error: "model is required" }, 400);

    const modelId = typeof body.model.id === "string" ? body.model.id.trim() : "";
    if (!modelId) return failureResponse({ ok: false, error: "Model ID is required" }, 400, body.provider);

    const providerApi = typeof body.provider.api === "string" ? body.provider.api : undefined;
    const modelApi = typeof body.model.api === "string" ? body.model.api : undefined;
    const providerForTest = { ...body.provider };
    if (
      providerAllowsMissingApiKey(providerName, providerApi ?? modelApi)
      && (typeof providerForTest.apiKey !== "string" || !providerForTest.apiKey.trim())
    ) {
      providerForTest.apiKey = "local";
    }
    redactionProvider = providerForTest;

    tempDir = mkdtempSync(join(tmpdir(), "pi-web-model-test-"));
    const modelsPath = join(tempDir, "models.json");
    writeFileSync(modelsPath, JSON.stringify({
      providers: {
        [providerName]: {
          ...providerForTest,
          models: [{ ...body.model, id: modelId }],
        },
      },
    }, null, 2), "utf8");

    const registry = ModelRegistry.create(AuthStorage.create(), modelsPath);
    const loadError = registry.getError();
    if (loadError) return failureResponse({ ok: false, error: loadError }, undefined, providerForTest);

    const model = registry.find(providerName, modelId);
    if (!model) return failureResponse({ ok: false, error: `Model not found: ${providerName}/${modelId}` }, undefined, providerForTest);

    const auth = await registry.getApiKeyAndHeaders(model);
    if (!auth.ok) return failureResponse({ ok: false, error: auth.error }, undefined, providerForTest);
    if (!auth.apiKey) return failureResponse({ ok: false, error: `No API key found for "${providerName}"` }, undefined, providerForTest);
    redactionProvider = {
      ...providerForTest,
      apiKey: auth.apiKey,
      headers: auth.headers,
    };

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), TEST_TIMEOUT_MS);
    let status: number | undefined;
    const startedAt = Date.now();

    try {
      const message = await completeSimple(model, {
        messages: [{
          role: "user",
          content: "Reply with OK only.",
          timestamp: Date.now(),
        }],
      }, {
        apiKey: auth.apiKey,
        headers: auth.headers,
        maxTokens: 16,
        timeoutMs: TEST_TIMEOUT_MS,
        maxRetries: 0,
        cacheRetention: "none",
        signal: controller.signal,
        onResponse: (response) => { status = response.status; },
      });

      const latencyMs = Date.now() - startedAt;
      if (message.stopReason === "error" || message.stopReason === "aborted") {
        return failureResponse({
          ok: false,
          error: message.errorMessage ?? (controller.signal.aborted ? "Test timed out" : "Model returned an error"),
          latencyMs,
          status,
        }, undefined, redactionProvider);
      }

      return NextResponse.json({
        ok: true,
        latencyMs,
        status,
        responseText: getAssistantText(message).slice(0, 300),
      });
    } finally {
      clearTimeout(timeout);
    }
  } catch (error) {
    return failureResponse({ ok: false, error: errorMessage(error) }, 500, redactionProvider);
  } finally {
    if (tempDir) rmSync(tempDir, { recursive: true, force: true });
  }
}
