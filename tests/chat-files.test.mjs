import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, mkdir, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileReference, messageFiles, findChatFile, readChatFile } from '../server/chat-files.mjs';
import { turnTimeline } from '../server/turn-timeline.mjs';

test('Codex file citations become deduplicated file cards and retain path restrictions',()=>{
  const text=String.raw`已处理完成。 :codex-file-citation{path="C:\Temp\报告.docx" purpose="output"} [报告](C:/Temp/报告.docx)`;
  const files=messageFiles(text);assert.equal(files.length,1);assert.equal(files[0].name,'报告.docx');
  assert.equal(messageFiles(':codex-file-citation{path="C:/Temp/.env" purpose="output"}').length,0);
  assert.equal(messageFiles(':codex-file-citation{path="https://example.com/report.xlsx"}').length,0);
  for(const name of ['book.xlsx','slides.pptx','notes.odt','table.ods','slides.odp','note.rtf'])assert.ok(fileReference('C:/Temp/'+name));
});

test('only explicit local file links and image attachments become capabilities',()=>{
  const rows=turnTimeline([{type:'userMessage',content:[{type:'localImage',path:'/tmp/photo.png'}]},
    {type:'agentMessage',text:'[report](</tmp/a report.pdf>) and [code](/tmp/app.py:12)'},
    {type:'fileChange',changes:[{path:'app.py',diff:'-old\n+new'}]}]);
  assert.equal(rows[0].files[0].kind,'image');assert.equal(rows[1].files[0].name,'a report.pdf');assert.equal(rows[1].files[1].name,'app.py');assert.equal(rows[2].files[0].diff,'-old\n+new');
  assert.equal(messageFiles('# Files mentioned by the user:\n\n## photo.png: C:/Temp/photo.png',[{type:'localImage',path:'c:\\TEMP\\photo.png'}]).length,1);
  assert.equal(messageFiles('[web](https://example.com/photo.png) /tmp/implicit.png').length,0);
  assert.equal(messageFiles('# Files mentioned by the user:\n\n## photo.png: C:/Temp/photo.png')[0].name,'photo.png');
  for(const target of ['C:/x/.env.json','C:/x/secrets/key.txt','C:/x/token.local.json','C:/x/test.pem','//server/share/report.pdf','data:image/png;base64,a','/tmp/test.exe'])assert.equal(fileReference(target),null,target);
  assert.equal(findChatFile({turns:[{timeline:rows}]},rows[0].files[0].id).name,'photo.png');assert.equal(findChatFile({turns:[]},rows[0].files[0].id),undefined);
});

test('reads are bounded, scoped to references, and recheck resolved private paths',async t=>{
  const root=await mkdtemp(join(tmpdir(),'chat-files-'));t.after(()=>rm(root,{recursive:true,force:true}));
  await writeFile(join(root,'report.txt'),'hello');const ref=fileReference('report.txt');
  assert.equal((await readChatFile(ref,root)).toString(),'hello');
  await assert.rejects(readChatFile(undefined,root),{status:403});
  await assert.rejects(readChatFile(ref,undefined),{status:409});
  await assert.rejects(readChatFile(ref,root,{maxBytes:2}),{status:413});
  await assert.rejects(readChatFile(fileReference('gone.txt'),root),{status:404});
  await mkdir(join(root,'secrets'));await writeFile(join(root,'secrets','private.txt'),'secret');
  try{await symlink(join(root,'secrets'),join(root,'public'),process.platform==='win32'?'junction':'dir');}catch(e){if(['EPERM','EACCES'].includes(e.code))return;throw e;}
  await assert.rejects(readChatFile(fileReference('public/private.txt'),root),{status:403});
});
