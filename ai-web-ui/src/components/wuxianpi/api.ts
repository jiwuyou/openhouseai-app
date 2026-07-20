import type {
  AssistantCopyRequest,
  AssistantCreateRequest,
  AssistantDetailData,
  AssistantListData,
  AssistantMutationData,
  AssistantSummary,
  AssistantUpdateRequest,
  CapabilityCatalogData,
  CapabilityConfigPatch,
  CapabilityCatalog,
  GlobalWuxianPiConfigV1,
  ExtensionBridgeResponse,
  PermissionMutationRequest,
  PermissionStateData,
  TtsClientInstruction,
  TtsSpeakRequest,
  WebExtensionListData,
  WebExtensionSummary,
  McpActionData,
  McpActionRequest,
} from "@/lib/wuxianpi/contracts";
import { WebApiError, webApi } from "@/lib/web-api-client";

export class WuxianPiApiError extends Error {
  constructor(message: string, public readonly status?: number, public readonly code?: string) {
    super(message);
    this.name = "WuxianPiApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  try {
    return await webApi.request<T>(path, init);
  } catch (error) {
    throw new WuxianPiApiError(error instanceof Error ? error.message : String(error), error instanceof WebApiError ? error.status : undefined);
  }
}

const assistantsRoute = (suffix = "") => webApi.endpoint("assistants", suffix);
const capabilitiesRoute = (suffix = "") => webApi.endpoint("capabilities", suffix);
const extensionsRoute = (suffix = "") => webApi.endpoint("extensions", suffix);

export async function listAssistants(options?: { includeArchived?: boolean }): Promise<AssistantSummary[]> {
  const payload = await webApi.request<AssistantListData | AssistantSummary[]>(assistantsRoute(), {}, options?.includeArchived ? { includeArchived: true } : undefined);
  return Array.isArray(payload) ? payload : payload.assistants;
}

export function getAssistant(id: string): Promise<AssistantDetailData> {
  return request<AssistantDetailData | { assistant: AssistantSummary & { files?: AssistantDetailData["files"] } }>(assistantsRoute(encodeURIComponent(id)))
    .then((data) => ({ assistant: data.assistant, files: "files" in data ? data.files : data.assistant.files ?? { agents: "", memory: "", workspaces: "" } }));
}

export function createAssistant(input: AssistantCreateRequest): Promise<AssistantSummary> {
  return request<AssistantMutationData>(assistantsRoute(), {
    method: "POST",
    body: JSON.stringify(input),
  }).then((data) => data.assistant);
}

export function updateAssistant(id: string, input: AssistantUpdateRequest): Promise<AssistantSummary> {
  return request<AssistantMutationData>(assistantsRoute(encodeURIComponent(id)), {
    method: "PATCH",
    body: JSON.stringify(input),
  }).then((data) => data.assistant);
}

export function setAssistantArchived(id: string, archived: boolean): Promise<AssistantSummary> {
  return updateAssistant(id, { manifest: { archived } });
}

export function cloneAssistant(id: string, newId?: string): Promise<AssistantSummary> {
  const payload: AssistantCopyRequest = { targetId: newId ?? `${id}-copy` };
  return request<AssistantMutationData>(assistantsRoute(`${encodeURIComponent(id)}/copy`), {
    method: "POST",
    body: JSON.stringify(payload),
  }).then((data) => data.assistant);
}

export function importAssistant(file: File, id: string): Promise<AssistantSummary> {
  const form = new FormData();
  form.set("id", id);
  form.set("file", file);
  return request<AssistantMutationData>(assistantsRoute("import"), {
    method: "POST",
    body: form,
  }).then((data) => data.assistant);
}

export async function exportAssistant(id: string): Promise<void> {
  const response = await webApi.raw(assistantsRoute(`${encodeURIComponent(id)}/export`));
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `${id}.wuxianpi.zip`;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function getCapabilityCatalog(): Promise<CapabilityCatalog> {
  return request<CapabilityCatalogData>(capabilitiesRoute()).then((data) => data.catalog);
}

export function getGlobalConfig(): Promise<GlobalWuxianPiConfigV1> {
  return request<CapabilityCatalogData>(capabilitiesRoute()).then((data) => data.config);
}

export function updateGlobalConfig(config: GlobalWuxianPiConfigV1 | CapabilityConfigPatch): Promise<GlobalWuxianPiConfigV1> {
  const patch: CapabilityConfigPatch = "schemaVersion" in config
    ? { defaults: config.defaults, mcpServers: config.mcpServers, ttsProfiles: config.ttsProfiles, ubuntu: config.ubuntu }
    : config;
  return request<GlobalWuxianPiConfigV1>(capabilitiesRoute("config"), {
    method: "PATCH",
    body: JSON.stringify(patch),
  });
}

export function speakText(input: TtsSpeakRequest, signal?: AbortSignal): Promise<TtsClientInstruction | Blob | null> {
  return webApi.raw(capabilitiesRoute("tts"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
    signal,
  }).then(async (response) => {
    const contentType = response.headers.get("content-type") ?? "";
    if (contentType.startsWith("audio/")) return response.blob();
    const data = await response.json() as
      | { kind: "client"; instruction: TtsClientInstruction }
      | { kind: "audio"; mimeType: string; data: string }
      | { kind: "completed"; provider: "termux-api" };
    if (data.kind === "client") return data.instruction;
    if (data.kind === "completed") return null;
    const binary = atob(data.data);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return new Blob([bytes], { type: data.mimeType });
  });
}

export async function listWebExtensions(): Promise<WebExtensionSummary[]> {
  const payload = await request<WebExtensionListData | Array<Record<string, unknown>>>(extensionsRoute());
  const rows = (Array.isArray(payload) ? payload : payload.extensions) as Array<WebExtensionSummary | Record<string, unknown>>;
  return rows.map((row) => {
    const value = row as Record<string, unknown>;
    const id = String(value.id ?? value.path ?? "unknown-extension");
    const rawManifest = value.manifest && typeof value.manifest === "object" ? value.manifest as Record<string, unknown> : {};
    return {
      id,
      path: String(value.path ?? ""),
      enabled: value.enabled !== false,
      resourceBaseUrl: String(value.resourceBaseUrl ?? ""),
      diagnostics: Array.isArray(value.diagnostics) ? value.diagnostics as WebExtensionSummary["diagnostics"] : [],
      manifest: {
        schemaVersion: 1,
        id,
        name: String(rawManifest.name ?? value.name ?? id),
        version: String(rawManifest.version ?? value.version ?? "0.0.0"),
        apiVersion: "1",
        description: typeof rawManifest.description === "string" ? rawManifest.description : undefined,
        permissions: Array.isArray(rawManifest.permissions) ? rawManifest.permissions as WebExtensionSummary["manifest"]["permissions"] : undefined,
        contributes: rawManifest.contributes && typeof rawManifest.contributes === "object"
          ? rawManifest.contributes as WebExtensionSummary["manifest"]["contributes"]
          : undefined,
      },
    };
  });
}

export function bridgeExtension(_extensionId: string, payload: unknown): Promise<ExtensionBridgeResponse> {
  return request<ExtensionBridgeResponse>(extensionsRoute("bridge"), {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function issueExtensionNonce(extensionId: string, assistantId?: string): Promise<string> {
  return request<{ extensionId: string; assistantId?: string; nonce: string }>(extensionsRoute("nonce"), {
    method: "POST",
    body: JSON.stringify({ action: "nonce", extensionId, assistantId }),
  }).then((data) => data.nonce);
}

export function getPermissionState(): Promise<PermissionStateData> {
  return request<PermissionStateData>(capabilitiesRoute("permissions"));
}

export function performMcpAction(input: McpActionRequest): Promise<McpActionData> {
  return request<McpActionData>(capabilitiesRoute("mcp"), {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function mutatePermission(input: PermissionMutationRequest): Promise<PermissionStateData> {
  return request<PermissionStateData>(capabilitiesRoute("permissions"), {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function isUnavailableError(error: unknown): boolean {
  return error instanceof WuxianPiApiError && [404, 405, 501].includes(error.status ?? 0);
}
