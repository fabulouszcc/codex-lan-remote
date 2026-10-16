import https from 'node:https';
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';

const hash = value => createHash('sha256').update(value).digest('hex');
function fail(status, message) { return Object.assign(new Error(message), { status }); }
async function body(request) {
  if (!request.headers['content-type']?.startsWith('application/json')) throw fail(415, '需要 JSON 请求');
  let size = 0; const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 65536) throw fail(413, '请求过大');
    chunks.push(chunk);
  }
  try { const value = JSON.parse(Buffer.concat(chunks)); if (!value || Array.isArray(value) || typeof value !== 'object') throw 0; return value; }
  catch { throw fail(400, '无效 JSON'); }
}
function reply(response, status, value) {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  response.end(JSON.stringify(value));
}

// No generic RPC proxy. Only explicitly configured chats and supported operations.
export function createGateway({ tls, bridge, deviceName = 'Codex 电脑', deviceId, connectionAddresses = {}, devices = [], saveDevices = async () => {}, pairingCode = randomBytes(16).toString('hex'), pairingTtlMs = 600000, now = Date.now }) {
  const sessions = new Map(devices.map(device => [device.tokenHash, device]));
  let pairingUntil = now() + pairingTtlMs;
  const attempts = new Map(); const idempotency = new Map(); const locks = new Map();
  let persistence = Promise.resolve();
  const mutateSessions = change => {
    const operation = persistence.catch(() => {}).then(async () => {
      const previous = new Map(sessions);
      try { change(); await saveDevices([...sessions.values()]); }
      catch (error) { sessions.clear(); for (const [key, value] of previous) sessions.set(key, value); throw error; }
    });
    persistence = operation;
    return operation;
  };
  let version = 0;
  const waiters = new Set();
  let publishTimer;
  // Full mobile snapshots are coalesced; never resend the last 50 turns per token.
  const changed = () => {
    if (publishTimer) return;
    publishTimer = setTimeout(() => {
      publishTimer = null; version++;
      for (const wake of [...waiters]) wake();
    }, 250);
    publishTimer.unref();
  };
  bridge.on('changed', changed);
  const server = https.createServer({ ...tls, minVersion: 'TLSv1.2', maxHeaderSize: 8192 }, async (request, response) => {
    try {
      // Native client only. Prevent browser-origin requests and CSRF pairing.
      if (request.headers.origin || request.headers['sec-fetch-site']) throw fail(403, '仅允许已配对客户端');
      const url = new URL(request.url, 'https://localhost');
      if (request.method === 'GET' && url.pathname === '/v1/info') {
        reply(response, 200, { protocol: 1, deviceName, deviceId, pairingOpen: now() < pairingUntil }); return;
      }
      if (request.method === 'POST' && url.pathname === '/v1/pair') {
        if (now() >= pairingUntil) throw fail(403, '配对码已过期，请重启连接服务生成新码');
        const ip = request.socket.remoteAddress;
        let rate = attempts.get(ip);
        if (!rate || now() - rate.start > 60000) {
          rate = { start: now(), count: 0 };
          if (attempts.size >= 256) attempts.delete(attempts.keys().next().value);
          attempts.set(ip, rate);
        }
        if (++rate.count > 5) throw fail(429, '配对尝试过多，请一分钟后重试');
        const input = await body(request);
        if (typeof input.code !== 'string' || !timingSafeEqual(Buffer.from(hash(input.code)), Buffer.from(hash(pairingCode)))) throw fail(403, '配对码不正确');
        const token = randomBytes(32).toString('hex');
        const device = { tokenHash: hash(token), name: String(input.name || 'Android').slice(0, 60), createdAt: now() };
        // Persist hashes only. Never log tokens or put them in URLs.
        try { await mutateSessions(() => {
          if (sessions.size >= 16) throw fail(409, '设备数量已达上限，请在电脑清理授权');
          sessions.set(device.tokenHash, device);
        }); } catch (error) { throw error.status ? error : fail(500, '保存配对失败'); }
        reply(response, 200, { token, deviceId, deviceName, protocol: 1 }); return;
      }
      const token = request.headers.authorization?.match(/^Bearer ([a-f0-9]{64})$/)?.[1];
      const sessionHash = token && hash(token);
      if (!sessionHash || !sessions.has(sessionHash)) throw fail(401, '请先配对');
      sessions.get(sessionHash).lastSeen = now();
      if (request.method === 'DELETE' && url.pathname === '/v1/session') {
        try { await mutateSessions(() => sessions.delete(sessionHash)); } catch { throw fail(500, '撤销失败'); }
        changed(); reply(response, 200, { revoked: true }); return;
      }
      if (request.method === 'GET' && url.pathname === '/v1/threads') {
        reply(response, 200, { threads: await bridge.catalog() }); return;
      }
      if (request.method === 'GET' && url.pathname === '/v1/models') {
        const threadId=url.searchParams.get('threadId');
        if(!bridge.snapshot().some(thread=>thread.id===threadId))throw fail(403,'聊天不在授权范围内');
        try{reply(response,200,await bridge.modelOptions(threadId));}catch(error){throw fail(400,error.message);}return;
      }
      if (request.method === 'POST' && url.pathname === '/v1/select') {
        const input = await body(request);
        if (typeof input.threadId !== 'string') throw fail(400, '需要聊天 ID');
        try { await bridge.openThread(input.threadId); } catch (error) { throw fail(403, error.message); }
        changed(); reply(response, 200, { selected: input.threadId }); return;
      }
      if (request.method === 'GET' && url.pathname === '/v1/state') {
        if (url.searchParams.get('after') === String(version)) {
          if (waiters.size >= 32) throw fail(429, '连接过多');
          await new Promise(resolve => {
            const done = () => { clearTimeout(timer); waiters.delete(done); response.off('close', done); resolve(); };
            const timer = setTimeout(done, 20000); waiters.add(done); response.once('close', done);
          });
        }
        if (!sessions.has(sessionHash)) throw fail(401, '设备授权已撤销');
        reply(response, 200, { version, deviceId, connectionAddresses, threads: bridge.snapshot() }); return;
      }
      if (request.method === 'POST' && url.pathname === '/v1/command') {
        const input = await body(request);
        if (typeof input.threadId !== 'string' || !['start', 'steer', 'interrupt', 'approve', 'model'].includes(input.op)) throw fail(400, '不支持的操作');
        if (['start', 'steer'].includes(input.op) && (typeof input.text !== 'string' || !input.text.trim() || input.text.length > 16000)) throw fail(400, '消息应为 1–16000 字符');
        if (!bridge.snapshot().some(thread => thread.id === input.threadId)) throw fail(403, '聊天不在授权范围内');
        const id = request.headers['idempotency-key'];
        if (typeof id !== 'string' || !/^[a-zA-Z0-9-]{16,80}$/.test(id)) throw fail(400, '缺少有效操作编号');
        const key = `${sessionHash}:${id}`;
        const payloadHash = hash(JSON.stringify(input));
        let operation = idempotency.get(key);
        if (operation && operation.payloadHash !== payloadHash) throw fail(409, '操作编号已被其他请求使用');
        if (!operation) {
          if (idempotency.size >= 2000) throw fail(429, '操作缓存已满，请重启服务');
          const prior = locks.get(input.threadId) || Promise.resolve();
          operation = { payloadHash, promise: prior.catch(() => {}).then(async () => {
            if (!sessions.has(sessionHash)) throw fail(401, '设备授权已撤销');
            const action = { at: new Date().toISOString(), deviceName: sessions.get(sessionHash)?.name,
              threadId: input.threadId, op: input.op, requestId: input.requestId, decision: input.decision };
            try {
              const result = await bridge.command(input.threadId, input);
              server.emit('controlAction', { ...action, ok: true }); return result;
            } catch (error) { server.emit('controlAction', { ...action, ok: false }); throw error; }
          }) };
          idempotency.set(key, operation);
          locks.set(input.threadId, operation.promise);
          // Both success and failure stay cached: an uncertain mutation is not replayed.
        }
        try { reply(response, 200, await operation.promise); }
        catch (error) { throw fail(error.status || 409, error.message); }
        return;
      }
      throw fail(404, '接口不存在');
    } catch (error) { reply(response, error.status || 500, { error: error.status ? error.message : '服务请求失败' }); }
  });
  server.requestTimeout = 30000; server.headersTimeout = 10000; server.maxConnections = 40;
  server.on('close', () => { clearTimeout(publishTimer); bridge.off('changed', changed); for (const wake of [...waiters]) wake(); });
  return {
    createListener() { return https.createServer({ ...tls, minVersion: 'TLSv1.2', maxHeaderSize: 8192 }, server.listeners('request')[0]); },
    server,
    get pairingCode() { return pairingCode; },
    get pairingUntil() { return pairingUntil; },
    rotatePairing() { pairingCode = randomBytes(16).toString('hex'); pairingUntil = now() + pairingTtlMs; attempts.clear(); return { code: pairingCode, expiresAt: pairingUntil }; },
    listDevices() { return [...sessions.values()].map(device => ({ id: device.tokenHash.slice(0, 24), name: device.name, createdAt: device.createdAt, lastSeen: device.lastSeen, recentlyOnline: !!device.lastSeen && now() - device.lastSeen < 45000 })); },
    async revokeDevice(id) {
      const key = [...sessions.keys()].find(key => key.slice(0, 24) === id);
      if (!key) throw fail(404, '设备不存在');
      await mutateSessions(() => sessions.delete(key)); changed(); return { revoked: true };
    },
  };
}
