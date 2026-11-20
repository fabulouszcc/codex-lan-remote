import { EventEmitter } from 'node:events';
import { spawn, execFile } from 'node:child_process';
import { open, stat } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { resolveDesktopBinary } from './desktop-binary.mjs';

const approvalMethods = new Set([
  'item/commandExecution/requestApproval',
  'item/fileChange/requestApproval',
  'item/tool/requestUserInput',
]);
const lifecycleEvents = new Set(['task_started', 'task_complete', 'turn_aborted']);
const lifecycle = /"type"\s*:\s*"(task_started|task_complete|turn_aborted)"/;
const readBlockSize = 64 * 1024;
const maxLifecycleScan = 16 * 1024 * 1024;
const busyMessage = '电脑上的这一轮还在执行，请等它完成后再从手机续发';

function input(text) {
  return [{ type: 'text', text, text_elements: [] }];
}

function classifyHistoryLine(line) {
  if (!lifecycle.test(line)) return null;
  try {
    const { type, payload } = JSON.parse(line);
    if (type !== 'event_msg' || !lifecycleEvents.has(payload?.type)) return null;
    return payload.type;
  } catch {
    return null;
  }
}

// The read-only server can reconstruct another process's unfinished turn as
// interrupted. Check the persisted lifecycle too, so it cannot be resumed early.
export async function savedTurnBusy(path, size) {
  const file = await open(path, 'r');
  try {
    const end = Math.max(0, size - maxLifecycleScan);
    let suffix = Buffer.alloc(0);
    for (let cursor = size; cursor > end;) {
      const position = Math.max(end, cursor - readBlockSize);
      const buffer = Buffer.alloc(cursor - position);
      const { bytesRead } = await file.read(buffer, 0, buffer.length, position);
      if (bytesRead !== buffer.length) throw Error('会话历史正在更新，请稍后再试');
      if (cursor === size && buffer.at(-1) !== 10) throw Error('会话历史正在更新，请稍后再试');
      const bytes = Buffer.concat([buffer, suffix]);
      let lineEnd = bytes.length;
      for (let i = bytes.length - 1; i >= 0; i--) {
        if (bytes[i] !== 10) continue;
        const event = classifyHistoryLine(bytes.toString('utf8', i + 1, lineEnd));
        if (event) return event === 'task_started';
        lineEnd = i;
      }
      suffix = bytes.subarray(0, lineEnd);
      cursor = position;
    }
    if (end > 0) throw Error('暂时无法确认上一轮是否已结束');
    return classifyHistoryLine(suffix.toString('utf8')) === 'task_started';
  } finally {
    await file.close();
  }
}

async function assertUnprivileged() {
  if (process.platform !== 'win32') throw Error('此手机连接后端仅支持 Windows');
  const script = '[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)';
  const { stdout } = await promisify(execFile)('powershell.exe', [
    '-NoProfile', '-NonInteractive', '-Command', script,
  ], { windowsHide: true, timeout: 5000 });
  if (stdout.trim() !== 'False') throw Error('App Server 手机后端需要以普通用户权限运行');
}

class AppServerRun extends EventEmitter {
  constructor(binary) {
    super();
    this.sequence = 0;
    this.pending = new Map();
    this.child = spawn(binary, ['app-server', '--listen', 'stdio://'], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'],
    });
    this.child.stdin.on('error', () => {});
    this.child.once('error', () => this.finish());
    this.child.once('exit', () => this.finish());
    this.lines = createInterface({ input: this.child.stdout });
    this.lines.on('line', line => {
      try {
        const row = JSON.parse(line);
        if (row.method) {
          this.emit('message', row);
          return;
        }
        const pending = this.pending.get(row.id);
        if (!pending) return;
        this.pending.delete(row.id);
        clearTimeout(pending.timer);
        if (row.error) {
          // Map the writer conflict without exposing provider URLs or input.
          const writerHeld = pending.method === 'thread/resume'
            && /already has (an active|a live local) writer/i.test(row.error.message || '');
          pending.reject(Error(writerHeld
            ? '桌面仍占用这个聊天。请等电脑上的任务完成并退出 Codex 桌面端，再从手机续发'
            : 'Codex App Server 未接受 ' + pending.method + ' 请求，请检查电脑端的模型配置和权限'));
        } else {
          pending.resolve(row.result);
        }
      } catch {
        // Ignore non-protocol output and keep the stream available.
      }
    });
  }

  async connect() {
    await this.request('initialize', {
      clientInfo: { name: 'codex_lan_app_server', version: '0.5.0' },
      capabilities: { experimentalApi: true },
    });
    this.write({ method: 'initialized' });
  }

  write(value) {
    if (this.ended || !this.child.stdin.writable) throw Error('App Server 连接已断开');
    this.child.stdin.write(JSON.stringify(value) + '\n');
  }

  request(method, params) {
    return new Promise((resolve, reject) => {
      const id = ++this.sequence;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(Error('App Server 确认超时，请刷新状态后再试'));
      }, 20000);
      this.pending.set(id, { method, resolve, reject, timer });
      try {
        this.write({ id, method, params });
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
      }
    });
  }

  finish() {
    if (this.ended) return;
    this.ended = true;
    this.lines?.close();
    this.child.stdin.end();
    for (const entry of this.pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(Error('App Server 连接已断开'));
    }
    this.pending.clear();
    this.emit('ended');
  }

  close() {
    this.finish();
    const timer = setTimeout(() => {
      if (this.child.exitCode === null && this.child.signalCode === null) this.child.kill();
    }, 1500);
    timer.unref();
  }
}

export class AppServerClient extends EventEmitter {
  isAppServer = true;
  initialized = false;

  constructor({ binary, reader, controlThreadIds }) {
    super();
    this.binary = binary;
    this.reader = reader;
    this.controlThreadIds = new Set(controlThreadIds);
    this.watched = new Map();
    this.runs = new Map();
    this.settings = new Map();
    this.revision = 0;
  }

  async connect() {
    await assertUnprivileged();
    this.binary = await resolveDesktopBinary(this.binary);
    await this.reader.connect();
    this.initialized = true;
    this.timer = setInterval(() => {
      for (const [id, entry] of this.watched) {
        if (!entry.loading && !this.runs.has(id)) void this.refresh(id, entry).catch(() => {});
      }
    }, 3000);
    this.timer.unref();
  }

  enableThreadControl(id) {
    this.controlThreadIds.add(id);
  }

  async discoverOwner() {
    if (!this.initialized) throw Error('App Server 尚未就绪');
    return 'local-app-server';
  }

  async followThread(id) {
    if (!this.watched.has(id)) this.watched.set(id, {});
    await this.refresh(id, this.watched.get(id));
  }

  unfollowThread(id) {
    this.watched.delete(id);
    if (!this.runs.has(id)) this.settings.delete(id);
  }

  async readSaved(id) {
    const thread = await this.reader.savedThread(id);
    if (!thread?.path || !thread.path.endsWith('.jsonl')) throw Error('只能续聊已保存在本机的会话');
    const info = await stat(thread.path);
    return { thread, info, key: info.mtimeMs + ':' + info.size };
  }

  async refresh(id, entry) {
    if (entry.loading || this.runs.has(id)) return;
    entry.loading = true;
    try {
      const { thread, info, key } = await this.readSaved(id);
      if (key === entry.key) return;
      const busy = await savedTurnBusy(thread.path, info.size);
      const turns = await this.reader.turns(id);
      const after = await stat(thread.path);
      if (after.size !== info.size || after.mtimeMs !== info.mtimeMs) return;
      if (this.watched.get(id) !== entry || this.runs.has(id)) return;
      entry.key = key;
      this.emit('savedState', { conversationId: id, turns, cwd: thread.cwd, ready: !busy, revision: ++this.revision });
    } catch (error) {
      entry.key = undefined;
      if (this.watched.get(id) === entry && !this.runs.has(id)) {
        this.emit('savedStateUnavailable', id);
      }
      throw error;
    } finally {
      entry.loading = false;
    }
  }

  async assertIdle(id) {
    const { thread, info, key } = await this.readSaved(id);
    if (thread.status?.type === 'active' || await savedTurnBusy(thread.path, info.size)) throw Error(busyMessage);
    const after = await stat(thread.path);
    if (after.size !== info.size || after.mtimeMs !== info.mtimeMs) {
      throw Error('桌面聊天刚更新，请刷新后再续发');
    }
    return { thread, key };
  }

  publish(run) {
    this.emit('threadState', {
      conversationId: run.id,
      change: {
        type: 'snapshot',
        revision: ++this.revision,
        conversationState: {
          id: run.id,
          cwd: run.cwd,
          latestThreadSettings: run.settings,
          threadRuntimeStatus: { type: run.turn?.status === 'inProgress' ? 'active' : 'idle' },
          turns: run.turn ? [{ ...run.turn, turnId: run.turn.id,
            turnStartedAtMs: run.turn.startedAt ? run.turn.startedAt * 1000 : run.startedAt,
            items: [...run.items.values()] }] : [],
          requests: [...run.approvals.values()],
        },
      },
    });
  }

  onMessage(run, message) {
    const { method, params = {}, id } = message;
    if (id !== undefined) {
      if (approvalMethods.has(method) && params.threadId === run.id && typeof params.turnId === 'string') {
        // The first item notification may follow the approval request.
        if (params.itemId && !run.items.has(params.itemId)) {
          const type = method.includes('commandExecution') ? 'commandExecution'
            : method.includes('fileChange') ? 'fileChange' : 'userInput';
          run.items.set(params.itemId, { id: params.itemId, type, status: 'inProgress' });
        }
        run.approvals.set(String(id), { id: String(id), rpcId: id, method, params });
        this.publish(run);
      } else {
        run.rpc.write({ id, error: { code: -32601, message: 'Request unsupported by the mobile client' } });
      }
      return;
    }
    if (params.threadId !== run.id) return;
    if (method === 'turn/started' || method === 'turn/completed') {
      run.turn = params.turn;
      for (const item of run.turn.items || []) run.items.set(item.id, item);
      if (method === 'turn/completed') {
        run.approvals.clear();
        // Finished history is persisted by the server. Let the next refresh
        // reload it from disk before another run can start.
        run.finishTimer = setTimeout(() => run.rpc.close(), 600);
      }
    } else if (method === 'item/started' || method === 'item/completed') {
      run.items.set(params.item.id, params.item);
    } else if (method === 'item/agentMessage/delta' || method === 'item/commandExecution/outputDelta') {
      const agent = method === 'item/agentMessage/delta';
      const field = agent ? 'text' : 'aggregatedOutput';
      const item = run.items.get(params.itemId) || { id: params.itemId, type: agent ? 'agentMessage' : 'commandExecution' };
      run.items.set(params.itemId, { ...item, [field]: ((item[field] || '') + params.delta).slice(0, 64000) });
    } else if (method === 'serverRequest/resolved') {
      run.approvals.delete(String(params.requestId));
    } else {
      return;
    }
    this.publish(run);
  }

  async startTurn(id, text) {
    if (!this.initialized || !this.controlThreadIds.has(id)) throw Error('此聊天未授权续发');
    if (this.runs.has(id)) throw Error('这一轮尚未结束，请稍后重试');
    if (this.runs.size >= 3) throw Error('当前有多个手机任务正在运行，请稍后重试');

    const run = { id, items: new Map(), approvals: new Map(), startedAt: Date.now() };
    this.runs.set(id, run);
    let startedRequest = false;
    try {
      const { thread, key } = await this.assertIdle(id);
      const latestTurnId = await this.reader.latestSavedTurnId(id);
      if (!this.initialized) throw Error('手机连接已停止');
      run.rpc = new AppServerRun(this.binary);
      run.rpc.on('message', message => this.onMessage(run, message));
      run.rpc.once('ended', () => this.finishRun(run));
      await run.rpc.connect();
      const resumed = await run.rpc.request('thread/resume', {
        threadId: id,
        excludeTurns: true,
        modelProvider: thread.modelProvider || undefined,
        sandbox: 'workspace-write',
        approvalPolicy: 'on-request',
      });
      if (resumed.thread?.id !== id) throw Error('App Server 未恢复选中的会话');
      run.cwd = resumed.cwd;
      const selection = this.settings.get(id);
      run.settings = {
        model: selection?.model ?? resumed.model,
        effort: selection?.reasoningEffort ?? resumed.reasoningEffort,
        serviceTier: selection?.serviceTier ?? resumed.serviceTier ?? 'default',
      };
      // Refresh after resume. Resume may append its own metadata, so compare
      // lifecycle again rather than rejecting its harmless metadata writes.
      const current = await this.assertIdle(id);
      if (current.key !== key && await this.reader.latestSavedTurnId(id) !== latestTurnId) {
        throw Error('桌面刚完成了新的一轮，请刷新后再续发');
      }
      if (!this.initialized) throw Error('手机连接已停止');
      startedRequest = true;
      const { turn } = await run.rpc.request('turn/start', {
        threadId: id, input: input(text), model: selection?.model,
        effort: selection?.reasoningEffort,
        serviceTier: selection?.serviceTier === 'priority' ? 'priority' : undefined,
      });
      if (!run.turn) {
        run.turn = turn;
        for (const item of turn.items || []) run.items.set(item.id, item);
        this.publish(run);
      }
    } catch (error) {
      if (!startedRequest || !String(error.message).includes('确认超时')) {
        if (run.rpc) run.rpc.close();
        else this.finishRun(run);
      }
      throw error;
    }
  }

  finishRun(run) {
    clearTimeout(run.finishTimer);
    if (this.runs.get(run.id) !== run) return;
    this.runs.delete(run.id);
    const entry = this.watched.get(run.id);
    if (!entry || !this.initialized) return;
    entry.key = undefined;
    if (run.turn?.status === 'inProgress') this.emit('savedStateUnavailable', run.id);
    void this.refresh(run.id, entry).catch(() => {});
  }


  async readModelSettings(id) {
    const selection = this.settings.get(id);
    if (selection) return selection;
    const run = this.runs.get(id);
    return run?.settings ? { model: run.settings.model, reasoningEffort: run.settings.effort } : null;
  }

  async updateModelSettings(id, model, reasoningEffort, { serviceTier } = {}) {
    if (this.runs.has(id)) throw Error('请在这一轮结束后切换模型');
    this.settings.set(id, { model, reasoningEffort, serviceTier });
    return { applied: true };
  }

  steerTurn(id, text) {
    const run = this.runs.get(id);
    if (!run?.turn || run.turn.status !== 'inProgress') throw Error('手机没有正在执行的这一轮');
    return run.rpc.request('turn/steer', { threadId: id, expectedTurnId: run.turn.id, input: input(text) });
  }

  interruptTurn(id, turnId) {
    const run = this.runs.get(id);
    if (!run?.turn || run.turn.id !== turnId) throw Error('任务已变化，请刷新后再操作');
    return run.rpc.request('turn/interrupt', { threadId: id, turnId });
  }

  replyApproval(id, requestId, method, result) {
    const run = this.runs.get(id);
    const approval = run?.approvals.get(String(requestId));
    if (!approval || approval.method !== method) throw Error('请求已失效，请刷新');
    run.rpc.write({ id: approval.rpcId, result });
  }

  replyCommandApproval(id, requestId, decision) {
    this.replyApproval(id, requestId, 'item/commandExecution/requestApproval', { decision });
  }

  replyFileApproval(id, requestId, decision) {
    this.replyApproval(id, requestId, 'item/fileChange/requestApproval', { decision });
  }

  replyUserInput(id, requestId, answers) {
    this.replyApproval(id, requestId, 'item/tool/requestUserInput', { answers });
  }

  close() {
    this.initialized = false;
    clearInterval(this.timer);
    this.watched.clear();
    for (const run of this.runs.values()) run.rpc?.close();
  }
}
