const statusEl = document.querySelector("#status");
const taskListEl = document.querySelector("#task-list");
const taskFormEl = document.querySelector("#task-form");
const taskInputEl = document.querySelector("#task-input");
const refreshButtonEl = document.querySelector("#refresh-button");

function setStatus(message) {
  statusEl.textContent = message;
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {}),
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || `HTTP ${response.status}`);
  }
  return data;
}

function formatTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString();
}

function renderState(state) {
  const tasks = Array.isArray(state.tasks) ? state.tasks : [];
  taskListEl.replaceChildren();

  if (!tasks.length) {
    const empty = document.createElement("li");
    empty.textContent = "还没有任务。";
    taskListEl.append(empty);
    return;
  }

  for (const task of tasks) {
    const item = document.createElement("li");
    const content = document.createElement("div");
    const title = document.createElement("div");
    const meta = document.createElement("div");
    const remove = document.createElement("button");

    title.className = "task-title";
    title.textContent = task.title || "Untitled";
    meta.className = "task-meta";
    meta.textContent = formatTime(task.createdAt);
    content.append(title, meta);

    remove.type = "button";
    remove.textContent = "删除";
    remove.addEventListener("click", async () => {
      try {
        setStatus("正在删除。");
        const next = await requestJson(`/api/tasks/${encodeURIComponent(task.id)}`, {
          method: "DELETE",
        });
        renderState(next);
        setStatus("已更新。");
      } catch (error) {
        setStatus(error instanceof Error ? error.message : String(error));
      }
    });

    item.append(content, remove);
    taskListEl.append(item);
  }
}

async function loadState() {
  try {
    setStatus("正在读取本地状态。");
    const state = await requestJson("/api/state");
    renderState(state);
    setStatus(`已读取，更新时间：${formatTime(state.updatedAt) || "未知"}`);
  } catch (error) {
    setStatus(error instanceof Error ? error.message : String(error));
  }
}

taskFormEl.addEventListener("submit", async (event) => {
  event.preventDefault();
  const title = taskInputEl.value.trim();
  if (!title) {
    taskInputEl.focus();
    return;
  }
  try {
    setStatus("正在添加。");
    const next = await requestJson("/api/tasks", {
      method: "POST",
      body: JSON.stringify({ title }),
    });
    taskInputEl.value = "";
    renderState(next);
    setStatus("已添加。");
  } catch (error) {
    setStatus(error instanceof Error ? error.message : String(error));
  }
});

refreshButtonEl.addEventListener("click", loadState);
loadState();

