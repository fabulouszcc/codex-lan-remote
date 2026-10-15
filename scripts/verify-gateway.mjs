// Explicit opt-in model call, restricted to the previously authorized test chat.
import https from 'node:https';
import { readFile, writeFile } from 'node:fs/promises';
import { X509Certificate, randomUUID } from 'node:crypto';
import { join } from 'node:path';
const TEST_THREAD = process.env.CODEX_LAN_TEST_THREAD;
if (!TEST_THREAD) throw new Error('Set CODEX_LAN_TEST_THREAD to an explicitly authorized test chat');
if (process.argv.includes('--control-regression') && !process.env.CODEX_LAN_TEST_WORKSPACE) throw new Error('Set CODEX_LAN_TEST_WORKSPACE to the dedicated test workspace');
if (!process.argv.includes('--allow-test-turn')) throw new Error('Pass --allow-test-turn to send one task to the authorized test chat');
const pairing = JSON.parse(await readFile(process.env.CODEX_LAN_PAIRING_FILE || '.local/phone-pairing.local.json', 'utf8'));
const cert = new X509Certificate(await readFile('.local/secrets/gateway.crt'));
if (cert.fingerprint256.replaceAll(':', '').toLowerCase() !== pairing.fingerprint) throw new Error('Local certificate fingerprint mismatch');
const address = new URL(pairing.address);
function request(method, path, value, token, operationId) {
  return new Promise((resolve, reject) => {
    const payload = value ? Buffer.from(JSON.stringify(value)) : undefined;
    const req = https.request({ hostname: address.hostname, port: address.port, path, method,
      ca: cert.toString(), checkServerIdentity: (_, certificate) => certificate.fingerprint256 === cert.fingerprint256 ? undefined : new Error('Pin mismatch'),
      headers: { ...(payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}), ...(operationId ? { 'idempotency-key': operationId } : {}) } }, response => {
      const chunks = []; response.on('data', data => chunks.push(data)); response.on('end', () => {
        try { resolve({ status: response.statusCode, body: JSON.parse(Buffer.concat(chunks)) }); } catch (error) { reject(error); }
      });
    }); req.setTimeout(30000, () => req.destroy(new Error('Network timeout'))); req.on('error', reject); req.end(payload);
  });
}
const report = { checkedAt: new Date().toISOString(), scope: 'HTTPS gateway to existing desktop test chat; not an Android device test', checks: {} };
let token;
try {
  report.checks.privateStateDeniedWithoutPairing = (await request('GET', '/v1/state')).status === 401;
  const paired = await request('POST', '/v1/pair', { code: pairing.code, name: '本机 HTTPS 联调验证' });
  if (paired.status !== 200) throw new Error('Pairing failed'); token = paired.body.token;
  report.checks.pair = true;
  let response = await request('GET', '/v1/state', undefined, token);
  let thread = response.body.threads?.find(thread => thread.id === TEST_THREAD);
  if (!thread) throw new Error('Dedicated authorized test chat is not available');
  if (!thread.ready) throw new Error('Desktop test chat not ready');
  report.checks.readSameDesktopChat = true;
  const marker = `LAN_GATEWAY_${randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase()}`;
  const sendRegression = process.argv.includes('--send-state-regression');
  if (sendRegression) {
    if (thread.activeTurnId !== '') throw new Error('Idle test chat must publish an empty string, not null');
    report.checks.idleWireValueSafeForAndroid = true;
  }
  // Match Android optString coercion for this regression (JSON null -> "null").
  const operation = sendRegression && String(thread.activeTurnId).length ? 'steer' : 'start';
  const command = { op: operation, threadId: TEST_THREAD, text: `LAN_TEST：这是已授权的 HTTPS 连接服务端到端验证。不要调用工具、不要修改文件，只回复 ${marker}` };
  const id = randomUUID();
  const started = await request('POST', '/v1/command', command, token, id);
  if (started.status !== 200) throw new Error('Desktop task was not acknowledged');
  report.checks.submitViaHttps = true;
  report.checks.retrySameOperationAcknowledged = (await request('POST', '/v1/command', command, token, id)).status === 200;
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    response = await request('GET', `/v1/state?after=${response.body.version}`, undefined, token);
    thread = response.body.threads.find(thread => thread.id === TEST_THREAD);
    if (thread.turns.some(turn => turn.status === 'completed' && turn.agentMessages?.some(text => text.includes(marker)))) break;
  }
  report.checks.streamedResult = thread.turns.some(turn => turn.status === 'completed' && turn.agentMessages?.some(text => text.includes(marker)));
  report.checks.oneUserTurn = thread.turns.filter(turn => turn.input === command.text).length === 1;
  if (sendRegression) report.checks.completedTaskReturnsToSendMode = thread.activeTurnId === '';
  if (process.argv.includes('--control-regression')) {
    const wait = async predicate => {
      const deadline = Date.now() + 90000;
      while (Date.now() < deadline) {
        response = await request('GET', `/v1/state?after=${response.body.version}`, undefined, token);
        thread = response.body.threads.find(thread => thread.id === TEST_THREAD);
        const result = predicate(thread); if (result) return result;
      }
      throw new Error('Control regression timed out');
    };
    const submit = async command => {
      const response = await request('POST', '/v1/command', { threadId: TEST_THREAD, ...command }, token, randomUUID());
      if (response.status !== 200) throw new Error(`Control operation ${command.op} not acknowledged`);
    };
    const suffix = randomUUID().slice(0, 8);
    const steerInput = `LAN_TEST_PHONE_V02_${suffix}：在当前测试工作目录执行 PowerShell：Start-Sleep -Seconds 12。结束后回复 ORIGINAL_${suffix}。不要修改文件。`;
    await submit({ op: 'start', text: steerInput });
    const active = await wait(thread => thread.turns.find(turn => turn.input === steerInput && turn.status === 'inProgress' && turn.toolResults?.some(tool => tool.type === 'commandExecution' && tool.status === 'inProgress')));
    await submit({ op: 'steer', text: `LAN_TEST：修改这一轮最终回复，只回复 PHONE_V02_STEER_${suffix}，忽略之前的回复要求。` });
    const steered = await wait(thread => thread.turns.find(turn => turn.id === active.id && turn.status === 'completed'));
    report.checks.steerSameActiveTurn = steered.agentMessages?.some(text => text.includes(`PHONE_V02_STEER_${suffix}`)) === true;
    for (const decision of ['accept', 'cancel']) {
      const file = `phone-v02-${decision}-${suffix}.txt`;
      const text = `LAN_TEST_PHONE_V02_${decision}_${suffix}：这是已授权的文件审批回归。只使用 apply_patch 在当前测试工作目录新建 ${file}，内容一行 PHONE_V02_${decision.toUpperCase()}。必须等待工具要求的审批；取消后不要重试或换工具。`;
      await submit({ op: 'start', text });
      const pending = await wait(thread => thread.approvals.find(approval => approval.kind === 'file') || thread.turns.find(turn => turn.input === text && turn.status === 'completed'));
      if (pending.kind !== 'file') throw new Error('Real file approval was not triggered');
      await submit({ op: 'approve', requestId: pending.id, turnId: pending.turnId, decision });
      await wait(thread => thread.approvals.length === 0 && !thread.turns.some(turn => turn.status === 'inProgress'));
      const filePath = join(process.env.CODEX_LAN_TEST_WORKSPACE, file);
      let content; try { content = await readFile(filePath, 'utf8'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      report.checks[decision === 'accept' ? 'realFileApprovalAccept' : 'realFileApprovalCancel'] = decision === 'accept' ? content?.trim() === 'PHONE_V02_ACCEPT' : content === undefined;
    }
  }
  report.checks.revoke = (await request('DELETE', '/v1/session', undefined, token)).status === 200;
  report.checks.revokedTokenDenied = (await request('GET', '/v1/state', undefined, token)).status === 401;
  token = null;
} catch (error) { report.error = error.message; }
finally { if (token) await request('DELETE', '/v1/session', undefined, token).catch(() => {}); }
report.passed = !report.error && Object.values(report.checks).every(Boolean);
await writeFile(process.argv.includes('--send-state-regression') ? '.local/verification/send-null-e2e.local.json' : '.local/verification/gateway-e2e.local.json', JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;
