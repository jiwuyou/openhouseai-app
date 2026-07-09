const fs = require("fs");
const http = require("http");
const path = require("path");
const { addMemo, deleteMemo, health, readState } = require("./state");

const HOST = process.env.HOST || "127.0.0.1";
const PORT = Number.parseInt(process.env.PORT || "23110", 10);
const APP_ROOT = path.resolve(__dirname, "..");
const PUBLIC_ROOT = path.join(APP_ROOT, "public");

const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
};

function sendJson(res, status, payload) {
  const body = `${JSON.stringify(payload, null, 2)}\n`;
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(body);
}

function sendText(res, status, message) {
  res.writeHead(status, {
    "content-type": "text/plain; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(`${message}\n`);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      raw += chunk;
      if (raw.length > 64 * 1024) {
        reject(new Error("request body too large"));
        req.destroy();
      }
    });
    req.on("end", () => {
      if (!raw.trim()) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(raw));
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

async function serveStatic(res, pathname) {
  const relativePath = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
  const filePath = path.resolve(PUBLIC_ROOT, relativePath);
  const rel = path.relative(PUBLIC_ROOT, filePath);
  if (rel.startsWith("..") || path.isAbsolute(rel)) {
    sendText(res, 403, "Forbidden");
    return;
  }

  let stat;
  try {
    stat = await fs.promises.stat(filePath);
  } catch {
    sendText(res, 404, "Not found");
    return;
  }
  if (!stat.isFile()) {
    sendText(res, 404, "Not found");
    return;
  }

  const ext = path.extname(filePath).toLowerCase();
  res.writeHead(200, {
    "content-type": MIME_TYPES[ext] || "application/octet-stream",
    "cache-control": "no-store",
  });
  fs.createReadStream(filePath).pipe(res);
}

async function handleApi(req, res, url) {
  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, {
      ...health("http"),
    });
    return true;
  }

  if (req.method === "GET" && url.pathname === "/api/state") {
    sendJson(res, 200, await readState());
    return true;
  }

  if (req.method === "POST" && url.pathname === "/api/memos") {
    const body = await readJsonBody(req);
    const text = String(body.text || "").trim();
    if (!text) {
      sendJson(res, 400, { error: "text is required" });
      return true;
    }
    const next = await addMemo(text);
    sendJson(res, 201, next);
    return true;
  }

  const deleteMatch = url.pathname.match(/^\/api\/memos\/([^/]+)$/);
  if (req.method === "DELETE" && deleteMatch) {
    const id = decodeURIComponent(deleteMatch[1]);
    const next = await deleteMemo(id);
    sendJson(res, 200, next);
    return true;
  }

  return false;
}

function createServer() {
  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url || "/", `http://${HOST}:${PORT}`);
      if (await handleApi(req, res, url)) {
        return;
      }
      if (req.method !== "GET" && req.method !== "HEAD") {
        sendText(res, 405, "Method not allowed");
        return;
      }
      await serveStatic(res, url.pathname);
    } catch (error) {
      sendJson(res, 500, {
        error: error instanceof Error ? error.message : String(error),
      });
    }
  });
}

if (require.main === module) {
  const server = createServer();
  server.listen(PORT, HOST, () => {
    console.log(`[memo-openhouse] listening on http://${HOST}:${PORT}/`);
  });

  process.on("SIGTERM", () => {
    server.close(() => process.exit(0));
  });
}

module.exports = {
  createServer,
  handleApi,
};
