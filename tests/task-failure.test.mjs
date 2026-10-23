import test from 'node:test';
import assert from 'node:assert/strict';
import {taskFailure} from '../server/task-failure.mjs';
import {summarizeThread} from '../server/thread-state.mjs';
import {prioritizeThreads,mobileStateWindow} from '../server/state-sync.mjs';

test('quota failures are classified without exposing provider error bodies',()=>{
  assert.equal(taskFailure({error:{codexErrorInfo:'usageLimitExceeded',message:'private provider body'}}),'quota');
  assert.equal(taskFailure({error:{code:429,message:'rate limit'}}),'rateLimit');
  assert.equal(taskFailure({error:{message:'context window exceeded'}}),'contextLimit');
  assert.equal(taskFailure({error:{message:'quota exceeded',willRetry:true}}),'');
  assert.equal(taskFailure({status:'interrupted'}),'');
  const result=summarizeThread({turns:[{turnId:'limited',status:'failed',error:{message:'insufficient quota; private provider body'},items:[]}]});
  assert.equal(result.turns[0].failureReason,'quota');assert.equal(JSON.stringify(result).includes('private provider body'),false);
});
test('selected chat is first and other chats do not transmit their history or erase cache order',()=>{
  const rows=prioritizeThreads([{id:'other',turns:[{id:'old',input:'large history'},{id:'recent',status:'failed',failureReason:'quota',input:'private body'}]},{id:'current',turns:[{id:'now',input:'selected content'}]}],'current');
  assert.equal(rows[0].id,'current');assert.equal(rows[0].turns[0].input,'selected content');assert.equal(rows[1].turns[0].input,undefined);assert.equal(rows[1].turns[0].failureReason,'quota');
  const wire=mobileStateWindow(rows,null,2,null).payload;assert.deepEqual(wire.threads[0].turnOrder,['now']);assert.equal('turnOrder' in wire.threads[1],false);
});
