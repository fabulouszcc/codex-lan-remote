import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { run, readJson } from './config.mjs';
const script = fileURLToPath(new URL('../scripts/configure-desktop-autostart.ps1', import.meta.url));
async function invoke(root, flags) {
  await run(join(process.env.SystemRoot || 'C:/Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe'),
    ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script, '-DataDirectory', root, ...flags],
    {windowsHide:true, timeout:20000, maxBuffer:65536});
}
export async function configureAutoStart(root) {
  if (process.platform !== 'win32') return;
  try { await invoke(root, []); }
  catch { throw Error('配置已保存，但 Windows 自动恢复任务未能更新；请在设置中请求管理员启动后重新保存'); }
}
export async function startBackgroundTask(root) {
  if (process.platform !== 'win32') return false;
  const state = await readJson(join(root, 'autostart.local.json'), null);
  if (state?.kind !== 'windows-task' || state.configured !== true) return false;
  try { await invoke(root, ['-StartOnly']); return true; } catch { return false; }
}
