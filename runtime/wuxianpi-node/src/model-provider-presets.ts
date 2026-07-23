export type ModelProviderApi =
  | "openai-completions"
  | "openai-responses"
  | "anthropic-messages"
  | "google-generative-ai";

export type ModelProviderApiSelection = ModelProviderApi | "auto";

export interface ModelProviderPreset {
  id: string;
  aliases: string[];
  label: string;
  api: ModelProviderApi;
  baseUrl: string;
  recommendedModel?: string;
  recommendedModels?: string[];
  requiresApiKey: boolean;
  category?: "official" | "aggregator" | "regional" | "local" | "custom";
  endpointCandidates?: string[];
  sourceTags?: Array<"pi-web" | "operit" | "cc-switch">;
  compat?: {
    operitProviderType?: string;
    ccSwitchApiFormat?: "anthropic" | "openai_chat" | "openai_responses" | "gemini_native";
    ccSwitchFamilies?: Array<"claude" | "codex" | "gemini" | "universal">;
  };
}

export const MODEL_PROVIDER_PRESETS: ModelProviderPreset[] = [
  {
    id: "deepseek",
    aliases: ["deepseek"],
    label: "DeepSeek",
    api: "openai-completions",
    baseUrl: "https://api.deepseek.com/v1",
    recommendedModel: "deepseek-chat",
    recommendedModels: ["deepseek-chat", "deepseek-reasoner"],
    requiresApiKey: true,
    category: "official",
    endpointCandidates: ["https://api.deepseek.com/v1/chat/completions"],
    sourceTags: ["pi-web", "operit"],
    compat: { operitProviderType: "DEEPSEEK", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "openai",
    aliases: ["openai", "gpt"],
    label: "OpenAI",
    api: "openai-completions",
    baseUrl: "https://api.openai.com/v1",
    recommendedModel: "gpt-5.4",
    recommendedModels: ["gpt-5.4", "gpt-5", "gpt-4.1", "gpt-4o"],
    requiresApiKey: true,
    category: "official",
    endpointCandidates: ["https://api.openai.com/v1/chat/completions"],
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "OPENAI", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "gpt-responses",
    aliases: ["gpt-responses", "openai-responses", "responses", "openai_responses"],
    label: "GPT Responses",
    api: "openai-responses",
    baseUrl: "https://api.openai.com/v1",
    recommendedModel: "gpt-5.4",
    recommendedModels: ["gpt-5.4", "gpt-5", "gpt-4.1"],
    requiresApiKey: true,
    category: "official",
    endpointCandidates: ["https://api.openai.com/v1/responses"],
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "OPENAI_RESPONSES", ccSwitchApiFormat: "openai_responses", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "anthropic",
    aliases: ["anthropic", "claude"],
    label: "Anthropic",
    api: "anthropic-messages",
    baseUrl: "https://api.anthropic.com",
    recommendedModel: "claude-sonnet-4-6",
    recommendedModels: ["claude-sonnet-4-6", "claude-sonnet-4", "claude-3-5-sonnet-latest"],
    requiresApiKey: true,
    category: "official",
    endpointCandidates: ["https://api.anthropic.com/v1/messages"],
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "ANTHROPIC", ccSwitchApiFormat: "anthropic", ccSwitchFamilies: ["claude", "universal"] },
  },
  {
    id: "google",
    aliases: ["google", "gemini", "google/gemini"],
    label: "Google Gemini",
    api: "google-generative-ai",
    baseUrl: "https://generativelanguage.googleapis.com/v1beta",
    recommendedModel: "gemini-2.5-flash",
    recommendedModels: ["gemini-2.5-flash", "gemini-2.5-pro", "gemini-1.5-flash"],
    requiresApiKey: true,
    category: "official",
    endpointCandidates: ["https://generativelanguage.googleapis.com/v1beta/models"],
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "GOOGLE", ccSwitchApiFormat: "gemini_native", ccSwitchFamilies: ["gemini", "universal"] },
  },
  {
    id: "openrouter",
    aliases: ["openrouter"],
    label: "OpenRouter",
    api: "openai-completions",
    baseUrl: "https://openrouter.ai/api/v1",
    recommendedModel: "openai/gpt-5.4",
    recommendedModels: ["openai/gpt-5.4", "anthropic/claude-sonnet-4", "google/gemini-2.5-flash"],
    requiresApiKey: true,
    category: "aggregator",
    endpointCandidates: ["https://openrouter.ai/api/v1/chat/completions"],
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "OPENROUTER", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["claude", "codex", "gemini", "universal"] },
  },
  {
    id: "siliconflow",
    aliases: ["siliconflow", "silicon-flow", "silicon_flow"],
    label: "SiliconFlow",
    api: "openai-completions",
    baseUrl: "https://api.siliconflow.cn/v1",
    recommendedModel: "deepseek-ai/DeepSeek-V3",
    recommendedModels: ["deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen2.5-Coder-32B-Instruct"],
    requiresApiKey: true,
    category: "aggregator",
    endpointCandidates: ["https://api.siliconflow.cn/v1/chat/completions"],
    sourceTags: ["pi-web", "operit"],
    compat: { operitProviderType: "SILICONFLOW", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "moonshot",
    aliases: ["moonshot", "kimi", "moonshot/kimi", "moonshotai", "kimi-coding"],
    label: "Moonshot Kimi",
    api: "openai-completions",
    baseUrl: "https://api.moonshot.cn/v1",
    recommendedModel: "kimi-k2-0711-preview",
    recommendedModels: ["kimi-k2-0711-preview", "moonshot-v1-128k", "moonshot-v1-32k", "moonshot-v1-8k"],
    requiresApiKey: true,
    category: "official",
    endpointCandidates: [
      "https://api.moonshot.cn/v1/chat/completions",
      "https://api.moonshot.ai/v1/chat/completions",
      "https://api.kimi.com/coding/v1/chat/completions",
    ],
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "MOONSHOT", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "doubao",
    aliases: ["doubao", "volcengine", "ark", "byteplus", "volcano"],
    label: "Doubao",
    api: "openai-completions",
    baseUrl: "https://ark.cn-beijing.volces.com/api/v3",
    recommendedModel: "doubao-seed-1-6-250615",
    recommendedModels: ["doubao-seed-1-6-250615", "doubao-seed-2-1-pro", "ark-code-latest"],
    requiresApiKey: true,
    category: "regional",
    endpointCandidates: [
      "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
      "https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions",
      "https://ark.ap-southeast.bytepluses.com/api/coding/v3/chat/completions",
    ],
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "DOUBAO", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["claude", "codex", "universal"] },
  },
  {
    id: "aliyun",
    aliases: ["aliyun", "dashscope", "qwen", "tongyi"],
    label: "Alibaba Cloud DashScope",
    api: "openai-completions",
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    recommendedModel: "qwen-max",
    recommendedModels: ["qwen-max", "qwen-plus", "qwen-coder-plus"],
    requiresApiKey: true,
    category: "regional",
    endpointCandidates: ["https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"],
    sourceTags: ["operit"],
    compat: { operitProviderType: "ALIYUN", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "zhipu",
    aliases: ["zhipu", "zai", "bigmodel", "glm"],
    label: "Zhipu / Z.ai",
    api: "openai-completions",
    baseUrl: "https://open.bigmodel.cn/api/paas/v4",
    recommendedModel: "glm-4.5",
    recommendedModels: ["glm-4.5", "glm-4-plus", "glm-4-air"],
    requiresApiKey: true,
    category: "regional",
    endpointCandidates: [
      "https://open.bigmodel.cn/api/paas/v4/chat/completions",
      "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions",
      "https://api.z.ai/api/paas/v4/chat/completions",
      "https://api.z.ai/api/coding/paas/v4/chat/completions",
    ],
    sourceTags: ["operit"],
    compat: { operitProviderType: "ZHIPU", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "baichuan",
    aliases: ["baichuan", "baichuan-ai"],
    label: "Baichuan",
    api: "openai-completions",
    baseUrl: "https://api.baichuan-ai.com/v1",
    recommendedModel: "baichuan4",
    recommendedModels: ["baichuan4"],
    requiresApiKey: true,
    category: "regional",
    endpointCandidates: ["https://api.baichuan-ai.com/v1/chat/completions"],
    sourceTags: ["operit"],
    compat: { operitProviderType: "BAICHUAN", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["universal"] },
  },
  {
    id: "mistral",
    aliases: ["mistral", "codestral"],
    label: "Mistral Codestral",
    api: "openai-completions",
    baseUrl: "https://codestral.mistral.ai/v1",
    recommendedModel: "codestral-latest",
    recommendedModels: ["codestral-latest"],
    requiresApiKey: true,
    category: "official",
    endpointCandidates: ["https://codestral.mistral.ai/v1/chat/completions"],
    sourceTags: ["operit"],
    compat: { operitProviderType: "MISTRAL", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "nvidia",
    aliases: ["nvidia", "nvidia-nim"],
    label: "NVIDIA NIM",
    api: "openai-completions",
    baseUrl: "https://integrate.api.nvidia.com/v1",
    recommendedModel: "nvidia/nemotron-3-nano-30b-a3b",
    recommendedModels: ["nvidia/nemotron-3-nano-30b-a3b"],
    requiresApiKey: true,
    category: "aggregator",
    endpointCandidates: ["https://integrate.api.nvidia.com/v1/chat/completions"],
    sourceTags: ["operit"],
    compat: { operitProviderType: "NVIDIA", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["universal"] },
  },
  {
    id: "novita",
    aliases: ["novita", "novita-openai"],
    label: "Novita AI",
    api: "openai-completions",
    baseUrl: "https://api.novita.ai/openai/v1",
    recommendedModel: "moonshotai/kimi-k2.5",
    recommendedModels: ["moonshotai/kimi-k2.5"],
    requiresApiKey: true,
    category: "aggregator",
    endpointCandidates: [
      "https://api.novita.ai/openai/v1/chat/completions",
      "https://api.novita.ai/anthropic/v1/messages",
    ],
    sourceTags: ["operit"],
    compat: { operitProviderType: "NOVITA", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["claude", "codex", "universal"] },
  },
  {
    id: "shengsuanyun",
    aliases: ["shengsuanyun", "shengsuan", "router.shengsuanyun"],
    label: "Shengsuanyun",
    api: "openai-completions",
    baseUrl: "https://router.shengsuanyun.com/api/v1",
    recommendedModel: "openai/gpt-5.5",
    recommendedModels: ["openai/gpt-5.5", "anthropic/claude-sonnet-4.6", "google/gemini-3.5-flash"],
    requiresApiKey: true,
    category: "aggregator",
    endpointCandidates: ["https://router.shengsuanyun.com/api/v1", "https://router.shengsuanyun.com/api"],
    sourceTags: ["cc-switch"],
    compat: { ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["claude", "codex", "gemini", "universal"] },
  },
  {
    id: "patewayai",
    aliases: ["pateway", "patewayai"],
    label: "PatewayAI",
    api: "openai-completions",
    baseUrl: "https://api.pateway.ai/v1",
    recommendedModel: "gpt-5.5",
    recommendedModels: ["gpt-5.5"],
    requiresApiKey: true,
    category: "aggregator",
    endpointCandidates: ["https://api.pateway.ai/v1"],
    sourceTags: ["cc-switch"],
    compat: { ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["claude", "codex", "universal"] },
  },
  {
    id: "ollama",
    aliases: ["ollama"],
    label: "Ollama",
    api: "openai-completions",
    baseUrl: "http://localhost:11434/v1",
    recommendedModel: "qwen2.5-coder:7b",
    recommendedModels: ["qwen2.5-coder:7b", "llama3.1:8b", "deepseek-r1"],
    requiresApiKey: false,
    category: "local",
    endpointCandidates: ["http://localhost:11434/v1/chat/completions", "http://localhost:11434/api/chat"],
    sourceTags: ["pi-web", "operit"],
    compat: { operitProviderType: "OLLAMA", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["universal"] },
  },
  {
    id: "lmstudio",
    aliases: ["lmstudio", "lm-studio", "lm_studio"],
    label: "LM Studio",
    api: "openai-completions",
    baseUrl: "http://localhost:1234/v1",
    recommendedModel: "local-model",
    recommendedModels: ["local-model"],
    requiresApiKey: false,
    category: "local",
    endpointCandidates: ["http://localhost:1234/v1/chat/completions"],
    sourceTags: ["pi-web", "operit"],
    compat: { operitProviderType: "LMSTUDIO", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["universal"] },
  },
  {
    id: "openai-local",
    aliases: ["openai-local", "local-openai", "local"],
    label: "Local OpenAI-compatible",
    api: "openai-completions",
    baseUrl: "http://localhost:8000/v1",
    recommendedModel: "local-model",
    recommendedModels: ["local-model"],
    requiresApiKey: false,
    category: "local",
    endpointCandidates: ["http://localhost:8000/v1/chat/completions"],
    sourceTags: ["operit"],
    compat: { operitProviderType: "OPENAI_LOCAL", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["universal"] },
  },
  {
    id: "custom-openai",
    aliases: ["custom-openai", "openai兼容", "custom", "openai-compatible", "openai_compatible"],
    label: "OpenAI 兼容",
    api: "openai-completions",
    baseUrl: "",
    requiresApiKey: true,
    category: "custom",
    sourceTags: ["pi-web", "operit", "cc-switch"],
    compat: { operitProviderType: "OPENAI_GENERIC", ccSwitchApiFormat: "openai_chat", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "gpt-responses-compatible",
    aliases: ["gpt-responses-compatible", "gpt-responses兼容", "openai-responses-compatible", "responses-compatible"],
    label: "GPT Responses 兼容",
    api: "openai-responses",
    baseUrl: "",
    recommendedModel: "gpt-5.4",
    recommendedModels: ["gpt-5.4", "gpt-5", "gpt-4.1"],
    requiresApiKey: true,
    category: "custom",
    sourceTags: ["pi-web", "cc-switch"],
    compat: { operitProviderType: "OPENAI_RESPONSES", ccSwitchApiFormat: "openai_responses", ccSwitchFamilies: ["codex", "universal"] },
  },
  {
    id: "custom-anthropic",
    aliases: ["custom-anthropic", "claude兼容", "custom-claude", "anthropic-compatible", "claude-compatible"],
    label: "Claude 兼容",
    api: "anthropic-messages",
    baseUrl: "",
    recommendedModel: "claude-sonnet-4-6",
    recommendedModels: ["claude-sonnet-4-6", "claude-sonnet-4", "claude-3-5-sonnet-latest"],
    requiresApiKey: true,
    category: "custom",
    sourceTags: ["pi-web", "cc-switch"],
    compat: { operitProviderType: "ANTHROPIC", ccSwitchApiFormat: "anthropic", ccSwitchFamilies: ["claude", "universal"] },
  },
];

export const SUPPORTED_MODEL_PROVIDER_APIS = new Set<ModelProviderApi>([
  "openai-completions",
  "openai-responses",
  "anthropic-messages",
  "google-generative-ai",
]);

function normalizeProviderId(providerId: string): string {
  return providerId.trim().toLowerCase().replace(/\s+/g, "-");
}

export function getModelProviderPreset(providerId: string | undefined): ModelProviderPreset | undefined {
  if (!providerId) return undefined;
  const normalized = normalizeProviderId(providerId);
  return MODEL_PROVIDER_PRESETS.find((preset) =>
    preset.id === normalized || preset.aliases.some((alias) => normalizeProviderId(alias) === normalized)
  );
}

export function isSupportedModelProviderApi(api: string | undefined): api is ModelProviderApi {
  return typeof api === "string" && SUPPORTED_MODEL_PROVIDER_APIS.has(api as ModelProviderApi);
}

export function normalizeModelProviderApi(
  value: string | undefined,
  options: { allowAuto?: boolean } = {},
): ModelProviderApiSelection | undefined {
  if (value === undefined) return undefined;
  const normalized = value.trim().toLowerCase();
  if (options.allowAuto && normalized === "auto") return "auto";
  if (normalized === "claude" || normalized === "anthropic") return "anthropic-messages";
  if (normalized === "gpt") return "openai-responses";
  if (normalized === "openai") return "openai-completions";
  if (normalized === "gemini") return "google-generative-ai";
  return isSupportedModelProviderApi(normalized) ? normalized : undefined;
}

export function providerAllowsMissingApiKey(providerId: string | undefined, api?: string): boolean {
  const preset = getModelProviderPreset(providerId);
  if (preset) return !preset.requiresApiKey;
  return api === "openai-completions" && providerId !== undefined && /^(ollama|lmstudio|lm-studio|local)$/i.test(providerId);
}
