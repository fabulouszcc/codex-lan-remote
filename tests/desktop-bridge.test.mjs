import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { DesktopBridge } from '../server/desktop-bridge.mjs';
import {pendingQuestions} from '../server/user-questions.mjs';

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

test('an old inProgress flag cannot keep a finished chat busy or expose stale approvals and questions',async t=>{
  const {bridge,client}=fixture([{id:'active'}]);t.after(()=>bridge.close());await bridge.connect();
  const state={turns:[{turnId:'old',status:'inProgress',items:[{type:'agentMessage',id:'q',questions:[{title:'Old?'}]}]},{turnId:'latest',status:'completed',items:[]}],requests:[{id:3,method:'item/commandExecution/requestApproval',params:{turnId:'old'}},{id:4,method:'tool/requestUserInput',params:{turnId:'old',questions:[]}}]};
  client.emit('threadState',{conversationId:'active',change:{type:'snapshot',revision:2,conversationState:state}});
  const row=bridge.snapshot()[0];assert.equal(row.activeTurnId,'');assert.equal(row.activity,undefined);assert.deepEqual(row.approvals,[]);assert.deepEqual(row.questions,[]);
  let started=false;client.startTurn=async()=>{started=true;};await bridge.command('active',{op:'start',text:'next'});assert.equal(started,true);await assert.rejects(bridge.command('active',{op:'interrupt',turnId:'old'}),/变化/);
  state.turns[1].status='inProgress';client.emit('threadState',{conversationId:'active',change:{type:'snapshot',revision:3,conversationState:state}});assert.equal(bridge.snapshot()[0].activeTurnId,'latest');assert.ok(bridge.snapshot()[0].activity);
});

test('native FIFO preserves desktop queued messages and removal targets the live owner',async t=>{
  const {bridge,client}=fixture([{id:'active'}]);t.after(()=>bridge.close());let queue=[{id:'desktop',prompt:'first',submission:{status:'queued'}}],removed;
  bridge.historyReader.queuedMessages=async()=>structuredClone(queue);
  client.setQueuedFollowUps=async(id,messages,options)=>{assert.equal(id,'active');assert.equal(options.ownerId,'owner');queue=messages;client.emit('queuedMessages',{conversationId:id,messages});};
  client.removeQueuedMessage=async(...args)=>{removed=args;};await bridge.connect();
  await Promise.all([bridge.command('active',{op:'queue',text:'second'}),bridge.command('active',{op:'queue',text:'third'})]);
  assert.deepEqual(queue.map(row=>row.prompt),['first','second','third']);assert.equal(queue[1].submissionIntent,'queue');
  assert.deepEqual(bridge.snapshot()[0].queuedMessages.map(row=>row.text),['first','second','third']);
  await bridge.command('active',{op:'queue-remove',messageId:'desktop'});assert.deepEqual(removed,['active','desktop','owner']);await assert.rejects(bridge.command('active',{op:'queue-remove',messageId:'unknown'}),/变化/);
});

test('new chat uses an authorized project directory without starting a task',async t=>{
  let created,shown;const {bridge}=fixture([],{allowSelectedChats:true,chatCreator:{create:async options=>{created=options;return{id:'new-chat',name:options.name};},show:async id=>{shown=id;}}});t.after(()=>bridge.close());
  bridge.historyReader.threadDirectories=new Map([['active','test-project']]);await bridge.connect();
  await assert.rejects(bridge.createThread({baseThreadId:'unknown',name:'new'}),/项目/);
  const result=await bridge.createThread({baseThreadId:'active',name:'phone chat'});assert.equal(created.cwd,'test-project');assert.equal(shown,'new-chat');assert.equal(result.id,'new-chat');assert.equal(bridge.snapshot().find(row=>row.id==='new-chat').ready,true);
});

test('background history sync neither follows nor enables control for unopened chats',async t=>{
  const {bridge,client}=fixture([]);t.after(()=>bridge.close());await bridge.connect();
  const history=await bridge.history('other');assert.equal(history.ready,false);assert.equal(history.historyReady,true);assert.equal(history.writable,false);assert.equal(history.turns[0].input,'old question');
  assert.equal(bridge.entries.size,0);assert.deepEqual(client.enabled,[]);assert.deepEqual(client.historyRequests,[]);await assert.rejects(bridge.history('unknown'),/列表/);
});

test('desktop-completed input and requests from older turns never leave pending reply cards',()=>{
  const request={id:8,method:'tool/requestUserInput',params:{turnId:'active',questions:[{id:'answer',question:'Choose'}]}};
  const state={turns:[{turnId:'old',status:'inProgress',items:[{type:'agentMessage',id:'old-question',questions:[{title:'Historical'}]}]},{turnId:'active',status:'inProgress',items:[{type:'userInputResponse',requestId:8,completed:true}]}],requests:[request]};
  assert.deepEqual(pendingQuestions(state),[]);
  state.turns[1].items=[];assert.equal(pendingQuestions(state).length,1);
  request.params.turnId='old';assert.deepEqual(pendingQuestions(state),[]);
  state.turns.forEach(turn=>turn.status='interrupted');assert.deepEqual(pendingQuestions(state),[]);
});

test('one chat revision gap repairs only that subscription while another chat stays writable', async t => {
  const {bridge,client}=fixture([{id:'busy'},{id:'idle'}]);t.after(()=>bridge.close());await bridge.connect();
  let reconnects=0,started;client.connect=async()=>{reconnects++;};client.startTurn=async(id,text)=>{started={id,text};};
  client.emit('threadState',{conversationId:'busy',change:{type:'patches',baseRevision:999,revision:1000,patches:[]}});
  assert.equal(bridge.snapshot().find(row=>row.id==='idle').ready,true);
  await bridge.command('idle',{op:'start',text:'independent task'});assert.deepEqual(started,{id:'idle',text:'independent task'});
  await new Promise(resolve=>setTimeout(resolve,20));assert.equal(reconnects,0);assert.equal(bridge.snapshot().find(row=>row.id==='busy').ready,true);
});

test('slow model discovery does not delay fetching the live complete history', async t => {
  const {bridge,client}=fixture([{id:'active'}]);t.after(()=>bridge.close());let release;
  client.readModelSettings=()=>new Promise(resolve=>{release=resolve;});
  const connecting=bridge.connect();await new Promise(resolve=>setTimeout(resolve,10));
  assert.deepEqual(client.historyRequests,['active']);assert.equal(bridge.snapshot()[0].ready,true);release({});await connecting;
});
test('late hydration with a different history window retains canonical live order before trimming',async t=>{
  const {bridge,client}=fixture([{id:'active'}]);t.after(()=>bridge.close());await bridge.connect();
  const turns=Array.from({length:51},(_,i)=>({turnId:'turn-'+i,status:'completed',items:[{type:'userMessage',id:'u'+i,content:[{type:'text',text:'message-'+i}]}]}));
  bridge.entries.get('active').history=turns.slice(1).map(t=>({id:t.turnId,input:'persisted',timeline:[]}));
  client.emit('threadState',{conversationId:'active',change:{type:'snapshot',revision:2,conversationState:{turns,requests:[]}}});
  const rows=bridge.snapshot()[0].turns;
  assert.deepEqual(rows.map(r=>r.id),turns.slice(1).map(r=>r.turnId));assert.equal(rows.at(-1).input,'message-50');
});
test('questions route native responses and async replies to the live owner, rejecting stale and repeated answers',async t=>{
  const {bridge,client}=fixture([{id:'active'}]);t.after(()=>bridge.close());await bridge.connect();
  const state={turns:[{turnId:'turn',status:'inProgress',items:[{type:'agentMessage',id:'call',questions:[{title:'Choose?',options:['One','Two']}]}]}],requests:[{id:8,method:'tool/requestUserInput',params:{turnId:'turn',questions:[{id:'native',question:'Native?',options:[{label:'Yes',description:'Confirm'}]}]}}]};
  client.emit('threadState',{conversationId:'active',change:{type:'snapshot',revision:2,conversationState:state}});
  const pending=bridge.snapshot()[0].questions;assert.equal(pending.length,2);
  let native,asyncReply;client.replyUserInput=async(...args)=>{native=args;};client.steerTurn=async(...args)=>{asyncReply=args;};
  const answer={op:'answer',requestId:8,turnId:'turn',answers:{native:{answers:['Yes']}}};
  await assert.rejects(bridge.command('active',{...answer,turnId:'old'}),/已处理|变化/);
  await assert.rejects(bridge.command('active',{...answer,answers:{native:{answers:['arbitrary']}}}),/可选项/);
  await bridge.command('active',answer);assert.deepEqual(native,['active',8,{native:{answers:['Yes']}},'owner']);
  await assert.rejects(bridge.command('active',answer),/已提交/);
  const id=pending.find(q=>q.kind==='async').questions[0].id;
  await bridge.command('active',{op:'answer',requestId:'call',turnId:'turn',answers:{[id]:{answers:['Custom text']}}});
  const text=asyncReply[1];assert.ok(text.startsWith('<send_user_message_question_reply>'));
  const replies=JSON.parse(text.slice(34,-35));assert.deepEqual(replies,[{questionItemId:id,question:'Choose?',answer:'Custom text'}]);
  state.turns[0].items.push({type:'steeringUserMessage',status:'accepted',input:[{type:'text',text}]});
  client.emit('threadState',{conversationId:'active',change:{type:'snapshot',revision:3,conversationState:state}});
  assert.equal(bridge.snapshot()[0].questions.filter(q=>q.kind==='async').length,0);
  client.emit('disconnected');assert.deepEqual(bridge.snapshot()[0].questions,[]);
});
test('model selection validates the real catalog, rejects read-only chats, and requires confirmed settings',async t=>{
  const {bridge,client}=fixture([{id:'active'}]);t.after(()=>bridge.close());await bridge.connect();
  let settings={model:'one',reasoningEffort:'low'},calls=0;
  bridge.historyReader.models=async()=>[{model:'one',efforts:['low']},{model:'two',efforts:['high']}];
  client.readModelSettings=async()=>settings;client.updateModelSettings=async(id,model,effort)=>{calls++;settings={model,reasoningEffort:effort};return {applied:true};};
  await assert.rejects(bridge.command('active',{op:'model',model:'invented',effort:'high'}),/可用列表/);
  await assert.rejects(bridge.command('active',{op:'model',model:'two',effort:'ultra'}),/可用列表/);assert.equal(calls,0);
  await bridge.command('active',{op:'model',model:'two',effort:'high'});assert.equal(bridge.snapshot()[0].model,'two');
  bridge.entries.get('active').writable=false;await assert.rejects(bridge.command('active',{op:'model',model:'one',effort:'low'}),/只|读取/);assert.equal(calls,1);
});
test('speed must exist in the model catalog and be confirmed by live thread state',async t=>{
  const {bridge,client}=fixture([{id:'active'}]);t.after(()=>bridge.close());await bridge.connect();
  bridge.historyReader.models=async()=>[{model:'one',efforts:['high'],serviceTiers:[{id:'priority'}]}];
  client.readModelSettings=async()=>({model:'one',reasoningEffort:'high'});
  client.updateModelSettings=async(id,model,effort,{serviceTier})=>{client.emit('threadState',{conversationId:id,change:{type:'snapshot',revision:2,conversationState:{turns:[],requests:[],latestThreadSettings:{serviceTier}}}});return {applied:true};};
  await assert.rejects(bridge.command('active',{op:'model',model:'one',effort:'high',serviceTier:'invented'}),/速度/);
  await bridge.command('active',{op:'model',model:'one',effort:'high',serviceTier:'priority'});
  assert.equal(bridge.snapshot()[0].serviceTier,'priority');assert.equal((await bridge.modelOptions('active')).current.serviceTier,'priority');
});
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
