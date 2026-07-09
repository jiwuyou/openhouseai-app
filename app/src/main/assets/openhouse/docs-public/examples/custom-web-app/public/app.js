const statusEl = document.querySelector("#status");
const memoListEl = document.querySelector("#memo-list");
const memoFormEl = document.querySelector("#memo-form");
const memoInputEl = document.querySelector("#memo-input");
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
  const memos = Array.isArray(state.memos) ? state.memos : [];
  memoListEl.replaceChildren();

  if (!memos.length) {
    const empty = document.createElement("li");
    empty.textContent = "还没有备忘录。";
    memoListEl.append(empty);
    return;
  }

  for (const memo of memos) {
    const item = document.createElement("li");
    const content = document.createElement("div");
    const text = document.createElement("div");
    const meta = document.createElement("div");
    const remove = document.createElement("button");

    text.className = "memo-text";
    text.textContent = memo.text || "";
    meta.className = "memo-meta";
    meta.textContent = formatTime(memo.createdAt);
    content.append(text, meta);

    remove.type = "button";
    remove.textContent = "删除";
    remove.addEventListener("click", async () => {
      try {
        setStatus("正在删除。");
        const next = await requestJson(`/api/memos/${encodeURIComponent(memo.id)}`, {
          method: "DELETE",
        });
        renderState(next);
        setStatus("已更新。");
      } catch (error) {
        setStatus(error instanceof Error ? error.message : String(error));
      }
    });

    item.append(content, remove);
    memoListEl.append(item);
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

memoFormEl.addEventListener("submit", async (event) => {
  event.preventDefault();
  const text = memoInputEl.value.trim();
  if (!text) {
    memoInputEl.focus();
    return;
  }
  try {
    setStatus("正在添加。");
    const next = await requestJson("/api/memos", {
      method: "POST",
      body: JSON.stringify({ text }),
    });
    memoInputEl.value = "";
    renderState(next);
    setStatus("已添加。");
  } catch (error) {
    setStatus(error instanceof Error ? error.message : String(error));
  }
});

refreshButtonEl.addEventListener("click", loadState);
loadState();
