import { homedir } from "os";
import { isAbsolute, join, resolve } from "path";

function expandHome(value: string): string {
  if (value === "~") return homedir();
  if (value.startsWith("~/")) return join(homedir(), value.slice(2));
  return value;
}

export function getDefaultCwd(): string {
  const configured = process.env.PI_WEB_DEFAULT_CWD?.trim();
  if (!configured) return homedir();
  const expanded = expandHome(configured);
  return isAbsolute(expanded) ? expanded : resolve(expanded);
}
