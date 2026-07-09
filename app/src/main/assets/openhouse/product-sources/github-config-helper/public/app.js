(function () {
  'use strict';

  const state = {
    status: null,
    currentSource: null
  };

  const elements = {
    overallState: document.getElementById('overall-state'),
    toolGrid: document.getElementById('tool-grid'),
    installHint: document.getElementById('install-hint'),
    accountLabel: document.getElementById('account-label'),
    githubDetails: document.getElementById('github-details'),
    taskState: document.getElementById('task-state'),
    taskLog: document.getElementById('task-log'),
    refreshStatus: document.getElementById('refresh-status'),
    loginButton: document.getElementById('login-button'),
    refreshButton: document.getElementById('refresh-button'),
    setupGitButton: document.getElementById('setup-git-button'),
    logoutButton: document.getElementById('logout-button')
  };

  const toolLabels = {
    git: 'git',
    gh: 'GitHub CLI',
    codex: 'Codex',
    claude: 'Claude Code'
  };

  const actionLabels = {
    connect: '授权并配置 git',
    refresh: '重新授权',
    setupGit: '配置 git',
    logout: '断开连接'
  };

  function actionLabel(action) {
    return actionLabels[action] || action;
  }

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function setBusy(isBusy) {
    for (const button of [
      elements.loginButton,
      elements.refreshButton,
      elements.setupGitButton,
      elements.logoutButton,
      elements.refreshStatus
    ]) {
      button.disabled = isBusy;
    }
  }

  function renderTools(status) {
    const tools = status.tools || {};
    elements.toolGrid.innerHTML = Object.keys(toolLabels)
      .map((name) => {
        const tool = tools[name] || {};
        const installed = Boolean(tool.installed);
        const className = installed ? 'ok' : 'missing';
        const value = installed ? tool.path : '未安装';
        return `
          <div class="tool-card ${className}">
            <div class="tool-name">${escapeHtml(toolLabels[name])}</div>
            <div class="tool-state">${installed ? '可用' : '缺失'}</div>
            <div class="tool-path">${escapeHtml(value || '')}</div>
          </div>
        `;
      })
      .join('');

    const missing = ['git', 'gh'].filter((name) => !tools[name] || !tools[name].installed);
    if (missing.length > 0) {
      elements.installHint.classList.remove('hidden');
      elements.installHint.textContent = `缺少 ${missing.join(', ')}。请在 Ubuntu 中安装：apt update && apt install -y git gh`;
    } else {
      elements.installHint.classList.add('hidden');
      elements.installHint.textContent = '';
    }
  }

  function detailRow(label, value) {
    return `<dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value || '-')}</dd>`;
  }

  function renderGithub(status) {
    const github = status.github || {};
    const helper = status.gitCredentialHelper || {};
    const tools = status.tools || {};
    const ghInstalled = Boolean(tools.gh && tools.gh.installed);
    const authenticated = Boolean(github.authenticated);
    const helperReady = Boolean(helper.configured);

    elements.overallState.textContent = authenticated && helperReady ? '已连接' : authenticated ? '需配置 git' : '未连接';
    elements.overallState.className = `state-pill ${authenticated && helperReady ? 'ready' : authenticated ? 'warn' : ''}`;
    elements.accountLabel.textContent = authenticated ? `账号：${github.account || '已登录'}` : '未连接';

    const scopes = Array.isArray(github.scopes) && github.scopes.length > 0 ? github.scopes.join(', ') : '-';
    const missingScopes =
      Array.isArray(github.missingScopes) && github.missingScopes.length > 0 ? github.missingScopes.join(', ') : '无';
    const helperEntries =
      Array.isArray(helper.entries) && helper.entries.length > 0 ? helper.entries.join('\n') : helper.statusText || '-';

    elements.githubDetails.innerHTML = [
      detailRow('GitHub host', status.host || 'github.com'),
      detailRow('账号', github.account || '-'),
      detailRow('Git 协议', github.gitProtocol || '-'),
      detailRow('已授权 scopes', scopes),
      detailRow('缺少 scopes', missingScopes),
      detailRow('git credential helper', helperReady ? '已使用 gh' : '未配置 gh'),
      detailRow('helper 条目', helperEntries)
    ].join('');

    elements.loginButton.disabled = !ghInstalled || authenticated;
    elements.refreshButton.disabled = !ghInstalled;
    elements.setupGitButton.disabled = !ghInstalled;
    elements.logoutButton.disabled = !ghInstalled || !authenticated;
  }

  async function refreshStatus() {
    elements.overallState.textContent = '检查中';
    const response = await fetch('/api/status', { cache: 'no-store' });
    if (!response.ok) {
      throw new Error(`状态检查失败：${response.status}`);
    }
    state.status = await response.json();
    renderTools(state.status);
    renderGithub(state.status);
  }

  function appendLog(line) {
    const atBottom =
      elements.taskLog.scrollHeight - elements.taskLog.clientHeight - elements.taskLog.scrollTop < 16;
    elements.taskLog.textContent += line.endsWith('\n') ? line : `${line}\n`;
    if (atBottom) {
      elements.taskLog.scrollTop = elements.taskLog.scrollHeight;
    }
  }

  async function startTask(path) {
    if (state.currentSource) {
      state.currentSource.close();
      state.currentSource = null;
    }

    setBusy(true);
    elements.taskLog.textContent = '';
    elements.taskState.textContent = '启动中';

    const response = await fetch(path, { method: 'POST' });
    const payload = await response.json();
    if (!response.ok || !payload.ok) {
      throw new Error(payload.error || `任务启动失败：${response.status}`);
    }

    elements.taskState.textContent = `运行中：${actionLabel(payload.action)}`;
    const source = new EventSource(payload.eventsUrl);
    state.currentSource = source;

    source.addEventListener('state', (event) => {
      const snapshot = JSON.parse(event.data);
      elements.taskState.textContent = `${actionLabel(snapshot.action)} / ${snapshot.state}`;
    });

    source.addEventListener('log', (event) => {
      const item = JSON.parse(event.data);
      appendLog(`[${item.time}] ${item.stream}: ${item.message}`);
    });

    source.addEventListener('done', async (event) => {
      const snapshot = JSON.parse(event.data);
      elements.taskState.textContent = `${actionLabel(snapshot.action)} / ${snapshot.state}`;
      source.close();
      state.currentSource = null;
      setBusy(false);
      await refreshStatus();
    });

    source.onerror = () => {
      if (state.currentSource === source) {
        elements.taskState.textContent = '连接中断';
        source.close();
        state.currentSource = null;
        setBusy(false);
      }
    };
  }

  async function run(actionPath) {
    try {
      await startTask(actionPath);
    } catch (error) {
      appendLog(`error: ${error.message}`);
      elements.taskState.textContent = '失败';
      setBusy(false);
    }
  }

  elements.refreshStatus.addEventListener('click', () => {
    refreshStatus().catch((error) => {
      appendLog(`error: ${error.message}`);
    });
  });
  elements.loginButton.addEventListener('click', () => run('/api/auth/login'));
  elements.refreshButton.addEventListener('click', () => run('/api/auth/refresh'));
  elements.setupGitButton.addEventListener('click', () => run('/api/auth/setup-git'));
  elements.logoutButton.addEventListener('click', () => run('/api/auth/logout'));

  refreshStatus().catch((error) => {
    elements.overallState.textContent = '检查失败';
    appendLog(`error: ${error.message}`);
  });
})();
