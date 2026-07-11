const appListEl = document.querySelector("#app-list");
const registryStatusEl = document.querySelector("#registry-status");
const reloadButtonEl = document.querySelector("#reload-button");
const titleEl = document.querySelector("#app-title");
const descriptionEl = document.querySelector("#app-description");
const controlsEl = document.querySelector("#service-controls");
const frameEl = document.querySelector("#app-frame");
const emptyEl = document.querySelector("#viewer-empty");

let currentItems = [];
let currentAppId = "";

function setRegistryStatus(message, isWarning = false) {
  registryStatusEl.textContent = message;
  registryStatusEl.classList.toggle("warning", isWarning);
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
  if (!response.ok || data.error) {
    throw new Error(data.error || `HTTP ${response.status}`);
  }
  return data;
}

function pickRegistryItems(registry) {
  const items = registry?.menuRegistry?.items;
  if (Array.isArray(items)) {
    return items.filter((item) => item && item.visible !== false);
  }
  const apps = Array.isArray(registry?.apps) ? registry.apps : [];
  return apps.map((app) => ({
    id: app.id,
    title: app.title || app.name || app.id,
    description: app.description || "",
    entry: { type: "webview", url: app.entry },
    serviceNames: app.service?.name ? [app.service.name] : [],
  }));
}

function renderApps(items) {
  appListEl.replaceChildren();
  for (const item of items) {
    const button = document.createElement("button");
    const title = document.createElement("span");
    const section = document.createElement("small");
    button.type = "button";
    button.className = "app-button";
    button.dataset.appId = item.id || "";
    title.textContent = item.title || item.id || "Untitled";
    section.textContent = item.section || item.kind || "app";
    button.append(title, section);
    button.addEventListener("click", () => openApp(item));
    appListEl.append(button);
  }
}

function markActive(id) {
  for (const button of appListEl.querySelectorAll(".app-button")) {
    button.classList.toggle("active", button.dataset.appId === id);
  }
}

function openApp(item) {
  currentAppId = item.id || "";
  markActive(currentAppId);
  titleEl.textContent = item.title || item.id || "Untitled";
  descriptionEl.textContent = item.description || "";
  renderServiceControls(item);

  const entry = item.entry || {};
  if (entry.type === "webview" && entry.url) {
    frameEl.src = entry.url;
    frameEl.classList.add("visible");
    emptyEl.classList.add("hidden");
    return;
  }

  frameEl.removeAttribute("src");
  frameEl.classList.remove("visible");
  emptyEl.classList.remove("hidden");
  emptyEl.textContent = entry.type === "native-view"
    ? `native-view: ${entry.view || item.id || ""}`
    : "这个入口没有可嵌入的 WebView URL。";
}

function renderServiceControls(item) {
  controlsEl.replaceChildren();
  const serviceNames = Array.isArray(item.serviceNames) ? item.serviceNames : [];
  if (!serviceNames.length) {
    return;
  }

  for (const serviceId of serviceNames.slice(0, 3)) {
    for (const action of ["status", "start", "restart", "stop"]) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = `${serviceId} ${action}`;
      button.addEventListener("click", () => runServiceAction(serviceId, action));
      controlsEl.append(button);
    }
  }
}

async function runServiceAction(serviceId, action) {
  try {
    setRegistryStatus(`${serviceId}: ${action}`);
    if (action === "status") {
      const data = await requestJson(`/api/service-manager/services/${encodeURIComponent(serviceId)}/status`);
      const state = data?.service?.state || data?.service?.status?.state || data?.state || "unknown";
      setRegistryStatus(`${serviceId}: ${state}`);
      return;
    }
    await requestJson(`/api/service-manager/services/${encodeURIComponent(serviceId)}/${action}`, {
      method: "POST",
      body: "{}",
    });
    setRegistryStatus(`${serviceId}: ${action} requested`);
  } catch (error) {
    setRegistryStatus(error instanceof Error ? error.message : String(error), true);
  }
}

async function loadRegistry() {
  try {
    setRegistryStatus("正在读取 App registry。");
    const registry = await requestJson("/api/app-registry");
    currentItems = pickRegistryItems(registry);
    renderApps(currentItems);
    setRegistryStatus(`已读取 ${currentItems.length} 个入口。`);
    const homeId = registry?.menuRegistry?.homeTarget || "";
    const first = currentItems.find((item) => item.id === homeId) || currentItems[0];
    if (first && !currentAppId) {
      openApp(first);
    } else if (currentAppId) {
      const current = currentItems.find((item) => item.id === currentAppId);
      if (current) openApp(current);
    }
  } catch (error) {
    setRegistryStatus(error instanceof Error ? error.message : String(error), true);
  }
}

reloadButtonEl.addEventListener("click", loadRegistry);
loadRegistry();
