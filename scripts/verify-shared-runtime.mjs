// Tests two local clients against ONE temporary runtime, not the running desktop runtime.
import { spawn } from 'node:child_process';
import net from 'node:net';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const [binary, output] = process.argv.slice(2);
if (!binary) throw new Error('Usage: node scripts/verify-shared-runtime.mjs <desktop codex.exe> [output.json]');
const reservation = net.createServer();
await new Promise((resolvePort, reject) => { reservation.on('error', reject); reservation.listen(0, '127.0.0.1', resolvePort); });
const port = reservation.address().port;
await new Promise(resolveClose => reservation.close(resolveClose));
const url = `ws://127.0.0.1:${port}`;
const child = spawn(binary, ['app-server', '--listen', url], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
child.stdout.on('data', () => {});
child.stderr.on('data', () => {});
let spawnError;
child.on('error', error => { spawnError = error; });
const sockets = [];

async function connect(name) {
  const ws = new WebSocket(url);
  sockets.push(ws);
  await new Promise((resolveOpen, reject) => {
    const timer = setTimeout(() => reject(new Error('WebSocket open timeout')), 5000);
    ws.addEventListener('open', () => { clearTimeout(timer); resolveOpen(); }, { once: true });
    ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('WebSocket open error')); }, { once: true });
  });
  let seq = 0;
  const pending = new Map();
  const events = new Set();
  ws.addEventListener('message', event => {
    const message = JSON.parse(String(event.data));
    if (message.method && message.id !== undefined) {
      ws.send(JSON.stringify({ id: message.id, error: { code: -32601, message: 'Verification client' } }));
    } else if (message.id !== undefined) {
      const item = pending.get(message.id);
      if (!item) return;
      pending.delete(message.id);
      clearTimeout(item.timer);
      message.error ? item.reject(message.error) : item.resolve(message.result);
    } else if (message.method) events.add(message.method);
  });
  const request = (method, params = {}) => new Promise((resolveRequest, reject) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('request timeout')); }, 20000);
    pending.set(id, { resolve: resolveRequest, reject, timer });
    ws.send(JSON.stringify({ id, method, params }));
  });
  await request('initialize', { clientInfo: { name, version: '0.1.0' }, capabilities: { experimentalApi: true } });
  ws.send(JSON.stringify({ method: 'initialized' }));
  return { request, events };
}

const report = {
  checkedAt: new Date().toISOString(),
  scope: 'two clients; single temporary loopback app-server; ephemeral thread only',
  generationRequested: false,
  desktopRuntimeAttached: false,
};
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (spawnError || child.exitCode !== null) throw new Error('temporary server exited');
    try { ready = (await fetch(`http://127.0.0.1:${port}/readyz`, { signal: AbortSignal.timeout(200) })).ok; } catch {}
    if (ready) break;
    await delay(50);
  }
  if (!ready) throw new Error('temporary server readiness timeout');
  const a = await connect('codex_lan_client_a');
  const b = await connect('codex_lan_client_b');
  const started = await a.request('thread/start', { cwd: process.cwd(), ephemeral: true });
  const threadId = started.thread.id;
  report.ephemeral = started.thread.ephemeral;
  const loaded = await b.request('thread/loaded/list');
  report.clientBSeesLoadedThreadFromA = loaded.data?.includes(threadId);
  const resumed = await b.request('thread/resume', { threadId, excludeTurns: true });
  report.clientBResumesSameThread = resumed.thread.id === threadId;
  report.runtimeStatus = resumed.thread.status?.type;
  report.clientAReceivedThreadEvent = a.events.has('thread/started');
  report.clientBReceivedThreadEvent = b.events.has('thread/started');
  await b.request('thread/unsubscribe', { threadId });
  await a.request('thread/unsubscribe', { threadId });
  report.ok = report.ephemeral && report.clientBSeesLoadedThreadFromA && report.clientBResumesSameThread;
} catch (error) {
  report.ok = false;
  report.errorCode = error.code ?? null;
  report.errorMessage = String(error.message || '').slice(0, 500);
  report.reason = String(error.message || '').includes('active writer') ? 'active_writer_conflict' : 'request_failed';
  process.exitCode = 1;
} finally {
  for (const socket of sockets) socket.close();
  child.kill();
}
if (output) {
  const path = resolve(output);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(report, null, 2) + '\n');
}
console.log(JSON.stringify(report, null, 2));
