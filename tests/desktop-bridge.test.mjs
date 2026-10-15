import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { DesktopBridge } from '../server/desktop-bridge.mjs';

class Client extends EventEmitter {
  initialized = true;
  historyRequests = [];
  enabled = [];
  async connect() {}
  close() {}
  enableThreadControl(id) { this.enabled.push(id); }
  async discoverOwner(id) { if (id === 'closed') throw Error('No owner'); return 'owner'; }
  async followThread(id) {
    this.emit('threadState', { conversationId: id, change: { type: 'snapshot', revision: 1, conversationState: { turns: [{ turnId: 'saved-turn', status: 'completed', items: [] }], requests: [] } } });
  }
  async loadHistory(id) { this.historyRequests.push(id); }
}
const stored = [{ id: 'saved-turn', status: 'completed', input: 'old question', agentMessages: ['old answer'], toolResults: [] }];
function fixture(threads, options = {}) {
  const client = new Client();
  const historyReader = { list: async () => [{ id: 'active', name: 'active' }, { id: 'other', name: 'other' }], turns: async () => structuredClone(stored), close() {} };
  const bridge = new DesktopBridge(threads, { clientFactory: () => client, historyReader, ...options });
  return { client, bridge };
}
test('initial subscription loads history without sending a model message and retains text over thin snapshots', async t => {
  const { bridge, client } = fixture([{ id: 'active' }]); t.after(() => bridge.close());
  await bridge.connect(); const snapshot = bridge.snapshot()[0];
  assert.deepEqual(client.historyRequests, ['active']);
  assert.equal(snapshot.turns[0].input, 'old question'); assert.deepEqual(snapshot.turns[0].agentMessages, ['old answer']);
  assert.equal(snapshot.ready, true); assert.equal(snapshot.historyReady, true);
  client.emit('disconnected');
  assert.equal(bridge.snapshot()[0].ready, false); assert.equal(bridge.snapshot()[0].turns[0].input, 'old question');
});
test('one unloaded chat does not prevent other desktop chats from syncing', async t => {
  const { bridge, client } = fixture([{ id: 'closed' }, { id: 'active' }]); t.after(() => bridge.close());
  await bridge.connect();
  assert.equal(bridge.snapshot().find(row => row.id === 'closed').ready, false);
  assert.equal(bridge.snapshot().find(row => row.id === 'closed').turns.length, 1);
  assert.equal(bridge.snapshot().find(row => row.id === 'active').ready, true);
  assert.deepEqual(client.historyRequests, ['active']);
});
test('mobile selection is validated against local catalog and is read-only unless explicitly configured', async t => {
  const { bridge, client } = fixture([{ id: 'active' }]); t.after(() => bridge.close());
  await bridge.connect(); await assert.rejects(bridge.openThread('unknown'), /列表/);
  await bridge.openThread('other');
  assert.equal(bridge.entries.get('other').writable, false); assert.deepEqual(client.enabled, []);
  const controlled = fixture([{ id: 'active' }], { allowSelectedChats: true }); t.after(() => controlled.bridge.close());
  await controlled.bridge.connect(); await controlled.bridge.openThread('other');
  assert.equal(controlled.bridge.entries.get('other').writable, true); assert.deepEqual(controlled.client.enabled, ['other']);
});
test('current turn comes from the live owner, never an unfinished history snapshot', async t => {
  const { bridge, client } = fixture([{ id: 'active' }]); t.after(() => bridge.close());
  await bridge.connect();
  bridge.entries.get('active').history.push({ id: 'stale-history', status: 'inProgress' });
  assert.equal(bridge.snapshot()[0].activeTurnId, '');
  client.emit('threadState', { conversationId: 'active', change: { type: 'snapshot', revision: 2,
    conversationState: { turns: [{ turnId: 'actual-turn', status: 'inProgress', items: [] }], requests: [] } } });
  assert.equal(bridge.snapshot()[0].activeTurnId, 'actual-turn');
  client.emit('disconnected');
  assert.equal(bridge.snapshot()[0].activeTurnId, '');
});
