import { stat, readdir } from 'node:fs/promises';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

async function currentStoreBinary(configured) {
  if (process.platform !== 'win32' || !configured) return null;
  const directory = path.basename(path.dirname(path.dirname(path.dirname(configured))));
  if (!/^OpenAI\.Codex_[\d.]+_(x64|arm64)__2p2nqsd0c76g0$/i.test(directory)
    || path.basename(configured).toLowerCase() !== 'codex.exe') return null;
  const script = "(Get-AppxPackage -Name 'OpenAI.Codex' | Where-Object PackageFamilyName -eq 'OpenAI.Codex_2p2nqsd0c76g0' | Select-Object -First 1).InstallLocation";
  const { stdout } = await promisify(execFile)('powershell.exe', [
    '-NoProfile', '-NonInteractive', '-Command', script,
  ], { windowsHide: true, timeout: 5000 });
  const location = stdout.trim();
  if (!path.isAbsolute(location)) return null;
  const binary = path.join(location, 'app', 'resources', 'codex.exe');
  return await stat(binary).then(result => result.isFile() ? binary : null, () => null);
}

// Desktop-managed binaries move on app updates. Never fall back to the npm CLI.
export async function resolveDesktopBinary(configured, {localAppData = process.env.LOCALAPPDATA} = {}) {
  if(configured && await stat(configured).then(value=>value.isFile(),()=>false))return configured;
  const current = await currentStoreBinary(configured).catch(() => null);
  if (current) return current;
  if(!localAppData)throw Error('桌面 Codex 程序不存在，请重新选择桌面程序路径');
  const root=path.resolve(localAppData,'OpenAI','Codex','bin');
  if(configured){
    const relative=path.relative(root,path.resolve(configured));
    if(relative.startsWith('..'+path.sep)||path.isAbsolute(relative)||relative.split(path.sep).length!==2||path.basename(relative).toLowerCase()!=='codex.exe')throw Error('指定的桌面 Codex 程序不存在，请在设置中重新选择');
  }
  const rows=await readdir(root,{withFileTypes:true}).catch(()=>[]),found=[];
  for(const row of rows){if(!row.isDirectory())continue;const file=path.join(root,row.name,'codex.exe'),info=await stat(file).catch(()=>null);if(info?.isFile())found.push({file,time:info.mtimeMs});}
  found.sort((a,b)=>b.time-a.time);
  if(!found.length)throw Error('未找到已安装的桌面 Codex 程序，请打开桌面 Codex 后重试');
  return found[0].file;
}
