import { randomBytes, randomUUID, X509Certificate } from 'node:crypto';
import { spawn } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdir, readFile, appendFile } from 'node:fs/promises';
import { DesktopBridge } from '../server/desktop-bridge.mjs';
import { HistoryReader } from '../server/history-reader.mjs';
import { createGateway } from '../server/gateway.mjs';
import { atomicJson, readJson, addresses } from './config.mjs';
const here = dirname(fileURLToPath(import.meta.url));
async function createCertificate(input) {
  return new Promise((resolve, reject) => {
    const windowsPowerShell = join(process.env.SystemRoot || 'C:/Windows', 'System32/WindowsPowerShell/v1.0');
    const child = spawn(join(windowsPowerShell, 'powershell.exe'), ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', join(here, 'create-certificate.ps1')],
      { windowsHide: true, env: { ...process.env, PSModulePath: join(windowsPowerShell, 'Modules') }, stdio: ['pipe', 'ignore', 'pipe'] });
    let stage = ''; child.stderr.on('data', bytes => { const match = /CERTIFICATE_ERROR:([a-z-]+):([a-zA-Z0-9_.,:-]+)/.exec(bytes.toString()); if (match) stage = match[1] + ':' + match[2]; });
    child.stdin.on('error', () => {});
    const timer = setTimeout(() => { child.kill(); reject(Error('证书生成超时')); }, 30000);
    child.on('error', () => { clearTimeout(timer); reject(Error('无法启动 Windows 证书工具')); });
    child.on('exit', code => { clearTimeout(timer); code === 0 ? resolve() : reject(Error('Windows 证书生成失败' + (stage ? '（' + stage + '）' : ''))); });
    child.stdin.end(JSON.stringify(input));
  });
}
export class GatewayRuntime {
  constructor(root) { this.root = root; this.active = null; }
  async prepareIdentity() {
    const secrets = join(this.root, 'secrets'); await mkdir(secrets, { recursive: true, mode: 0o700 });
    const identityPath = join(secrets, 'gateway-identity.local.json');
    let identity = await readJson(identityPath, null);
    const pfx = join(secrets, 'gateway.p12'), certificate = join(secrets, 'gateway.crt');
    if (!identity) {
      // Do not silently replace a partial pre-existing identity.
      if (await readFile(pfx).then(() => true, () => false)) throw Error('证书存在但身份配置缺失，请先修复数据目录');
      identity = { deviceId: randomUUID(), password: randomBytes(32).toString('hex') };
      await createCertificate({ pfx, certificate, password: identity.password }); await atomicJson(identityPath, identity);
    }
    const cert = new X509Certificate(await readFile(certificate));
    this.identity = { ...identity, fingerprint: cert.fingerprint256.replaceAll(':', '').toLowerCase() };
    this.tls = { pfx: await readFile(pfx), passphrase: identity.password };
  }
  async candidate(config) {
    if (!addresses().some(row => row.address === config.host)) throw Error('远控 IP 尚未出现在本机网卡；请先连接组网或调整地址');
    const devicesPath = join(this.root, 'secrets', 'devices.local.json');
    const devices = await readJson(devicesPath, []);
    const historyReader = config.codexBinary ? new HistoryReader(config.codexBinary) : undefined;
    const bridge = new DesktopBridge(config.threads, { historyReader, allowSelectedChats: config.allowSelectedChats });
    const connectionAddresses={ primary:`https://${config.host}:${config.port}`, ...(config.lanHost ? {lan:`https://${config.lanHost}:${config.port}`} : {}) };
    const gateway = createGateway({ tls: this.tls, bridge, deviceId: this.identity.deviceId, deviceName: config.name, connectionAddresses, devices, saveDevices: value => atomicJson(devicesPath, value) });
    const servers=[gateway.server];
    gateway.server.on('controlAction', event => { void appendFile(join(this.root, 'actions.local.jsonl'), JSON.stringify(event) + '\n', { mode: 0o600 }).catch(() => {}); });
    try {
      await new Promise((resolve, reject) => { gateway.server.once('error', reject); gateway.server.listen(config.port, config.host, resolve); });
      if(config.lanHost && config.lanHost!==config.host){const lan=gateway.createListener();servers.push(lan);await new Promise((resolve,reject)=>{lan.once('error',reject);lan.listen(config.port,config.lanHost,resolve);});}
    } catch { bridge.close(); for(const server of servers){server.closeAllConnections();server.close();} throw Error('无法监听远控地址，请检查端口占用和网卡状态'); }
    void bridge.connect();
    return { config, gateway, bridge, servers };
  }
  async start(config) {
    if (!this.identity) await this.prepareIdentity();
    const prior = this.active;
    if (prior && JSON.stringify(prior.config) === JSON.stringify(config)) return this.status();
    if (prior && prior.config.port === config.port) {
      await this.stop();
      try { this.active = await this.candidate(config); }
      catch (error) { this.active = await this.candidate(prior.config).catch(() => null); throw error; }
    } else {
      const next = await this.candidate(config); await this.stop(); this.active = next;
    }
    return this.status();
  }
  status() {
    const active = this.active;
    return { running: !!active, address: active ? `https://${active.config.host}:${active.config.port}` : null,
      lanAddress: active?.config.lanHost ? `https://${active.config.lanHost}:${active.config.port}` : null,
      fingerprint: this.identity?.fingerprint, deviceId: this.identity?.deviceId,
      chats: active?.bridge.snapshot().map(row => ({ id: row.id, name: row.name, ready: row.ready, writable: row.writable })) || [],
      devices: active?.gateway.listDevices() || [] };
  }
  pairing(rotate = false) {
    if (!this.active) throw Error('请先启动手机连接服务');
    const gateway = this.active.gateway; if (rotate) gateway.rotatePairing();
    return { address: this.status().address, fingerprint: this.identity.fingerprint, code: gateway.pairingCode, expiresAt: new Date(gateway.pairingUntil).toISOString() };
  }
  async stop() {
    const active = this.active; if (!active) return;
    this.active = null; active.bridge.close();
    await Promise.all(active.servers.map(server=>{server.closeAllConnections();return new Promise(resolve=>server.close(resolve));}));
  }
}
