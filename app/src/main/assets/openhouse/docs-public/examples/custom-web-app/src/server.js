const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");

const HOST = process.env.HOST || "127.0.0.1";
const PORT = Number.parseInt(process.env.PORT || "23110", 10);
const APP_ROOT = path.resolve(__dirname, "..");
const PUBLIC_ROOT = path.join(APP_ROOT, "public");
const DATA_DIR = path.resolve(process.env.OPENHOUSE_CUSTOM_APP_DATA_DIR || path.join(APP_ROOT, "data"));
const STATE_FILE = path.join(DATA_DIR, "state.json");

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

async function ensureState() {
  await fs.promises.mkdir(DATA_DIR, { recursive: true });
  try {
    await fs.promises.access(STATE_FILE, fs.constants.R_OK);
  } catch {
    await writeState({
      app: "hello-openhouse",
      updatedAt: new Date().toISOString(),
      tasks: [
        {
          id: createId(),
          title: "从 OpenHouse 桌面打开这个自定义 App",
          createdAt: new Date().toISOString(),
        },
      ],
    });
  }
}

async function readState() {
  await ensureState();
  const raw = await fs.promises.readFile(STATE_FILE, "utf8");
  return JSON.parse(raw);
}

async function writeState(state) {
  await fs.promises.mkdir(DATA_DIR, { recursive: true });
  const next = {
    ...state,
    updatedAt: new Date().toISOString(),
  };
  const tmp = `${STATE_FILE}.${process.pid}.tmp`;
  await fs.promises.writeFile(tmp, `${JSON.stringify(next, null, 2)}\n`, "utf8");
  await fs.promises.rename(tmp, STATE_FILE);
  return next;
}

function createId() {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

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
      ok: true,
      app: "hello-openhouse",
      time: new Date().toISOString(),
    });
    return true;
  }

  if (req.method === "GET" && url.pathname === "/api/state") {
    sendJson(res, 200, await readState());
    return true;
  }

  if (req.method === "POST" && url.pathname === "/api/tasks") {
    const body = await readJsonBody(req);
    const title = String(body.title || "").trim();
    if (!title) {
      sendJson(res, 400, { error: "title is required" });
      return true;
    }
    const state = await readState();
    const next = await writeState({
      ...state,
      tasks: [
        {
          id: createId(),
          title,
          createdAt: new Date().toISOString(),
        },
        ...state.tasks,
      ],
    });
    sendJson(res, 201, next);
    return true;
  }

  const deleteMatch = url.pathname.match(/^\/api\/tasks\/([^/]+)$/);
  if (req.method === "DELETE" && deleteMatch) {
    const id = decodeURIComponent(deleteMatch[1]);
    const state = await readState();
    const next = await writeState({
      ...state,
      tasks: state.tasks.filter((task) => task.id !== id),
    });
    sendJson(res, 200, next);
    return true;
  }

  return false;
}

const server = http.createServer(async (req, res) => {
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

server.listen(PORT, HOST, () => {
  console.log(`[hello-openhouse] listening on http://${HOST}:${PORT}/`);
});

process.on("SIGTERM", () => {
  server.close(() => process.exit(0));
});

