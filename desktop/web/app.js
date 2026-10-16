const $ = id => document.getElementById(id);
const token = location.hash.slice(1) || sessionStorage.getItem('codex-lan-admin');
if (token) sessionStorage.setItem('codex-lan-admin', token);
history.replaceState(null, '', location.pathname);
let initialized = false, latest, busy = false, settingsDirty = false;
async function call(path, body) {
  const response = await fetch('/api/' + path, { method: body === undefined ? 'GET' : 'POST',
    headers: { authorization: 'Bearer ' + token, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const result = await response.json(); if (!response.ok) throw Error(result.error); return result;
}
function feedback(text, error = false) { $('feedback').textContent = text; $('feedback').className = error ? 'error' : ''; }
function mode() { return document.querySelector('input[name=network]:checked').value === 'lan' ? 'existing' : 'bundled'; }
function modeName(value) { return value === 'bundled' ? '内置 EasyTier' : '局域网 / 已有网络'; }
function clearPairing() { $('pairing').hidden = true; $('pairingJson').value = ''; $('qr').replaceChildren(); }
function showMode() {
  const selected = mode(), lan = selected === 'existing'; clearPairing();
  for (const [id, visible] of [['lanSettings',lan],['etSettings',!lan],['bundled',selected === 'bundled']]) { $(id).hidden = !visible; $(id).disabled = !visible; }
  $('phoneCidrRow').hidden = selected !== 'bundled'; updatePairButton();
  $('saveStart').textContent = '保存并启用' + modeName(selected);
  $('modeHint').textContent = '当前编辑' + modeName(selected) + '，其他连接方式的设置保持独立。';
  $('pairHint').textContent = selected === 'bundled' ? '扫码后由 DHCP 自动分配手机地址，无需填写手机 IP（需 Android 0.4.13 及以上）。点击下方按钮即可启用内置 ET 并生成入网配对码。二维码含网络密钥，仅向自己的手机展示。' : '选择已有网卡后生成配对码。Wi-Fi 使用局域网直连；已有 ET 请先让手机加入同一网络，不导出外部 ET 密钥。';
}
function populate(config, state) {
  const value = config || { mode:'existing', name:'Codex 电脑', threads:[], easytier:{} }, profiles = value.profiles || {};
  const current = {host:value.host,port:value.port}; if (!profiles[value.mode]) profiles[value.mode] = current;
  const lan = value.mode === 'external' ? (profiles.external || current) : (profiles.existing || {}), bundled = profiles.bundled || {};
  const suggested = state.addresses[0];
  $('lanIp').value = lan.host || suggested?.address || ''; $('lanPort').value = lan.port || 9443;
  $('etPort').value = bundled.port || 9443;
  for (const id of ['name','codexBinary']) $(id).value = value[id] || '';
  for (const id of ['allowSelectedChats','autoStart']) $(id).checked = !!value[id];
  $('threadIds').value = (value.threads || []).map(row => row.id).join('\n');
  document.querySelector(`input[name=network][value=${value.mode === 'bundled' ? 'easytier' : 'lan'}]`).checked = true;
  const et = value.easytier || {}; $('etName').value = et.name || ''; $('cidr').value = et.cidr || '';
  $('peers').value = (et.peers || []).join('\n'); $('listenPort').value = et.listenPort || 11011; $('rpcPort').value = et.rpcPort || 15899;
  $('networkSecret').placeholder = value.hasSecret ? '已加密保存；留空保留原密钥' : '填写组网密钥'; showMode();
}
async function refresh() {
  latest = await call('status'); const running = latest.gateway.running;
  $('badge').textContent = running ? modeName(latest.gateway.mode || latest.config?.mode) + '已启用' : '尚未启动'; $('activeMode').textContent = running ? '当前启用 · ' + modeName(latest.gateway.mode || latest.config?.mode) : '当前连接'; $('address').textContent = latest.gateway.address || '尚未启动';
  $('desktopStatus').textContent = running ? `实时可控聊天 ${latest.gateway.chats.filter(row => row.ready && row.writable).length} 个 · 最近在线手机 ${latest.gateway.devices.filter(row => row.recentlyOnline).length} 台` : '选择连接方式，保存并启用';
  $('etStatus').textContent = `受管实例：${latest.easytier.state} · 版本 ${latest.easytier.version}`;
  if (!initialized) { populate(latest.config, latest); initialized = true; }
  $('lanAddresses').replaceChildren(...latest.addresses.map(row => { const option = document.createElement('option'); option.value = row.address; option.label = row.name; return option; }));
  const adapterOptions = [Object.assign(document.createElement('option'), {value:'',textContent:'手动填写地址'}), ...latest.addresses.map(row => Object.assign(document.createElement('option'), {value:row.address,textContent:row.name+' · '+row.address}))];
  $('lanAdapter').replaceChildren(...adapterOptions); $('lanAdapter').value = latest.addresses.some(row=>row.address === $('lanIp').value) ? $('lanIp').value : '';
  $('devices').replaceChildren();
  if (!latest.gateway.devices.length) $('devices').textContent = '暂无已授权手机，或连接服务尚未启动。';
  for (const device of latest.gateway.devices) {
    const row = document.createElement('div'); row.className = 'device'; const info = document.createElement('div'); info.textContent = device.name;
    const note = document.createElement('small'); note.textContent = device.recentlyOnline ? '最近 45 秒内有连接' : '已授权'; info.append(note);
    const button = document.createElement('button'); button.className = 'secondary'; button.textContent = '撤销授权';
    button.onclick = () => { if (confirm('撤销这台手机的授权？它需要重新配对才能访问电脑。')) action(() => call('revoke', { id: device.id }), '授权已撤销'); };
    row.append(info, button); $('devices').append(row);
  }
  updatePairButton();
  $('events').replaceChildren(...latest.events.slice().reverse().map(event => { const row = document.createElement('div'); row.className = 'event'; row.textContent = new Date(event.at).toLocaleTimeString() + ' · ' + event.message; return row; }));
}
async function action(fn, success) {
  if (busy) return; busy = true; const controls = Array.from(document.querySelectorAll('button,input,textarea,select')).map(node => [node,node.disabled]); controls.forEach(([node]) => node.disabled = true);
  try { await fn(); feedback(success); await refresh(); } catch (error) { feedback(error.message, true); }
  finally { busy = false; controls.forEach(([node,disabled]) => node.disabled = disabled); }
}
function config() {
  const prior = latest?.config, selected = mode();
  const host = selected === 'existing' ? $('lanIp').value : $('cidr').value.trim().split('/')[0];
  const port = +$(selected === 'existing' ? 'lanPort' : 'etPort').value;
  return { mode:selected, name:$('name').value, host, port, codexBinary:$('codexBinary').value,
    allowSelectedChats:$('allowSelectedChats').checked, autoStart:$('autoStart').checked,
    threads:$('threadIds').value.split(/\r?\n/).map(id=>id.trim()).filter(Boolean).map(id=>({id,name:prior?.threads.find(row=>row.id === id)?.name || '桌面聊天'})),
    ...(selected === 'bundled' ? { networkSecret:$('networkSecret').value, clearSecret:$('clearSecret').checked,
      easytier:{name:$('etName').value,cidr:$('cidr').value,peers:$('peers').value.split(/\r?\n/).map(s=>s.trim()).filter(Boolean),listenPort:+$('listenPort').value,rpcPort:+$('rpcPort').value} } : {}) };
}
function saveSettings(start) {
  if (!$('settings').reportValidity()) return;
  const input = config(); clearPairing();
  return action(async () => {
    await call('config',input); settingsDirty = false;
    if (input.mode === 'bundled') { $('networkSecret').value = ''; $('clearSecret').checked = false; }
    if (start) await call('start',{});
  }, start ? modeName(input.mode) + '已启用' : modeName(input.mode) + '设置已保存，当前运行连接未切换');
}
$('settings').onsubmit = event => { event.preventDefault(); saveSettings(true); };
$('saveOnly').onclick = () => saveSettings(false);
$('start').onclick = () => $('settings').requestSubmit();
$('stop').onclick = () => { if (confirm('停止手机连接？手机将暂时无法远控电脑。')) action(() => call('stop', {}), '连接已停止'); };
$('stopEt').onclick = () => { if (confirm('停止插件拥有的 EasyTier？使用该组网的手机连接也会断开。')) action(() => call('easytier-stop', {}), '受管 EasyTier 已停止'); };
$('restartAdmin').onclick = () => { if (confirm('请求 Windows 管理员授权以创建内置虚拟网卡？确认 UAC 后会重启插件后台，并重新打开设置页；手机可能短暂断开。')) action(() => call('elevate', {}), '已请求 Windows 管理员授权；请处理系统 UAC 提示'); };
function pairingNeedsApply() { return settingsDirty || !latest?.gateway.running || mode() !== latest?.gateway.mode || mode() !== latest?.config?.mode; }
function updatePairButton() { $('pair').textContent = pairingNeedsApply() ? '保存启用并生成配对码' : '生成新的配对码'; }
$('pair').onclick = () => {
  const apply = pairingNeedsApply(), selected = mode();
  if (apply && !$('settings').reportValidity()) return;
  const draft = apply ? config() : undefined;
  return action(async () => {
  clearPairing();
  const info = await call('pairing', { rotate:true, mode:selected, apply, ...(apply ? {config:draft} : {}) });
  if (apply) { settingsDirty = false; if (selected === 'bundled') { $('networkSecret').value = ''; $('clearSecret').checked = false; } }
  $('pairing').hidden = false; $('pairingJson').value = JSON.stringify(info, null, 2);
  $('expires').textContent = '有效至 ' + new Date(info.expiresAt).toLocaleTimeString() + ' · 新码不会撤销已配对手机';
  try { if (typeof qrcode === 'undefined') throw Error(); const qr = qrcode(0, 'M'); qr.addData(unescape(encodeURIComponent(JSON.stringify(info)))); qr.make(); $('qr').innerHTML = qr.createSvgTag({ scalable: true }); }
  catch { clearPairing(); throw Error('二维码生成失败，请减少组网参数后重试'); }
  const expires = info.expiresAt; setTimeout(() => { if ($('pairingJson').value.includes(expires)) clearPairing(); }, Math.max(0, Date.parse(expires) - Date.now()));
}, '新的配对码已生成');
};
$('copy').onclick = async () => { try { await navigator.clipboard.writeText($('pairingJson').value); feedback('配对信息已复制'); } catch { $('pairingJson').select(); feedback('请手动复制选中的配对信息'); } };
document.querySelectorAll('input[name=network]').forEach(input => input.onchange = () => { settingsDirty = true; showMode(); });
document.querySelectorAll('#settings input, #settings textarea, #settings select').forEach(input => input.addEventListener('input', () => { settingsDirty = true; updatePairButton(); }));
document.querySelectorAll('#settings input, #settings textarea, #settings select, #phoneCidr').forEach(input => input.addEventListener('input', clearPairing));
$('lanAdapter').onchange = () => { if ($('lanAdapter').value) $('lanIp').value = $('lanAdapter').value; settingsDirty = true; clearPairing(); updatePairButton(); };

document.addEventListener('visibilitychange', () => { if (document.hidden) clearPairing(); });
document.querySelectorAll('[data-section]').forEach(button => button.onclick = () => $(button.dataset.section).scrollIntoView({ behavior: 'smooth', block: 'center' }));
$('settingsHelp').onclick = () => feedback('在 Windows 开始菜单搜索“手机连接设置”，或使用桌面同名快捷方式。设置地址的访问凭据会自动获取，不需要保存网址或向聊天发送指令。旧版安装可重新运行新版安装器来创建入口。');
function backendUnavailable(error) {
  $('badge').textContent = '后台连接已中断'; $('activeMode').textContent = '连接状态待确认';
  $('desktopStatus').textContent = '页面上的地址是上次记录，当前运行状态未确认。';
  feedback('无法连接本机设置后台。若刚请求管理员启动，请完成系统授权，并从插件入口重新打开设置。\n' + error.message, true);
}
refresh().catch(backendUnavailable);
setInterval(() => { if (!busy) refresh().catch(backendUnavailable); }, 5000);
