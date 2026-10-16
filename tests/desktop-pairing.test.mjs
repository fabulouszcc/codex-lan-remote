import test from 'node:test';
import assert from 'node:assert/strict';
import { pairingInfo } from '../desktop/pairing.mjs';

const base = { address: 'https://10.90.0.1:9443', fingerprint: 'a'.repeat(64), code: 'test_only', expiresAt: '2100-01-01T00:00:00Z' };
const config = { mode: 'bundled', host: '10.90.0.1', port: 9443, secretCipher: 'encrypted-only', easytier: { name: '测试组网', cidr: '10.90.0.1/24', peers: ['tcp://public.easytier.cn:11010'], listenPort: 11011, rpcPort: 15899 } };
test('LAN QR contains no network credentials and never decrypts a saved secret', async () => {
  const info = await pairingInfo(base, { ...config, mode: 'existing' }, { mode: 'existing' }, () => { throw Error('must not decrypt'); });
  assert.equal(info.networkMode, 'lan'); assert.equal(info.easytier, undefined); assert.equal(info.secretCipher, undefined); assert.equal(info.address, base.address);
});
test('external ET QR leaves existing phone network setup in place without exporting credentials', async () => {
  const info=await pairingInfo(base,{...config,mode:'external'},{mode:'external'},()=>{throw Error('must not decrypt external ET');});
  assert.equal(info.networkMode,'existing');assert.equal(info.easytier,undefined);
});
test('ET adapters under existing networks preserve the phone overlay without exporting its secret', async () => {
  const info=await pairingInfo(base,{...config,mode:'existing'},{mode:'existing'},()=>{throw Error('must not decrypt');},[{name:'et_5_fixture',address:config.host}]);
  assert.equal(info.networkMode,'existing');assert.equal(info.easytier,undefined);
});
test('ET QR exports only the selected network and a distinct mobile address', async () => {
  let calls = 0;
  const info = await pairingInfo(base, config, { mode: 'bundled', phoneCidr: '10.90.0.2/24' }, async cipher => { assert.equal(cipher, 'encrypted-only'); calls++; return '测试密钥'; });
  assert.equal(calls, 1); assert.equal(info.networkMode, 'easytier');
  assert.deepEqual(info.easytier, { name: '测试组网', secret: '测试密钥', cidr: '10.90.0.2/24', peers: 'tcp://public.easytier.cn:11010' });
  assert.equal(info.secretCipher, undefined); assert.equal(info.easytier.rpcPort, undefined);
});
test('mismatched modes and invalid mobile addresses fail before secret export', async () => {
  const decrypt = () => { throw Error('must not decrypt'); };
  await assert.rejects(pairingInfo(base, config, { mode: 'existing' }, decrypt), /应用/);
  for (const cidr of ['10.90.0.1/24', '10.90.0.0/24', '10.90.0.255/24', '10.91.0.2/24', '10.90.0.2/16', '999.0.0.2/24'])
    await assert.rejects(pairingInfo(base, config, { mode: 'bundled', phoneCidr: cidr }, decrypt), /手机/);
});

test('scan-to-join defaults to DHCP without embedding a static mobile address', async () => {
  for (const phoneCidr of [undefined, '']) {
    const info = await pairingInfo(base,config,{mode:'bundled',phoneCidr},async ()=>'fixture');
    assert.equal(info.easytier.dhcp,true); assert.equal(info.easytier.networkCidr,config.easytier.cidr);
    assert.equal(info.easytier.cidr,undefined);
  }
});
test('no-key ET network remains supported and oversized QR exports are rejected', async () => {
  const input = { mode: 'bundled', phoneCidr: '10.90.0.2/24' };
  const info = await pairingInfo(base, { ...config, secretCipher: undefined }, input);
  assert.equal(info.easytier.secret, '');
  await assert.rejects(pairingInfo(base, config, input, async () => '密'.repeat(512)), /过长/);
});
