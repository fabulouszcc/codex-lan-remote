import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { readJson } from './config.mjs';
import { fileURLToPath } from 'node:url';
import { startBackgroundTask } from './autostart.mjs';
export function dataRoot() {
  if (process.env.CODEX_LAN_DATA_DIR) return process.env.CODEX_LAN_DATA_DIR;
  if (!process.env.LOCALAPPDATA) throw Error('此插件需要 Windows 本地执行环境');
  return join(process.env.LOCALAPPDATA, 'CodexLAN');
}
export async function adminCall(endpoint, path, body, timeoutMs = 30000) {
  const base = new URL(endpoint.origin);
  if (base.protocol !== 'http:' || base.hostname !== '127.0.0.1' || base.username || base.password || base.pathname !== '/') throw Error('本机管理地址无效');
  const response = await fetch(new URL(path, base), { method: body === undefined ? 'GET' : 'POST',
    headers: { authorization: 'Bearer ' + endpoint.token, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(timeoutMs), redirect: 'error' });
  const result = await response.json(); if (!response.ok) throw Error(result.error || '本机连接设置不可用'); return result;
}
const starting = new Map();
export function ensureManager(root = dataRoot()) {
  if (starting.has(root)) return starting.get(root);
  const pending = findOrStartManager(root).finally(() => starting.delete(root));
  starting.set(root, pending); return pending;
}
async function findOrStartManager(root) {
  const path = join(root, 'manager.local.json');
  let endpoint = await readJson(path, null);
  const available = endpoint => endpoint && adminCall(endpoint, '/api/status', undefined, 1500).then(value => value.kind === 'codex-lan-manager', () => false);
  if (await available(endpoint)) return endpoint;
  if (!await startBackgroundTask(root)) {
    const child = spawn(process.execPath, [fileURLToPath(new URL('./daemon.mjs', import.meta.url)), '--data', root], { detached: true, windowsHide: true, stdio: 'ignore', env: process.env });
    child.on('error', () => {}); child.unref();
  }
  for (let i = 0; i < 80; i++) {
    await new Promise(resolve => setTimeout(resolve, 250)); endpoint = await readJson(path, null);
    if (await available(endpoint)) return endpoint;
  }
  throw Error('后台启动失败，请检查管理端口占用或用启动脚本查看错误');
}
export async function restoreAutoStart(root = dataRoot()) {
  const config = await readJson(join(root, 'config.local.json'), null);
  // Initialization is not permission to enable a previously disabled service.
  if (config?.autoStart !== true) return null;
  return ensureManager(root);
}
