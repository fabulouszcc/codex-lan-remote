import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,rm,utimes} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {resolveDesktopBinary} from '../server/desktop-binary.mjs';

test('a removed desktop-managed binary resolves to the updated installed desktop only',async t=>{
  const local=await mkdtemp(path.join(tmpdir(),'desktop-update-'));t.after(()=>rm(local,{recursive:true,force:true}));
  const root=path.join(local,'OpenAI/Codex/bin'),older=path.join(root,'older/codex.exe'),latest=path.join(root,'latest/codex.exe');
  for(const file of [older,latest]){await mkdir(path.dirname(file),{recursive:true});await writeFile(file,'fixture');}
  await utimes(older,new Date(1000),new Date(1000));await utimes(latest,new Date(2000),new Date(2000));
  assert.equal(await resolveDesktopBinary(path.join(root,'deleted/codex.exe'),{localAppData:local}),latest);
  assert.equal(await resolveDesktopBinary(older,{localAppData:local}),older);
  await assert.rejects(resolveDesktopBinary(path.join(local,'custom/missing.exe'),{localAppData:local}),/重新选择/);
});
