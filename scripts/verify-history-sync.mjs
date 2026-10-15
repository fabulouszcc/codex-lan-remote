// Read-only history verification through HTTPS; no model turns or approvals.
import https from 'node:https';
import { readFile, writeFile } from 'node:fs/promises';
import { X509Certificate } from 'node:crypto';
const TEST = process.env.CODEX_LAN_TEST_THREAD;
if (!TEST) throw new Error('Set CODEX_LAN_TEST_THREAD to an explicitly authorized test chat');
const pairing = JSON.parse(await readFile('.local/phone-pairing.local.json', 'utf8'));
const certificate = new X509Certificate(await readFile('.local/secrets/gateway.crt'));
if (certificate.fingerprint256.replaceAll(':', '').toLowerCase() !== pairing.fingerprint) throw Error('Certificate mismatch');
const base = new URL(pairing.address);
let token;
function request(method, path, body) {
  return new Promise((resolve, reject) => {
    const payload = body ? Buffer.from(JSON.stringify(body)) : undefined;
    const req = https.request({ hostname: base.hostname, port: base.port, method, path, ca: certificate.toString(),
      checkServerIdentity: (_, cert) => cert.fingerprint256 === certificate.fingerprint256 ? undefined : Error('Certificate mismatch'),
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {}) } }, response => {
      const chunks = []; response.on('data', chunk => chunks.push(chunk)); response.on('end', () => {
        try { resolve({ status: response.statusCode, body: JSON.parse(Buffer.concat(chunks)) }); } catch (error) { reject(error); }
      });
    }); req.on('error', reject); req.setTimeout(30000, () => req.destroy(Error('Request timeout'))); req.end(payload);
  });
}
const report = { checkedAt: new Date().toISOString(), scope: 'HTTPS catalog, stored/live history, no model messages, not an Android UI test', checks: {} };
try {
  const paired = await request('POST', '/v1/pair', { code: pairing.code, name: '本机历史读取回归' });
  if (paired.status !== 200) throw Error('Pairing failed'); token = paired.body.token;
  let state = await request('GET', '/v1/state');
  let thread = state.body.threads.find(row => row.id === TEST);
  report.checks.historyBeforeSendingAnyMessage = thread?.turns?.some(turn => turn.agentMessages?.includes('PHONE_TEST_OK')) === true;
  report.checks.memoryHistoryPresent = thread?.turns?.some(turn => turn.agentMessages?.includes('BLUE-731')) === true;
  const catalog = await request('GET', '/v1/threads');
  report.catalogCount = catalog.body.threads.length;
  report.checks.multipleChatCatalog = report.catalogCount > 1;
  const other = catalog.body.threads.find(row => row.id === process.env.CODEX_LAN_SECOND_TEST_THREAD && row.id !== TEST);
  if (!other) throw Error('No second chat for read-only selection check');
  report.checks.selectSecondChat = (await request('POST', '/v1/select', { threadId: other.id })).status === 200;
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    state = await request('GET', `/v1/state?after=${state.body.version}`);
    if (state.body.threads.some(row => row.id === other.id && row.historyReady)) break;
  }
  report.checks.secondChatHistoryLoaded = state.body.threads.some(row => row.id === other.id && row.historyReady);
  report.checks.firstChatRetained = state.body.threads.some(row => row.id === TEST && row.turns.some(turn => turn.agentMessages?.includes('PHONE_TEST_OK')));
  report.checks.selectBack = (await request('POST', '/v1/select', { threadId: TEST })).status === 200;
} catch (error) { report.error = error.message; }
finally { if (token) await request('DELETE', '/v1/session').catch(() => {}); }
report.passed = !report.error && Object.values(report.checks).every(Boolean);
await writeFile('.local/verification/history-sync.local.json', JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
if (!report.passed) process.exitCode = 1;
