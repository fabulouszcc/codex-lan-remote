import { randomUUID } from 'node:crypto';
import { mkdir, open, unlink, rmdir } from 'node:fs/promises';
import path from 'node:path';
import { fileReference } from './chat-files.mjs';

const fail=(status,message)=>Object.assign(Error(message),{status});
export const MAX_UPLOAD=32*1024*1024;
export function uploadName(value){
  if(typeof value!=='string'||!value||value.length>160||value!==value.normalize('NFC')||/[\\/:*?"<>|\x00-\x1f\x7f]/.test(value)||/[. ]$/.test(value)||/^(?:con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(value)||!fileReference('/attachments/'+value))throw fail(400,'文件名或类型不支持');
  return value;
}
export class Uploads {
  constructor(root,{entries={},save=async()=>{}}={}){this.root=root;this.entries=entries;this.save=save;this.pending=Promise.resolve();this.active=0;}
  async receive(stream,{name,sessionHash,threadId,authorized}){
    name=uploadName(name);
    if(this.active>=2)throw fail(429,'请等待当前文件上传完成');
    this.active++;
    const id=randomUUID(),directory=path.join(this.root,id),target=path.join(directory,name);
    let handle,complete=false;
    try{
      await mkdir(directory,{recursive:true,mode:0o700});handle=await open(target,'wx',0o600);
      let size=0;
      for await(const chunk of stream){size+=chunk.length;if(size>MAX_UPLOAD)throw fail(413,'文件超过 32 MB');await handle.writeFile(chunk);}
      await handle.close();handle=null;
      if(!size)throw fail(400,'不能发送空文件');
      if(!authorized())throw fail(403,'设备或聊天授权已变化');
      const reference=fileReference(target,name);
      const entry={id,sessionHash,threadId,path:target,name,size,kind:reference.kind};
      const operation=this.pending.catch(()=>{}).then(async()=>{const next={...this.entries,[id]:entry};await this.save(next);this.entries=next;});
      this.pending=operation;await operation;complete=true;
      return {id,name,size,kind:entry.kind};
    }finally{await handle?.close().catch(()=>{});if(!complete){await unlink(target).catch(()=>{});await rmdir(directory).catch(()=>{});}this.active--;}
  }
  resolve(ids,sessionHash,threadId){
    if(!Array.isArray(ids)||ids.length>8||new Set(ids).size!==ids.length)throw fail(400,'最多选择 8 个不同附件');
    return ids.map(id=>{const row=this.entries[id];if(typeof id!=='string'||!Object.hasOwn(this.entries,id)||!row||row.sessionHash!==sessionHash||row.threadId!==threadId)throw fail(403,'附件不属于此设备和聊天');return row;});
  }
}
