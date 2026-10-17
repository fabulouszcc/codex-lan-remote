import http from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { ConfigStore, addresses, validateConfig } from './config.mjs';
import { GatewayRuntime } from './runtime.mjs';
import { EasyTierManager } from './easytier.mjs';
import { pairingInfo } from './pairing.mjs';
const fail = (status, message) => Object.assign(Error(message), { status });
const safeEqual = (a, b) => typeof a === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
export async function createManager({ root, assets, binDir, port = 19547, runtime = new GatewayRuntime(root), easytier = new EasyTierManager(binDir, { dataDir: join(root, 'easytier') }), store = new ConfigStore(root), token = randomBytes(32).toString('hex'), onShutdown = () => {}, syncAutoStart = async () => {} }) {
  let mutation = Promise.resolve(); const events = [];
  const record = message => { events.push({ at: new Date().toISOString(), message }); if (events.length > 50) events.shift(); };
  const status = async () => ({ kind: 'codex-lan-manager', version: '0.4.16-preview', config: await store.public(), addresses: addresses(), gateway: runtime.status(), easytier: easytier.status(), events });
  const startConfigured = async () => {
    const saved = await store.load(); if (!saved) throw Error('请先保存连接配置');
    const config = { ...validateConfig(saved), secretCipher: saved.secretCipher };
    if (config.mode === 'bundled') {
      const wasRunning = !!easytier.child;
      await easytier.start(config);
      try {
        const deadline = Date.now() + 20000;
        while (!addresses().some(row => row.address === config.host)) {
          if (!easytier.child || Date.now() > deadline) throw Error('内置 EasyTier 未创建虚拟网卡；首次使用可能需要管理员权限，请检查组网参数');
          await new Promise(resolve => setTimeout(resolve, 500));
        }
        await runtime.start(config); easytier.state = '虚拟网卡已建立';
      } catch (error) { if (!wasRunning) await easytier.stop(); throw error; }
    } else {
      if (easytier.child && runtime.active?.config.mode === 'bundled' && runtime.active.config.host === config.host) throw Error('已有网络不能使用即将停止的内置虚拟地址，请选择其他本机网卡');
      await runtime.start(config); await easytier.stop();
    }
    record('手机连接服务已启动'); return status();
  };
  const start = async () => {
    try { return await startConfigured(); }
    catch (error) { record('连接启动失败：' + error.message); throw error; }
  };
  const server = http.createServer(async (request, response) => {
    const reply = (code, body, type = 'application/json; charset=utf-8') => {
      response.writeHead(code, { 'content-type': type, 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer',
        'content-security-policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'self'; base-uri 'none'" });
      response.end(typeof body === 'string' ? body : JSON.stringify(body));
    };
    try {
      const origin = `http://127.0.0.1:${server.address().port}`;
      if (request.headers.host !== new URL(origin).host || (request.headers.origin && request.headers.origin !== origin)) throw fail(403, '仅允许本机设置页面');
      const url = new URL(request.url, origin);
      if (request.method === 'GET' && ['/', '/app.js', '/style.css', '/qrcode.js'].includes(url.pathname)) {
        const name = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
        reply(200, await readFile(join(assets, name), 'utf8'), name.endsWith('.html') ? 'text/html; charset=utf-8' : name.endsWith('.js') ? 'text/javascript; charset=utf-8' : 'text/css; charset=utf-8'); return;
      }
      if (!safeEqual(request.headers.authorization || '', 'Bearer ' + token)) throw fail(401, '请通过插件入口重新打开设置页面');
      if (request.method === 'GET' && url.pathname === '/api/status') { reply(200, await status()); return; }
      if (request.method !== 'POST') throw fail(404, '接口不存在');
      if (!request.headers['content-type']?.startsWith('application/json')) throw fail(415, '需要 JSON 请求');
      let size = 0; const chunks = [];
      for await (const chunk of request) { size += chunk.length; if (size > 65536) throw fail(413, '配置过大'); chunks.push(chunk); }
      let input; try { input = JSON.parse(Buffer.concat(chunks)); } catch { throw fail(400, '配置格式无效'); }
      if (!input || typeof input !== 'object' || Array.isArray(input)) throw fail(400, '配置格式无效');
      const operation = mutation.catch(() => {}).then(async () => {
        if (url.pathname === '/api/config') { const value = await store.save(input); await syncAutoStart(root); record('连接配置已保存，点击应用并启动后生效'); return value; }
        if (url.pathname === '/api/start') return start();
        if (url.pathname === '/api/stop') { await runtime.stop(); await easytier.stop(); record('手机连接服务已停止'); return status(); }
        if (url.pathname === '/api/pairing') {
          if (input.apply === true) {
            const draft = validateConfig(input.config);
            if (draft.mode !== input.mode) throw Error('配对方式与准备启用的连接方式不一致');
            // Reject invalid mobile network settings before writing config or changing a live connection.
            await pairingInfo({ address:`https://${draft.host}:${draft.port}`, fingerprint:'0'.repeat(64), code:'preflight', expiresAt:new Date(Date.now()+600000).toISOString() }, draft, input);
            await store.save(input.config);
            await syncAutoStart(root);
            await start();
          }
          if (!runtime.active) throw Error('请先启动手机连接服务');
          const active = runtime.active.config;
          const saved = await store.load();
          if (!saved) throw Error('请先保存连接配置');
          if (JSON.stringify(active) !== JSON.stringify({ ...validateConfig(saved), secretCipher: saved.secretCipher })) throw Error('已保存配置尚未应用，请先应用并启动');
          // Validate credentials and mobile address before rotating a working pairing code.
          const info = await pairingInfo(runtime.pairing(false), active, input);
          return { ...info, ...runtime.pairing(input.rotate === true) };
        }
        if (url.pathname === '/api/revoke') { if (!runtime.active) throw Error('服务尚未运行'); const value = await runtime.active.gateway.revokeDevice(input.id); record('已撤销一台手机的授权'); return value; }
        if (url.pathname === '/api/easytier-stop') { if (runtime.active?.config.mode === 'bundled') await runtime.stop(); await easytier.stop(); record('受管 EasyTier 已停止'); return status(); }
        if (url.pathname === '/api/shutdown') { setTimeout(onShutdown, 100); return { stopping: true }; }
        if (url.pathname === '/api/elevate') {
          const script = fileURLToPath(new URL('../scripts/start-desktop-plugin.ps1', import.meta.url));
          const child = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, '-Administrator', '-DataDirectory', root], { windowsHide: true, stdio: 'ignore' });
          child.on('error', () => {}); child.unref(); return { requested: true };
        }
        throw fail(404, '接口不存在');
      });
      mutation = operation; reply(200, await operation);
    } catch (error) { reply(error.status || 400, { error: error.message }); }
  });
  server.maxConnections = 20; server.requestTimeout = 30000; server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  const url = `http://127.0.0.1:${server.address().port}/#${token}`;
  return { server, token, url, status, start, async close() { await mutation.catch(() => {}); await runtime.stop(); await easytier.stop(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } };
}
