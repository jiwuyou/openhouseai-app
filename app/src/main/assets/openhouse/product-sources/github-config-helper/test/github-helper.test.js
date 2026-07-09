'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const {
  SCOPES,
  buildGhCommand,
  buildGhCommandSequence,
  commandToDisplay,
  parseGhAuthStatus,
  parseGitCredentialHelpers,
  redactSensitive
} = require('../src/github-helper');

test('buildGhCommand returns fixed allowlisted login command', () => {
  const command = buildGhCommand('login');

  assert.equal(command.executable, 'gh');
  assert.deepEqual(command.args, [
    'auth',
    'login',
    '--hostname',
    'github.com',
    '--web',
    '--git-protocol',
    'https',
    '--scopes',
    SCOPES
  ]);
  assert.match(commandToDisplay(command), /^gh auth login --hostname github\.com --web/);
});

test('buildGhCommand returns fixed allowlisted maintenance commands', () => {
  assert.deepEqual(buildGhCommand('refresh').args, ['auth', 'refresh', '--hostname', 'github.com', '--scopes', SCOPES]);
  assert.deepEqual(buildGhCommand('setupGit').args, ['auth', 'setup-git', '--hostname', 'github.com']);
  assert.deepEqual(buildGhCommand('logout').args, ['auth', 'logout', '--hostname', 'github.com']);
  assert.throws(() => buildGhCommand('shell'), /Unsupported GitHub helper action/);
});

test('buildGhCommandSequence returns fixed connect command sequence', () => {
  const commands = buildGhCommandSequence('connect');

  assert.equal(commands.length, 2);
  assert.deepEqual(commands[0].args, [
    'auth',
    'login',
    '--hostname',
    'github.com',
    '--web',
    '--git-protocol',
    'https',
    '--scopes',
    SCOPES
  ]);
  assert.deepEqual(commands[1].args, ['auth', 'setup-git', '--hostname', 'github.com']);
  assert.throws(() => buildGhCommandSequence('shell'), /Unsupported GitHub helper action/);
});

test('redactSensitive removes tokens and secret-bearing headers', () => {
  const raw = [
    'Authorization: Bearer gho_abcdefghijklmnopqrstuvwxyz123456',
    'Cookie: session=secret',
    'GITHUB_TOKEN=github_pat_abcdefghijklmnopqrstuvwxyz1234567890',
    '{"password":"hunter2","token":"ghs_abcdefghijklmnopqrstuvwxyz123456"}',
    'client_secret=plainsecret'
  ].join('\n');

  const safe = redactSensitive(raw);

  assert.doesNotMatch(safe, /gho_abcdefghijklmnopqrstuvwxyz/i);
  assert.doesNotMatch(safe, /github_pat_abcdefghijklmnopqrstuvwxyz/i);
  assert.doesNotMatch(safe, /hunter2/);
  assert.doesNotMatch(safe, /plainsecret/);
  assert.match(safe, /\[REDACTED/);
});

test('parseGhAuthStatus extracts account, protocol, scopes, and missing scopes', () => {
  const sample = `
github.com
  ✓ Logged in to github.com account octocat (/home/user/.config/gh/hosts.yml)
  - Active account: true
  - Git operations protocol: https
  - Token: gho_abcdefghijklmnopqrstuvwxyz123456
  - Token scopes: 'repo', 'workflow', 'user'
`;

  const parsed = parseGhAuthStatus(sample, 0);

  assert.equal(parsed.authenticated, true);
  assert.equal(parsed.account, 'octocat');
  assert.equal(parsed.gitProtocol, 'https');
  assert.deepEqual(parsed.scopes, ['repo', 'workflow', 'user']);
  assert.equal(parsed.missingScopes.includes('delete_repo'), true);
  assert.doesNotMatch(parsed.statusText, /gho_abcdefghijklmnopqrstuvwxyz/i);
});

test('parseGitCredentialHelpers detects gh credential helper', () => {
  const parsed = parseGitCredentialHelpers(
    'credential.https://github.com.helper=!/usr/bin/gh auth git-credential\ncredential.helper=cache'
  );

  assert.equal(parsed.configured, true);
  assert.equal(parsed.entries.length, 2);
});
