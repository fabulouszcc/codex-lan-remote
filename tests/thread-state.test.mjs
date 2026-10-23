import test from 'node:test';
import assert from 'node:assert/strict';
import { ThreadState, getTurns, summarizeThread, activeTurn } from '../server/thread-state.mjs';
import { turnTimeline } from '../server/turn-timeline.mjs';
import { HistoryReader } from '../server/history-reader.mjs';

test('only the latest canonical turn determines live activity',()=>{
  const old={turnId:'old',status:'inProgress',items:[]},last={turnId:'latest',status:'completed',items:[]};
  const state={turnHistory:{kind:'canonical',history:{islands:[{entries:[{value:'old'},{value:'latest'}]}],entitiesByKey:{old,latest:last}}}};
  assert.equal(activeTurn(state),null);assert.equal(summarizeThread(state).activity,undefined);last.status='inProgress';assert.equal(activeTurn(state).turnId,'latest');assert.ok(summarizeThread(state).activity);
});
test('streaming patches preserve old snapshots and share untouched large branches',()=>{
  const store=new ThreadState();store.apply({type:'snapshot',revision:1,conversationState:{large:{payload:'x'.repeat(1000000)},turns:[{text:'before'}]}});
  const before=store.state;store.apply({type:'patches',baseRevision:1,revision:2,patches:[{op:'replace',path:['turns',0,'text'],value:'after'}]});
  assert.equal(before.turns[0].text,'before');assert.equal(store.state.turns[0].text,'after');assert.equal(store.state.large,before.large);assert.notEqual(store.state.turns,before.turns);
  const valid=store.state;assert.throws(()=>store.apply({type:'patches',baseRevision:2,revision:3,patches:[{op:'replace',path:['large','payload'],value:'changed'},{op:'replace',path:['missing'],value:'invalid'}]}));
  assert.equal(store.state,valid);assert.equal(valid.large.payload.length,1000000);
});
test('live activity exposes only visible reasoning summary and disappears when the turn completes',()=>{
  const state={turns:[{turnId:'t',status:'inProgress',items:[{type:'reasoning',summary:[{text:'Visible progress'}],content:[{text:'PRIVATE'}],encryptedContent:'PRIVATE'}]}]};
  const activity=summarizeThread(state).activity;assert.equal(activity.summary,'Visible progress');assert.ok(!JSON.stringify(activity).includes('PRIVATE'));assert.match(activity.label,/思考/);
  state.turns[0].status='completed';assert.equal(summarizeThread(state).activity,undefined);
});

test('desktop follow-up messages and tools retain chronological placement in live and stored projections', async () => {
  const items=[{type:'userMessage',id:'u1',content:[{type:'text',text:'first'}]}, {type:'agentMessage',id:'a1',text:'checking'},
    {type:'commandExecution',id:'c1',command:'echo ok',status:'completed',aggregatedOutput:'ok'},
    {type:'userMessage',id:'u2',content:[{type:'text',text:'desktop follow-up'}]},
    {type:'agentMessage',id:'a2',text:'adjusting'}, {type:'mcpToolCall',id:'m1',tool:'read',status:'completed',result:{content:[{type:'text',text:'result'}],_meta:{secret:'never-forward'}}},
    {type:'agentMessage',id:'a3',text:'done'}];
  const live=summarizeThread({turns:[{turnId:'t',params:{input:[{type:'text',text:'first'}]},items}]}).turns[0];
  assert.equal(live.input,'first\n\ndesktop follow-up');
  assert.deepEqual(live.timeline.map(i=>i.id),['u1','a1','c1','u2','a2','m1','a3']);
  assert.deepEqual(live.timeline.map(i=>i.type),['user','message','tool','user','message','tool','message']);
  assert.equal(live.timeline[5].output,'result');assert.ok(!JSON.stringify(live).includes('never-forward'));
  const reader=new HistoryReader('unused');reader.connect=async()=>{};reader.request=async()=>({data:[{id:'t',items}]});
  assert.deepEqual((await reader.turns('thread'))[0].timeline,live.timeline);
});
test('accepted desktop steering input remains visible and canonical replacement does not duplicate it', () => {
  const first={type:'userMessage',id:'u1',content:[{type:'text',text:'first'}]};
  const steer={type:'steeringUserMessage',id:'s1',status:'accepted',clientUserMessageId:'client2',serverUserMessageId:'u2',input:[{type:'text',text:'follow-up'},{type:'input_image',image_url:'PRIVATE'}],restoreMessage:{secret:'PRIVATE'}};
  const turn={params:{input:first.content},items:[first,{type:'agentMessage',id:'a',text:'working'},steer]};
  const summary=summarizeThread({turns:[turn]}).turns[0];
  assert.equal(summary.input,'first\n\nfollow-up');
  assert.equal(summary.timeline.find(item=>item.id==='s1').clientMessageId,'client2');
  assert.deepEqual(summary.timeline.map(i=>i.id),['u1','a','s1']);
  assert.ok(!JSON.stringify(summary).includes('PRIVATE'));
  turn.items.push({type:'userMessage',id:'u2',clientId:'client2',content:[{type:'text',text:'follow-up'}]});
  assert.equal(turnTimeline(turn.items).find(item=>item.id==='u2').clientMessageId,'client2');
  assert.deepEqual(turnTimeline(turn.items).filter(i=>i.type==='user').map(i=>i.text),['first','follow-up']);
});

test('timeline bounds tool detail and excludes hidden reasoning and media data', () => {
  const items=[{type:'reasoning',content:['HIDDEN']},{type:'functionCallOutput',id:'f',name:'test',output:[{type:'input_text',text:'visible'},{type:'encrypted_content',encrypted_content:'HIDDEN'},{type:'input_image',image_url:'HIDDEN'}]},
    ...Array.from({length:10},(_,i)=>({type:'dynamicToolCall',id:String(i),tool:'test',arguments:'x'.repeat(10000),contentItems:[{type:'inputText',text:'y'.repeat(10000)}]}))];
  const rows=turnTimeline(items);assert.equal(rows[0].output,'visible');assert.ok(!JSON.stringify(rows).includes('HIDDEN'));
  assert.ok(rows.reduce((size,row)=>size+row.input.length+row.output.length,0)<=24000);assert.ok(rows.some(row=>row.truncated));
});

test('rebuilds state from a snapshot and ordered object/array changes', () => {
  const store = new ThreadState();
  store.apply({ type: 'snapshot', revision: 1, conversationState: { id: 'test', turns: [{ text: 'a' }], requests: [] } });
  store.apply({ type: 'patches', baseRevision: 1, revision: 2, patches: [
    { op: 'replace', path: ['turns', 0, 'text'], value: 'b' },
    { op: 'add', path: ['turns', 1], value: { text: 'c' } },
    { op: 'add', path: ['requests', 0], value: { id: 7 } },
  ] });
  store.apply({ type: 'patches', baseRevision: 2, revision: 3, patches: [{ op: 'remove', path: ['requests', 0] }] });
  assert.deepEqual(store.state, { id: 'test', turns: [{ text: 'b' }, { text: 'c' }], requests: [] });
  assert.equal(store.revision, 3);
});

test('gaps and invalid batches cannot partially mutate state or revision', () => {
  const store = new ThreadState();
  store.apply({ type: 'snapshot', revision: 3, conversationState: { id: 'test', turns: [] } });
  assert.throws(() => store.apply({ type: 'patches', baseRevision: 2, revision: 4, patches: [] }), /gap/);
  assert.throws(() => store.apply({ type: 'patches', baseRevision: 3, revision: 4, patches: [
    { op: 'replace', path: ['id'], value: 'changed' },
    { op: 'replace', path: ['missing', 'text'], value: 'invalid' },
  ] }), /parent/);
  assert.equal(store.state.id, 'test');
  assert.equal(store.revision, 3);
});

test('rejects prototype pollution and invalid array offsets', () => {
  const store = new ThreadState();
  store.apply({ type: 'snapshot', revision: 1, conversationState: { values: [] } });
  for (const path of [['__proto__', 'polluted'], ['constructor', 'prototype', 'polluted'], ['values', -1]]) {
    assert.throws(() => store.apply({ type: 'patches', baseRevision: 1, revision: 2, patches: [{ op: 'add', path, value: true }] }));
  }
  assert.equal({}.polluted, undefined);
  assert.equal(store.revision, 1);
});

test('canonical history order is independent of object insertion order and duplicated entries', () => {
  const state = { turns: [], turnHistory: { kind: 'canonical', history: {
    entitiesByKey: { b: { turnId: 'second' }, a: { turnId: 'first' } },
    islands: [{ entries: [{ value: 'a' }, { value: 'b' }] }, { entries: [{ value: 'b' }] }],
  } } };
  assert.deepEqual(getTurns(state).map(turn => turn.turnId), ['first', 'second']);
});
