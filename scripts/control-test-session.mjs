// Local stdin-driven test driver. No TCP listener and no cross-thread control.
// Only use with a dedicated test thread explicitly authorized by the user.
import { DesktopIpcClient } from '../server/desktop-ipc.mjs';
import { ThreadState, getTurns, summarizeThread } from '../server/thread-state.mjs';
import { createInterface } from 'node:readline';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

const threadId = process.argv[2];
if (!threadId || process.argv[3] !== '--allow-control') throw new Error('Usage: node scripts/control-test-session.mjs <authorized test thread> --allow-control');
const client = new DesktopIpcClient({ timeoutMs: 20000, controlThreadIds: [threadId] });
const store = new ThreadState();
const output = resolve('.local/verification/control-test-state.local.json');
mkdirSync(dirname(output), { recursive: true });
let owner;
let updateCount = 0;
let lastStatus;
let lastTurn;
const requestIds = new Set();
const log = value => console.log(JSON.stringify(value));
client.on('threadState', ({ change }) => {
  try {
    const state = store.apply(change);
    updateCount++;
    const summary = summarizeThread(state);
    writeFileSync(output, JSON.stringify({ threadId, revision: store.revision, updateCount, summary }, null, 2) + '\n');
    const turn = getTurns(state).at(-1);
    const status = `${state.threadRuntimeStatus?.type}:${turn?.status}`;
    if (status !== lastStatus || turn?.turnId !== lastTurn) {
      lastStatus = status; lastTurn = turn?.turnId;
      log({ event: 'state', runtime: state.threadRuntimeStatus?.type, turnId: turn?.turnId, status: turn?.status, turnCount: summary.turnCount });
    }
    for (const request of state.requests || []) {
      const id = request.id ?? request.requestId;
      if (!requestIds.has(id)) {
        requestIds.add(id);
        log({ event: 'approvalOrInput', request });
      }
    }
  } catch (error) { log({ event: 'stateError', message: error.message }); }
});
client.on('protocolMismatch', event => log({ event: 'protocolMismatch', ...event }));
client.on('disconnected', () => log({ event: 'disconnected' }));
await client.connect();
owner = await client.discoverOwner(threadId);
await client.followThread(threadId, owner);
log({ ready: true, threadId, output });

const lines = createInterface({ input: process.stdin });
for await (const line of lines) {
  let command;
  try {
    command = JSON.parse(line);
    let result;
    if (command.op === 'status') result = { revision: store.revision, updateCount, summary: summarizeThread(store.state) };
    else if (command.op === 'start') result = await client.startTurn(threadId, command.text, { ownerId: owner, request: command.request, context: command.context });
    else if (command.op === 'steer') result = await client.steerTurn(threadId, command.text, { ownerId: owner, cwd: store.state.cwd });
    else if (command.op === 'interrupt') {
      const active = getTurns(store.state).findLast(turn => turn.status === 'inProgress' && turn.turnId);
      if (!active) throw new Error('No active test turn');
      result = await client.interruptTurn(threadId, active.turnId, owner);
    } else if (command.op === 'queue') {
      const id = randomUUID();
      const message = { id, text: command.text, createdAt: Date.now(), cwd: store.state.cwd,
        context: { prompt: command.text, addedFiles: [], fileAttachments: [], ideContext: null, imageAttachments: [], commentAttachments: [] },
        submissionIntent: 'queue', submission: { hostId: 'local', status: 'queued', queueModeOverride: 'queue' } };
      result = { id, response: await client.setQueuedFollowUps(threadId, [message], { ownerId: owner }) };
    } else if (command.op === 'approve') {
      const request = store.state?.requests?.find(request => (request.id ?? request.requestId) === command.requestId);
      if (!request) throw new Error('Approval request is stale or not present in test thread');
      const available = request.params?.availableDecisions;
      if (available && !available.includes(command.decision)) throw new Error('Decision is not offered by this approval request');
      const kind = String(request.type || request.method || '');
      if (/command/i.test(kind)) result = await client.replyCommandApproval(threadId, command.requestId, command.decision, owner);
      else if (/file/i.test(kind)) result = await client.replyFileApproval(threadId, command.requestId, command.decision, owner);
      else throw new Error('Unsupported approval request kind');
    } else if (command.op === 'close') { log({ op: 'close', ok: true }); break; }
    else throw new Error('Unknown test operation');
    log({ op: command.op, ok: true, result });
  } catch (error) { log({ op: command?.op, ok: false, error: error.reason || error.message }); }
}
lines.close();
process.stdin.pause();
client.close();
