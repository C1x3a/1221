import {from64,to64,validTransport} from './core.mjs';

export const SYNC_API='https://c1clip-sync.netlify.app/api/settings';
const PREFIX='C1S1.';
const AAD=new TextEncoder().encode('c1clip-controller-settings-v1');

export function createSyncCode(){return PREFIX+to64(crypto.getRandomValues(new Uint8Array(32)))}
export function syncKey(code){const clean=String(code||'').trim();if(!clean.startsWith(PREFIX))throw new Error('同步码格式不正确');const bytes=from64(clean.slice(PREFIX.length));if(bytes.length!==32)throw new Error('同步码格式不正确');return {clean,bytes}}
export async function workspaceId(code){const {bytes}=syncKey(code),hash=new Uint8Array(await crypto.subtle.digest('SHA-256',bytes));return [...hash].map(x=>x.toString(16).padStart(2,'0')).join('')}
async function keyFor(code,usage){return crypto.subtle.importKey('raw',syncKey(code).bytes,{name:'AES-GCM'},false,usage)}

export function validController(value){
 if(!value||typeof value!=='object'||!/^c1clip-[A-Za-z0-9_-]{20,50}$/.test(value.host)||!Array.isArray(value.devices)||value.devices.length>500||!Array.isArray(value.groups)||value.groups.length>500||(value.transport!==undefined&&!validTransport(value.transport)))return false;
 const ids=new Set();
 for(const d of value.devices){if(!d||typeof d.name!=='string'||!d.name.trim()||d.name.length>60||!/^d[A-Za-z0-9_-]{10,40}$/.test(d.id)||ids.has(d.id))return false;try{if(from64(d.key).length!==32)return false}catch{return false}ids.add(d.id)}
 return value.groups.every(g=>g&&/^g[A-Za-z0-9_-]{10,40}$/.test(g.id)&&typeof g.name==='string'&&g.name.trim()&&g.name.length<=60&&Array.isArray(g.members)&&g.members.length<=500&&g.members.every(id=>ids.has(id)));
}

export async function encryptController(code,controller){
 if(!validController(controller))throw new Error('本机设置格式不正确，无法同步');
 const iv=crypto.getRandomValues(new Uint8Array(12));
 const plaintext=new TextEncoder().encode(JSON.stringify({v:1,controller}));
 const result=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:AAD},await keyFor(code,['encrypt']),plaintext);
 return {v:1,iv:to64(iv),ciphertext:to64(new Uint8Array(result))};
}

export async function decryptController(code,envelope){
 try{
  if(!envelope||envelope.v!==1||from64(envelope.iv).length!==12||typeof envelope.ciphertext!=='string')throw new Error();
  const result=await crypto.subtle.decrypt({name:'AES-GCM',iv:from64(envelope.iv),additionalData:AAD},await keyFor(code,['decrypt']),from64(envelope.ciphertext));
  const payload=JSON.parse(new TextDecoder().decode(result));
  if(payload.v!==1||!validController(payload.controller))throw new Error();
  return payload.controller;
 }catch{throw new Error('同步码不正确，或云端设置已损坏')}
}

async function responseJson(response){try{return await response.json()}catch{return {}}}
export async function fetchSettings(code,{fetcher=fetch,api=SYNC_API}={}){
 const id=await workspaceId(code);let response;try{response=await fetcher(api+'/'+id,{method:'GET',cache:'no-store'})}catch{throw new Error('无法连接同步服务器，请检查网络后重试')};const data=await responseJson(response);
 if(response.status===404)throw new Error('没有找到这个同步码对应的设置');
 if(!response.ok)throw new Error('暂时无法读取共享设置，请稍后重试');
 return {revision:data.revision,updatedAt:data.updatedAt,controller:await decryptController(code,data.envelope)};
}
export async function putSettings(code,expectedRevision,controller,{fetcher=fetch,api=SYNC_API}={}){
 const id=await workspaceId(code),envelope=await encryptController(code,controller);let response;try{response=await fetcher(api+'/'+id,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({expectedRevision,envelope})})}catch{throw new Error('无法连接同步服务器，请检查网络后重试')};const data=await responseJson(response);
 if(response.status===409){const error=new Error('另一台电脑已经修改了共享设置');error.name='SyncConflict';error.revision=data.revision;throw error}
 if(!response.ok)throw new Error('暂时无法保存共享设置，请稍后重试');
 return {revision:data.revision,updatedAt:data.updatedAt};
}
