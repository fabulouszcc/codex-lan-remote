import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdir, rm, rmdir, stat, readFile, writeFile } from 'node:fs/promises';
import { createManager } from './manager.mjs';
import { atomicJson, protectDirectory, readJson } from './config.mjs';
import { dataRoot } from './client.mjs';
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(process.argv.includes('--data') ? process.argv[process.argv.indexOf('--data') + 1] : dataRoot());
await protectDirectory(root);
const lock = join(root, 'manager.lock');
try { await mkdir(lock); }
catch (error) {
  if (error.code !== 'EEXIST') throw error;
  const owner = Number(await readFile(join(lock, 'pid'), 'utf8').catch(() => '0'));
  if (!owner && Date.now() - (await stat(lock)).mtimeMs < 30000) process.exit(0);
  if (owner > 0) { try { process.kill(owner, 0); process.exit(0); } catch (failed) { if (failed.code !== 'ESRCH') process.exit(0); } }
  // Only this fixed lock directory, never a configurable recursive target.
  await rm(join(lock, 'pid'), { force: true }); await rmdir(lock); await mkdir(lock);
}
await writeFile(join(lock, 'pid'), String(process.pid), { mode: 0o600 });
let manager;
try {
  manager = await createManager({ root, assets: join(here, 'web'), binDir: join(here, '..', 'vendor', 'easytier'), port: Number(process.env.CODEX_LAN_MANAGER_PORT || 19547), onShutdown: close });
  await atomicJson(join(root, 'manager.local.json'), { origin: new URL(manager.url).origin, token: manager.token, pid: process.pid });
  const config = await readJson(join(root, 'config.local.json'), null);
  if (config?.autoStart) await manager.start().catch(() => {});
  console.error('Codex 手机连接后台已启动（仅本机设置入口）');
} catch (error) {
  await rm(join(lock, 'pid'), { force: true }); await rmdir(lock);
  console.error('Codex 手机连接后台启动失败：' + error.message); process.exit(1);
}
let closing = false;
async function close() {
  if (closing) return; closing = true;
  await manager.close(); await rm(join(root, 'manager.local.json'), { force: true });
  await rm(join(lock, 'pid'), { force: true }); await rmdir(lock); process.exit(0);
}
process.on('SIGINT', close); process.on('SIGTERM', close);
