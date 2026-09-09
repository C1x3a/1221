import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('./app.mjs',import.meta.url),'utf8');
const recovery=source.slice(source.indexOf('function resumeNetwork('),source.indexOf("document.addEventListener('visibilitychange'"));
function harness(){
 const timers=new Map(),calls={boot:0,hello:0,ping:0};let id=0;
 const ctx={document:{visibilityState:'visible'},Date:{now:()=>60000},prefs:{phone:{device:'paired-phone',key:'unchanged'}},role:'phone',wasHidden:true,mqttReady:true,phoneConn:{healthy:false,lastAttempt:55000},networkBootAt:0,resumeTimer:null,
 setTimeout:fn=>{timers.set(++id,fn);return id},clearTimeout:id=>timers.delete(id),alive:link=>!!link?.healthy,bootNetwork:()=>{calls.boot++},connectPhone:()=>{calls.hello++},sendToController:()=>{calls.ping++;return Promise.resolve()},updateNetwork:()=>{}};
 vm.createContext(ctx);vm.runInContext(recovery,ctx);
 return {ctx,calls,run:code=>vm.runInContext(code,ctx),flush:()=>{const pending=[...timers.values()];timers.clear();pending.forEach(fn=>fn())}};
}
test('unlock rebuilds a stale socket even when MQTT still claims connected, preserving pairing',()=>{
 const h=harness(),pair=h.ctx.prefs.phone;h.run('resumeNetwork(true)');h.flush();assert.equal(h.calls.boot,1);assert.equal(h.ctx.prefs.phone,pair);assert.equal(pair.key,'unchanged');
});
test('visibility, focus and page restore signals coalesce into one reconnect',()=>{
 const h=harness();h.run('resumeNetwork(true);resumeNetwork();resumeNetwork(true)');h.flush();assert.equal(h.calls.boot,1);
});
test('returning to background before the reconnect timer fires prevents a background reconnect',()=>{
 const h=harness();h.run('resumeNetwork(true)');h.ctx.document.visibilityState='hidden';h.flush();assert.equal(h.calls.boot,0);
});
test('ordinary focus changes keep a healthy connection intact',()=>{
 const h=harness();h.ctx.wasHidden=false;h.ctx.phoneConn.healthy=true;h.run('resumeNetwork()');h.flush();assert.equal(h.calls.boot,0);
});
test('watchdog recovers sockets with no reply, but allows a fresh connection time to handshake',()=>{
 const h=harness();h.run('checkPhoneConnection()');assert.equal(h.calls.boot,1);
 h.ctx.networkBootAt=55000;h.run('checkPhoneConnection()');assert.equal(h.calls.boot,1);
 h.ctx.phoneConn.healthy=true;h.run('checkPhoneConnection()');assert.equal(h.calls.ping,1);
});
test('a suspended MQTT publish times out instead of blocking future sends forever',async()=>{
 const timers=new Map();let id=0;
 const ctx={mqttClients:[{c1ready:true,connected:true,publish:()=>{}}],mqttReady:true,generation:1,seal:async()=>({}),setTimeout:fn=>{timers.set(++id,fn);return id},clearTimeout:id=>timers.delete(id)};
 vm.createContext(ctx);vm.runInContext(source.slice(source.indexOf('async function publishEncrypted('),source.indexOf('function decodePacket(')),ctx);
 const pending=vm.runInContext("publishEncrypted('topic','secret',{})",ctx);
 await new Promise(resolve=>setImmediate(resolve));assert.equal(timers.size,1);for(const fn of timers.values())fn();await assert.rejects(pending,/连接服务已断开/);
});
