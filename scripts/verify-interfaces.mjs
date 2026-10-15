// Dependency-free, local interface probe. Does not send model turns or execute project commands.
import { spawn, execFile } from 'node:child_process';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const exec = promisify(execFile);
const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i];
  if (!['--codex', '--easytier-cli', '--rpc', '--read-thread', '--resume-thread', '--schema', '--out'].includes(key) || !process.argv[i + 1]) {
    throw new Error('Usage: node scripts/verify-interfaces.mjs --codex <absolute exe> [--easytier-cli <exe>] [--read-thread <id>] [--resume-thread <idle id>] [--schema <generated schema directory>] [--out <json>]');
  }
  options[key.slice(2)] = process.argv[i + 1];
}
if (!options.codex) throw new Error('--codex must identify the desktop bundled executable.');

function failure(error) {
  const message = String(error.message || '');
  return {
    ok: false,
    code: error.code ?? null,
    reason: message.includes('active writer') ? 'active_writer_conflict'
      : message.includes('timeout') ? 'timeout'
      : message.includes('thread not found') ? 'thread_not_found'
      : 'request_failed',
  };
}

async function capture(file, args) {
  // Never log stdout/stderr here: EasyTier's JSON node response contains a network secret.
  const { stdout } = await exec(file, args, { windowsHide: true, timeout: 15000, maxBuffer: 4 * 1024 * 1024 });
  return stdout;
}

class Client {
  constructor(file) {
    this.child = spawn(file, ['app-server', '--listen', 'stdio://'], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.seq = 0;
    this.pending = new Map();
    this.notifications = new Set();
    this.lines = createInterface({ input: this.child.stdout });
    this.child.stderr.on('data', () => {});
    this.child.stdin.on('error', () => {});
    this.child.on('error', error => this.rejectAll(error));
    this.child.on('exit', () => this.rejectAll(new Error('app-server exited')));
    this.lines.on('line', line => {
      let message;
      try { message = JSON.parse(line); } catch { return; }
      if (message.method && message.id !== undefined) {
        // A probe never approves tools, permissions, or commands.
        this.child.stdin.write(JSON.stringify({ id: message.id, error: { code: -32601, message: 'Read-only verification client' } }) + '\n');
      } else if (message.id !== undefined) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        clearTimeout(pending.timer);
        message.error ? pending.reject(message.error) : pending.resolve(message.result);
      } else if (message.method) {
        this.notifications.add(message.method);
      }
    });
  }
  rejectAll(error) {
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.reject(error);
    }
    this.pending.clear();
  }
  request(method, params = {}) {
    return new Promise((resolveRequest, reject) => {
      const id = ++this.seq;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('request timeout'));
      }, 20000);
      this.pending.set(id, { resolve: resolveRequest, reject, timer });
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
    });
  }
  notify(method) {
    this.child.stdin.write(JSON.stringify({ method }) + '\n');
  }
  async close() {
    this.child.stdin.end();
    this.lines.close();
    if (this.child.exitCode !== null) return;
    await new Promise(resolveClose => {
      const timer = setTimeout(() => { this.child.kill(); resolveClose(); }, 1000);
      this.child.once('exit', () => { clearTimeout(timer); resolveClose(); });
    });
  }
}

const report = {
  checkedAt: new Date().toISOString(),
  platform: process.platform,
  scope: 'separate local app-server; no model generation, desktop reconfiguration or network changes',
  codex: {}, easytier: {},
};
report.codex.version = (await capture(options.codex, ['--version'])).trim();
const client = new Client(options.codex);
try {
  const init = await client.request('initialize', {
    clientInfo: { name: 'codex_lan_interface_probe', title: 'LAN Interface Probe', version: '0.1.0' },
    capabilities: { experimentalApi: true },
  });
  report.codex.initialize = { ok: true, server: init.userAgent };
  client.notify('initialized');
  const list = await client.request('thread/list', { limit: 100, sourceKinds: [] });
  report.codex.list = { ok: true, count: list.data?.length ?? 0, hasMore: !!list.nextCursor };
  if (options['read-thread']) {
    const value = await client.request('thread/read', { threadId: options['read-thread'], includeTurns: true });
    report.codex.read = {
      ok: true, sameThreadId: value.thread?.id === options['read-thread'],
      listed: list.data?.some(thread => thread.id === options['read-thread']),
      turnCount: value.thread?.turns?.length ?? 0, source: value.thread?.source,
      runtimeStatusInProbe: value.thread?.status?.type,
    };
  }
  const loaded = await client.request('thread/loaded/list');
  report.codex.loaded = { ok: true, count: loaded.data?.length ?? 0, includesReadThread: loaded.data?.includes(options['read-thread']) ?? false };
  // Explicit opt-in: resume only an idle target, with no overrides and no turn/start.
  // This can load configured MCP integrations; it is not a purely read-only call.
  if (options['resume-thread']) {
    const threadId = options['resume-thread'];
    try {
      const before = await client.request('thread/read', { threadId, includeTurns: true });
      const resumed = await client.request('thread/resume', { threadId, excludeTurns: true });
      const after = await client.request('thread/read', { threadId, includeTurns: true });
      report.codex.resume = {
        ok: true, sameThreadId: resumed.thread?.id === threadId,
        turnCountUnchanged: before.thread?.turns?.length === after.thread?.turns?.length,
        updatedAtUnchanged: before.thread?.updatedAt === after.thread?.updatedAt,
        generationRequested: false,
      };
      await client.request('thread/unsubscribe', { threadId });
    } catch (error) { report.codex.resume = failure(error); }
  }
  report.codex.notificationNames = [...client.notifications];
} catch (error) {
  report.codex.error = failure(error);
} finally { await client.close(); }

try {
  await capture(options.codex, ['app-server', 'daemon', 'version']);
  report.codex.defaultControlSocket = { ok: true };
} catch (error) {
  report.codex.defaultControlSocket = { ok: false, reason: 'default_control_socket_unreachable' };
}

if (options.schema) {
  const schema = JSON.parse(await readFile(resolve(options.schema, 'ClientRequest.json'), 'utf8'));
  const methods = new Set((schema.oneOf || schema.anyOf || []).flatMap(item => item.properties?.method?.enum || []));
  const selected = ['thread/list', 'thread/read', 'thread/resume', 'thread/loaded/list', 'turn/start', 'turn/steer', 'turn/interrupt'];
  report.codex.schemaMethods = Object.fromEntries(selected.map(method => [method, methods.has(method)]));
}

if (options['easytier-cli']) {
  const rpcArgs = ['-p', options.rpc || '127.0.0.1:15888', '-o', 'json'];
  try {
    const node = JSON.parse(await capture(options['easytier-cli'], [...rpcArgs, 'node']));
    const peers = JSON.parse(await capture(options['easytier-cli'], [...rpcArgs, 'peer']));
    report.easytier = {
      ok: true, version: node.version, virtualAddress: node.ipv4_addr,
      peerCountIncludingSelf: Array.isArray(peers) ? peers.length : null,
      statusFieldsWhitelisted: true,
    };
  } catch (error) { report.easytier = failure(error); }
}

if (options.out) {
  const path = resolve(options.out);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(report, null, 2) + '\n');
}
console.log(JSON.stringify(report, null, 2));
if (report.codex.error) process.exitCode = 1;
