import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { savedTurnBusy } from '../server/app-server-client.mjs';
import { DesktopBridge } from '../server/desktop-bridge.mjs';

const event = type => JSON.stringify({ type: 'event_msg', payload: { type } }) + '\n';

test('persisted turn checks ignore unrelated records and require a terminal lifecycle event', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'codex-lan-turn-test-'));
  t.after(() => rm(directory, { recursive: true }));
  const file = join(directory, 'session.jsonl');
  const longItem = JSON.stringify({
    type: 'response_item',
    payload: { type: 'task_complete', text: 'large'.repeat(40000) },
  }) + '\n';
  const busy = event('task_complete') + event('task_started') + longItem;
  await writeFile(file, busy);
  assert.equal(await savedTurnBusy(file, Buffer.byteLength(busy)), true);

  const stopped = busy + event('turn_aborted');
  await writeFile(file, stopped);
  assert.equal(await savedTurnBusy(file, Buffer.byteLength(stopped)), false);

  const partial = stopped + '{"type":"event_msg"';
  await writeFile(file, partial);
  await assert.rejects(savedTurnBusy(file, Buffer.byteLength(partial)), /正在更新/);
});

test('a missing desktop pipe enables saved history sync without enabling writes for a running desktop turn', async t => {
  class FallbackClient extends EventEmitter {
    isAppServer = true;
    async connect() { this.initialized = true; }
    async discoverOwner() { return 'app-server'; }
    async followThread() {}
    close() {}
  }
  const fallback = new FallbackClient();
  let legacyClosed = false;
  const bridge = new DesktopBridge([{ id: 'local-chat' }], {
    clientFactory: () => ({
      async connect() { throw Object.assign(Error('Pipe missing'), { code: 'ENOENT' }); },
      close() { legacyClosed = true; },
    }),
    fallbackClientFactory: () => fallback,
  });
  t.after(() => bridge.close());
  await bridge.connect();
  assert.equal(legacyClosed, true);

  fallback.emit('savedState', {
    conversationId: 'local-chat', turns: [{ id: 'first', status: 'inProgress' }],
    ready: false, revision: 1,
  });
  assert.equal(bridge.snapshot()[0].historyReady, true);
  assert.equal(bridge.snapshot()[0].ready, false);
  await assert.rejects(bridge.command('local-chat', { op: 'start', text: 'next' }), /还在执行/);

  fallback.emit('savedState', {
    conversationId: 'local-chat', turns: [{ id: 'first', status: 'completed', agentMessages: ['done'] }],
    ready: true, revision: 2,
  });
  assert.equal(bridge.snapshot()[0].ready, true);
  assert.deepEqual(bridge.snapshot()[0].turns[0].agentMessages, ['done']);
  assert.equal(bridge.snapshot()[0].queueSupported, false);
});
