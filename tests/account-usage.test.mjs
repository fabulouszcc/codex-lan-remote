import test from 'node:test';
import assert from 'node:assert/strict';
import {accountUsage} from '../server/account-usage.mjs';
import {HistoryReader} from '../server/history-reader.mjs';

test('quota uses the Codex bucket, keeps zero usage distinct from missing data and whitelists fields',()=>{
  const value=accountUsage({accountId:'never-forward',rateLimits:{primary:{usedPercent:99}},rateLimitsByLimitId:{codex:{primary:{usedPercent:0,windowDurationMins:300,resetsAt:2000,secret:'no'},secondary:{usedPercent:115,windowDurationMins:10080,resetsAt:null},credits:{balance:'no'}}}},1234);
  assert.deepEqual(value,{available:true,primary:{remainingPercent:100,windowDurationMins:300,resetsAt:2000},secondary:{remainingPercent:0,windowDurationMins:10080,resetsAt:null},updatedAt:1234,stale:false});
  for(const usedPercent of [null,undefined,'0',NaN,-1])assert.equal(accountUsage({rateLimits:{primary:{usedPercent}}}).available,false);
  assert.equal(accountUsage({rateLimits:{limitId:'other',primary:{usedPercent:10}}}).available,false);
});

test('read-only quota requests share a cache, and a failed refresh marks earlier values stale',async()=>{
  const reader=new HistoryReader('not-spawned');let count=0;reader.connect=async()=>{};
  reader.request=async(method,params)=>{assert.equal(method,'account/rateLimits/read');assert.deepEqual(params,{excludeResetCreditDetails:true});count++;return {rateLimits:{primary:{usedPercent:25}}};};
  const values=await Promise.all([reader.usage(),reader.usage()]);assert.equal(count,1);assert.equal(values[0].primary.remainingPercent,75);
  await reader.usage();assert.equal(count,1);reader.usageAt=0;reader.request=async()=>{throw Error('private backend detail');};
  const stale=await reader.usage();assert.equal(stale.stale,true);assert.equal(stale.updatedAt,values[0].updatedAt);assert.equal(stale.primary.remainingPercent,75);assert.ok(!JSON.stringify(stale).includes('private'));
});
