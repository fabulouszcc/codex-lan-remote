// Adapter for the private Windows desktop coordination protocol observed in
// Codex desktop 26.1007.9446.0. This is not a public or stable plugin API.
// Defaults to read-only; mutations require an explicit per-client thread allowlist.
import net from 'node:net';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';

export const DEFAULT_PIPE = '\\\\.\\pipe\\codex-ipc';
export const PROTOCOL_VERSIONS = Object.freeze({
  initialize: 0,
  'thread-owner-discovery': 1,
  'thread-follower-read-model-settings': 1,
  'thread-follower-update-thread-settings': 2,
  'thread-stream-following-changed': 1,
  'thread-stream-state-changed': 11,
  'thread-follower-start-turn': 2,
  'thread-follower-load-complete-history': 1,
  'thread-follower-steer-turn': 1,
  'thread-follower-interrupt-turn': 4,
  'thread-follower-set-queued-follow-ups-state': 1,
  'thread-follower-remove-queued-message': 1,
  'thread-follower-clear-queued-messages': 1,
  'thread-follower-command-approval-decision': 1,
  'thread-follower-file-approval-decision': 1,
  'thread-follower-submit-user-input': 1,
});

export function encodeFrame(message) {
  const body = Buffer.from(JSON.stringify(message), 'utf8');
  const header = Buffer.alloc(4);
  header.writeUInt32LE(body.length);
  return Buffer.concat([header, body]);
}

export class FrameDecoder {
  constructor(onMessage, { maxFrameBytes = 32 * 1024 * 1024 } = {}) {
    this.onMessage = onMessage;
    this.maxFrameBytes = maxFrameBytes;
    this.header = Buffer.alloc(4);
    this.headerBytes = 0;
    this.body = null;
    this.bodyBytes = 0;
  }
  push(chunk) {
    let offset = 0;
    while (offset < chunk.length) {
      if (this.body === null) {
        const size = Math.min(4 - this.headerBytes, chunk.length - offset);
        chunk.copy(this.header, this.headerBytes, offset, offset + size);
        offset += size;
        this.headerBytes += size;
        if (this.headerBytes !== 4) continue;
        const length = this.header.readUInt32LE(0);
        if (!length || length > this.maxFrameBytes) throw new Error('Invalid IPC frame length');
        this.body = Buffer.allocUnsafe(length);
        this.bodyBytes = 0;
        this.headerBytes = 0;
      }
      const size = Math.min(this.body.length - this.bodyBytes, chunk.length - offset);
      chunk.copy(this.body, this.bodyBytes, offset, offset + size);
      this.bodyBytes += size;
      offset += size;
      if (this.bodyBytes !== this.body.length) continue;
      const text = new TextDecoder('utf-8', { fatal: true }).decode(this.body);
      this.body = null;
      this.bodyBytes = 0;
      this.onMessage(JSON.parse(text));
    }
  }
}

function validateThreadId(id) {
  if (typeof id !== 'string' || !/^[a-zA-Z0-9_-]{1,128}$/.test(id)) throw new Error('Invalid thread ID');
}

export class DesktopIpcClient extends EventEmitter {
  #socket;
  #clientId = 'initializing-client';
  #pending = new Map();
  #subscriptions = new Map();
  #closed = false;
  #connected = false;
  #connecting;
  #controlThreadIds;
  constructor({ pipePath = DEFAULT_PIPE, timeoutMs = 5000, maxFrameBytes, controlThreadIds = [] } = {}) {
    super();
    this.pipePath = pipePath;
    this.timeoutMs = timeoutMs;
    this.#controlThreadIds = new Set(controlThreadIds);
    this.decoder = new FrameDecoder(message => this.#onMessage(message), { maxFrameBytes });
  }
  get initialized() { return this.#connected && this.#clientId !== 'initializing-client' && !this.#closed; }

  async connect() {
    if (this.#closed) throw new Error('IPC client closed');
    if (this.initialized) return;
    if (this.#connecting) return this.#connecting;
    this.#connecting = this.#open();
    try { await this.#connecting; } catch (error) { this.close(); throw error; }
    finally { this.#connecting = undefined; }
  }

  async #open() {
    const socket = net.createConnection(this.pipePath);
    this.#socket = socket;
    socket.on('error', error => this.#fail(error));
    socket.on('close', () => {
      this.#connected = false;
      this.#fail(new Error('Desktop IPC disconnected'));
      this.emit('disconnected');
    });
    socket.on('data', chunk => {
      try { this.decoder.push(chunk); }
      catch (error) { this.#fail(error); socket.destroy(); }
    });
    await new Promise((resolveConnect, reject) => {
      const cleanup = () => { clearTimeout(timer); socket.off('connect', onConnect); socket.off('error', onError); socket.off('close', onClose); };
      const onConnect = () => { cleanup(); this.#connected = true; resolveConnect(); };
      const onError = error => { cleanup(); reject(error); };
      const onClose = () => { cleanup(); reject(new Error('Desktop IPC disconnected during connect')); };
      const timer = setTimeout(() => { cleanup(); reject(new Error('Desktop IPC connection timeout')); }, this.timeoutMs);
      socket.once('connect', onConnect);
      socket.once('error', onError);
      socket.once('close', onClose);
    });
    const response = await this.#request('initialize', { clientType: 'codex-lan-adapter' });
    if (typeof response.result?.clientId !== 'string') throw new Error('Invalid desktop IPC initialize response');
    this.#clientId = response.result.clientId;
  }

  #fail(error) {
    for (const request of this.#pending.values()) { clearTimeout(request.timer); request.reject(error); }
    this.#pending.clear();
    this.emit('transportError', error);
  }

  #send(message) {
    if (this.#closed || !this.#socket?.writable) throw new Error('Desktop IPC not connected');
    // Probe traffic is small; fail instead of accumulating unlimited outgoing frames.
    if (this.#socket.writableLength > 1024 * 1024) throw new Error('Desktop IPC send buffer full');
    this.#socket.write(encodeFrame(message));
  }

  #request(method, params, targetClientId) {
    if (!Object.hasOwn(PROTOCOL_VERSIONS, method)) throw new Error('IPC method is not allowed');
    return new Promise((resolveRequest, reject) => {
      const requestId = randomUUID();
      const timer = setTimeout(() => { this.#pending.delete(requestId); reject(new Error(`Desktop IPC ${method} timeout`)); }, this.timeoutMs);
      this.#pending.set(requestId, { resolve: resolveRequest, reject, timer, method, targetClientId });
      try {
        this.#send({ type: 'request', requestId, sourceClientId: this.#clientId,
          method, version: PROTOCOL_VERSIONS[method], params, targetClientId, timeoutMs: this.timeoutMs });
      } catch (error) { clearTimeout(timer); this.#pending.delete(requestId); reject(error); }
    });
  }

  #onMessage(message) {
    if (!message || typeof message !== 'object') throw new Error('Invalid IPC message');
    if (message.type === 'client-discovery-request') {
      // Never advertise ownership or execute another client's request.
      this.#send({ type: 'client-discovery-response', requestId: message.requestId, response: { canHandle: false } });
      return;
    }
    if (message.type === 'request') {
      this.#send({ type: 'response', requestId: message.requestId, resultType: 'error', error: 'readonly-client' });
      return;
    }
    if (message.type === 'response') {
      const request = this.#pending.get(message.requestId);
      if (!request) return;
      this.#pending.delete(message.requestId);
      clearTimeout(request.timer);
      if (message.resultType !== 'success') {
        const error = new Error('Desktop IPC request failed');
        error.reason = typeof message.error === 'string' ? message.error : 'unknown';
        request.reject(error);
      } else if (message.method !== request.method || (request.targetClientId && message.handledByClientId !== request.targetClientId)) {
        request.reject(new Error('Desktop IPC response does not match target request'));
      } else request.resolve(message);
      return;
    }
    if (message.type !== 'broadcast') return;
    if (message.targetClientIds && !message.targetClientIds.includes(this.#clientId)) return;
    if (message.method !== 'thread-stream-state-changed') return;
    const { conversationId, hostId, change } = message.params || {};
    const owner = this.#subscriptions.get(conversationId);
    if (!owner || hostId !== 'local' || message.sourceClientId !== owner) return;
    if (message.version !== PROTOCOL_VERSIONS[message.method]) {
      this.emit('protocolMismatch', { method: message.method, version: message.version });
      return;
    }
    if (change?.type !== 'snapshot' && change?.type !== 'patches') return;
    // Raw snapshots may include user content. Consumers must not log them by default.
    this.emit('threadState', { conversationId, change });
  }

  async discoverOwner(threadId) {
    validateThreadId(threadId);
    if (!this.initialized) throw new Error('Initialize desktop IPC first');
    const response = await this.#request('thread-owner-discovery', { hostId: 'local', conversationId: threadId });
    if (!response.handledByClientId) throw new Error('Desktop did not identify the thread owner');
    return response.handledByClientId;
  }

  async readModelSettings(threadId, ownerId) {
    validateThreadId(threadId);
    const response = await this.#request('thread-follower-read-model-settings', { conversationId: threadId }, ownerId);
    return response.result?.settings ?? null;
  }

  async loadHistory(threadId, ownerId) {
    validateThreadId(threadId);
    const owner = ownerId || await this.discoverOwner(threadId);
    const response = await this.#request('thread-follower-load-complete-history', { conversationId: threadId }, owner);
    return response.result;
  }

  updateModelSettings(threadId, model, effort, { ownerId } = {}) {
    if (typeof model !== 'string' || !model.trim() || model.length > 160) throw Error('Invalid model');
    if (typeof effort !== 'string' || !effort || effort.length > 32) throw Error('Invalid reasoning effort');
    return this.#control(threadId, 'thread-follower-update-thread-settings', { threadSettings: { model, effort } }, ownerId);
  }

  enableThreadControl(threadId) {
    validateThreadId(threadId);
    this.#controlThreadIds.add(threadId);
  }

  async #control(threadId, method, params, ownerId) {
    validateThreadId(threadId);
    if (!this.#controlThreadIds.has(threadId)) throw new Error('Control is not enabled for this thread');
    if (!this.initialized) throw new Error('Initialize desktop IPC first');
    const owner = ownerId || await this.discoverOwner(threadId);
    const response = await this.#request(method, { ...params, conversationId: threadId }, owner);
    return response.result;
  }

  startTurn(threadId, text, { ownerId, request = {}, context = {} } = {}) {
    if (typeof text !== 'string' || !text.trim()) throw new Error('A non-empty test message is required');
    return this.#control(threadId, 'thread-follower-start-turn', {
      turnStart: { request: { ...request, threadId, clientUserMessageId: randomUUID(), input: [{ type: 'text', text, text_elements: [] }] }, context },
    }, ownerId);
  }

  steerTurn(threadId, text, { ownerId, cwd } = {}) {
    if (typeof text !== 'string' || !text.trim()) throw new Error('A non-empty steering message is required');
    const id = randomUUID();
    return this.#control(threadId, 'thread-follower-steer-turn', {
      input: [{ type: 'text', text, text_elements: [] }], clientUserMessageId: id,
      restoreMessage: { id, text, cwd, createdAt: Date.now(),
        context: { prompt: text, addedFiles: [], fileAttachments: [], ideContext: null, imageAttachments: [], commentAttachments: [] } },
    }, ownerId);
  }

  interruptTurn(threadId, expectedTurnId, ownerId) {
    if (typeof expectedTurnId !== 'string' || !expectedTurnId) throw new Error('Expected active turn ID is required');
    return this.#control(threadId, 'thread-follower-interrupt-turn', { expectedTurnId, mode: 'user-stop' }, ownerId);
  }

  setQueuedFollowUps(threadId, messages, { ownerId, discardedMessageIds = [] } = {}) {
    if (!Array.isArray(messages)) throw new Error('Queued messages must be an array');
    return this.#control(threadId, 'thread-follower-set-queued-follow-ups-state', {
      state: { [threadId]: messages }, discardedMessageIds,
    }, ownerId);
  }

  replyCommandApproval(threadId, requestId, decision, ownerId) {
    if (!['accept', 'decline', 'cancel'].includes(decision)) throw new Error('Unsupported single-request approval decision');
    return this.#control(threadId, 'thread-follower-command-approval-decision', { requestId, decision }, ownerId);
  }

  replyFileApproval(threadId, requestId, decision, ownerId) {
    if (!['accept', 'decline', 'cancel'].includes(decision)) throw new Error('Unsupported single-request approval decision');
    return this.#control(threadId, 'thread-follower-file-approval-decision', { requestId, decision }, ownerId);
  }

  async followThread(threadId, ownerId) {
    validateThreadId(threadId);
    const owner = ownerId || await this.discoverOwner(threadId);
    this.#subscriptions.set(threadId, owner);
    try { this.#following(threadId, owner, true); }
    catch (error) { this.#subscriptions.delete(threadId); throw error; }
  }

  #following(threadId, ownerId, following) {
    this.#send({ type: 'broadcast', sourceClientId: this.#clientId,
      targetClientIds: [ownerId], method: 'thread-stream-following-changed', version: 1,
      params: { hostId: 'local', conversationId: threadId, following } });
  }

  unfollowThread(threadId) {
    const owner = this.#subscriptions.get(threadId);
    if (!owner) return;
    try { this.#following(threadId, owner, false); }
    finally { this.#subscriptions.delete(threadId); }
  }

  close() {
    if (this.#closed) return;
    for (const [threadId, owner] of this.#subscriptions) {
      try { this.#following(threadId, owner, false); } catch {}
    }
    this.#subscriptions.clear();
    this.#closed = true;
    this.#fail(new Error('Desktop IPC client closed'));
    this.#socket?.end();
    const socket = this.#socket;
    const timer = setTimeout(() => socket?.destroy(), 500);
    timer.unref();
  }
}
