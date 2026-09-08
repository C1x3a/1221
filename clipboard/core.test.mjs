import test from 'node:test';
import assert from 'node:assert/strict';
import {parseLines,copyBatch,token,seal,unseal,pairingCode,readPair,validBatch,TTL} from './core.mjs';

test('four independent records preserve order, duplicate values, punctuation and spaces',async()=>{
 const items=parseLines('姓名示例\r\n\r\n  地址 示例，A号  \n姓名示例\n尾号 0001');
 assert.deepEqual(items,['姓名示例','  地址 示例，A号  ','姓名示例','尾号 0001']);
 const written=[],intervals=[];const result=await copyBatch(items,{write:async t=>written.push(t),wait:async ms=>intervals.push(ms)});
 assert.deepEqual(written,items);assert.deepEqual(intervals,[1500,1500,1500]);assert.equal(result.copied,4);assert.equal(result.error,null);
});
test('browser denial on the third item stops and reports only two completed writes',async()=>{
 let attempts=0;const result=await copyBatch(['a','b','c','d'],{write:async()=>{if(++attempts===3)throw Object.assign(new Error(),{name:'NotAllowedError'})},wait:async()=>{}});
 assert.equal(attempts,3);assert.equal(result.copied,2);assert.ok(result.error);
});
test('leaving the foreground stops before the next write',async()=>{
 const written=[];let foreground=true;const result=await copyBatch(['a','b','c','d'],{write:async t=>written.push(t),canContinue:()=>foreground,wait:async()=>{foreground=false}});
 assert.deepEqual(written,['a']);assert.equal(result.copied,1);assert.ok(result.error);
});
test('separate device keys, authenticated encryption, and tamper rejection',async()=>{
 const a=token(32),b=token(32),batch={t:'batch',sid:token(),batch:{id:token(18),createdAt:Date.now(),items:['一','二','三','四']}};
 const sealed=await seal(a,batch);assert.deepEqual(await unseal(a,sealed),batch);await assert.rejects(unseal(b,sealed));
 const tampered={...sealed,data:(sealed.data[0]==='A'?'B':'A')+sealed.data.slice(1)};await assert.rejects(unseal(a,tampered));
 assert.notEqual((await seal(a,batch)).iv,sealed.iv);
});
test('pairing URL round trip and malformed input rejection',()=>{
 const pair={v:1,host:'c1clip-'+token(18),device:'d'+token(12),name:'小米 12 Pro · 1',key:token(32)};
 assert.deepEqual(readPair('https://example.test/#pair='+pairingCode(pair)),pair);assert.throws(()=>readPair('not-a-pair'));
 assert.throws(()=>readPair(pairingCode({...pair,key:token(16)})));
});
test('batches reject old, future, excessive or empty payloads',()=>{
 const batch={id:token(18),createdAt:Date.now(),items:['a','b','c','d']};assert.ok(validBatch(batch));
 for(const invalid of [{...batch,items:[]},{...batch,items:['']},{...batch,items:Array(21).fill('a')},{...batch,createdAt:Date.now()-TTL-1},{...batch,createdAt:Date.now()+120000},{...batch,items:['a'.repeat(4001)]}])assert.equal(validBatch(invalid),false);
});
