'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const GITHUB_HOST = 'github.com';
const DEFAULT_HOST = process.env.GITHUB_CONFIG_HELPER_HOST || '127.0.0.1';
const DEFAULT_PORT = Number(process.env.GITHUB_CONFIG_HELPER_PORT || process.env.PORT || 23120);
const COMMAND_TIMEOUT_MS = 8000;

const SCOPES = [
  'repo',
  'workflow',
  'delete_repo',
  'admin:org',
  'admin:public_key',
  'admin:repo_hook',
  'admin:org_hook',
  'admin:gpg_key',
  'notifications',
  'user',
  'project',
  'read:packages',
  'write:packages',
  'delete:packages',
  'codespace',
  'security_events',
  'read:audit_log',
  'gist'
].join(',');

const GH_COMMANDS = Object.freeze({
  login: Object.freeze({
    executable: 'gh',
    args: Object.freeze([
      'auth',
      'login',
      '--hostname',
      GITHUB_HOST,
      '--web',
      '--git-protocol',
      'https',
      '--scopes',
      SCOPES
    ]),
    promptResponses: Object.freeze([
      Object.freeze({ id: 'open-browser', pattern: /press enter|open .*browser|continue in your browser/i, text: '\n' })
    ])
  }),
  refresh: Object.freeze({
    executable: 'gh',
    args: Object.freeze(['auth', 'refresh', '--hostname', GITHUB_HOST, '--scopes', SCOPES]),
    promptResponses: Object.freeze([
      Object.freeze({ id: 'open-browser', pattern: /press enter|open .*browser|continue in your browser/i, text: '\n' })
    ])
  }),
  setupGit: Object.freeze({
    executable: 'gh',
    args: Object.freeze(['auth', 'setup-git', '--hostname', GITHUB_HOST]),
    promptResponses: Object.freeze([])
  }),
  logout: Object.freeze({
    executable: 'gh',
    args: Object.freeze(['auth', 'logout', '--hostname', GITHUB_HOST]),
    promptResponses: Object.freeze([
      Object.freeze({ id: 'confirm-logout', pattern: /are you sure|log out|logout|y\/n/i, text: 'y\n' })
    ])
  })
});

const GH_COMMAND_SEQUENCES = Object.freeze({
  connect: Object.freeze(['login', 'setupGit'])
});

function buildGhCommand(action) {
  const command = GH_COMMANDS[action];
  if (!command) {
    throw new Error(`Unsupported GitHub helper action: ${action}`);
  }

  return {
    executable: command.executable,
    args: Array.from(command.args),
    promptResponses: command.promptResponses.map((response) => ({ ...response }))
  };
}

function buildGhCommandSequence(action) {
  const sequence = GH_COMMAND_SEQUENCES[action] || [action];
  return sequence.map(buildGhCommand);
}

function quoteForDisplay(value) {
  if (/^[A-Za-z0-9_@%+=:,./-]+$/.test(value)) {
    return value;
  }
  return `'${String(value).replace(/'/g, "'\\''")}'`;
}

function commandToDisplay(command) {
  return [command.executable].concat(command.args).map(quoteForDisplay).join(' ');
}

function redactSensitive(input) {
  if (input === null || input === undefined) {
    return '';
  }

  let text = String(input);

  const replacements = [
    [/\bgh[opsru]_[A-Za-z0-9_]{16,}\b/g, '[REDACTED_TOKEN]'],
    [/\bgithub_pat_[A-Za-z0-9_]{20,}\b/g, '[REDACTED_TOKEN]'],
    [/\b(?:GITHUB_TOKEN|GH_TOKEN|GH_ENTERPRISE_TOKEN|NPM_TOKEN|ACCESS_TOKEN|REFRESH_TOKEN)=[^\s]+/gi, (match) => `${match.split('=')[0]}=[REDACTED]`],
    [/(\bAuthorization\s*:\s*)(?:Bearer|token|Basic)?\s*[^\r\n]+/gi, '$1[REDACTED]'],
    [/(\bCookie\s*:\s*)[^\r\n]+/gi, '$1[REDACTED]'],
    [/(\bSet-Cookie\s*:\s*)[^\r\n]+/gi, '$1[REDACTED]'],
    [/((?:password|passwd|secret|client_secret|access_token|refresh_token|auth_token|token)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s&]+)/gi, '$1[REDACTED]'],
    [/("(?:password|passwd|secret|client_secret|access_token|refresh_token|auth_token|token|authorization|cookie)"\s*:\s*)"[^"]*"/gi, '$1"[REDACTED]"']
  ];

  for (const [pattern, replacement] of replacements) {
    text = text.replace(pattern, replacement);
  }

  return text;
}

function splitScopes(scopeText) {
  if (!scopeText) {
    return [];
  }

  return scopeText
    .split(',')
    .map((scope) => scope.trim().replace(/^['"]|['"]$/g, ''))
    .filter(Boolean)
    .filter((scope) => !/none|unknown/i.test(scope));
}

function parseGhAuthStatus(output, exitCode) {
  const safeOutput = redactSensitive(output);
  const authenticated = exitCode === 0 && /logged in to|active account:\s*true|token scopes:/i.test(safeOutput);
  const accountMatch = safeOutput.match(/logged in to\s+\S+\s+account\s+([^\s(]+)/i);
  const activeAccountMatch = safeOutput.match(/active account:\s*([^\s]+)/i);
  const protocolMatch = safeOutput.match(/git operations protocol:\s*([^\s]+)/i);
  const scopesMatch = safeOutput.match(/token scopes:\s*(.+)$/im);
  const scopes = splitScopes(scopesMatch ? scopesMatch[1] : '');
  const requiredScopes = SCOPES.split(',');
  const scopeSet = new Set(scopes);

  return {
    authenticated,
    account: accountMatch ? accountMatch[1] : null,
    activeAccount: activeAccountMatch ? activeAccountMatch[1] : null,
    gitProtocol: protocolMatch ? protocolMatch[1] : null,
    scopes,
    missingScopes: scopes.length > 0 ? requiredScopes.filter((scope) => !scopeSet.has(scope)) : requiredScopes,
    statusText: safeOutput.trim(),
    exitCode
  };
}

function parseGitCredentialHelpers(output) {
  const safeOutput = redactSensitive(output);
  const entries = safeOutput
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  const configured = entries.some((line) => /gh\s+auth\s+git-credential/i.test(line));

  return {
    configured,
    entries
  };
}

function findExecutable(name, env = process.env) {
  const pathValue = env.PATH || '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin';
  const extensions = process.platform === 'win32' ? ['', '.exe', '.cmd', '.bat'] : [''];

  for (const directory of pathValue.split(path.delimiter)) {
    if (!directory) {
      continue;
    }

    for (const extension of extensions) {
      const candidate = path.join(directory, `${name}${extension}`);
      try {
        fs.accessSync(candidate, fs.constants.X_OK);
        return candidate;
      } catch (_error) {
        // Keep scanning PATH.
      }
    }
  }

  return null;
}

function runCommand(executable, args, options = {}) {
  const timeoutMs = options.timeoutMs || COMMAND_TIMEOUT_MS;

  return new Promise((resolve) => {
    let stdout = '';
    let stderr = '';
    let settled = false;
    let timedOut = false;

    const child = spawn(executable, args, {
      cwd: options.cwd || process.env.HOME || process.cwd(),
      env: { ...process.env, GH_NO_UPDATE_NOTIFIER: '1', ...(options.env || {}) },
      stdio: ['ignore', 'pipe', 'pipe']
    });

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGTERM');
    }, timeoutMs);

    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString('utf8');
    });

    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString('utf8');
    });

    child.on('error', (error) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      resolve({
        exitCode: null,
        stdout: redactSensitive(stdout),
        stderr: redactSensitive(stderr),
        error: redactSensitive(error.message),
        timedOut
      });
    });

    child.on('close', (exitCode) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      resolve({
        exitCode,
        stdout: redactSensitive(stdout),
        stderr: redactSensitive(stderr),
        error: null,
        timedOut
      });
    });
  });
}

async function collectStatus() {
  const tools = {};
  for (const name of ['git', 'gh', 'codex', 'claude']) {
    const executablePath = findExecutable(name);
    tools[name] = {
      installed: Boolean(executablePath),
      path: executablePath
    };
  }

  let github = {
    authenticated: false,
    account: null,
    activeAccount: null,
    gitProtocol: null,
    scopes: [],
    missingScopes: SCOPES.split(','),
    statusText: tools.gh.installed ? 'gh auth status has not run.' : 'gh is not installed.',
    exitCode: tools.gh.installed ? null : 127
  };

  if (tools.gh.installed) {
    const result = await runCommand('gh', ['auth', 'status', '--hostname', GITHUB_HOST], { timeoutMs: COMMAND_TIMEOUT_MS });
    github = parseGhAuthStatus(`${result.stdout}\n${result.stderr}\n${result.error || ''}`, result.exitCode);
    github.timedOut = result.timedOut;
  }

  let gitCredentialHelper = {
    configured: false,
    entries: [],
    statusText: tools.git.installed ? 'git credential helper has not been checked.' : 'git is not installed.'
  };

  if (tools.git.installed) {
    const result = await runCommand('git', ['config', '--global', '--get-regexp', '^credential'], {
      timeoutMs: COMMAND_TIMEOUT_MS
    });
    const parsed = parseGitCredentialHelpers(`${result.stdout}\n${result.stderr}\n${result.error || ''}`);
    gitCredentialHelper = {
      ...parsed,
      statusText: redactSensitive(`${result.stdout}\n${result.stderr}\n${result.error || ''}`).trim(),
      exitCode: result.exitCode,
      timedOut: result.timedOut
    };
  }

  return {
    ok: true,
    host: GITHUB_HOST,
    requiredScopes: SCOPES.split(','),
    home: process.env.HOME || null,
    tools,
    github,
    gitCredentialHelper
  };
}

module.exports = {
  GITHUB_HOST,
  DEFAULT_HOST,
  DEFAULT_PORT,
  SCOPES,
  buildGhCommand,
  buildGhCommandSequence,
  collectStatus,
  commandToDisplay,
  findExecutable,
  parseGhAuthStatus,
  parseGitCredentialHelpers,
  redactSensitive,
  runCommand,
  splitScopes
};
