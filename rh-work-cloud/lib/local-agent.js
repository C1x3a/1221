import crypto from 'node:crypto';
import { readState, updateState, addLog } from './store.js';

const ONLINE_MS = 90_000;

export function ensureAgentToken(){
  const s=readState();
  if(s.settings.localAgentToken) return s.settings.localAgentToken;
  const token=crypto.randomBytes(32).toString('hex');
  updateState({settings:{localAgentToken:token}});
  return token;
}

export function pairAgent(password){
  const s=readState();
  if(String(password||'')!==String(s.settings.adminPassword||'')) throw new Error('PAIR_PASSWORD_INVALID');
  const token=ensureAgentToken();
  addLog('local_agent_paired','本地监控端已完成配对','success');
  return {token,serverTime:new Date().toISOString()};
}

export function verifyAgentToken(token){
  const expected=ensureAgentToken();
  const a=Buffer.from(String(token||''));
  const b=Buffer.from(String(expected||''));
  return a.length===b.length && a.length>0 && crypto.timingSafeEqual(a,b);
}

export function agentOnline(state=readState()){
  const a=state.runtime?.localAgent||{};
  return Boolean(a.lastSeenAt && Date.now()-new Date(a.lastSeenAt).getTime()<ONLINE_MS);
}

export function heartbeat(payload={}){
  const now=new Date().toISOString();
  const s=readState();
  const prev=s.runtime?.localAgent||{};
  const firstOnline=!agentOnline(s);
  const localAgent={
    ...prev,
    deviceId:String(payload.deviceId||prev.deviceId||'office-pc'),
    hostname:String(payload.hostname||prev.hostname||''),
    version:String(payload.version||prev.version||''),
    browser:String(payload.browser||prev.browser||''),
    browserReady:Boolean(payload.browserReady),
    chatgptLoggedIn:Boolean(payload.chatgptLoggedIn),
    workReady:Boolean(payload.workReady),
    lastError:payload.lastError?String(payload.lastError):null,
    lastSeenAt:now,
    status:'online'
  };
  updateState({runtime:{localAgent}});
  if(firstOnline) addLog('local_agent_online',`公司电脑监控端已上线${localAgent.hostname?`：${localAgent.hostname}`:''}`,'success');
  return {ok:true,serverTime:now};
}

export function queueAgentCommand(type,payload={},reason='manual'){
  const s=readState();
  const queue=Array.isArray(s.runtime?.localAgentQueue)?s.runtime.localAgentQueue:[];
  if(type==='CHECK_USAGE'){
    const existing=queue.find(x=>x.type==='CHECK_USAGE' && ['queued','assigned'].includes(x.status));
    if(existing) return existing;
  }
  const cmd={id:crypto.randomUUID(),type,payload,reason,status:'queued',createdAt:new Date().toISOString(),assignedAt:null,finishedAt:null,result:null,error:null};
  const next=[...queue,cmd].slice(-100);
  updateState({runtime:{localAgentQueue:next}});
  addLog('local_agent_command_queued',`${type} 已发送到公司电脑`,'info',{commandId:cmd.id,reason});
  return cmd;
}

export function pollAgent(deviceId='office-pc'){
  const s=readState();
  const queue=Array.isArray(s.runtime?.localAgentQueue)?s.runtime.localAgentQueue:[];
  const idx=queue.findIndex(x=>x.status==='queued');
  if(idx<0) return {command:null};
  const cmd={...queue[idx],status:'assigned',assignedAt:new Date().toISOString(),deviceId};
  const next=[...queue]; next[idx]=cmd;
  updateState({runtime:{localAgentQueue:next}});
  return {command:cmd};
}

function usageResetForExhausted(usage){
  const resets=[];
  for(const k of ['fiveHour','weekly']){
    const q=usage?.[k];
    if(Number(q?.remainingPercent)===0 && q?.resetAt) resets.push(new Date(q.resetAt).getTime());
  }
  if(!resets.length) return null;
  return new Date(Math.max(...resets)).toISOString();
}

function knownAvailable(usage){
  const f=Number(usage?.fiveHour?.remainingPercent);
  const w=Number(usage?.weekly?.remainingPercent);
  if(!Number.isFinite(f) || !Number.isFinite(w)) return false;
  return f>0 && w>0;
}

function applyUsage(usage){
  const normalized={...usage,status:'ok',checkedAt:new Date().toISOString(),source:'local-office-pc',error:null};
  const patch={usage:normalized};
  const resetAt=usageResetForExhausted(normalized);
  if(resetAt){
    patch.runtime={status:'waiting',gptStatus:'limited',limitDetectedAt:new Date().toISOString(),resetAt,lastError:null};
  }else if(knownAvailable(normalized)){
    const s=readState();
    patch.runtime={gptStatus:'available',lastError:null};
    if(s.runtime.status==='waiting') patch.runtime.status='ready_local';
  }
  updateState(patch);
  const f=normalized.fiveHour?.remainingPercent;
  const w=normalized.weekly?.remainingPercent;
  addLog('local_usage_update',`公司电脑读取额度：5小时剩余 ${Number.isFinite(Number(f))?f:'?'}%；每周剩余 ${Number.isFinite(Number(w))?w:'?'}%`,'success',{fiveHour:normalized.fiveHour,weekly:normalized.weekly});
  return normalized;
}

export function reportUsage(usage){
  return applyUsage(usage||{});
}

export function finishAgentCommand(id,{ok=true,result=null,error=null}={}){
  const s=readState();
  const queue=Array.isArray(s.runtime?.localAgentQueue)?s.runtime.localAgentQueue:[];
  const idx=queue.findIndex(x=>x.id===id);
  if(idx<0) return {ok:false,missing:true};
  const cmd={...queue[idx],status:ok?'done':'failed',finishedAt:new Date().toISOString(),result:result||null,error:error?String(error):null};
  const next=[...queue]; next[idx]=cmd;
  const patch={runtime:{localAgentQueue:next.slice(-100)}};
  if(cmd.type==='CHECK_USAGE' && ok && result?.usage) applyUsage(result.usage);
  if(cmd.type==='SEND_WORK'){
    if(ok && result?.submitted){
      patch.runtime={...patch.runtime,status:'idle',gptStatus:'available',lastSuccessAt:new Date().toISOString(),lastError:null,resetAt:null,limitDetectedAt:null};
      addLog('local_execution_success','公司电脑已确认提交续跑消息','success',{url:result.url,commandId:id});
    }else if(result?.limited && result?.usage){
      applyUsage(result.usage);
      addLog('local_execution_limited','公司电脑检测到额度不足，进入等待恢复','warn',{commandId:id});
    }else if(!ok){
      patch.runtime={...patch.runtime,status:'error',lastError:error||'本地监控端执行失败'};
      addLog('local_execution_failed',error||'本地监控端执行失败','error',{commandId:id});
    }
  }
  updateState(patch);
  return {ok:true,command:cmd};
}

export function localAgentSnapshot(){
  const s=readState();
  const a=s.runtime?.localAgent||{};
  const queue=Array.isArray(s.runtime?.localAgentQueue)?s.runtime.localAgentQueue:[];
  return {
    online:agentOnline(s),
    ...a,
    pending:queue.filter(x=>['queued','assigned'].includes(x.status)).length,
    recentCommands:queue.slice(-20).reverse()
  };
}
