const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const APP_ID = "hello-openhouse";
const APP_ROOT = path.resolve(__dirname, "..");
const DATA_DIR = path.resolve(process.env.OPENHOUSE_CUSTOM_APP_DATA_DIR || path.join(APP_ROOT, "data"));
const STATE_FILE = path.join(DATA_DIR, "state.json");

function nowIso() {
  return new Date().toISOString();
}

function createId() {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function health(mode = "local") {
  return {
    ok: true,
    app: APP_ID,
    mode,
    time: nowIso(),
  };
}

async function ensureState() {
  await fs.promises.mkdir(DATA_DIR, { recursive: true });
  try {
    await fs.promises.access(STATE_FILE, fs.constants.R_OK);
  } catch {
    await writeState({
      app: APP_ID,
      updatedAt: nowIso(),
      tasks: [
        {
          id: createId(),
          title: "从 OpenHouse 桌面打开这个自定义 App",
          createdAt: nowIso(),
        },
      ],
    });
  }
}

async function readState() {
  await ensureState();
  const raw = await fs.promises.readFile(STATE_FILE, "utf8");
  const state = JSON.parse(raw);
  return {
    app: APP_ID,
    ...state,
    tasks: Array.isArray(state.tasks) ? state.tasks : [],
  };
}

async function writeState(state) {
  await fs.promises.mkdir(DATA_DIR, { recursive: true });
  const next = {
    ...state,
    updatedAt: nowIso(),
  };
  const tmp = `${STATE_FILE}.${process.pid}.${createId()}.tmp`;
  await fs.promises.writeFile(tmp, `${JSON.stringify(next, null, 2)}\n`, "utf8");
  await fs.promises.rename(tmp, STATE_FILE);
  return next;
}

async function addTask(title) {
  const cleanTitle = String(title || "").trim();
  if (!cleanTitle) {
    throw new Error("title is required");
  }

  const state = await readState();
  return writeState({
    ...state,
    tasks: [
      {
        id: createId(),
        title: cleanTitle,
        createdAt: nowIso(),
      },
      ...state.tasks,
    ],
  });
}

async function listTasks() {
  const state = await readState();
  return { ok: true, app: APP_ID, tasks: state.tasks, updatedAt: state.updatedAt };
}

async function deleteTask(id) {
  const taskId = String(id || "").trim();
  if (!taskId) {
    throw new Error("id is required");
  }

  const state = await readState();
  return writeState({
    ...state,
    tasks: state.tasks.filter((task) => task.id !== taskId),
  });
}

module.exports = {
  APP_ID,
  APP_ROOT,
  DATA_DIR,
  STATE_FILE,
  addTask,
  deleteTask,
  health,
  listTasks,
  readState,
};
