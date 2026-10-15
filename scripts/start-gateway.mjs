import { readFile, writeFile, mkdir, rename, appendFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomBytes, randomUUID, X509Certificate } from 'node:crypto';
import { networkInterfaces } from 'node:os';
import { DesktopBridge } from '../server/desktop-bridge.mjs';
import { createGateway } from '../server/gateway.mjs';
import { HistoryReader } from '../server/history-reader.mjs';

const run = promisify(execFile);
async function saveJson(path, value) {
  const temporary = `${path}.tmp`;
  await writeFile(temporary, JSON.stringify(value, null, 2), { mode: 0o600 });
  await rename(temporary, path);
}
const configPath = resolve(process.argv[2] || '.local/gateway.local.json');
if (!existsSync(configPath)) throw new Error('请先创建本地配置，参见 docs/手机测试指南.md');
const config = JSON.parse((await readFile(configPath, 'utf8')).replace(/^\uFEFF/, ''));
if (!Array.isArray(config.threads) || config.threads.length === 0 || config.threads.length > 8 || config.threads.some(thread => !/^[a-zA-Z0-9_-]{1,128}$/.test(thread.id))) throw new Error('请配置 1–8 个明确授权的聊天 ID');
const host = config.host || '127.0.0.1';
const localAddresses = Object.values(networkInterfaces()).flat().filter(Boolean).map(value => value.address);
if (!localAddresses.includes(host) || ['0.0.0.0', '::'].includes(host)) throw new Error('监听 IP 必须是本机具体网卡地址');
const port = config.port ?? 9443;
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('端口应为 1024–65535');
const secrets = resolve('.local/secrets'); await mkdir(secrets, { recursive: true });
const identityPath = resolve(secrets, 'gateway-identity.local.json');
const pfxPath = resolve(secrets, 'gateway.p12'); const certificatePath = resolve(secrets, 'gateway.crt');
let identity;
if (existsSync(identityPath)) identity = JSON.parse(await readFile(identityPath, 'utf8'));
else {
  if (existsSync(pfxPath) || existsSync(certificatePath)) throw new Error('证书文件存在但身份配置缺失，请先检查 .local/secrets，避免无提示更换已配对身份');
  identity = { deviceId: randomUUID(), password: randomBytes(32).toString('hex') };
  // keytool reads its password from a local environment variable, not command-line arguments.
  const keytool = config.keytool || 'C:\\Program Files\\Android\\Android Studio\\jbr\\bin\\keytool.exe';
  const env = { ...process.env, CODEX_LAN_KEY_PASSWORD: identity.password };
  await run(keytool, ['-genkeypair', '-alias', 'codex-lan', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-dname', 'CN=Codex LAN Remote', '-storetype', 'PKCS12', '-keystore', pfxPath, '-storepass:env', 'CODEX_LAN_KEY_PASSWORD'], { env, windowsHide: true });
  await run(keytool, ['-exportcert', '-alias', 'codex-lan', '-keystore', pfxPath, '-storepass:env', 'CODEX_LAN_KEY_PASSWORD', '-file', certificatePath], { env, windowsHide: true });
  await saveJson(identityPath, identity);
}
const fingerprint = new X509Certificate(await readFile(certificatePath)).fingerprint256.replaceAll(':', '').toLowerCase();
const devicesPath = resolve(secrets, 'devices.local.json');
const devices = existsSync(devicesPath) ? JSON.parse(await readFile(devicesPath, 'utf8')) : [];
// To revoke all clients, stop this service and remove devices.local.json.
const historyReader = config.codexBinary ? new HistoryReader(config.codexBinary) : undefined;
const bridge = new DesktopBridge(config.threads, { historyReader, allowSelectedChats: config.allowSelectedChats === true });
const gateway = createGateway({ tls: { pfx: await readFile(pfxPath), passphrase: identity.password }, bridge,
  deviceId: identity.deviceId, deviceName: config.name || '我的 Codex 电脑', devices,
  saveDevices: async value => saveJson(devicesPath, value) });
await mkdir(resolve('.local/verification'), { recursive: true });
gateway.server.on('controlAction', event => {
  // No prompts, commands, network secrets, or bearer tokens in the action receipt.
  void appendFile(resolve('.local/verification/gateway-actions.local.jsonl'), JSON.stringify(event) + '\n')
    .catch(() => console.error('操作回执写入失败'));
});
await new Promise((resolveListen, reject) => { gateway.server.once('error', reject); gateway.server.listen(port, host, resolveListen); });
// Local-only pairing information. Never write it to the committed docs or server logs.
const pairing = { address: `https://${host.includes(':') ? `[${host}]` : host}:${port}`, fingerprint, code: gateway.pairingCode,
  expiresAt: new Date(gateway.pairingUntil).toISOString() };
await saveJson(resolve('.local/phone-pairing.local.json'), pairing);
console.log(`远控服务已启动：${pairing.address}`);
console.log('配对信息：.local/phone-pairing.local.json（10 分钟有效，仅保存在本机）');
console.log(`授权聊天数量：${config.threads.length}。按 Ctrl+C 停止本服务。`);
void bridge.connect();
const close = () => { bridge.close(); gateway.server.closeAllConnections(); gateway.server.close(); };
process.once('SIGINT', close); process.once('SIGTERM', close);
