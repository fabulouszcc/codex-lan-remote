import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { access, mkdir } from 'node:fs/promises';
import net from 'node:net';
import { assertNoSubnetConflict, dpapi, run } from './config.mjs';
export async function availablePort(port, host = '127.0.0.1') {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', () => reject(Error(`端口 ${port} 已被占用，请修改端口`))); server.listen(port, host, resolve); });
  await new Promise(resolve => server.close(resolve));
}
export class EasyTierManager {
  constructor(binDir, { dataDir = binDir, spawnChild = spawn, runCommand = run, checkSubnet = assertNoSubnetConflict, decrypt = value => dpapi(value, true), checkPort = availablePort } = {}) {
    Object.assign(this, { binDir, dataDir, spawnChild, runCommand, checkSubnet, decrypt, checkPort }); this.child = null; this.state = '未启动';
  }
  status() { return { owned: !!this.child, pid: this.child?.pid, state: this.state, version: '2.6.4', rpcLoopbackOnly: true, lastExit:this.lastExit || null }; }
  async start(config) {
    const signature = JSON.stringify({ easytier: config.easytier, secretCipher: config.secretCipher });
    if (this.child) {
      if (signature !== this.signature) throw Error('内置组网参数已变更，请先停止连接，再应用并启动');
      return this.status();
    }
    const core = join(this.binDir, 'easytier-core.exe'); await access(core).catch(() => { throw Error('缺少插件内置 EasyTier，请重新安装完整插件包'); });
    await access(join(this.binDir, 'Packet.dll')).catch(() => { throw Error('Windows 原生依赖未就绪，请运行发布包安装器补齐（首次需联网）'); });
    await mkdir(this.dataDir, { recursive: true, mode: 0o700 });
    this.checkSubnet(config.easytier.cidr);
    await this.checkPort(config.easytier.rpcPort);
    await this.checkPort(config.easytier.listenPort, '0.0.0.0');
    const secret = config.secretCipher ? await this.decrypt(config.secretCipher) : '';
    const et = config.easytier;
    const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('ET_')));
    // Never put the secret on the command line. All native output is discarded
    // because even node/status and startup errors may echo configuration secrets.
    const child = this.spawnChild(core, ['--instance-name', 'codex-lan-desktop', '--hostname', 'codex-lan-desktop', '--network-name', et.name,
      '--ipv4', et.cidr, '--dev-name', et.devName, '--listeners', `tcp://0.0.0.0:${et.listenPort}`,
      '--rpc-portal', `127.0.0.1:${et.rpcPort}`, '--peers', ...et.peers, '--console-log-level', 'error', '--file-log-level', 'off'],
      { windowsHide: true, cwd: this.dataDir, env: { ...env, ET_NETWORK_SECRET: secret }, stdio: 'ignore' });
    this.child = child; this.signature = signature; this.state = '启动中'; this.rpcPort = et.rpcPort; this.lastExit = null;
    child.on('error', () => { if (this.child === child) { this.child = null; this.state = '启动失败，请检查管理员权限和原生库'; } });
    child.on('exit', (code, signal) => { if (this.child === child) { this.child = null; this.lastExit = {code:Number.isInteger(code)?code:null,signal:typeof signal==='string'?signal:null}; this.state = '已退出，请检查权限或配置'; } });
    return this.status();
  }
  async node() {
    if (!this.child) return null;
    try {
      const { stdout } = await this.runCommand(join(this.binDir, 'easytier-cli.exe'), ['-p', `127.0.0.1:${this.rpcPort}`, '--output', 'json', 'node'], { windowsHide: true, timeout: 5000, maxBuffer: 1024 * 1024 });
      const value = JSON.parse(stdout);
      // Explicit whitelist only; never forward network identity or complete RPC.
      return { virtualIpv4: value.virtual_ipv4, peerId: value.peer_id };
    } catch { return null; }
  }
  async stop() {
    const child = this.child; if (!child) return;
    this.child = null; this.state = '已停止';
    // Only the exact ChildProcess created above is eligible for termination.
    await new Promise(resolve => { const timer = setTimeout(resolve, 5000); child.once('exit', () => { clearTimeout(timer); resolve(); }); child.kill(); });
  }
}
