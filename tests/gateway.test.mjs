import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import https from 'node:https';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { X509Certificate } from 'node:crypto';
import { createGateway } from '../server/gateway.mjs';
import { DesktopBridge } from '../server/desktop-bridge.mjs';
import { gunzipSync } from 'node:zlib';
import { fileReference } from '../server/chat-files.mjs';



const directory = await mkdtemp(join(tmpdir(), 'codex-lan-tls-'));
const keytool = process.env.CODEX_LAN_TEST_KEYTOOL || (process.platform === 'win32'
  ? 'C:\\Program Files\\Android\\Android Studio\\jbr\\bin\\keytool.exe' : 'keytool');
const run = promisify(execFile);
const pfxPath = join(directory, 'test.p12'), certPath = join(directory, 'test.crt');
await run(keytool, ['-genkeypair', '-alias', 'test', '-keyalg', 'RSA', '-validity', '2', '-dname', 'CN=localhost', '-ext', 'SAN=ip:127.0.0.1', '-storetype', 'PKCS12', '-keystore', pfxPath, '-storepass', 'test-password'], { windowsHide: true });
await run(keytool, ['-exportcert', '-alias', 'test', '-keystore', pfxPath, '-storepass', 'test-password', '-file', certPath], { windowsHide: true });
const tls = { pfx: await readFile(pfxPath), passphrase: 'test-password' };
const certificate = new X509Certificate(await readFile(certPath));
await rm(directory, { recursive: true });

class FakeBridge extends EventEmitter {
  count = 0;
  snapshot() { return [{ id: 'allowed-chat', ready: true, turns: [] }]; }
  async catalog() { return [{ id: 'allowed-chat', name: 'Test chat' }]; }
  async openThread(id) { if (id !== 'allowed-chat') throw Error('Unknown chat'); }
  async modelOptions(){return {models:[{model:'available',efforts:['high']}],current:{model:'available',effort:'high'}};}
  async command(id, command) { this.count++; await new Promise(resolve => setTimeout(resolve, 20)); return { accepted: true, text: command.text }; }
}
test('installed desktop version is advertised separately from sync revision',async t=>{
  const f=await fixture(t,{desktopVersion:'0.5.0'});const info=await f.request('GET','/v1/info');assert.equal(info.data.desktopVersion,'0.5.0');const token=await f.pair();const state=await f.request('GET','/v1/state',undefined,{authorization:'Bearer '+token});assert.equal(state.data.desktopVersion,'0.5.0');assert.equal(typeof state.data.version,'number');
});
test('model discovery requires pairing and a known chat',async t=>{
  const {request,pair}=await fixture(t);assert.equal((await request('GET','/v1/models?threadId=allowed-chat')).status,401);
  const token=await pair(),headers={authorization:`Bearer ${token}`};
  assert.equal((await request('GET','/v1/models?threadId=unknown',undefined,headers)).status,403);
  const response=await request('GET','/v1/models?threadId=allowed-chat',undefined,headers);assert.equal(response.status,200);assert.equal(response.data.models[0].model,'available');
});

test('account quota is available only to paired clients',async t=>{
  const f=await fixture(t);f.bridge.usage=async()=>({available:true,primary:{remainingPercent:60,windowDurationMins:300,resetsAt:12345},secondary:null,updatedAt:1,stale:false});
  assert.equal((await f.request('GET','/v1/usage')).status,401);
  const token=await f.pair();const response=await f.request('GET','/v1/usage',undefined,{authorization:'Bearer '+token});
  assert.equal(response.status,200);assert.equal(response.data.primary.remainingPercent,60);
});
async function fixture(t, options = {}) {
  const bridge = new FakeBridge(); const saved = [];
  const gateway = createGateway({ tls, bridge, deviceId: 'test-device', pairingCode: 'test-pairing-code', saveDevices: async value => saved.push(structuredClone(value)), ...options });
  gateway.server.listen(0, '127.0.0.1'); await once(gateway.server, 'listening');
  t.after(async () => { gateway.server.closeAllConnections(); await new Promise(resolve => gateway.server.close(resolve)); });
  const request = (method, path, value, headers = {}, trust = true) => new Promise((resolve, reject) => {
    const payload = value === undefined ? null : Buffer.from(JSON.stringify(value));
    const req = https.request({ hostname: '127.0.0.1', port: gateway.server.address().port, path, method,
      ca: trust ? certificate.toString() : undefined,
      headers: { ...(payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {}), ...headers } }, response => {
      const chunks = []; response.on('data', chunk => chunks.push(chunk)); response.on('end', () => {
        try { const raw=Buffer.concat(chunks);resolve({ status: response.statusCode, data: response.headers['content-type']==='application/octet-stream'?raw:JSON.parse(response.headers['content-encoding']==='gzip'?gunzipSync(raw):raw), encoding:response.headers['content-encoding'],wireBytes:raw.length }); } catch (error) { reject(error); }
      });
    }); req.on('error', reject); req.end(payload);
  });
  const pair = async () => {
    const response = await request('POST', '/v1/pair', { code: 'test-pairing-code', name: '小米测试' });
    assert.equal(response.status, 200); return response.data.token;
  };
  return { bridge, saved, gateway, request, pair };
}

test('negotiated compression and sparse snapshots retain order, repairs and session authorization',async t=>{
  const f=await fixture(t),token=await f.pair(),headers={authorization:`Bearer ${token}`,'accept-encoding':'gzip'};
  let rows=[{id:'one',input:'a'.repeat(10000)},{id:'two',input:'b'.repeat(10000)}];
  f.bridge.snapshot=()=>[{id:'allowed-chat',ready:true,turns:rows}];
  const full=await f.request('GET','/v1/state?delta=1',undefined,headers);assert.equal(full.encoding,'gzip');assert.equal(full.data.sync,'full');assert.equal(full.data.threads[0].turns.length,2);assert.ok(full.wireBytes<1000);
  rows=[rows[1],{id:'three',input:'new'}];f.bridge.emit('changed');
  const delta=await f.request('GET','/v1/state?delta=1&after='+full.data.version,undefined,headers);
  assert.equal(delta.data.sync,'delta');assert.equal(delta.data.baseVersion,full.data.version);assert.deepEqual(delta.data.threads[0].turnOrder,['two','three']);assert.deepEqual(delta.data.threads[0].turns,[rows[1]]);
  const repair=await f.request('GET','/v1/state?delta=1&after=-1',undefined,headers);assert.equal(repair.data.sync,'full');assert.equal(repair.data.threads[0].turns.length,2);
  const plain=await f.request('GET','/v1/state',undefined,{...headers,'accept-encoding':'gzip;q=0'});assert.equal(plain.encoding,undefined);assert.equal(plain.data.threads[0].turns.length,2);
  await f.gateway.revokeDevice(f.gateway.listDevices()[0].id);assert.equal((await f.request('GET','/v1/state?delta=1&after=-1',undefined,headers)).status,401);
});

test('TLS and pairing protect private chat state; persisted credentials contain hashes only', async t => {
  const f = await fixture(t);
  await assert.rejects(f.request('GET', '/v1/info', undefined, {}, false));
  const info = await f.request('GET', '/v1/info');
  assert.equal(info.data.protocol, 1); assert.equal(info.data.threads, undefined);
  assert.equal((await f.request('GET', '/v1/state')).status, 401);
  assert.equal((await f.request('POST', '/v1/pair', { code: 'wrong' })).status, 403);
  const token = await f.pair();
  assert.equal(token.length, 64); assert.equal(JSON.stringify(f.saved).includes(token), false);
  const state = await f.request('GET', '/v1/state', undefined, { authorization: `Bearer ${token}` });
  assert.equal(state.status, 200); assert.equal(state.data.threads[0].id, 'allowed-chat');
});

test('LAN listener shares pairing, session revocation and authorization with overlay listener', async t => {
  const f=await fixture(t);const token=await f.pair();const lan=f.gateway.createListener();lan.listen(0,'127.0.0.1');await once(lan,'listening');
  t.after(async()=>{lan.closeAllConnections();await new Promise(resolve=>lan.close(resolve));});
  const get=authorization=>new Promise((resolve,reject)=>{const req=https.get({hostname:'127.0.0.1',port:lan.address().port,path:'/v1/state',ca:certificate.toString(),headers:authorization?{authorization:`Bearer ${authorization}`}:{ }},res=>{res.resume();res.on('end',()=>resolve(res.statusCode));});req.on('error',reject);});
  assert.equal(await get(),401);assert.equal(await get(token),200);
  await f.gateway.revokeDevice(f.gateway.listDevices()[0].id);
  assert.equal(await get(token),401);
});
test('local management rotates pairing without dropping existing devices and revokes a selected device', async t => {
  const f = await fixture(t); const token = await f.pair();
  const before = f.gateway.listDevices(); assert.equal(before.length, 1);
  assert.ok(!JSON.stringify(before).includes('tokenHash')); assert.ok(!JSON.stringify(before).includes(token));
  const rotated = f.gateway.rotatePairing(); assert.notEqual(rotated.code, 'test-pairing-code');
  assert.equal((await f.request('POST', '/v1/pair', { code: 'test-pairing-code' })).status, 403);
  const auth = { authorization: 'Bearer ' + token };
  assert.equal((await f.request('GET', '/v1/state', undefined, auth)).status, 200);
  assert.equal((await f.request('POST', '/v1/pair', { code: rotated.code })).status, 200);
  await f.gateway.revokeDevice(before[0].id);
  assert.equal((await f.request('GET', '/v1/state', undefined, auth)).status, 401);
  assert.equal(f.gateway.listDevices().length, 1);
});

test('browser-origin pairing is rejected and pairing expires/rate-limits', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('POST', '/v1/pair', { code: 'test-pairing-code' }, { origin: 'https://example.com' })).status, 403);
  for (let i = 0; i < 5; i++) assert.equal((await f.request('POST', '/v1/pair', { code: 'wrong' })).status, 403);
  assert.equal((await f.request('POST', '/v1/pair', { code: 'test-pairing-code' })).status, 429);
  const expired = await fixture(t, { pairingTtlMs: 0 });
  assert.equal((await expired.request('POST', '/v1/pair', { code: 'test-pairing-code' })).status, 403);
});

test('duplicate and concurrent command retries execute once; changed payload cannot reuse an ID', async t => {
  const f = await fixture(t); const token = await f.pair();
  const headers = { authorization: `Bearer ${token}`, 'idempotency-key': 'test-operation-00001' };
  const command = { threadId: 'allowed-chat', op: 'start', text: 'hello' };
  const [a, b] = await Promise.all([f.request('POST', '/v1/command', command, headers), f.request('POST', '/v1/command', command, headers)]);
  assert.equal(a.status, 200); assert.deepEqual(a, b); assert.equal(f.bridge.count, 1);
  assert.equal((await f.request('POST', '/v1/command', { ...command, text: 'changed' }, headers)).status, 409);
  assert.equal((await f.request('POST', '/v1/command', { ...command, threadId: 'private-chat' }, headers)).status, 403);
  assert.equal((await f.request('POST', '/v1/command', { ...command, op: 'shell' }, headers)).status, 400);
  assert.equal(f.bridge.count, 1);
});

test('uncertain failures are cached and never replayed automatically', async t => {
  const f = await fixture(t); const token = await f.pair();
  f.bridge.command = async () => { f.bridge.count++; throw new Error('uncertain transport timeout'); };
  const headers = { authorization: `Bearer ${token}`, 'idempotency-key': 'test-operation-00002' };
  const command = { threadId: 'allowed-chat', op: 'start', text: 'hello' };
  assert.equal((await f.request('POST', '/v1/command', command, headers)).status, 409);
  assert.equal((await f.request('POST', '/v1/command', command, headers)).status, 409);
  assert.equal(f.bridge.count, 1);
});

test('long poll wakes on state changes; revoked sessions lose access', async t => {
  const f = await fixture(t); const token = await f.pair(); const headers = { authorization: `Bearer ${token}` };
  const state = await f.request('GET', '/v1/state', undefined, headers);
  const waiting = f.request('GET', `/v1/state?after=${state.data.version}`, undefined, headers);
  await new Promise(resolve => setTimeout(resolve, 50)); f.bridge.emit('changed');
  const next = await waiting; assert.equal(next.data.version, state.data.version + 1);
  assert.equal((await f.request('DELETE', '/v1/session', undefined, headers)).status, 200);
  assert.equal((await f.request('GET', '/v1/state', undefined, headers)).status, 401);
});

test('bridge enforces live approval identity, offered choices, and single submission', async () => {
  const bridge = new DesktopBridge([{ id: 'allowed-chat' }]);
  let count = 0;
  bridge.client = { initialized: true, replyCommandApproval: async () => { count++; } };
  const entry = bridge.entries.get('allowed-chat'); entry.ready = true; entry.owner = 'owner';
  entry.store.apply({ type: 'snapshot', revision: 1, conversationState: { turns: [{ turnId: 'turn-1', status: 'inProgress', items: [] }],
    requests: [{ id: 15, type: 'commandExecutionApproval', params: { availableDecisions: ['accept', 'cancel'], command: 'echo hello' } }] } });
  await assert.rejects(bridge.command('private-chat', { op: 'approve', requestId: 15, decision: 'accept' }), /未就绪/);
  await assert.rejects(bridge.command('allowed-chat', { op: 'approve', requestId: 14, decision: 'accept' }), /失效/);
  await assert.rejects(bridge.command('allowed-chat', { op: 'approve', requestId: 15, decision: 'decline' }), /选项/);
  await assert.rejects(bridge.command('allowed-chat', { op: 'approve', turnId: 'previous-turn', requestId: 15, decision: 'accept' }), /轮次/);
  await bridge.command('allowed-chat', { op: 'approve', requestId: 15, decision: 'accept' });
  await assert.rejects(bridge.command('allowed-chat', { op: 'approve', requestId: 15, decision: 'cancel' }), /已提交/);
  assert.equal(count, 1);
  await assert.rejects(bridge.command('allowed-chat', { op: 'interrupt', turnId: 'old-turn' }), /变化/);
});

test('catalog and selection require pairing and never accept an arbitrary thread ID', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('GET', '/v1/threads')).status, 401);
  assert.equal((await f.request('POST', '/v1/select', { threadId: 'allowed-chat' })).status, 401);
  const token = await f.pair(), headers = { authorization: `Bearer ${token}` };
  assert.equal((await f.request('GET', '/v1/threads', undefined, headers)).data.threads[0].id, 'allowed-chat');
  assert.equal((await f.request('POST', '/v1/select', { threadId: 'arbitrary-private-id' }, headers)).status, 403);
  assert.equal((await f.request('POST', '/v1/select', { threadId: 'allowed-chat' }, headers)).status, 200);
  assert.equal(f.bridge.count, 0);
});

test('file bytes require pairing and an exact file reference in the authorized chat',async t=>{
  const root=await mkdtemp(join(tmpdir(),'gateway-file-'));t.after(()=>rm(root,{recursive:true,force:true}));await writeFile(join(root,'result.txt'),'preview');
  const f=await fixture(t),ref=fileReference(join(root,'result.txt'));
  f.bridge.snapshot=()=>[{id:'allowed-chat',turns:[{timeline:[{files:[ref]}]}]}];
  const route='/v1/file?threadId=allowed-chat&id='+ref.id;
  assert.equal((await f.request('GET',route)).status,401);
  const token=await f.pair(),headers={authorization:'Bearer '+token};
  assert.equal((await f.request('GET',route,undefined,headers)).data.toString(),'preview');
  assert.equal((await f.request('GET','/v1/file?threadId=other&id='+ref.id,undefined,headers)).status,403);
  assert.equal((await f.request('GET','/v1/file?threadId=allowed-chat&id=unknown&path='+encodeURIComponent(join(root,'result.txt')),undefined,headers)).status,403);
  await f.gateway.revokeDevice(f.gateway.listDevices()[0].id);assert.equal((await f.request('GET',route,undefined,headers)).status,401);
});
