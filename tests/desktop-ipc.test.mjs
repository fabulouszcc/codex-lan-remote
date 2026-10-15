import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import net from 'node:net';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DesktopIpcClient, FrameDecoder, encodeFrame } from '../server/desktop-ipc.mjs';

test('frames retain Unicode through single-byte fragmentation and concatenation', () => {
  const output = [];
  const decoder = new FrameDecoder(value => output.push(value));
  // A known wire frame, independent of encodeFrame.
  const first = Buffer.from([7, 0, 0, 0, 123, 34, 120, 34, 58, 49, 125]);
  const second = encodeFrame({ text: '局域网🙂' });
  for (const byte of Buffer.concat([first, second])) decoder.push(Buffer.from([byte]));
  decoder.push(Buffer.concat([first, first]));
  assert.deepEqual(output, [{ x: 1 }, { text: '局域网🙂' }, { x: 1 }, { x: 1 }]);
});

test('decoder rejects zero/oversize frames before allocating and rejects invalid UTF-8/JSON', () => {
  for (const length of [0, 1025]) {
    const header = Buffer.alloc(4);
    header.writeUInt32LE(length);
    assert.throws(() => new FrameDecoder(() => {}, { maxFrameBytes: 1024 }).push(header), /frame length/);
  }
  assert.throws(() => new FrameDecoder(() => {}).push(Buffer.from([1, 0, 0, 0, 0xff])), /encoded data/);
  assert.throws(() => new FrameDecoder(() => {}).push(Buffer.from([1, 0, 0, 0, 0x7b])), SyntaxError);
});

async function fixture(t, onRequest, options = {}) {
  const pipePath = process.platform === 'win32'
    ? `\\\\.\\pipe\\codex-lan-test-${randomUUID()}`
    : join(tmpdir(), `codex-lan-test-${randomUUID()}.sock`);
  const sockets = new Set();
  const messages = [];
  const server = net.createServer(socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {});
    const send = value => socket.write(encodeFrame(value));
    const decoder = new FrameDecoder(message => {
      messages.push(message);
      if (message.type === 'request' && message.method === 'initialize') {
        send({ type: 'response', requestId: message.requestId, method: message.method,
          resultType: 'success', handledByClientId: 'probe-client', result: { clientId: 'probe-client' } });
      } else onRequest(message, send);
    });
    socket.on('data', chunk => { try { decoder.push(chunk); } catch { socket.destroy(); } });
  });
  server.listen(pipePath);
  await once(server, 'listening');
  const client = new DesktopIpcClient({ pipePath, timeoutMs: 250, ...options });
  t.after(async () => {
    client.close();
    for (const socket of sockets) socket.destroy();
    await new Promise(resolveClose => server.close(resolveClose));
  });
  await client.connect();
  return { client, messages };
}

function respond(request, send, result, owner = 'desktop-owner') {
  send({ type: 'response', requestId: request.requestId, method: request.method,
    resultType: 'success', handledByClientId: owner, result });
}

test('discovers actual owner, reads settings, and accepts only matching owner/target/version state', async t => {
  const { client, messages } = await fixture(t, (request, send) => {
    if (request.method === 'thread-owner-discovery') respond(request, send, { supportsUntrustedAppInput: true });
    if (request.method === 'thread-follower-read-model-settings') respond(request, send, { settings: { model: 'test-model' } });
    if (request.method === 'thread-stream-following-changed' && request.params.following) {
      const message = { type: 'broadcast', method: 'thread-stream-state-changed', sourceClientId: 'desktop-owner',
        targetClientIds: ['probe-client'], version: 11,
        params: { hostId: 'local', conversationId: 'test-thread', change: { type: 'snapshot', revision: 1, conversationState: { id: 'test-thread' } } } };
      send({ ...message, sourceClientId: 'wrong-owner' });
      send({ ...message, targetClientIds: ['wrong-client'] });
      send({ ...message, version: 999 });
      send(message);
    }
  });
  const owner = await client.discoverOwner('test-thread');
  assert.equal(owner, 'desktop-owner');
  assert.deepEqual(await client.readModelSettings('test-thread', owner), { model: 'test-model' });
  let accepted = 0;
  let mismatch = 0;
  client.on('threadState', () => accepted++);
  client.on('protocolMismatch', () => mismatch++);
  const state = once(client, 'threadState');
  await client.followThread('test-thread', owner);
  const [value] = await state;
  assert.equal(value.change.conversationState.id, 'test-thread');
  assert.equal(accepted, 1);
  assert.equal(mismatch, 1);
  assert.equal(messages.find(message => message.method === 'thread-follower-read-model-settings').targetClientId, owner);
  assert.deepEqual(messages.filter(message => message.type === 'request').map(message => [message.method, message.version]), [
    ['initialize', 0], ['thread-owner-discovery', 1], ['thread-follower-read-model-settings', 1],
  ]);
});

test('does not silently recover or create another runtime when no desktop owner is available', async t => {
  const { client } = await fixture(t, (request, send) => {
    send({ type: 'response', requestId: request.requestId, resultType: 'error', error: 'no-client-found' });
  });
  await assert.rejects(client.discoverOwner('test-thread'), error => error.reason === 'no-client-found');
});

test('rejects a settings response from the wrong target and expires missing responses', async t => {
  const { client } = await fixture(t, (request, send) => {
    if (request.method === 'thread-follower-read-model-settings') respond(request, send, { settings: {} }, 'wrong-owner');
  });
  await assert.rejects(client.readModelSettings('test-thread', 'desktop-owner'), /does not match/);
  await assert.rejects(client.discoverOwner('test-thread'), /timeout/);
});

test('closed clients reject outstanding requests and cannot reconnect', async t => {
  const { client } = await fixture(t, () => {});
  const pending = client.discoverOwner('test-thread');
  client.close();
  await assert.rejects(pending, /closed/);
  await assert.rejects(client.connect(), /closed/);
});

test('control defaults to disabled and rejects non-allowlisted threads before sending', async () => {
  const client = new DesktopIpcClient({ controlThreadIds: ['allowed-thread'] });
  await assert.rejects(client.startTurn('other-thread', 'test'), /not enabled/);
  await assert.rejects(client.setQueuedFollowUps('other-thread', []), /not enabled/);
  await assert.rejects(client.replyCommandApproval('other-thread', 1, 'accept'), /not enabled/);
  assert.throws(() => client.replyCommandApproval('allowed-thread', 1, 'acceptForSession'), /Unsupported/);
  assert.throws(() => client.interruptTurn('allowed-thread', ''), /active turn ID/);
  client.close();
});

test('explicit control remains bound to the allowlisted thread and actual owner', async t => {
  const { client, messages } = await fixture(t, (request, send) => {
    if (request.method === 'thread-follower-start-turn') respond(request, send, { result: { turn: { id: 'test-turn' } } });
  }, { controlThreadIds: ['allowed-thread'] });
  const result = await client.startTurn('allowed-thread', 'test', { ownerId: 'desktop-owner', request: { threadId: 'other-thread' } });
  assert.equal(result.result.turn.id, 'test-turn');
  const sent = messages.find(message => message.method === 'thread-follower-start-turn');
  assert.equal(sent.params.conversationId, 'allowed-thread');
  assert.equal(sent.params.turnStart.request.threadId, 'allowed-thread');
  assert.equal(sent.targetClientId, 'desktop-owner');
});
