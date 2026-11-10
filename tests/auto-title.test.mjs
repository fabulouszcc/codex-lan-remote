import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {AutoTitles} from '../server/auto-title.mjs';

function fixture({changeName=false,fail=false}={}){
  const calls=[];let name='新聊天',saved;
  const client=new EventEmitter();client.close=()=>{};
  client.request=async(method,params)=>{
    calls.push({method,params});
    if(method==='thread/read')return {thread:{name}};
    if(method==='model/list')return {data:[{model:'title-model',isDefault:true,supportedReasoningEfforts:[{reasoningEffort:'low'}]}]};
    if(method==='config/read')return {config:{mcp_servers:{connector:{command:'not-run'}}}};
    if(method==='thread/start'){assert.equal(params.ephemeral,true);assert.equal(params.permissions,':read-only');assert.deepEqual(params.runtimeWorkspaceRoots,[]);assert.equal(params.config.mcp_servers.connector.enabled,false);assert.equal(params.config['features.shell_tool'],false);return {thread:{id:'ephemeral'}};}
    if(method==='turn/start'){
      if(fail)throw Error('offline');
      queueMicrotask(()=>{if(changeName)name='用户手动标题';client.emit('notification',{method:'item/completed',params:{threadId:'ephemeral',item:{type:'agentMessage',text:'{"title":"深色主题切换"}'}}});client.emit('notification',{method:'turn/completed',params:{threadId:'ephemeral',turn:{id:'title-turn',status:'completed'}}});});return {turn:{id:'title-turn'}};
    }
    if(method==='thread/name/set'){name=params.name;return {};}
    return {};
  };
  const titles=new AutoTitles('unused',{openClient:async()=>client,save:async entries=>{saved=structuredClone(entries);}});
  return {titles,calls,get name(){return name;},get saved(){return saved;}};
}

test('automatic titles use one isolated read-only model turn and update only the expected chat',async()=>{
  const f=fixture();await f.titles.remember('chat','新聊天');
  assert.equal(await f.titles.generate({threadId:'chat',cwd:'project',prompt:'增加深色主题'}),'深色主题切换');
  assert.equal(f.name,'深色主题切换');assert.equal(f.titles.wants('chat'),false);assert.deepEqual(f.saved,{});
  assert.equal(f.calls.filter(c=>c.method==='turn/start').length,1);assert.equal(f.calls.find(c=>c.method==='turn/start').params.threadId,'ephemeral');assert.ok(!f.calls.some(c=>c.method==='thread/resume'));assert.ok(f.calls.some(c=>c.method==='thread/unsubscribe'));
  await f.titles.generate({threadId:'chat',prompt:'again'});assert.equal(f.calls.filter(c=>c.method==='turn/start').length,1);
});
test('a desktop rename while the model runs is preserved',async()=>{
  const f=fixture({changeName:true});await f.titles.remember('chat','新聊天');assert.equal(await f.titles.generate({threadId:'chat',prompt:'request'}),null);assert.equal(f.name,'用户手动标题');assert.ok(!f.calls.some(c=>c.method==='thread/name/set'));assert.equal(f.titles.wants('chat'),false);
});
test('failed title generation never retries a paid turn on every synchronization',async()=>{
  const f=fixture({fail:true});await f.titles.remember('chat','新聊天');await f.titles.generate({threadId:'chat',prompt:'request'});await f.titles.generate({threadId:'chat',prompt:'request'});assert.equal(f.calls.filter(c=>c.method==='turn/start').length,1);assert.equal(f.name,'新聊天');assert.equal(f.titles.wants('chat'),false);assert.equal(f.saved.chat.failed,true);
});
test('cached generated titles survive a runtime change without billing a second turn',async()=>{
  const f=fixture();await f.titles.remember('chat','新聊天');let checks=0;
  assert.equal(await f.titles.generate({threadId:'chat',prompt:'request',canApply:()=>++checks===1}),null);
  assert.equal(await f.titles.generate({threadId:'chat',prompt:'request'}),'深色主题切换');assert.equal(f.calls.filter(c=>c.method==='turn/start').length,1);
});
