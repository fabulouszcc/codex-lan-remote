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
  await store.save({ mode: 'existing', host, port: 9443, allowSelectedChats: true, networkSecret: secret });
  assert.ok(!(await readFile(store.path, 'utf8')).includes(secret)); assert.equal((await store.public()).secretCipher, undefined); assert.equal((await store.public()).hasSecret, true);
});
