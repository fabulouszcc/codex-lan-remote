import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, writeFile, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import http from 'node:http';
import { createManager } from '../desktop/manager.mjs';
import { ConfigStore, validateConfig, assertNoSubnetConflict, dpapi, addresses } from '../desktop/config.mjs';
import { EasyTierManager } from '../desktop/easytier.mjs';
const assets = new URL('../desktop/web/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

test('automatic start failures remain visible in bounded manager events', async t => {
  const host=addresses()[0]?.address;if(!host)return;
  const root=await mkdtemp(join(tmpdir(),'codex-lan-start-error-')),saved={mode:'existing',host,port:9443,allowSelectedChats:true};
  const runtime={status:()=>({running:false}),stop:async()=>{},start:async()=>{throw Error('fixture startup failure');}};
  const easytier={status:()=>({owned:false}),stop:async()=>{}};
  const manager=await createManager({root,assets:decodeURIComponent(assets),port:0,runtime,easytier,store:{load:async()=>saved,public:async()=>saved}});t.after(()=>manager.close());
  await assert.rejects(manager.start(),/fixture startup failure/);assert.equal((await manager.status()).events.at(-1).message,'连接启动失败：fixture startup failure');
});

test('generate-and-apply validates mobile CIDR before changing LAN, then starts bundled ET and returns its QR', async t => {
  const host=addresses().find(row=>!row.address.endsWith('.0')&&!row.address.endsWith('.255'))?.address;if(!host)return;
  const root=await mkdtemp(join(tmpdir(),'codex-lan-pair-apply-')),store=new ConfigStore(root);
  await store.save({mode:'existing',host,port:9443,allowSelectedChats:true});let starts=0,rotations=0;
  const runtime={active:{config:{...validateConfig(await store.load()),secretCipher:undefined}},status:()=>({running:true}),stop:async()=>{},start:async config=>{starts++;runtime.active={config};},pairing:rotate=>{if(rotate)rotations++;return{address:`https://${runtime.active.config.host}:9443`,fingerprint:'a'.repeat(64),code:`test_${rotations}`,expiresAt:'2100-01-01T00:00:00Z'};}};
  const easytier={child:null,status:()=>({owned:false}),start:async()=>{easytier.child={};},stop:async()=>{easytier.child=null;}};
  const manager=await createManager({root,assets:decodeURIComponent(assets),port:0,runtime,easytier,store});t.after(()=>manager.close());
  const input={mode:'bundled',apply:true,rotate:true,phoneCidr:'invalid',config:{mode:'bundled',host,port:9443,allowSelectedChats:true,easytier:{name:'fixture',cidr:host+'/24',peers:['tcp://example.invalid:11010'],listenPort:11011,rpcPort:15899}}};
  const request=()=>fetch(new URL('/api/pairing',manager.url),{method:'POST',headers:{authorization:'Bearer '+manager.token,'content-type':'application/json'},body:JSON.stringify(input)});
  assert.equal((await request()).status,400);assert.equal(starts,0);assert.equal((await store.load()).mode,'existing');assert.equal(rotations,0);
  input.phoneCidr=host.split('.').slice(0,3).join('.')+(host.endsWith('.2')?'.3/24':'.2/24');
  const response=await request();assert.equal(response.status,200);const info=await response.json();assert.equal(info.networkMode,'easytier');assert.equal(info.easytier.cidr,input.phoneCidr);assert.equal(starts,1);assert.equal(rotations,1);
  delete input.phoneCidr;input.apply=false;delete input.config;
  const dhcp=await request();assert.equal(dhcp.status,200);assert.equal((await dhcp.json()).easytier.dhcp,true);assert.equal(starts,1);
});

test('pairing API follows the applied mode and rejects stale settings before rotating', async t => {
  const host=addresses().find(row=>!/easytier|^et_|tun|tap|vpn|wireguard|vmnet/i.test(row.name))?.address;if(!host)return;
  let saved={mode:'existing',host,port:9443,allowSelectedChats:true},rotations=0;
  const runtime={active:{config:{...validateConfig(saved),secretCipher:undefined}},status:()=>({running:true}),stop:async()=>{},pairing:rotate=>{
    if(rotate)rotations++;return{address:`https://${host}:9443`,fingerprint:'a'.repeat(64),code:`test_${rotations}`,expiresAt:'2100-01-01T00:00:00Z'};
  }};
  const store={load:async()=>saved,public:async()=>saved};
  const easytier={status:()=>({owned:false}),stop:async()=>{}};
  const root=await mkdtemp(join(tmpdir(),'codex-lan-pair-'));
  const manager=await createManager({root,assets:decodeURIComponent(assets),port:0,runtime,easytier,store});t.after(()=>manager.close());
  const request=body=>fetch(new URL('/api/pairing',manager.url),{method:'POST',headers:{authorization:'Bearer '+manager.token,'content-type':'application/json'},body:JSON.stringify(body)});
  assert.equal((await request({mode:'bundled',rotate:true})).status,400);assert.equal(rotations,0);
  const response=await request({mode:'existing',rotate:true});assert.equal(response.status,200);
  const info=await response.json();assert.equal(info.networkMode,'lan');assert.equal(info.code,'test_1');assert.equal(info.easytier,undefined);
  saved={...saved,port:9444};assert.equal((await request({mode:'existing',rotate:true})).status,400);assert.equal(rotations,1);
});
test('local admin rejects missing token, hostile origin, rebinding and oversized mutation', async t => {
  const root = await mkdtemp(join(tmpdir(), 'codex-lan-manager-'));
  const runtime = { status: () => ({ running: false }), stop: async () => {} };
  const easytier = { status: () => ({ state: 'stopped' }), stop: async () => {} };
  const manager = await createManager({ root, assets: decodeURIComponent(assets), port: 0, runtime, easytier }); t.after(() => manager.close());
  const origin = new URL(manager.url).origin;
  const auth = { authorization: 'Bearer ' + manager.token };
  assert.equal((await fetch(origin + '/api/status')).status, 401);
  assert.equal((await fetch(origin + '/api/status', { headers: { ...auth, origin: 'https://evil.example' } })).status, 403);
  const rebinding = await new Promise((resolve, reject) => {
    const request = http.get(origin + '/api/status', { headers: { ...auth, host: 'evil.example' } }, response => { response.resume(); resolve(response.statusCode); }); request.on('error', reject);
  });
  assert.equal(rebinding, 403);
  const status = await (await fetch(origin + '/api/status', { headers: auth })).json();
  assert.equal(status.kind, 'codex-lan-manager'); assert.equal(status.config, null);
  assert.equal((await fetch(origin + '/api/config', { method: 'POST', headers: { ...auth, 'content-type': 'application/json' }, body: JSON.stringify({ data: 'x'.repeat(66000) }) })).status, 413);
  assert.equal((await fetch(origin + '/api/anything', { method: 'POST', headers: { ...auth, 'content-type': 'application/json' }, body: '{}' })).status, 404);
  const html = await fetch(origin + '/'); assert.equal(html.status, 200); assert.ok(html.headers.get('content-security-policy').includes("script-src 'self'"));
  assert.ok(!(await html.text()).includes(manager.token));
});
test('existing network config never needs EasyTier binaries or credentials and does not persist unknown fields', async () => {
  const host = addresses()[0]?.address; if (!host) return;
  const root = await mkdtemp(join(tmpdir(), 'codex-lan-config-')); const store = new ConfigStore(root);
  await store.save({ mode: 'existing', host, port: 9443, allowSelectedChats: true, arbitrary: 'not-saved', networkSecret: '' });
  const value = await store.load(); assert.equal(value.host, host); assert.equal(value.arbitrary, undefined); assert.equal(value.networkSecret, undefined);
  assert.throws(() => validateConfig({ mode: 'existing', host: '0.0.0.0', port: 9443, allowSelectedChats: true }));
  assert.throws(() => validateConfig({ mode: 'existing', host, port: 9443, allowSelectedChats: false, threads: [] }), /聊天/);
});
test('subnet overlap and bundled port collisions fail before launching an instance', () => {
  assert.throws(() => assertNoSubnetConflict('192.168.250.2/24', [{ name: 'existing-et', cidr: '192.168.250.1/24' }]), /冲突/);
  assert.throws(() => assertNoSubnetConflict('10.2.0.2/16', [{ name: 'existing-et', cidr: '10.2.3.4/24' }]), /冲突/);
  assert.doesNotThrow(() => assertNoSubnetConflict('192.168.101.2/24', [{ name: 'existing-et', cidr: '192.168.250.1/24' }]));
  assert.throws(() => validateConfig({ mode: 'bundled', host: '192.168.101.2', port: 9443, allowSelectedChats: true, easytier: { name: 'test', cidr: '192.168.101.2/24', peers: ['tcp://example.com:11010'], listenPort: 9443, rpcPort: 15899 } }), /重复/);
});
test('owned EasyTier hides secret arguments, discards raw logs, whitelists RPC and only kills its child', async () => {
  const root = await mkdtemp(join(tmpdir(), 'codex-lan-et-')); await writeFile(join(root, 'easytier-core.exe'), 'test'); await writeFile(join(root, 'Packet.dll'), 'test');
  let captured, killed = 0; const child = new EventEmitter(); child.pid = 42; child.kill = () => { killed++; child.emit('exit', 0); };
  const manager = new EasyTierManager(root, { spawnChild: (...args) => { captured = args; return child; }, checkSubnet: () => {}, checkPort: async () => {}, decrypt: async () => 'private-test-secret',
    runCommand: async () => ({ stdout: JSON.stringify({ virtual_ipv4: '192.168.101.2', peer_id: 1, network_secret: 'never-return' }) }) });
  const config = { secretCipher: 'cipher', easytier: { name: 'test', cidr: '192.168.101.2/24', peers: ['tcp://example.com:11010'], listenPort: 11011, rpcPort: 15899, devName: 'codex-lan-et' } };
  await manager.start(config);
  assert.ok(!JSON.stringify(captured[1]).includes('private-test-secret'));
  assert.equal(captured[2].env.ET_NETWORK_SECRET, 'private-test-secret'); assert.equal(captured[2].stdio, 'ignore');
  assert.ok(captured[1].includes('127.0.0.1:15899'));
  assert.deepEqual(await manager.node(), { virtualIpv4: '192.168.101.2', peerId: 1 });
  await assert.rejects(manager.start({ ...config, secretCipher: 'changed' }), /先停止/);
  await manager.stop(); await manager.stop(); assert.equal(killed, 1); assert.equal(manager.status().owned, false);
});
test('Windows DPAPI keeps Unicode secret out of config and public state', { skip: process.platform !== 'win32' }, async () => {
  const secret = '测试密钥-DPAPI-🦊'; const cipher = await dpapi(secret);
  assert.ok(!cipher.includes(secret)); assert.equal(await dpapi(cipher, true), secret);
  const host = addresses()[0].address, root = await mkdtemp(join(tmpdir(), 'codex-lan-secret-')), store = new ConfigStore(root);
  await store.save({ mode: 'bundled', host:'10.90.0.1', port: 9443, allowSelectedChats: true, networkSecret: secret, easytier:{name:'test',cidr:'10.90.0.1/24',peers:['tcp://example.invalid:11010'],listenPort:11011,rpcPort:15899} });
  assert.ok(!(await readFile(store.path, 'utf8')).includes(secret)); assert.equal((await store.public()).secretCipher, undefined); assert.equal((await store.public()).hasSecret, true);
});

test('LAN and external profiles preserve bundled parameters and encrypted secret across saves', async () => {
  const host=addresses()[0]?.address;if(!host)return;
  const root=await mkdtemp(join(tmpdir(),'codex-lan-separated-')),store=new ConfigStore(root);
  const et={name:'saved-network',cidr:'10.90.0.1/24',peers:['tcp://example.invalid:11010'],listenPort:11011,rpcPort:15899,devName:'codex-lan-et'};
  const legacy={mode:'bundled',host:'10.90.0.1',port:9443,allowSelectedChats:true,easytier:et,secretCipher:'opaque-test-cipher',profiles:{existing:{host,port:9445,secretCipher:'must-be-dropped'}}};
  await writeFile(store.path,JSON.stringify(legacy));const before=await readFile(store.path,'utf8');
  const migrated=await store.public();assert.equal(migrated.profiles.bundled.host,'10.90.0.1');assert.equal(migrated.profiles.existing.secretCipher,undefined);assert.equal(await readFile(store.path,'utf8'),before);
  await store.save({mode:'existing',host,port:9446,allowSelectedChats:true,networkSecret:'ignored',clearSecret:true,easytier:{name:'must-not-overwrite'}});
  let value=await store.load();assert.deepEqual(value.easytier,et);assert.equal(value.secretCipher,'opaque-test-cipher');assert.equal(value.profiles.bundled.port,9443);assert.equal(value.profiles.existing.port,9446);
  await store.save({mode:'external',host,port:9447,allowSelectedChats:true});value=await store.load();
  assert.equal(value.profiles.existing.port,9446);assert.equal(value.profiles.external.port,9447);assert.deepEqual(value.easytier,et);assert.equal(value.secretCipher,'opaque-test-cipher');
  await store.save({mode:'bundled',host:'10.90.0.1',port:9448,allowSelectedChats:true,easytier:et,networkSecret:''});value=await store.load();
  assert.equal(value.profiles.existing.port,9446);assert.equal(value.profiles.external.port,9447);assert.equal(value.profiles.bundled.port,9448);assert.equal(value.secretCipher,'opaque-test-cipher');assert.equal((await store.public()).secretCipher,undefined);
});
