import { createHash } from 'node:crypto';
import { open, realpath } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const image = /\.(png|jpe?g|webp|gif|bmp)$/i;
const text = /\.(txt|md|markdown|csv|tsv|json|jsonl|log|xml|ya?ml|toml|ini|tex|bib|py|js|mjs|cjs|ts|tsx|jsx|java|kt|kts|gradle|m|r|c|h|cpp|hpp|rs|go|css|html|svg|sh|ps1|bat|sql|diff|patch)$/i;
const document = /\.(pdf|docx?|xlsx?|pptx?|odt|ods|odp|rtf|zip|apk|mp4|mp3|wav)$/i;
const forbidden = /(?:^|[\\/])(?:\.env(?:\.[^\\/]*)?|\.git|\.ssh|\.aws|\.codex(?![\\/](?:attachments|visualizations)(?:[\\/]|$))|secrets?|credentials?|[^\\/]*\.local\.(?:json|toml)|[^\\/]*\.(?:pem|key|p12|pfx|keystore))(?=[\\/]|$)/i;
export function fileReference(value, label) {
  if (typeof value !== 'string' || value.length > 4096) return null;
  let target=value.trim().replace(/^<|>$/g,'');
  try { target=decodeURIComponent(target); } catch { return null; }
  if(target.startsWith('file://'))try{target=fileURLToPath(target);}catch{return null;}
  target=target.replace(/:(?:\d+)(?::\d+)?$/,'');
  if(target.replace(/^[a-z]:/i,'').includes(':'))return null;
  if(!target || /[\x00-\x1f]/.test(target) || /^(?:[a-z]+:)?\/\//i.test(target) || /^\\\\/.test(target) || /^[a-z]+:/i.test(target)&&! /^[a-z]:[\\/]/i.test(target))return null;
  if(forbidden.test(target)||!(image.test(target)||text.test(target)||document.test(target)))return null;
  const windows=/^[a-z]:[\\/]/i.test(target)||path.sep==='\\';
  target=windows?path.win32.normalize(target):path.posix.normalize(target);
  const name=target.split(/[\\/]/).at(-1);
  return {id:createHash('sha256').update(windows?target.toLowerCase():target).digest('hex'),path:target,name,label:String(label||name).slice(0,200),kind:image.test(target)?'image':text.test(target)?'text':'document'};
}
export function messageFiles(value='', content=[]) {
  const found=new Map(),add=(target,label)=>{const ref=fileReference(target,label);if(ref&&!found.has(ref.id)&&found.size<24)found.set(ref.id,ref);};
  for(const part of Array.isArray(content)?content:[])if(part?.type==='localImage')add(part.path);
  // Only explicit message links/attachments grant file access, never tool output.
  for(const match of value.matchAll(/!?\[([^\]\n]*)\]\((<[^>\n]+>|[^)\n]+)\)/g))add(match[2],match[1]);
  if(/# Files (?:mentioned|pasted) by the user:/.test(value))for(const match of value.matchAll(/^## [^\n]+?: (.+)$/gm))add(match[1]);
  for(const match of value.matchAll(/<image\s+[^>]*path="([^"]+)"[^>]*>/g))add(match[1]);
  for(const match of value.matchAll(/:{1,2}codex-file-citation\{((?:[^}"'\r\n]|"[^"\r\n]*"|'[^'\r\n]*')*)\}/g)){
    const attribute=/(?:^|\s)path=(?:"([^"\r\n]+)"|'([^'\r\n]+)')/.exec(match[1]);
    if(attribute)add(attribute[1]||attribute[2]);
  }
  return [...found.values()];
}
export function findChatFile(thread,id) {
  for(const turn of thread?.turns||[])for(const row of turn.timeline||[])for(const file of row.files||[])if(file.id===id)return file;
}
export async function readChatFile(reference,cwd,{maxBytes=32*1024*1024}={}) {
  if(!reference)throw Object.assign(Error('文件不在此聊天的可查看范围内'),{status:403});
  const target=path.isAbsolute(reference.path)?reference.path:cwd?path.resolve(cwd,reference.path):null;
  if(!target)throw Object.assign(Error('聊天工作目录尚未就绪'),{status:409});
  const resolved=await realpath(target).catch(()=>{throw Object.assign(Error('文件不存在或已移动'),{status:404});});
  if(forbidden.test(resolved)||resolved.startsWith('\\\\'))throw Object.assign(Error('此文件不允许通过手机读取'),{status:403});
  const handle=await open(resolved,'r');
  try{
    const stat=await handle.stat();if(!stat.isFile())throw Object.assign(Error('仅支持普通文件'),{status:400});
    if(stat.size>maxBytes)throw Object.assign(Error('文件超过 32 MB，请在电脑查看'),{status:413});
    const bytes=Buffer.alloc(Math.min(stat.size+1,maxBytes+1));let used=0;
    while(used<bytes.length){const {bytesRead}=await handle.read(bytes,used,bytes.length-used,null);if(!bytesRead)break;used+=bytesRead;}
    if(used>stat.size||used>maxBytes)throw Object.assign(Error('文件正在变化，请稍后重试'),{status:409});
    return bytes.subarray(0,used);
  }finally{await handle.close();}
}
