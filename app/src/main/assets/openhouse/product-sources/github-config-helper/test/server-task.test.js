'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { createServer, hasRunningAuthTask, tasks } = require('../src/server');

function makeFakeGh() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'github-helper-fake-gh-'));
  const ghPath = path.join(dir, 'gh');
  fs.writeFileSync(
    ghPath,
    [
      '#!/usr/bin/env sh',
      'if [ -n "${FAKE_GH_LOG:-}" ]; then',
      '  printf "%s\\n" "$*" >> "$FAKE_GH_LOG"',
      'fi',
      'case "$1 $2" in',
      '  "auth status")',
      '    printf "%s\\n" "github.com"',
      '    printf "%s\\n" "  - Active account: false"',
      '    exit 1',
      '    ;;',
      '  "auth setup-git"|"auth login"|"auth refresh"|"auth logout")',
      '    sleep "${FAKE_GH_DELAY:-0}"',
      '    printf "%s\\n" "fake gh completed"',
      '    exit 0',
      '    ;;',
      'esac',
      'printf "%s\\n" "unexpected fake gh command: $*" >&2',
      'exit 2',
      ''
    ].join('\n'),
    { mode: 0o700 }
  );
  return dir;
}

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => (error ? reject(error) : resolve()));
  });
}

function requestJson(port, method, requestPath) {
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        host: '127.0.0.1',
        port,
        path: requestPath,
        method
      },
      (response) => {
        let raw = '';
        response.setEncoding('utf8');
        response.on('data', (chunk) => {
          raw += chunk;
        });
        response.on('end', () => {
          resolve({
            statusCode: response.statusCode,
            body: raw,
            json: raw ? JSON.parse(raw) : null
          });
        });
      }
    );

    request.on('error', reject);
    request.end();
  });
}

async function waitForNoRunningTask() {
  const started = Date.now();
  while (hasRunningAuthTask()) {
    if (Date.now() - started > 3000) {
      throw new Error('timed out waiting for fake auth task');
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
}

async function withServer(fn) {
  const originalPath = process.env.PATH;
  const originalDelay = process.env.FAKE_GH_DELAY;
  const originalLog = process.env.FAKE_GH_LOG;
  const fakeBin = makeFakeGh();
  process.env.PATH = `${fakeBin}${path.delimiter}${originalPath || ''}`;
  delete process.env.FAKE_GH_DELAY;
  delete process.env.FAKE_GH_LOG;
  tasks.clear();

  const server = createServer();
  const port = await listen(server);

  try {
    return await fn(port);
  } finally {
    await waitForNoRunningTask();
    tasks.clear();
    await close(server);
    process.env.PATH = originalPath;
    if (originalDelay === undefined) {
      delete process.env.FAKE_GH_DELAY;
    } else {
      process.env.FAKE_GH_DELAY = originalDelay;
    }
    if (originalLog === undefined) {
      delete process.env.FAKE_GH_LOG;
    } else {
      process.env.FAKE_GH_LOG = originalLog;
    }
    fs.rmSync(fakeBin, { recursive: true, force: true });
  }
}

test('main auth mutation runs login then setup-git in order', async () => {
  await withServer(async (port) => {
    const logPath = path.join(os.tmpdir(), `github-helper-gh-${process.pid}-${Date.now()}.log`);
    process.env.FAKE_GH_LOG = logPath;

    const response = await requestJson(port, 'POST', '/api/auth/login');

    assert.equal(response.statusCode, 202);
    assert.equal(response.json.ok, true);
    assert.equal(response.json.action, 'connect');
    assert.match(response.json.eventsUrl, /^\/api\/tasks\/[a-f0-9-]+\/events$/i);

    await waitForNoRunningTask();
    const commands = fs.readFileSync(logPath, 'utf8').trim().split(/\r?\n/);
    fs.rmSync(logPath, { force: true });

    assert.equal(commands.length, 2);
    assert.match(commands[0], /^auth login --hostname github\.com --web --git-protocol https --scopes /);
    assert.equal(commands[1], 'auth setup-git --hostname github.com');
  });
});

test('setup-git repair mutation still runs as a single allowlisted task', async () => {
  await withServer(async (port) => {
    const response = await requestJson(port, 'POST', '/api/auth/setup-git');

    assert.equal(response.statusCode, 202);
    assert.equal(response.json.ok, true);
    assert.equal(response.json.action, 'setupGit');
  });
});

test('auth mutations return 409 while another auth task is running', async () => {
  await withServer(async (port) => {
    process.env.FAKE_GH_DELAY = '0.5';

    const first = await requestJson(port, 'POST', '/api/auth/login');
    assert.equal(first.statusCode, 202);

    const second = await requestJson(port, 'POST', '/api/auth/setup-git');
    assert.equal(second.statusCode, 409);
    assert.match(second.json.error, /already running/);
  });
});
