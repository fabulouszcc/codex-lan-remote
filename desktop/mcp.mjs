import { createInterface } from 'node:readline';
import { ensureManager, adminCall } from './client.mjs';
const tools = [
  { name: 'open_phone_settings', description: '打开手机连接插件设置入口。返回本机设置页面链接，可在 Codex 浏览器面板显示；含已有网络、内置 EasyTier、配对和设备授权管理。', inputSchema: { type: 'object', properties: {}, additionalProperties: false }, annotations: { readOnlyHint: true } },
  { name: 'phone_connection_status', description: '查看手机远控服务、桌面聊天就绪状态及本插件管理的 EasyTier 状态。不返回组网密钥或配对码。', inputSchema: { type: 'object', properties: {}, additionalProperties: false }, annotations: { readOnlyHint: true } },
  { name: 'start_phone_connection', description: '应用已保存配置并启动手机远控服务。内置模式只启动本插件拥有的 EasyTier，不管理已有系统组网。', inputSchema: { type: 'object', properties: {}, additionalProperties: false }, annotations: { readOnlyHint: false, idempotentHint: true } },
  { name: 'stop_phone_connection', description: '停止本插件手机连接服务及其拥有的 EasyTier。手机会断开；已有系统组网不受影响。仅在用户要求停止时调用。', inputSchema: { type: 'object', properties: {}, additionalProperties: false }, annotations: { readOnlyHint: false, destructiveHint: true } },
];
const send = value => process.stdout.write(JSON.stringify(value) + '\n');
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of lines) {
  let message;
  try {
    if (line.length > 1024 * 1024) throw Error('请求过大'); message = JSON.parse(line);
    if (message.id === undefined) continue;
    let result;
    if (message.method === 'initialize') result = { protocolVersion: message.params?.protocolVersion || '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'codex-lan-desktop', version: '0.3.4' } };
    else if (message.method === 'ping') result = {};
    else if (message.method === 'tools/list') result = { tools };
    else if (message.method === 'tools/call') {
      const name = message.params?.name; if (!tools.some(tool => tool.name === name)) throw Error('未知插件工具');
      try {
        const endpoint = await ensureManager();
        let value;
        if (name === 'open_phone_settings') value = { settingsUrl: endpoint.origin + '/#' + endpoint.token, instruction: '在 Codex 浏览器面板打开此链接；此链接仅供本机使用，请勿发送给手机或其他人。' };
        else if (name === 'start_phone_connection') value = await adminCall(endpoint, '/api/start', {});
        else if (name === 'stop_phone_connection') value = await adminCall(endpoint, '/api/stop', {});
        else value = await adminCall(endpoint, '/api/status');
        // Model tools need a status summary, not machine paths or saved config.
        if (name !== 'open_phone_settings') value = { version: value.version, gateway: value.gateway, easytier: value.easytier };
        result = { content: [{ type: 'text', text: JSON.stringify(value) }] };
      } catch (error) { result = { isError: true, content: [{ type: 'text', text: error.message }] }; }
    } else { send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not found' } }); continue; }
    send({ jsonrpc: '2.0', id: message.id, result });
  } catch { send({ jsonrpc: '2.0', id: message?.id ?? null, error: { code: -32600, message: 'Invalid request' } }); }
}
