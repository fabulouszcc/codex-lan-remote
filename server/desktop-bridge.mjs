import { EventEmitter } from 'node:events';
import { DesktopIpcClient } from './desktop-ipc.mjs';
import { ThreadState, getTurns, summarizeThread } from './thread-state.mjs';
import { pendingQuestions, validateAnswers } from './user-questions.mjs';

export class DesktopBridge extends EventEmitter {
  constructor(threads, { clientFactory = options => new DesktopIpcClient(options), historyReader, allowSelectedChats = false } = {}) {
    super();
    this.entries = new Map(threads.map(thread => [thread.id, { ...thread, writable: true, history: [], store: new ThreadState(), ready: false, consumed: new Set() }]));
    this.historyReader = historyReader;
    this.allowSelectedChats = allowSelectedChats;
    this.catalogRows = threads;
    this.clientFactory = clientFactory;
    this.generation = 0;
    this.closed = false;
    this.refreshTimer = setInterval(() => {
      if (!this.closed && this.client?.initialized) for (const entry of this.entries.values()) if (!entry.ready) void this.hydrate(entry, this.generation);
    }, 10000);
    this.refreshTimer.unref();
  }
  async connect() {
    if (this.closed) return;
    const generation = ++this.generation;
    this.client?.close();
    for (const entry of this.entries.values()) { entry.ready = false; }
    const client = this.clientFactory({ controlThreadIds: [...this.entries.values()].filter(entry => entry.writable).map(entry => entry.id), timeoutMs: 30000 });
    this.client = client;
    client.on('threadState', ({ conversationId, change }) => {
      if (generation !== this.generation) return;
      const entry = this.entries.get(conversationId);
      if (!entry) return;
      try { entry.store.apply(change); entry.ready = true; this.emit('changed'); }
      catch { this.repairEntry(entry, generation); }
    });
    client.on('disconnected', () => {
      if (generation !== this.generation || this.closed) return;
      for (const entry of this.entries.values()) entry.ready = false;
      this.emit('changed'); this.scheduleReconnect();
    });
    client.on('protocolMismatch', ({ conversationId } = {}) => {
      if(generation!==this.generation||this.closed)return;
      const entry=this.entries.get(conversationId);
      if(entry)this.repairEntry(entry,generation);
    });
    try {
      await client.connect();
      await Promise.allSettled([...this.entries.values()].map(entry => this.hydrate(entry, generation)));
    } catch { this.scheduleReconnect(); }
  }
  async catalog() {
    if (this.historyReader && (!this.catalogAt || Date.now() - this.catalogAt > 15000)) {
      try { this.catalogRows = await this.historyReader.list(); this.catalogAt = Date.now(); } catch { /* Keep known chats available. */ }
    }
    const rows = new Map(this.catalogRows.map(row => [row.id, row]));
    for (const entry of this.entries.values()) rows.set(entry.id, { ...rows.get(entry.id), id: entry.id, name: entry.name });
    return [...rows.values()];
  }
  async usage() {
    return this.historyReader?.usage ? this.historyReader.usage() : {available:false,primary:null,secondary:null,updatedAt:null,stale:true};
  }
  async openThread(id) {
    if (this.entries.has(id)) {
      const entry = this.entries.get(id); entry.historyReady = false;
      void this.hydrate(entry, this.generation); return;
    }
    const row = (await this.catalog()).find(row => row.id === id);
    if (!row) throw Error('聊天不在本机列表中');
    if (this.entries.size >= 24) throw Error('已打开 24 个聊天，请重启连接服务后再选择');
    const entry = { ...row, writable: this.allowSelectedChats, history: [], store: new ThreadState(), ready: false, consumed: new Set() };
    this.entries.set(id, entry);
    if (entry.writable) this.client?.enableThreadControl(id);
    this.emit('changed');
    void this.hydrate(entry, this.generation);
  }
  async hydrate(entry, generation) {
    if (this.closed || entry.loadingGeneration === generation) return;
    entry.loadingGeneration = generation;
    entry.repairPending = false;
    const persisted = async () => {
      if (!this.historyReader || entry.historyReady) return;
      try {
        const history = await this.historyReader.turns(entry.id);
        if (generation !== this.generation) return;
        entry.history = history; entry.historyReady = true; this.emit('changed');
      } catch { entry.historyError = true; }
    };
    const live = async () => {
      try {
        const client = this.client;
        const owner = await client.discoverOwner(entry.id);
        if (generation !== this.generation) return;
        entry.owner = owner;
        if(entry.resubscribe){client.unfollowThread?.(entry.id);entry.resubscribe=false;}
        await client.followThread(entry.id, owner);
        const [history]=await Promise.allSettled([
          client.loadHistory(entry.id, owner),
          client.readModelSettings ? client.readModelSettings(entry.id,owner).then(value=>{if(generation===this.generation)entry.modelSettings=value;}) : Promise.resolve(),
        ]);
        if (generation === this.generation) { if(history.status==='fulfilled')entry.historyReady = true; this.emit('changed'); }
      } catch { /* Stored history remains viewable without a live desktop owner. */ }
    };
    try { await Promise.allSettled([persisted(), live()]); }
    finally {
      if(entry.loadingGeneration===generation)entry.loadingGeneration=null;
      if(entry.repairPending&&!this.closed&&generation===this.generation){entry.repairTimer=setTimeout(()=>void this.hydrate(entry,generation),250);entry.repairTimer.unref();}
    }
  }
  repairEntry(entry,generation){
    entry.ready=false;entry.resubscribe=true;entry.repairPending=true;this.emit('changed');
    if(entry.loadingGeneration!==generation)void this.hydrate(entry,generation);
  }
  scheduleReconnect() {
    if (this.closed || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => { this.reconnectTimer = null; void this.connect(); }, 3000);
    this.reconnectTimer.unref();
  }
  fileRoot(threadId) { return this.entries.get(threadId)?.store.state?.cwd; }
  snapshot() {
    return [...this.entries.values()].map(entry => {
      const state = entry.store.state;
      const liveTurn=entry.ready?getTurns(state).findLast(turn=>turn.status==='inProgress'):null;
      const summary = summarizeThread(state);
      const turns = new Map((entry.history || []).map(turn => [turn.id, turn]));
      for (const turn of summary.turns) {
        const previous = turns.get(turn.id);
        // Replacing a Map value preserves its old insertion position. Reinsert
        // live turns in canonical order so late history hydration cannot leave
        // an ancient turn at the tail and trim a recent one from the window.
        turns.delete(turn.id);
        turns.set(turn.id, { ...turn, input: turn.input || previous?.input,
          agentMessages: turn.agentMessages?.length ? turn.agentMessages : previous?.agentMessages || [],
          timeline: turn.timeline?.length ? turn.timeline : previous?.timeline || [],
          toolResults: turn.toolResults?.length ? turn.toolResults : previous?.toolResults || [] });
      }
      return { id: entry.id, name: entry.name || '桌面聊天', ready: entry.ready,
        model: state?.latestThreadSettings?.model || state?.latestModel || entry.modelSettings?.model || '', reasoningEffort:state?.latestReasoningEffort || state?.latestThreadSettings?.effort || entry.modelSettings?.reasoningEffort || '',
        serviceTier:state?.latestThreadSettings?.serviceTier??'default',
        activity: entry.ready ? summary.activity : undefined,
        writable: !!entry.writable, historyReady: !!entry.historyReady,
        // Only the live owner can identify the current turn; cached history may
        // still contain an inProgress status captured before a disconnection.
        // Use an empty string on the wire: Android JSONObject.optString coerces
        // JSON null into the nonempty string "null" (unlike JSON-java on the JVM).
        activeTurnId: entry.ready ? getTurns(state).findLast(turn => turn.status === 'inProgress')?.turnId ?? '' : '',
        revision: entry.store.revision, runtimeStatus: summary.runtimeStatus || 'unavailable',
        turns: [...turns.values()].slice(-50),
        questions: entry.ready ? pendingQuestions(state).map(q => ({ ...q, submitting: entry.consumed.has(JSON.stringify(['question', q.turnId, q.id])) })) : [],
        approvals: (liveTurn ? state?.requests || [] : []).flatMap(request => {
          const type = String(request.type || request.method || '');
          const kind = /command/i.test(type) ? 'command' : /file/i.test(type) ? 'file' : null;
          if (!kind) return [];
          const id = request.id ?? request.requestId;
          const params = request.params || {};
          if(params.turnId&&params.turnId!==liveTurn.turnId)return [];
          if(params.itemId&&!(liveTurn.items||[]).some(item=>item.id===params.itemId))return [];
          const items = getTurns(state).flatMap(turn => turn.items || []);
          const item = items.find(item => item.id === params.itemId);
          return [{ id, kind, turnId: getTurns(state).at(-1)?.turnId, decisions: (params.availableDecisions || ['accept', 'cancel']).filter(value => ['accept', 'decline', 'cancel'].includes(value)),
            command: params.command ?? item?.command ?? null, cwd: params.cwd ?? null,
            reason: params.reason ?? null, changes: kind === 'file' ? (item?.changes || []) : [],
            submitting: entry.consumed.has(JSON.stringify([getTurns(state).at(-1)?.turnId, id])) }];
        }) };
    });
  }
  async command(threadId, command) {
    const entry = this.entries.get(threadId);
    if (!entry || !entry.ready || !this.client.initialized) throw new Error('聊天尚未就绪，请等待重新连接');
    if (!entry.writable) throw new Error('此聊天仅允许读取，请在电脑授权后操作');
    const state = entry.store.state;
    const active = getTurns(state).findLast(turn => turn.status === 'inProgress');
    if (command.op === 'model') {
      const {models}=await this.modelOptions(threadId);
      const model=models.find(row=>row.model===command.model);
      if(!model || !model.efforts.includes(command.effort))throw Error('模型或思考强度不在电脑可用列表中，请刷新后选择');
      if(command.serviceTier!==undefined && command.serviceTier!=='default' && !(model.serviceTiers||[]).some(tier=>tier.id===command.serviceTier&&tier.id==='priority'))throw Error('此模型未提供所选速度');
      const result=await this.client.updateModelSettings(threadId,command.model,command.effort,{ownerId:entry.owner,serviceTier:command.serviceTier});
      if(!result?.applied)throw Error('电脑未应用模型设置，请刷新重试');
      entry.modelSettings=await this.client.readModelSettings(threadId,entry.owner);
      if(entry.modelSettings?.model!==command.model || entry.modelSettings?.reasoningEffort!==command.effort)throw Error('模型设置回执尚未确认，请刷新后核对');
      if(command.serviceTier!==undefined){
        for(let i=0;i<20&&(entry.store.state?.latestThreadSettings?.serviceTier??'default')!==command.serviceTier;i++)await new Promise(resolve=>setTimeout(resolve,50));
        if((entry.store.state?.latestThreadSettings?.serviceTier??'default')!==command.serviceTier)throw Error('电脑速度设置尚未确认，请刷新后核对');
      }
      this.emit('changed');
    } else if (command.op === 'start') {
      if (active) throw new Error('聊天正在执行，请等待完成或使用追加指令');
      await this.client.startTurn(threadId, command.text, { ownerId: entry.owner });
    } else if (command.op === 'steer') {
      if (!active) throw new Error('没有正在执行的任务');
      await this.client.steerTurn(threadId, command.text, { ownerId: entry.owner, cwd: state.cwd });
    } else if (command.op === 'interrupt') {
      if (!active || command.turnId !== active.turnId) throw new Error('任务已变化，请刷新后重试');
      await this.client.interruptTurn(threadId, active.turnId, entry.owner);
    } else if (command.op === 'answer') {
      const request = pendingQuestions(state).find(q => q.id === command.requestId && q.turnId === command.turnId);
      if (!request) throw Error('问题已处理或任务已变化，请刷新');
      const answers = validateAnswers(request, command.answers);
      const key = JSON.stringify(['question', request.turnId, request.id]);
      if (entry.consumed.has(key)) throw Error('回答已提交，请等待电脑确认');
      entry.consumed.add(key);
      if (entry.consumed.size > 1000) entry.consumed.delete(entry.consumed.values().next().value);
      if (request.kind === 'native') await this.client.replyUserInput(threadId, request.id, answers, entry.owner);
      else {
        const replies = request.questions.map(q => ({ questionItemId: q.id, question: q.title, answer: answers[q.id].answers[0] }));
        await this.client.steerTurn(threadId, '<send_user_message_question_reply>\n' + JSON.stringify(replies) + '\n</send_user_message_question_reply>', { ownerId: entry.owner, cwd: state.cwd });
      }
      this.emit('changed');
    } else if (command.op === 'approve') {
      if (command.turnId && command.turnId !== active?.turnId) throw new Error('审批轮次已变化，请刷新后操作');
      const request = state.requests?.find(value => (value.id ?? value.requestId) === command.requestId);
      if (!request) throw new Error('审批已失效');
      const key = JSON.stringify([getTurns(state).at(-1)?.turnId, command.requestId]);
      if (entry.consumed.has(key)) throw new Error('审批已提交，请等待桌面确认');
      const available = request.params?.availableDecisions || ['accept', 'cancel'];
      if (!['accept', 'decline', 'cancel'].includes(command.decision) || !available.includes(command.decision)) throw new Error('不支持该审批选项');
      const type = String(request.type || request.method || '');
      const method = /command/i.test(type) ? 'replyCommandApproval' : /file/i.test(type) ? 'replyFileApproval' : null;
      if (!method) throw new Error('此请求需在桌面处理');
      // A timeout is uncertain: never automatically replay an approval with a new ID.
      entry.consumed.add(key);
      if (entry.consumed.size > 1000) entry.consumed.delete(entry.consumed.values().next().value);
      await this.client[method](threadId, command.requestId, command.decision, entry.owner);
    } else throw new Error('不支持的操作');
    return { accepted: true };
  }
  async modelOptions(threadId){
    const entry=this.entries.get(threadId);
    if(!entry?.ready)throw Error('桌面聊天尚未就绪');
    if(!this.modelCatalog || Date.now()-this.modelCatalogAt>60000){
      if(!this.historyReader?.models)throw Error('当前桌面版本不支持模型列表');
      this.modelCatalog=await this.historyReader.models();this.modelCatalogAt=Date.now();
    }
    entry.modelSettings=await this.client.readModelSettings(threadId,entry.owner);
    return {models:this.modelCatalog,current:{model:entry.modelSettings?.model||'',effort:entry.modelSettings?.reasoningEffort||'',serviceTier:entry.store.state?.latestThreadSettings?.serviceTier??'default'}};
  }
  close() { this.closed = true; ++this.generation; clearTimeout(this.reconnectTimer); clearInterval(this.refreshTimer);for(const entry of this.entries.values())clearTimeout(entry.repairTimer);this.client?.close(); this.historyReader?.close(); }
}
