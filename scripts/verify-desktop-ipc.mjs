import { DesktopIpcClient } from '../server/desktop-ipc.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i];
  if (!['--thread', '--duration-ms', '--out'].includes(key) || !process.argv[i + 1]) throw new Error('Usage: node scripts/verify-desktop-ipc.mjs --thread <id> [--duration-ms 15000] [--out <local json>]');
  options[key.slice(2)] = process.argv[i + 1];
}
if (!options.thread) throw new Error('--thread is required');
const duration = Number(options['duration-ms'] || 15000);
if (!Number.isInteger(duration) || duration < 100 || duration > 60000) throw new Error('Duration must be 100..60000 ms');

const client = new DesktopIpcClient();
const report = {
  checkedAt: new Date().toISOString(), transport: 'existing_desktop_named_pipe',
  privateProtocol: true, ownerDiscovered: false, modelSettingsRead: false,
  snapshots: 0, patches: 0, snapshotMatchesRequestedId: false,
  revisionGaps: 0, protocolMismatch: false, observations: [],
  modelTaskSubmitted: false, newAppServerStarted: false,
};
let revision;
client.on('protocolMismatch', () => { report.protocolMismatch = true; });
client.on('threadState', ({ change }) => {
  if (change.type === 'snapshot') {
    report.snapshots++;
    const state = change.conversationState;
    report.snapshotMatchesRequestedId ||= state?.id === options.thread;
    report.snapshotHasSessionId ||= typeof state?.sessionId === 'string';
    report.snapshotHasRuntimeStatus ||= state?.threadRuntimeStatus != null;
    report.snapshotContainsCurrentTurnHistory ||= state?.turnHistory != null;
    // Persist counts and booleans only, never transcript content, paths, or settings values.
    report.observations.push({ type: 'snapshot', revision: change.revision });
  } else {
    report.patches++;
    if (revision === undefined || change.baseRevision !== revision) report.revisionGaps++;
    report.observations.push({ type: 'patches', baseRevision: change.baseRevision, revision: change.revision, patchCount: change.patches?.length ?? 0 });
  }
  revision = change.revision;
  if (report.observations.length > 20) report.observations.shift();
});

try {
  await client.connect();
  report.initialized = client.initialized;
  const owner = await client.discoverOwner(options.thread);
  report.ownerDiscovered = true;
  const settings = await client.readModelSettings(options.thread, owner);
  report.modelSettingsRead = settings !== null;
  await client.followThread(options.thread, owner);
  console.log(JSON.stringify({ observing: true, durationMs: duration, ownerDiscovered: true }));
  await delay(duration);
  report.ok = report.initialized && report.ownerDiscovered && report.modelSettingsRead && report.snapshotMatchesRequestedId && !report.protocolMismatch;
  report.realtimeIncrementalUpdatesVerified = report.patches > 0 && report.revisionGaps === 0;
} catch (error) {
  report.ok = false;
  report.error = error.reason || error.code || error.message;
} finally { client.close(); }

if (options.out) {
  const path = resolve(options.out);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(report, null, 2) + '\n');
}
console.log(JSON.stringify(report, null, 2));
if (!report.ok) process.exitCode = 1;
