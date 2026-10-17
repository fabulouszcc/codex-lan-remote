import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { once } from 'node:events';
import { ensureManager, restoreAutoStart } from '../desktop/client.mjs';

test('Windows background entry does not launch a daemon when auto-start is disabled', async () => {
  const root=await mkdtemp(join(tmpdir(),'codex-lan-disabled-task-'));
  await writeFile(join(root,'config.local.json'),'{"autoStart":false}');
  const child=spawn(process.execPath,[fileURLToPath(new URL('../desktop/background.mjs',import.meta.url)),'--data',root],{windowsHide:true,stdio:['ignore','ignore','ignore']});
  const [code]=await once(child,'exit');assert.equal(code,0);
  const {readFile}=await import('node:fs/promises');
  const status=JSON.parse(await readFile(join(root,'background-status.local.json'),'utf8'));
  assert.equal(status.enabled,false);
  await assert.rejects(readFile(join(root,'manager.local.json')),{code:'ENOENT'});
});

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'codex-lan-bootstrap-'));
  let requests = 0;
  const server = http.createServer((request, response) => {
    requests++;
    assert.equal(request.headers.authorization, 'Bearer fixture-only');
    setTimeout(() => { response.writeHead(200, {'content-type':'application/json'}); response.end('{"kind":"codex-lan-manager"}'); }, 150);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  const endpoint = {origin:`http://127.0.0.1:${server.address().port}`,token:'fixture-only'};
  await writeFile(join(root,'manager.local.json'),JSON.stringify(endpoint));
  return {root,endpoint,requests:()=>requests};
}

test('automatic restore honors the saved opt-in and shares concurrent startup checks', async t => {
  const f=await fixture(t);
  assert.equal(await restoreAutoStart(f.root),null); assert.equal(f.requests(),0);
  for(const autoStart of [false,'true',1]) {
    await writeFile(join(f.root,'config.local.json'),JSON.stringify({autoStart}));
    assert.equal(await restoreAutoStart(f.root),null);
  }
  assert.equal(f.requests(),0);
  await writeFile(join(f.root,'config.local.json'),'{"autoStart":true}');
  const values=await Promise.all([restoreAutoStart(f.root),ensureManager(f.root),ensureManager(f.root)]);
  assert.ok(values.every(value=>value.origin===f.endpoint.origin)); assert.equal(f.requests(),1);
});

test('MCP initialize restores an opted-in manager without requiring a tool call', async t => {
  const f=await fixture(t);
  await writeFile(join(f.root,'config.local.json'),'{"autoStart":true}');
  const child=spawn(process.execPath,[fileURLToPath(new URL('../desktop/mcp.mjs',import.meta.url))],{windowsHide:true,env:{...process.env,CODEX_LAN_DATA_DIR:f.root},stdio:['pipe','pipe','pipe']});
  t.after(()=>child.kill()); child.stderr.resume();
  const lines=createInterface({input:child.stdout}); t.after(()=>lines.close());
  const replies=[];lines.on('line',line=>replies.push(JSON.parse(line)));
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2024-11-05'}})+'\n');
  const deadline=Date.now()+5000;
  while((!replies.length||!f.requests())&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,10));
  assert.equal(replies[0]?.result.serverInfo.name,'codex-lan-desktop'); assert.equal(f.requests(),1);
  child.stdin.write('{"jsonrpc":"2.0","id":2,"method":"initialize"}\n');
  while(replies.length<2&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,10));
  assert.equal(replies.length,2);assert.equal(f.requests(),1);
  // Allow bootstrap to finish before closing its parent. No extra protocol frames.
  await new Promise(resolve=>setTimeout(resolve,200));assert.equal(replies.length,2);
  child.stdin.end();await once(child,'exit');
});
