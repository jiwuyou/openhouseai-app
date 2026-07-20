import { createReadStream } from "node:fs";
import { realpath, stat } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { join, relative, resolve, sep } from "node:path";
import { contentType } from "./web-services.js";

export class StaticFiles {
  private rootReal?: Promise<string>;

  constructor(private readonly root?: string) {
    if (root) this.rootReal = realpath(resolve(root));
  }

  get enabled(): boolean { return !!this.rootReal; }

  async serve(request: IncomingMessage, response: ServerResponse, pathname: string): Promise<boolean> {
    if (!this.rootReal || (request.method !== "GET" && request.method !== "HEAD")) return false;
    const root = await this.rootReal;
    const decoded = decodeURIComponent(pathname);
    const requested = resolve(root, `.${decoded}`);
    let target = requested;
    if (!inside(root, target)) return false;
    try {
      const info = await stat(target);
      if (info.isDirectory()) target = join(target, "index.html");
      else if (!info.isFile()) return false;
    } catch {
      // SPA navigation routes fall back to index.html. Missing files with an
      // extension remain 404 so broken assets are visible during debugging.
      if (decoded.split("/").at(-1)?.includes(".")) return false;
      target = join(root, "index.html");
    }
    const targetReal = await realpath(target);
    if (!inside(root, targetReal)) return false;
    const info = await stat(targetReal);
    response.writeHead(200, {
      "content-type": contentType(targetReal),
      "content-length": info.size,
      "cache-control": targetReal.endsWith("index.html") ? "no-cache" : "public, max-age=31536000, immutable",
    });
    if (request.method === "HEAD") response.end();
    else createReadStream(targetReal).pipe(response);
    return true;
  }
}

function inside(root: string, target: string): boolean {
  const nested = relative(root, target);
  return nested === "" || (!nested.startsWith(`..${sep}`) && nested !== ".." && !nested.startsWith("/"));
}
