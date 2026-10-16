import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { join } from 'node:path';
import { networkInterfaces } from 'node:os';
import { isIP } from 'node:net';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
export const run = promisify(execFile);
export async function atomicJson(path, value) {
  const temporary = path + '.tmp';
  await writeFile(temporary, JSON.stringify(value, null, 2), { mode: 0o600 }); await rename(temporary, path);
}
export async function readJson(path, fallback) {
  try { return JSON.parse((await readFile(path, 'utf8')).replace(/^\uFEFF/, '')); }
  catch (error) { if (error.code === 'ENOENT') return fallback; throw error; }
}
export function addresses() {
  return Object.entries(networkInterfaces()).flatMap(([name, rows]) => rows.filter(row => row.family === 'IPv4' && !row.internal && !row.address.startsWith('169.254.')).map(row => ({ name, address: row.address, cidr: row.cidr })));
}
export function validateConfig(value) {
  if (!value || !['existing', 'bundled'].includes(value.mode)) throw Error('请选择已有网络或内置 EasyTier');
  const host = String(value.host || '').trim();
  if (isIP(host) !== 4 || host === '0.0.0.0' || host.startsWith('127.') || host.startsWith('169.254.')) throw Error('请输入本机可达的局域网或虚拟 IPv4 地址');
  if (value.mode === 'existing' && !addresses().some(row => row.address === host)) throw Error('该 IP 不属于本机网卡，请填写电脑的本机地址');
  const port = Number(value.port);
  const lanHost=String(value.lanHost || '').trim();
  if(lanHost && (!/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(lanHost) || !addresses().some(row=>row.address===lanHost))) throw Error('局域网直连地址必须是本机的私有 IPv4 网卡地址');
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw Error('远控端口应在 1024–65535 之间');
  const threads = Array.isArray(value.threads) ? value.threads : [];
  if (threads.length > 24 || threads.some(row => !/^[a-zA-Z0-9_-]{1,128}$/.test(row.id))) throw Error('聊天范围无效');
  if (!threads.length && !value.allowSelectedChats) throw Error('请选择至少一个允许控制的聊天，或允许手机主动选择本机聊天');
  const et = value.easytier || {};
  const safe = { mode: value.mode, host, port, lanHost, name: String(value.name || 'Codex 电脑').slice(0, 60),
    autoStart: value.autoStart === true,
    codexBinary: String(value.codexBinary || ''), allowSelectedChats: value.allowSelectedChats === true,
    threads: threads.map(row => ({ id: row.id, name: String(row.name || '桌面聊天').slice(0, 80) })),
    easytier: { name: String(et.name || '').trim(), cidr: String(et.cidr || '').trim(), peers: Array.isArray(et.peers) ? et.peers : [],
      listenPort: Number(et.listenPort || 11011), rpcPort: Number(et.rpcPort || 15899), devName: 'codex-lan-et' } };
  if (safe.mode === 'bundled') validateEasyTier(safe);
  return safe;
}
export function validateEasyTier(config) {
  const et = config.easytier;
  const match = /^(\d+\.\d+\.\d+\.\d+)\/(\d+)$/.exec(et.cidr);
  if (!et.name || et.name.length > 128) throw Error('请填写组网名称，最多 128 字符');
  if (!match || isIP(match[1]) !== 4 || +match[2] < 8 || +match[2] > 30 || match[1] !== config.host) throw Error('内置组网需要静态虚拟 IPv4/前缀，且 IP 应与远控地址一致');
  const ip = ipv4(match[1]), mask = (0xffffffff << (32 - +match[2])) >>> 0;
  if ((ip & mask) >>> 0 === ip || ((ip | mask) >>> 0) === 0xffffffff || ip >>> 24 === 0 || ip >>> 24 === 127 || ip >>> 24 >= 224) throw Error('虚拟 IP 必须是可分配的主机地址');
  for (const port of [et.listenPort, et.rpcPort]) if (!Number.isInteger(port) || port < 1024 || port > 65535) throw Error('EasyTier 端口应在 1024–65535 之间');
  if (new Set([et.listenPort, et.rpcPort, config.port]).size !== 3) throw Error('EasyTier 监听、管理和远控端口不能重复');
  if (!Array.isArray(et.peers) || !et.peers.length || et.peers.length > 16) throw Error('请填写 1–16 个组网节点');
  for (const peer of et.peers) {
    let url; try { url = new URL(peer); } catch { throw Error('组网节点地址格式无效'); }
    if (!['tcp:', 'udp:', 'ws:', 'wss:', 'quic:', 'wg:'].includes(url.protocol) || !url.hostname || url.username || url.password || url.search || url.hash) throw Error('请使用无凭据的 EasyTier 节点 URL');
    if (isIP(url.hostname) === 4 && (ipv4(url.hostname) & mask) === (ip & mask)) throw Error('组网节点应使用底层网络地址，不能填写同一虚拟子网的地址');
  }
}
export function ipv4(address) { return address.split('.').reduce((n, part) => (n * 256 + Number(part)) >>> 0, 0); }
export function assertNoSubnetConflict(cidr, rows = addresses()) {
  const [address, prefix] = cidr.split('/'); const ip = ipv4(address), p = +prefix;
  for (const row of rows) {
    const [other, otherPrefix] = row.cidr.split('/'); const mask = (0xffffffff << (32 - Math.min(p, +otherPrefix))) >>> 0;
    if ((ip & mask) === (ipv4(other) & mask)) throw Error(`虚拟子网与已有网卡 ${row.name} 冲突，请修改内置地址或使用已有网络`);
  }
}
export async function protectDirectory(path) {
  await mkdir(path, { recursive: true, mode: 0o700 });
  if (process.platform === 'win32') {
    const { stdout } = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', '[Security.Principal.WindowsIdentity]::GetCurrent().User.Value'], { windowsHide: true });
    await run('icacls.exe', [path, '/inheritance:r', '/grant:r', `*${stdout.trim()}:(OI)(CI)F`, '*S-1-5-18:(OI)(CI)F'], { windowsHide: true });
  }
}
// DPAPI input travels via stdin, never command arguments, files, or logs.
export function dpapi(value, decrypt = false) {
  if (process.platform !== 'win32') throw Error('此版本的组网密钥存储需要 Windows DPAPI');
  return new Promise((resolve, reject) => {
    const script = '[Console]::InputEncoding=[Text.UTF8Encoding]::new($false); [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); Add-Type -AssemblyName System.Security; $v=[Console]::In.ReadToEnd(); ' + (decrypt
      ? '$b=[Convert]::FromBase64String($v); [Console]::Write([Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))'
      : '$b=[Text.Encoding]::UTF8.GetBytes($v); [Console]::Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)))');
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    const chunks = []; const timer = setTimeout(() => { child.kill(); reject(Error('Windows 密钥存储超时')); }, 15000);
    child.stdout.on('data', bytes => chunks.push(bytes)); child.stderr.resume(); child.stdin.on('error', () => {});
    child.on('error', () => { clearTimeout(timer); reject(Error('无法访问 Windows 密钥存储')); });
    child.on('exit', code => { clearTimeout(timer); code === 0 ? resolve(Buffer.concat(chunks).toString('utf8')) : reject(Error('Windows 密钥存储失败')); });
    child.stdin.end(value);
  });
}
export class ConfigStore {
  constructor(root) { this.root = root; this.path = join(root, 'config.local.json'); }
  async load() { return readJson(this.path, null); }
  async public() { const value = await this.load(); if (!value) return null; const { secretCipher, ...config } = value; return { ...config, hasSecret: !!secretCipher }; }
  async save(input) {
    const next = validateConfig(input), prior = await this.load();
    next.profiles = { ...(prior?.profiles || {}), [next.mode]: { host: next.host, port: next.port } };
    const supplied = input.networkSecret;
    if (supplied !== undefined && (typeof supplied !== 'string' || supplied.length > 512)) throw Error('组网密钥最多 512 字符');
    next.secretCipher = input.clearSecret ? undefined : supplied ? await dpapi(supplied) : prior?.secretCipher;
    await atomicJson(this.path, next); return this.public();
  }
}
