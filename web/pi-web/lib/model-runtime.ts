import { join } from "node:path";
import { getAgentDir, ModelRuntime } from "@earendil-works/pi-coding-agent";

declare global {
  var __piWebModelRuntimes: Map<string, Promise<ModelRuntime>> | undefined;
}

function runtimeCache(): Map<string, Promise<ModelRuntime>> {
  globalThis.__piWebModelRuntimes ??= new Map();
  return globalThis.__piWebModelRuntimes;
}

export function webAgentDir(): string {
  return getAgentDir();
}

export async function getWebModelRuntime(modelsPath = join(webAgentDir(), "models.json")): Promise<ModelRuntime> {
  const cache = runtimeCache();
  let runtime = cache.get(modelsPath);
  if (!runtime) {
    runtime = ModelRuntime.create({
      authPath: join(webAgentDir(), "auth.json"),
      modelsPath,
    });
    cache.set(modelsPath, runtime);
    runtime.catch(() => cache.delete(modelsPath));
  }
  return runtime;
}
