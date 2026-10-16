import { isIP } from 'node:net';
import { ipv4, validateEasyTier, dpapi, addresses } from './config.mjs';

// Explicit pairing export only. Never add these credentials to public status or logs.
export async function pairingInfo(base, config, input, decrypt = value => dpapi(value, true), interfaces = addresses()) {
  if (!config || input.mode !== config.mode) throw Error('连接方式尚未应用，请先保存配置并应用启动');
  if (config.mode !== 'bundled') {
    const virtual = interfaces.some(row => row.address === config.host && /easytier|^et_|tun|tap|vpn|wireguard|vmnet/i.test(row.name));
    return { ...base, networkMode: config.mode === 'external' || virtual ? 'existing' : 'lan' };
  }
  validateEasyTier(config);
  const cidr = String(input.phoneCidr || '').trim(), match = /^(\d+\.\d+\.\d+\.\d+)\/(\d+)$/.exec(cidr);
  const [desktopIp, prefix] = config.easytier.cidr.split('/'), mask = (0xffffffff << (32 - Number(prefix))) >>> 0;
  if (cidr) {
    if (!match || isIP(match[1]) !== 4 || Number(match[2]) !== Number(prefix)) throw Error('请填写与电脑同一虚拟子网的手机 IPv4 / 前缀');
    const ip = ipv4(match[1]), network = (ipv4(desktopIp) & mask) >>> 0;
    if (((ip & mask) >>> 0) !== network || ip === network || (ip | mask) >>> 0 === 0xffffffff || match[1] === desktopIp) throw Error('手机虚拟 IP 必须在同一子网，且不能与电脑、网络或广播地址重复');
  }
  const secret = config.secretCipher ? await decrypt(config.secretCipher) : '';
  const result = { ...base, networkMode: 'easytier', easytier: { name: config.easytier.name, secret,
    ...(cidr ? {cidr} : {dhcp:true, networkCidr:config.easytier.cidr}), peers: config.easytier.peers.join('\n') } };
  // Keep enough margin for one offline QR at error correction M (UTF-8 bytes).
  if (Buffer.byteLength(JSON.stringify(result), 'utf8') > 1800) throw Error('组网参数过长，无法放入一个二维码；请减少节点数量或使用手动配置');
  return result;
}
