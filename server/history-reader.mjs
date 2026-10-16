// Separate read-only app-server for persisted metadata/history. Never resumes a thread.
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { turnTimeline, turnInput } from './turn-timeline.mjs';
export class HistoryReader {
  constructor(binary) { this.binary = binary; this.pending = new Map(); this.sequence = 0; }
  async connect() {
    if (this.initialized && this.child) return;
    if (this.connecting) return this.connecting;
    this.connecting = this.open();
    try { await this.connecting; } finally { this.connecting = null; }
  }
  async open() {
    const child = spawn(this.binary, ['app-server', '--listen', 'stdio://'], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    this.child = child;
    child.stderr.on('data', () => {}); child.stdin.on('error', () => {});
    const failed = () => {
      if (this.child !== child) return;
      this.child = null; this.initialized = false;
      for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(Error('历史读取连接不可用')); }
      this.pending.clear();
    };
    child.on('error', failed); child.on('exit', failed);
    this.lines = createInterface({ input: child.stdout });
    this.lines.on('line', line => {
      let value; try { value = JSON.parse(line); } catch { return; }
      if (value.method && value.id !== undefined) {
        child.stdin.write(JSON.stringify({ id: value.id, error: { code: -32601, message: 'Read-only history client' } }) + '\n'); return;
      }
      const entry = this.pending.get(value.id); if (!entry) return;
      this.pending.delete(value.id); clearTimeout(entry.timer);
      if (value.error) entry.reject(Error('历史接口读取失败')); else entry.resolve(value.result);
    });
    await this.request('initialize', { clientInfo: { name: 'codex_lan_history', version: '0.2.0' }, capabilities: { experimentalApi: true } });
    child.stdin.write('{"method":"initialized"}\n');
    this.initialized = true;
  }
  request(method, params) {
    if (!['initialize', 'thread/list', 'thread/turns/list', 'model/list'].includes(method)) throw Error('历史客户端禁止该操作');
    return new Promise((resolve, reject) => {
      const id = ++this.sequence;
      const timer = setTimeout(() => { this.pending.delete(id); reject(Error('历史读取超时')); }, 15000);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n', error => {
        if (error) { this.pending.delete(id); clearTimeout(timer); reject(Error('历史读取连接不可用')); }
      });
    });
  }
  async list() {
    await this.connect();
    const value = await this.request('thread/list', { limit: 100, sourceKinds: [], sortKey: 'updated_at' });
    return (value.data || []).map(thread => ({ id: thread.id, name: thread.name || thread.preview?.slice(0, 70) || '未命名聊天', updatedAt: thread.updatedAt }));
  }
  async models() {
    await this.connect();
    const result=[];let cursor;
    for(let page=0;page<5;page++){
      const value=await this.request('model/list',{limit:100,includeHidden:false,...cursor?{cursor}:{}});
      result.push(...(value.data||[]).filter(model=>!model.hidden).map(model=>({
        model:model.model,displayName:model.displayName||model.model,
        defaultEffort:model.defaultReasoningEffort,
        efforts:(model.supportedReasoningEfforts||[]).map(option=>option.reasoningEffort),
      })));
      cursor=value.nextCursor;if(!cursor)break;
    }
    return result;
  }
  async turns(threadId) {
    await this.connect();
    const value = await this.request('thread/turns/list', { threadId, limit: 50, sortDirection: 'desc', itemsView: 'full' });
    return (value.data || []).reverse().map(turn => ({
      id: turn.id, status: turn.status, startedAtMs: turn.startedAt ? turn.startedAt * 1000 : undefined,
      input: turnInput(turn),
      agentMessages: (turn.items || []).filter(item => item.type === 'agentMessage').map(item => item.text),
      timeline: turnTimeline(turn.items),
      itemTypes: (turn.items || []).map(item => item.type),
      toolResults: (turn.items || []).filter(item => ['commandExecution', 'fileChange'].includes(item.type)).map(item => ({ id: item.id, type: item.type, status: item.status, exitCode: item.exitCode })),
    }));
  }
  close() {
    const child = this.child; this.child = null; this.initialized = false;
    for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(Error('历史读取已关闭')); } this.pending.clear();
    this.lines?.close(); child?.stdin.end(); const timer = setTimeout(() => child?.kill(), 500); timer.unref();
  }
}
