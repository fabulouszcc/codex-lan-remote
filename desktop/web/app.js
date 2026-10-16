const $ = id => document.getElementById(id);
const token = location.hash.slice(1) || sessionStorage.getItem('codex-lan-admin');
if (token) sessionStorage.setItem('codex-lan-admin', token);
history.replaceState(null, '', location.pathname);
let initialized = false, latest, busy = false;
async function call(path, body) {
  const response = await fetch('/api/' + path, { method: body === undefined ? 'GET' : 'POST',
    headers: { authorization: 'Bearer ' + token, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json(); if (!response.ok) throw Error(result.error); return result;
}
function feedback(text, error = false) { $('feedback').textContent = text; $('feedback').className = error ? 'error' : ''; }
function mode() { return document.querySelector('input[name=mode]:checked').value; }
function showMode(restore = false) {
  $('bundled').hidden = mode() !== 'bundled'; $('modeHint').textContent = mode() === 'existing' ? '只使用现有网络，不接管你已运行的组网程序。' : '仅管理插件启动的 EasyTier；虚拟 IP、子网和端口不能与现有实例冲突。';
  $('restartAdmin').hidden = mode() !== 'bundled';
  const profile = latest?.config?.profiles?.[mode()] || (latest?.config?.mode === mode() ? latest.config : null);
  if (restore && profile) { $('host').value = profile.host; $('port').value = profile.port; }
}
function populate(config, state) {
  const value = config || { host: state.addresses[0]?.address || '', port: 9443, name: 'Codex 电脑', threads: [], allowSelectedChats: false, easytier: {} };
  for (const id of ['name', 'host', 'port', 'lanHost', 'codexBinary']) $(id).value = value[id] || '';
  for (const id of ['allowSelectedChats', 'autoStart']) $(id).checked = !!value[id];
  $('threadIds').value = value.threads.map(row => row.id).join('\n');
  document.querySelector(`input[name=mode][value=${value.mode || 'existing'}]`).checked = true;
  const et = value.easytier || {}; $('etName').value = et.name || ''; $('cidr').value = et.cidr || '';
  $('peers').value = (et.peers || []).join('\n'); $('listenPort').value = et.listenPort || 11011; $('rpcPort').value = et.rpcPort || 15899;
  $('networkSecret').placeholder = value.hasSecret ? '已加密保存；留空保留原密钥' : '填写组网密钥'; showMode();
}
async function refresh() {
  latest = await call('status'); const running = latest.gateway.running;
  $('badge').textContent = running ? '手机连接已运行' : '尚未启动'; $('address').textContent = latest.gateway.address || '尚未启动';
  $('desktopStatus').textContent = running ? `实时可控聊天 ${latest.gateway.chats.filter(row => row.ready && row.writable).length} 个 · 最近在线手机 ${latest.gateway.devices.filter(row => row.recentlyOnline).length} 台` : '保存配置后，应用并启动';
  $('etStatus').textContent = `受管实例：${latest.easytier.state} · 版本 ${latest.easytier.version}`;
  if (!initialized) { populate(latest.config, latest); initialized = true; }
  $('addresses').replaceChildren(...latest.addresses.map(row => { const option = document.createElement('option'); option.value = row.address; option.label = row.name; return option; }));
  $('devices').replaceChildren();
  if (!latest.gateway.devices.length) $('devices').textContent = '暂无已授权手机，或连接服务尚未启动。';
  for (const device of latest.gateway.devices) {
    const row = document.createElement('div'); row.className = 'device'; const info = document.createElement('div'); info.textContent = device.name;
    const note = document.createElement('small'); note.textContent = device.recentlyOnline ? '最近 45 秒内有连接' : '已授权'; info.append(note);
    const button = document.createElement('button'); button.className = 'secondary'; button.textContent = '撤销授权';
    button.onclick = () => { if (confirm('撤销这台手机的授权？它需要重新配对才能访问电脑。')) action(() => call('revoke', { id: device.id }), '授权已撤销'); };
    row.append(info, button); $('devices').append(row);
  }
  $('events').replaceChildren(...latest.events.slice().reverse().map(event => { const row = document.createElement('div'); row.className = 'event'; row.textContent = new Date(event.at).toLocaleTimeString() + ' · ' + event.message; return row; }));
}
async function action(fn, success) {
  if (busy) return; busy = true; document.querySelectorAll('button').forEach(button => button.disabled = true);
  try { await fn(); feedback(success); await refresh(); } catch (error) { feedback(error.message, true); }
  finally { busy = false; document.querySelectorAll('button').forEach(button => button.disabled = false); }
}
function config() {
  const prior = latest?.config;
  return { mode: mode(), name: $('name').value, host: $('host').value, port: +$('port').value, lanHost: $('lanHost').value, codexBinary: $('codexBinary').value,
    allowSelectedChats: $('allowSelectedChats').checked, autoStart: $('autoStart').checked,
    threads: $('threadIds').value.split(/\r?\n/).map(id => id.trim()).filter(Boolean).map(id => ({ id, name: prior?.threads.find(row => row.id === id)?.name || '桌面聊天' })),
    networkSecret: $('networkSecret').value, clearSecret: $('clearSecret').checked,
    easytier: { name: $('etName').value, cidr: $('cidr').value, peers: $('peers').value.split(/\r?\n/).map(s => s.trim()).filter(Boolean), listenPort: +$('listenPort').value, rpcPort: +$('rpcPort').value } };
}
$('settings').onsubmit = event => { event.preventDefault(); action(async () => { await call('config', config()); $('networkSecret').value = ''; $('clearSecret').checked = false; }, '配置已保存；点击应用并启动后生效'); };
$('start').onclick = () => action(() => call('start', {}), '手机连接已启动');
$('stop').onclick = () => { if (confirm('停止手机连接？手机将暂时无法远控电脑。')) action(() => call('stop', {}), '连接已停止'); };
$('stopEt').onclick = () => { if (confirm('停止插件拥有的 EasyTier？使用该组网的手机连接也会断开。')) action(() => call('easytier-stop', {}), '受管 EasyTier 已停止'); };
$('restartAdmin').onclick = () => { if (confirm('请求 Windows 管理员授权以创建内置虚拟网卡？确认 UAC 后会重启插件后台，并重新打开设置页；手机可能短暂断开。')) action(() => call('elevate', {}), '已请求 Windows 管理员授权；请处理系统 UAC 提示'); };
$('pair').onclick = () => action(async () => {
  const info = await call('pairing', { rotate: true }); $('pairing').hidden = false; $('pairingJson').value = JSON.stringify(info, null, 2);
  $('expires').textContent = '有效至 ' + new Date(info.expiresAt).toLocaleTimeString() + ' · 新码不会撤销已配对手机';
  if (typeof qrcode !== 'undefined') { const qr = qrcode(0, 'M'); qr.addData(JSON.stringify(info)); qr.make(); $('qr').innerHTML = qr.createSvgTag({ scalable: true }); }
}, '新的配对码已生成');
$('copy').onclick = async () => { try { await navigator.clipboard.writeText($('pairingJson').value); feedback('配对信息已复制'); } catch { $('pairingJson').select(); feedback('请手动复制选中的配对信息'); } };
document.querySelectorAll('input[name=mode]').forEach(input => input.onchange = () => showMode(true));
document.querySelectorAll('[data-section]').forEach(button => button.onclick = () => $(button.dataset.section).scrollIntoView({ behavior: 'smooth', block: 'center' }));
$('settingsHelp').onclick = () => feedback('在 Windows 开始菜单搜索“手机连接设置”，或使用桌面同名快捷方式。设置地址的访问凭据会自动获取，不需要保存网址或向聊天发送指令。旧版安装可重新运行新版安装器来创建入口。');
refresh().catch(error => feedback(error.message, true));
setInterval(() => { if (!busy) refresh().catch(error => feedback('后台连接中断：' + error.message, true)); }, 5000);
