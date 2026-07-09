'use strict';

const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');

const {
  DEFAULT_HOST,
  DEFAULT_PORT,
  GITHUB_HOST,
  buildGhCommandSequence,
  collectStatus,
  commandToDisplay,
  redactSensitive
} = require('./github-helper');

const ROOT_DIR = path.resolve(__dirname, '..');
const PUBLIC_DIR = path.join(ROOT_DIR, 'public');
const TASK_RETENTION_MS = 30 * 60 * 1000;

const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.svg': 'image/svg+xml'
};

const tasks = new Map();

function nowIso() {
  return new Date().toISOString();
}

function sendJson(response, statusCode, body) {
  const payload = JSON.stringify(body);
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(payload)
  });
  response.end(payload);
}

function readRequestBody(request) {
  return new Promise((resolve, reject) => {
    let body = '';
    request.on('data', (chunk) => {
      body += chunk.toString('utf8');
      if (body.length > 1024 * 64) {
        reject(new Error('Request body is too large.'));
        request.destroy();
      }
    });
    request.on('end', () => resolve(body));
    request.on('error', reject);
  });
}

function sendSse(response, event, payload, id) {
  response.write(`event: ${event}\n`);
  if (id !== undefined && id !== null) {
    response.write(`id: ${id}\n`);
  }
  response.write(`data: ${JSON.stringify(payload)}\n\n`);
}

function taskSnapshot(task) {
  return {
    id: task.id,
    action: task.action,
    state: task.state,
    exitCode: task.exitCode,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
    command: task.commandDisplay
  };
}

function appendTaskEvent(task, stream, message) {
  const event = {
    id: task.nextEventId++,
    time: nowIso(),
    stream,
    message: redactSensitive(message)
  };
  task.updatedAt = event.time;
  task.events.push(event);

  for (const client of task.clients) {
    client('log', event, event.id);
  }
}

function finishTask(task, state, exitCode) {
  task.state = state;
  task.exitCode = exitCode;
  task.updatedAt = nowIso();
  const snapshot = taskSnapshot(task);

  for (const client of task.clients) {
    client('state', snapshot);
    client('done', snapshot);
  }

  setTimeout(() => {
    for (const client of task.clients) {
      client('close', snapshot);
    }
  }, 50);

  setTimeout(() => {
    tasks.delete(task.id);
  }, TASK_RETENTION_MS).unref();
}

function maybeRespondToPrompt(task, child, chunkText) {
  if (!task.promptResponses || task.promptResponses.length === 0) {
    return;
  }

  for (const response of task.promptResponses) {
    if (task.answeredPrompts.has(response.id)) {
      continue;
    }
    if (response.pattern.test(chunkText)) {
      task.answeredPrompts.add(response.id);
      child.stdin.write(response.text);
      appendTaskEvent(task, 'system', '已向 gh 发送所需的交互确认。');
    }
  }
}

function createTask(action) {
  const commands = buildGhCommandSequence(action);
  const task = {
    id: crypto.randomUUID(),
    action,
    state: 'running',
    exitCode: null,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    commandDisplay: commands.map(commandToDisplay).join(' && '),
    promptResponses: [],
    answeredPrompts: new Set(),
    nextEventId: 1,
    events: [],
    clients: new Set()
  };

  tasks.set(task.id, task);
  runTaskCommands(task, commands).catch((error) => {
    appendTaskEvent(task, 'error', error.message);
    finishTask(task, 'failed', null);
  });

  return task;
}

function runTaskCommand(task, command, index, total) {
  return new Promise((resolve) => {
    task.promptResponses = command.promptResponses;
    task.answeredPrompts = new Set();

    const label = total > 1 ? `步骤 ${index + 1}/${total}` : '开始执行';
    appendTaskEvent(task, 'system', `${label}：${commandToDisplay(command)}`);

    let settled = false;
    const child = spawn(command.executable, command.args, {
      cwd: process.env.HOME || process.cwd(),
      env: {
        ...process.env,
        GH_NO_UPDATE_NOTIFIER: '1'
      },
      stdio: ['pipe', 'pipe', 'pipe']
    });

    const onOutput = (stream) => (chunk) => {
      const text = chunk.toString('utf8');
      appendTaskEvent(task, stream, text);
      maybeRespondToPrompt(task, child, text);
    };

    child.stdout.on('data', onOutput('stdout'));
    child.stderr.on('data', onOutput('stderr'));

    child.on('error', (error) => {
      if (settled) {
        return;
      }
      settled = true;
      appendTaskEvent(task, 'error', error.message);
      resolve({ exitCode: null, ok: false });
    });

    child.on('close', (exitCode) => {
      if (settled) {
        return;
      }
      settled = true;
      appendTaskEvent(task, 'system', `命令结束，退出码：${exitCode}`);
      resolve({ exitCode, ok: exitCode === 0 });
    });
  });
}

async function runTaskCommands(task, commands) {
  appendTaskEvent(task, 'system', `开始执行：${task.commandDisplay}`);

  for (let index = 0; index < commands.length; index += 1) {
    const result = await runTaskCommand(task, commands[index], index, commands.length);
    if (!result.ok) {
      finishTask(task, 'failed', result.exitCode);
      return;
    }
  }

  finishTask(task, 'completed', 0);
}

function hasRunningAuthTask() {
  for (const task of tasks.values()) {
    if (task.state === 'running') {
      return true;
    }
  }
  return false;
}

function streamTask(request, response, taskId) {
  const task = tasks.get(taskId);
  if (!task) {
    sendJson(response, 404, { ok: false, error: 'Task not found.' });
    return;
  }

  response.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  });

  const send = (event, payload, id) => sendSse(response, event, payload, id);
  send('state', taskSnapshot(task));
  for (const event of task.events) {
    send('log', event, event.id);
  }

  if (task.state !== 'running') {
    send('done', taskSnapshot(task));
    response.end();
    return;
  }

  const keepAlive = setInterval(() => {
    send('ping', { time: nowIso() });
  }, 15000);

  const client = (event, payload, id) => {
    if (event === 'close') {
      clearInterval(keepAlive);
      response.end();
      task.clients.delete(client);
      return;
    }
    send(event, payload, id);
  };

  task.clients.add(client);
  request.on('close', () => {
    clearInterval(keepAlive);
    task.clients.delete(client);
  });
}

function serveStatic(request, response, url) {
  const requestPath = url.pathname === '/' ? '/index.html' : url.pathname;
  const decodedPath = decodeURIComponent(requestPath);
  const filePath = path.resolve(PUBLIC_DIR, `.${decodedPath}`);

  if (!filePath.startsWith(`${PUBLIC_DIR}${path.sep}`)) {
    sendJson(response, 403, { ok: false, error: 'Forbidden.' });
    return;
  }

  fs.readFile(filePath, (error, content) => {
    if (error) {
      sendJson(response, error.code === 'ENOENT' ? 404 : 500, {
        ok: false,
        error: error.code === 'ENOENT' ? 'Not found.' : 'Failed to read static asset.'
      });
      return;
    }

    const ext = path.extname(filePath);
    response.writeHead(200, {
      'Content-Type': mimeTypes[ext] || 'application/octet-stream',
      'Cache-Control': 'no-store'
    });
    response.end(content);
  });
}

async function handleApi(request, response, url) {
  if (request.method === 'GET' && url.pathname === '/health') {
    sendJson(response, 200, {
      ok: true,
      name: 'github-config-helper',
      host: GITHUB_HOST,
      time: nowIso()
    });
    return;
  }

  if (request.method === 'GET' && url.pathname === '/api/status') {
    const status = await collectStatus();
    sendJson(response, 200, status);
    return;
  }

  const taskMatch = url.pathname.match(/^\/api\/tasks\/([a-f0-9-]+)\/events$/i);
  if (request.method === 'GET' && taskMatch) {
    streamTask(request, response, taskMatch[1]);
    return;
  }

  const authActionByPath = {
    '/api/auth/login': 'connect',
    '/api/auth/refresh': 'refresh',
    '/api/auth/setup-git': 'setupGit',
    '/api/auth/logout': 'logout'
  };

  if (request.method === 'POST' && authActionByPath[url.pathname]) {
    if (hasRunningAuthTask()) {
      sendJson(response, 409, { ok: false, error: 'Another GitHub auth task is already running.' });
      return;
    }

    await readRequestBody(request);
    const task = createTask(authActionByPath[url.pathname]);
    sendJson(response, 202, {
      ok: true,
      taskId: task.id,
      action: task.action,
      eventsUrl: `/api/tasks/${task.id}/events`
    });
    return;
  }

  sendJson(response, 404, { ok: false, error: 'Unknown API endpoint.' });
}

function createServer() {
  return http.createServer(async (request, response) => {
    const url = new URL(request.url, `http://${request.headers.host || `${DEFAULT_HOST}:${DEFAULT_PORT}`}`);

    try {
      if (url.pathname === '/health' || url.pathname.startsWith('/api/')) {
        await handleApi(request, response, url);
        return;
      }

      if (request.method !== 'GET' && request.method !== 'HEAD') {
        sendJson(response, 405, { ok: false, error: 'Method not allowed.' });
        return;
      }

      serveStatic(request, response, url);
    } catch (error) {
      sendJson(response, 500, { ok: false, error: redactSensitive(error.message) });
    }
  });
}

if (require.main === module) {
  const server = createServer();
  server.listen(DEFAULT_PORT, DEFAULT_HOST, () => {
    const address = server.address();
    console.log(`github-config-helper listening on http://${address.address}:${address.port}/`);
  });
}

module.exports = {
  createServer,
  createTask,
  hasRunningAuthTask,
  tasks
};
