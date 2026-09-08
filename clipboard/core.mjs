export const VERSION=1;
export const TTL=12*60*60*1000;
export const LIMIT=20;
export const MAX_CHARS=16000;
export const DEVICE_NAMES=['小米 12 Pro · 1','小米 12 Pro · 2','红米 K60E','红米 K50 Pro'];
export const PUBLIC_TRANSPORT={urls:['wss://broker.emqx.io:8084/mqtt','wss://broker-cn.emqx.io:8084/mqtt'],username:'',password:''};
export const token=(bytes=24)=>to64(crypto.getRandomValues(new Uint8Array(bytes)));
export function to64(bytes){return btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'')}
export function from64(text){if(typeof text!=='string'||!/^[A-Za-z0-9_-]+$/.test(text))throw new Error('连接信息格式不正确');return Uint8Array.from(atob(text.replaceAll('-','+').replaceAll('_','/')),c=>c.charCodeAt(0))}
export function parseLines(text,maxItems=LIMIT){const lines=String(text).split(/\r?\n/).filter(s=>s.trim().length);if(!lines.length)throw new Error('请至少填写一条信息');if(lines.length>maxItems)throw new Error('一次最多发送 '+maxItems+' 条独立信息，请分次发送');if(lines.some(s=>s.length>4000)||lines.join('').length>MAX_CHARS)throw new Error('内容过长，请分批发送');return lines}
export function chunkItems(items){const chunks=[];for(let i=0;i<items.length;i+=LIMIT)chunks.push(items.slice(i,i+LIMIT));return chunks}
export function validBatch(batch){return !!batch&&typeof batch.id==='string'&&/^[A-Za-z0-9_-]{12,64}$/.test(batch.id)&&Number.isFinite(batch.createdAt)&&batch.createdAt>Date.now()-TTL&&batch.createdAt<Date.now()+60000&&Array.isArray(batch.items)&&batch.items.length>0&&batch.items.length<=LIMIT&&batch.items.every(t=>typeof t==='string'&&t.trim()&&t.length<=4000)&&batch.items.join('').length<=MAX_CHARS}
export function pairingCode(pair){return to64(new TextEncoder().encode(JSON.stringify(pair)))}
export function validTransport(value){
 if(!value||!Array.isArray(value.urls)||value.urls.length<1||value.urls.length>3||typeof value.username!=='string'||value.username.length>128||typeof value.password!=='string'||value.password.length>256)return false;
 return value.urls.every(raw=>{if(typeof raw!=='string'||raw.length>300)return false;try{const url=new URL(raw);return url.protocol==='wss:'&&!!url.hostname&&!url.username&&!url.password}catch{return false}})
}
export function validPair(p){
 try{return !!p&&p.v===2&&/^c1clip-[A-Za-z0-9_-]{20,50}$/.test(p.host)&&/^d[A-Za-z0-9_-]{10,40}$/.test(p.device)&&typeof p.name==='string'&&!!p.name.trim()&&p.name.length<=60&&typeof p.key==='string'&&from64(p.key).length===32&&validTransport(p.broker)}catch{return false}
}
export function readPair(input){let raw=String(input).trim();if(raw.includes('#pair='))raw=raw.split('#pair=')[1];raw=raw.split(/[\s&]/)[0];if(raw.length>4000)throw new Error('连接信息过长');let p;try{p=JSON.parse(new TextDecoder().decode(from64(raw)))}catch{throw new Error('连接链接不完整，请重新复制或扫码')};if(p?.v===1)throw new Error('这是旧版配对链接，请在电脑端重新生成并扫码');if(!validPair(p))throw new Error('连接信息格式不正确');return p}
const keys=new Map();
async function cryptoKey(secret){if(!keys.has(secret))keys.set(secret,crypto.subtle.importKey('raw',from64(secret),{name:'AES-GCM'},false,['encrypt','decrypt']));return keys.get(secret)}
export async function seal(secret,data){const iv=crypto.getRandomValues(new Uint8Array(12));const ciphertext=await crypto.subtle.encrypt({name:'AES-GCM',iv},await cryptoKey(secret),new TextEncoder().encode(JSON.stringify(data)));return {v:1,iv:to64(iv),data:to64(new Uint8Array(ciphertext))}}
export async function unseal(secret,packet){if(!packet||packet.v!==1||typeof packet.data!=='string'||packet.data.length>100000||typeof packet.iv!=='string'||from64(packet.iv).length!==12)throw new Error('无效数据');const plain=await crypto.subtle.decrypt({name:'AES-GCM',iv:from64(packet.iv)},await cryptoKey(secret),from64(packet.data));return JSON.parse(new TextDecoder().decode(plain))}
export async function copyBatch(items,{write,delay=1500,canContinue=()=>true,onProgress=()=>{},wait=ms=>new Promise(r=>setTimeout(r,ms))}){
 let copied=0;
 for(const text of items){
  if(!canContinue())return {copied,total:items.length,error:'页面离开前台或已停止，请返回后重试'};
  try{await write(text)}catch(err){return {copied,total:items.length,error:err?.name==='NotAllowedError'?'浏览器拦截了连续复制，可切换兼容方式重试':err?.message||'复制失败'}}
  copied++;onProgress(copied);
  if(copied<items.length)await wait(delay);
 }
 return {copied,total:items.length,error:null};
}
