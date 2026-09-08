import test from 'node:test';
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;
import {SYNC_API,createSyncCode,syncKey,workspaceId,encryptController,decryptController,fetchSettings,putSettings} from './sync.mjs';

const controller={host:'c1clip-abcdefghijklmnopqrstuvwx',devices:[{id:'dabcdefghijklmnop',name:'小米 12 Pro · 1',key:'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'}],groups:[{id:'gabcdefghijklmnop',name:'家里',members:['dabcdefghijklmnop']}]};

test('sync code encrypts and decrypts controller settings only',async()=>{
 assert.equal(SYNC_API,'https://c1clip-sync.netlify.app/api/settings');
 const code=createSyncCode();assert.equal(syncKey(code).bytes.length,32);assert.match(await workspaceId(code),/^[a-f0-9]{64}$/);
 const envelope=await encryptController(code,controller);assert.notEqual(envelope.ciphertext.includes('小米'),true);assert.deepEqual(await decryptController(code,envelope),controller);
 await assert.rejects(()=>decryptController(createSyncCode(),envelope),/同步码不正确/);
});

test('network failures have a readable Chinese message',async()=>{
 await assert.rejects(()=>fetchSettings(createSyncCode(),{fetcher:async()=>{throw new TypeError('Failed to fetch')}}),/无法连接同步服务器/);
});

test('client transports revisions and handles conflicts',async()=>{
 const code=createSyncCode(),envelope=await encryptController(code,controller),calls=[];
 const fetcher=async(url,init={})=>{calls.push({url,init});if(init.method==='PUT')return new Response(JSON.stringify({revision:2,updatedAt:'now'}),{status:200});return new Response(JSON.stringify({revision:1,updatedAt:'then',envelope}),{status:200})};
 assert.equal((await fetchSettings(code,{fetcher,api:'https://sync.test'})).controller.devices[0].name,'小米 12 Pro · 1');
 assert.equal((await putSettings(code,1,controller,{fetcher,api:'https://sync.test'})).revision,2);assert.equal(JSON.parse(calls[1].init.body).expectedRevision,1);
 const conflict=()=>putSettings(code,2,controller,{api:'https://sync.test',fetcher:async()=>new Response(JSON.stringify({revision:3}),{status:409})});
 await assert.rejects(conflict,error=>error.name==='SyncConflict'&&error.revision===3);
});
