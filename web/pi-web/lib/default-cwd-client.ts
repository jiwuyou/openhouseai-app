export async function fetchDefaultCwd(): Promise<string> {
  const response = await fetch("/api/default-cwd", { method: "POST" });
  const data = await response.json() as { cwd?: string; error?: string };
  if (!response.ok || !data.cwd) {
    throw new Error(data.error || `Unable to resolve default cwd (HTTP ${response.status})`);
  }
  return data.cwd;
}
