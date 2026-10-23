import test from 'node:test';
import assert from 'node:assert/strict';
import {groupThreads} from '../server/sidebar.mjs';
import {HistoryReader} from '../server/history-reader.mjs';

test('pinned projects stay above recent projects and retain desktop pin order',()=>{
  const state={'local-projects':{a:{id:'a',name:'A',rootPaths:['C:/a']},b:{id:'b',name:'B',rootPaths:['C:/b']},c:{id:'c',name:'C',rootPaths:['C:/c']}},'pinned-project-ids':['b','a'],'electron-persisted-atom-state':{'flat-project-sidebar-preferences-v1':{projectSortMode:'updated_at'}}};
  const rows=groupThreads([{id:'a',cwd:'C:/a',updatedAt:50},{id:'c',cwd:'C:/c',updatedAt:100},{id:'b',cwd:'C:/b',updatedAt:1}],state);
  assert.deepEqual(rows.map(row=>row.id),['b','a','c']);assert.equal(rows[0].projectPinned,true);assert.equal(rows[2].projectPinned,false);assert.equal(rows[0].groupId,'project:b');
  state['pinned-project-ids']=[];assert.deepEqual(groupThreads([{id:'a',cwd:'C:/a',updatedAt:50},{id:'c',cwd:'C:/c',updatedAt:100},{id:'b',cwd:'C:/b',updatedAt:1}],state).map(row=>row.id),['c','a','b']);
});

test('desktop project assignments, explicit projectless chats and custom sections take precedence over cwd',()=>{
  const state={'local-projects':{p:{id:'p',name:'Research',rootPaths:['C:/work']}},'project-order':['p'],'projectless-thread-ids':['loose'],'thread-project-assignments':{assigned:{projectId:'p'}}};
  const rows=groupThreads([{id:'loose',cwd:'C:/work'},{id:'child',cwd:'C:\\work\\nested'},{id:'assigned',cwd:'D:/elsewhere'},{id:'custom',section:{id:'section',name:'Reading'}}],state);
  assert.equal(rows.find(r=>r.id==='loose').groupId,'chats');assert.equal(rows.find(r=>r.id==='child').groupName,'Research');assert.equal(rows.find(r=>r.id==='assigned').groupId,'project:p');assert.equal(rows[0].groupName,'Reading');
  assert.equal(rows.some(r=>'cwd' in r||'rootPaths' in r),false);
});
test('history catalog follows every page and rejects a repeated cursor',async()=>{
  const reader=new HistoryReader('unused',{sidebarReader:async()=>({})});reader.connect=async()=>{};let calls=0;
  reader.request=async(method,params)=>{assert.equal(method,'thread/list');if(params.sectionId)return {data:[]};calls++;return params.cursor?{data:[{id:'last'}]}:{data:Array.from({length:100},(_,i)=>({id:String(i)})),nextCursor:'next'};};
  assert.equal((await reader.list()).length,101);assert.equal(calls,2);
  reader.request=async()=>({data:[],nextCursor:'again'});await assert.rejects(reader.list(),/游标/);
});

test('desktop pin order wins over recency and project placement',async()=>{
  const reader=new HistoryReader('unused',{sidebarReader:async()=>({'pinned-thread-ids':['old']} )});reader.connect=async()=>{};
  reader.request=async(_method,params)=>params.sectionId?{data:[{id:'b'},{id:'a'}]}:{data:[{id:'a',name:'A',updatedAt:50},{id:'b',name:'B',updatedAt:10},{id:'c',name:'C',updatedAt:100}]};
  const rows=await reader.list();assert.deepEqual(rows.map(r=>r.id),['b','a','c']);assert.equal(rows[0].groupName,'置顶');assert.equal(rows[0].pinned,true);
});
