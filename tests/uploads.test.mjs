import test from 'node:test';
import assert from 'node:assert/strict';
import {Readable} from 'node:stream';
import {mkdtemp,readFile,readdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {Uploads,uploadName,MAX_UPLOAD} from '../server/uploads.mjs';
import {messageInput} from '../server/message-input.mjs';

test('upload metadata is bound to device/chat, persists and restores the exact streamed bytes',async t=>{
  const root=await mkdtemp(join(tmpdir(),'lan-upload-'));t.after(()=>rm(root,{recursive:true,force:true}));let saved;
  const store=new Uploads(root,{save:async entries=>{saved=structuredClone(entries);}});
  const item=await store.receive(Readable.from([Buffer.from('文档'),Buffer.from(' evidence')]),{name:'测试.md',threadId:'chat',sessionHash:'device',authorized:()=>true});
  assert.deepEqual(Object.keys(item).sort(),['id','kind','name','size']);
  const restored=new Uploads(root,{entries:saved}),row=restored.resolve([item.id],'device','chat')[0];assert.equal(await readFile(row.path,'utf8'),'文档 evidence');
  for(const [device,chat]of[['other','chat'],['device','other']])assert.throws(()=>restored.resolve([item.id],device,chat),error=>error.status===403);
  assert.throws(()=>restored.resolve([item.id,item.id],'device','chat'),/不同附件/);assert.throws(()=>restored.resolve(['__proto__'],'device','chat'),/不属于/);
});
test('unsafe names and secret formats cannot become native message headers',()=>{
  for(const name of['../x.md','a\\b.txt','x\n## malicious.md','CON.txt','a.txt:stream','a.txt.','.env','secret.key','a.exe'])assert.throws(()=>uploadName(name),/不支持/);
  assert.equal(uploadName('说明 文档.pdf'),'说明 文档.pdf');
});
test('oversize, interrupted, revoked and failed-persistence uploads leave no partial file',async t=>{
  const root=await mkdtemp(join(tmpdir(),'lan-upload-'));t.after(()=>rm(root,{recursive:true,force:true}));
  const options={name:'test.md',sessionHash:'device',threadId:'chat',authorized:()=>true};
  for(const mode of['size','interrupted','revoked','save']){
    const store=new Uploads(root,{save:async()=>{if(mode==='save')throw Error('disk');}});
    async function* parts(){if(mode==='size'){const chunk=Buffer.alloc(65536);for(let size=0;size<=MAX_UPLOAD;size+=chunk.length)yield chunk;}else{yield Buffer.from('test');if(mode==='interrupted')throw Error('lost');}}
    await assert.rejects(store.receive(Readable.from(parts()),{...options,authorized:()=>mode!=='revoked'}));assert.deepEqual(await readdir(root),[]);assert.equal(store.active,0);
  }
});
test('desktop file headers, image input and queued restore context use original paths without base64 copies',()=>{
  const files=[{id:'f',name:'说明.md',path:'C:\\attachments\\f\\说明.md',kind:'text'},{id:'i',name:'图.png',path:'C:\\attachments\\i\\图.png',kind:'image'}];
  const message=messageInput('看附件',{attachments:files,cwd:'C:\\project',id:'message'});
  assert.match(message.input[0].text,/# Files mentioned by the user:/);assert.match(message.input[0].text,/Image attachment: true/);assert.match(message.input[0].text,/## My request:\n看附件$/);
  assert.deepEqual(message.input[1],{type:'localImage',path:files[1].path});assert.equal(message.context.fileAttachments[0].fsPath,files[0].path);assert.equal(message.context.imageAttachments[0].localPath,files[1].path);assert.equal(message.localTurnMetadata.fileAttachmentCount,1);assert.equal(message.restoreMessage.text,'看附件');assert.equal(message.attachments[1].isImageAttachment,true);
});

test('validated skills use typed native input and never enter the file attachment header',()=>{
  const message=messageInput('$pr test',{skills:[{name:'pr',path:'C:\\skills\\pr\\SKILL.md'}]});assert.deepEqual(message.input[1],{type:'skill',name:'pr',path:'C:\\skills\\pr\\SKILL.md'});assert.equal(message.input[0].text,'$pr test');assert.deepEqual(message.attachments,[]);assert.equal(message.restoreMessage.context.prompt,'[$pr](<C:/skills/pr/SKILL.md>) test');
});
