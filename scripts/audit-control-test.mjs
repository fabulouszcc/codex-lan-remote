// Reads the authorized test conversation and checks actual file/rollout outcomes.
import { DesktopIpcClient } from '../server/desktop-ipc.mjs';
import { ThreadState, getTurns } from '../server/thread-state.mjs';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const threadId = process.argv[2];
if (!threadId) throw new Error('Usage: node scripts/audit-control-test.mjs <authorized test thread>');
const client = new DesktopIpcClient();
const store = new ThreadState();
await client.connect();
try {
  const owner = await client.discoverOwner(threadId);
  await new Promise((resolveSnapshot, reject) => {
    const timer = setTimeout(() => reject(new Error('Snapshot timeout')), 5000);
    client.on('threadState', ({ change }) => {
      try { store.apply(change); clearTimeout(timer); resolveSnapshot(); }
      catch (error) { clearTimeout(timer); reject(error); }
    });
    client.followThread(threadId, owner).catch(reject);
  });
} finally { client.close(); }
const state = store.state;
if (!state.cwd?.includes('codex-lan-control-test')) throw new Error('Only the dedicated test workspace can be audited');
const turns = getTurns(state);
const find = prefix => turns.find(turn => turn.params?.input?.some(input => input.text?.startsWith(prefix)));
const replied = (turn, marker) => turn?.status === 'completed' && turn.items?.some(item => item.type === 'agentMessage' && item.phase === 'final_answer' && item.text?.trim() === marker);
const step1 = find('LAN_TEST_STEP_1');
const memory = find('LAN_TEST_STEP_2');
const base = find('LAN_TEST_QUEUE_BASE');
const queued = find('LAN_TEST_QUEUED');
const interrupted = find('LAN_TEST_INTERRUPT：');
const foreground = find('LAN_TEST_INTERRUPT_FOREGROUND');
const accepted = find('LAN_TEST_APPROVAL_ACCEPT');
const denied = find('LAN_TEST_APPROVAL_DENY');
const fileApproval = find('LAN_TEST_FILE_APPROVAL');
const steered = find('LAN_TEST_STEER_BASE');
const reconnected = find('LAN_TEST_RECONNECT');
async function markerFile(name, marker) {
  try { return (await readFile(resolve(state.cwd, 'work', name), 'utf8')).trim() === marker; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}
let deniedFileAbsent;
try { await readFile(resolve(state.cwd, 'work/approval-deny.txt')); deniedFileAbsent = false; }
catch (error) { if (error.code !== 'ENOENT') throw error; deniedFileAbsent = true; }
const markers = new Set();
let storedThreadMatches = false;
if (state.rolloutPath) {
  const rollout = await readFile(state.rolloutPath, 'utf8');
  for (const line of rollout.split('\n').filter(Boolean)) {
    const record = JSON.parse(line);
    if (record.type === 'session_meta') storedThreadMatches ||= record.payload?.id === threadId;
    let text;
    if (record.type === 'event_msg' && record.payload?.type === 'agent_message') text = record.payload.message;
    if (record.type === 'response_item' && record.payload?.type === 'message' && record.payload.role === 'assistant') {
      text = record.payload.content?.map(content => content.text || '').join('\n');
    }
    for (const marker of text?.match(/LAN_TEST_[A-Z0-9_-]+/g) || []) markers.add(marker);
  }
}
function interruptionResult(turn) {
  const command = turn?.items?.find(item => item.type === 'commandExecution');
  const outputMarkers = command?.aggregatedOutput?.match(/LAN_TEST_[A-Z0-9_-]+/g) || [];
  return { turnId: turn?.turnId, turnStatus: turn?.status, commandStatus: command?.status,
    exitCode: command?.exitCode, commandDurationMs: command?.durationMs,
    turnDurationMs: turn?.durationMs, outputMarkers,
    processStillFinishedNormally: command?.exitCode === 0 && outputMarkers.some(marker => marker.includes('SHOULD_NOT_FINISH')) };
}
const checks = {
  submit: replied(step1, 'LAN_TEST_ACK_PINE-472'),
  continueWithContext: replied(memory, 'LAN_TEST_MEMORY_PINE-472'),
  queuedExecution: replied(base, 'LAN_TEST_BASE_DONE') && replied(queued, 'LAN_TEST_QUEUE_DONE')
    && queued.turnStartedAtMs >= base.turnStartedAtMs + base.durationMs,
  interruptConversation: interrupted?.status === 'interrupted',
  commandApprovalAccept: replied(accepted, 'LAN_TEST_APPROVED') && await markerFile('approval-accept.txt', 'LAN_TEST_APPROVED'),
  commandApprovalCancel: denied?.status === 'interrupted' && deniedFileAbsent
    && denied.items?.some(item => item.type === 'commandExecution' && item.status === 'declined'),
  fileApprovalAccept: replied(fileApproval, 'LAN_TEST_FILE_APPROVED') && await markerFile('file-approval.txt', 'LAN_TEST_FILE_APPROVED'),
  steerSameTurn: replied(steered, 'LAN_TEST_STEERED') && steered.items?.some(item => item.type === 'steeringUserMessage'),
  continueAfterClientReconnect: replied(reconnected, 'LAN_TEST_RECONNECTED_PINE-472'),
  storedThreadIdMatches: storedThreadMatches,
  storedAssistantResponses: ['LAN_TEST_ACK_PINE-472', 'LAN_TEST_MEMORY_PINE-472', 'LAN_TEST_QUEUE_DONE', 'LAN_TEST_APPROVED', 'LAN_TEST_FILE_APPROVED', 'LAN_TEST_STEERED', 'LAN_TEST_RECONNECTED_PINE-472'].every(marker => markers.has(marker)),
  noPendingApproval: state.requests?.length === 0,
};
const report = {
  checkedAt: new Date().toISOString(), threadId, turnCount: turns.length, revision: store.revision,
  checks, passed: Object.values(checks).filter(Boolean).length, total: Object.keys(checks).length,
  backgroundCommandInterruption: interruptionResult(interrupted),
  foregroundCommandInterruption: interruptionResult(foreground),
  scope: 'local desktop IPC; actual model turns and approvals; no Android or EasyTier transport test',
  desktopRestartTested: false, multiDeviceApprovalRaceTested: false,
};
report.processStopChecks = {
  backgroundCommandStopped: ['failed', 'completed', 'interrupted'].includes(report.backgroundCommandInterruption.commandStatus)
    && typeof report.backgroundCommandInterruption.exitCode === 'number' && report.backgroundCommandInterruption.exitCode !== 0
    && !report.backgroundCommandInterruption.processStillFinishedNormally,
  foregroundCommandStopped: ['failed', 'completed', 'interrupted'].includes(report.foregroundCommandInterruption.commandStatus)
    && typeof report.foregroundCommandInterruption.exitCode === 'number' && report.foregroundCommandInterruption.exitCode !== 0
    && !report.foregroundCommandInterruption.processStillFinishedNormally,
};
report.overallFullyPassed = report.passed === report.total && Object.values(report.processStopChecks).every(Boolean);
await mkdir('.local/verification', { recursive: true });
await writeFile('.local/verification/control-flow-results.local.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (!report.overallFullyPassed) process.exitCode = 2;
