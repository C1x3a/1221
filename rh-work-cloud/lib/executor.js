import { createSession, connectSession, releaseSession, computer, viewerUrl, steelClient } from './steel.js';
import { readState, updateState, addLog } from './store.js';

const INPUTS = "#prompt-textarea,[data-testid='prompt-textarea'],textarea,div[contenteditable='true'][role='textbox'],div[contenteditable='true']";
const LIMIT_PATTERNS = [
  /you(?:'|’)ve reached (?:your )?(?:usage )?limit/i,
  /usage limit/i,
  /limit reached/i,
  /try again (?:after|later)/i,
  /达到.{0,12}(?:限额|上限)/i,
  /已达到.{0,12}(?:限额|上限)/i,
  /额度.{0,8}(?:用尽|已用完|不足)/i,
  /稍后再试/i
];

function bodyHasLimit(text){ return LIMIT_PATTERNS.some(r=>r.test(text || '')); }

function parseResetFromText(text){
  const compact = String(text||'').replace(/\s+/g,' ');
  let m = compact.match(/(?:reset(?:s)?|try again|available again)[^\n]{0,100}?(?:in\s*)?(?:(\d+)\s*(?:hours?|hrs?|小时))?[^\n]{0,50}?(?:(\d+)\s*(?:minutes?|mins?|分钟))?/i);
  if (m && (m[1] || m[2])) return new Date(Date.now() + (Number(m[1]||0)*60 + Number(m[2]||0))*60000).toISOString();
  m = compact.match(/(?:today|今天|今日)\s*(?:at\s*)?([01]?\d|2[0-3]):([0-5]\d)/i);
  if (m){ const d=new Date(); d.setHours(Number(m[1]),Number(m[2]),0,0); if(d.getTime()<Date.now()) d.setDate(d.getDate()+1); return d.toISOString(); }
  m = compact.match(/(?:tomorrow|明天|明日)\s*(?:at\s*)?([01]?\d|2[0-3]):([0-5]\d)/i);
  if (m){ const d=new Date(); d.setDate(d.getDate()+1); d.setHours(Number(m[1]),Number(m[2]),0,0); return d.toISOString(); }
  return null;
}

function loggedOut(url,text){
  const s=(text||'').slice(0,1800);
  return /\/auth\//i.test(url) || /log in|sign in|登录|注册/i.test(s) && !/log out|退出登录/i.test(s);
}

async function safeGoto(page,url){
  let last;
  for (const timeout of [45000, 90000]) {
    try {
      await page.goto(url,{waitUntil:'commit',timeout});
      await page.waitForTimeout(4000);
      return;
    } catch (e) { last=e; }
  }
  throw last;
}

async function inspect(page){
  const url=page.url();
  const text=await page.locator('body').innerText().catch(()=> '');
  const inputCount=await page.locator(INPUTS).count().catch(()=>0);
  return {url,text,inputCount,limited:bodyHasLimit(text),loggedOut:loggedOut(url,text)};
}

function recordLimit(text){
  const state=readState();
  const detectedAt=new Date().toISOString();
  const explicit=parseResetFromText(text);
  const resetAt=explicit || new Date(Date.now() + Number(state.settings.fallbackWindowMinutes||300)*60000).toISOString();
  updateState({runtime:{status:'waiting',gptStatus:'limited',limitDetectedAt:detectedAt,resetAt,lastError:null}});
  addLog('limit_detected',`检测到额度限制，预计恢复 ${new Date(resetAt).toLocaleString('zh-CN',{hour12:false})}`,'warn',{resetAt,explicit:Boolean(explicit)});
  return resetAt;
}

export async function startLoginSession(){
  const state=readState();
  if (state.runtime.activeSessionId) await finishLoginSession().catch(()=>{});
  const {session,key}=await createSession(state.runtime.steelProfileId,840000);
  const debug=viewerUrl(session);
  updateState({runtime:{activeSessionId:session.id,activeDebugUrl:debug,status:'login_waiting',steelProfileId:session.profileId || state.runtime.steelProfileId || null,lastError:null}});
  try {
    const {browser,page}=await connectSession(key,session);
    await safeGoto(page,'https://chatgpt.com/').catch(()=>{});
    await browser.close().catch(()=>{});
  } catch {}
  addLog('login_session_started','已启动唯一 Steel 登录会话');
  return {sessionId:session.id,debugUrl:debug,profileId:session.profileId || state.runtime.steelProfileId || null};
}

export async function finishLoginSession(){
  const state=readState();
  if (!state.runtime.activeSessionId) return {ok:true};
  const {client}=steelClient();
  await releaseSession(client,state.runtime.activeSessionId);
  updateState({runtime:{activeSessionId:null,activeDebugUrl:null,status:'idle'}});
  addLog('login_session_saved','登录会话已释放，Profile 将用于后续执行');
  return {ok:true};
}

export async function sessionScreenshot(){
  const state=readState();
  if (!state.runtime.activeSessionId) throw new Error('NO_ACTIVE_SESSION');
  return computer(state.runtime.activeSessionId,{action:'take_screenshot'});
}

export async function sessionControl(action){
  const state=readState();
  if (!state.runtime.activeSessionId) throw new Error('NO_ACTIVE_SESSION');
  return computer(state.runtime.activeSessionId,{...action,screenshot:true});
}

export async function testWork(){
  const state=readState();
  if (!state.runtime.steelProfileId) throw new Error('PROFILE_MISSING');
  if (!state.settings.workUrl) throw new Error('WORK_URL_MISSING');
  const {client,key,session}=await createSession(state.runtime.steelProfileId,300000);
  let browser;
  try {
    ({browser}=await connectSession(key,session));
    const page=browser.contexts()[0].pages()[0];
    await safeGoto(page,state.settings.workUrl);
    const info=await inspect(page);
    if (info.loggedOut) throw new Error('LOGIN_REQUIRED');
    if (info.limited){ recordLimit(info.text); return {ok:true,limited:true,url:info.url,hasInput:Boolean(info.inputCount)}; }
    updateState({runtime:{gptStatus:'available',status:'idle',lastError:null}});
    addLog('work_test_ok',`Work 页面连接成功${info.inputCount?'，已找到输入框':'，页面已打开但未找到输入框'}`,'info',{url:info.url});
    return {ok:true,limited:false,url:info.url,hasInput:Boolean(info.inputCount)};
  } finally {
    try{await browser?.close();}catch{}
    await releaseSession(client,session.id);
  }
}

async function submitPrompt(page,prompt){
  await page.locator(INPUTS).first().waitFor({state:'visible',timeout:45000});
  const target=page.locator(INPUTS).first();
  try { await target.fill(prompt); }
  catch { await target.click(); await target.press('Control+A').catch(()=>{}); await target.press('Backspace').catch(()=>{}); await target.type(prompt,{delay:2}); }
  const buttons=["button[data-testid='send-button']","button[aria-label*='Send']","button[aria-label*='发送']"];
  for (const sel of buttons){ const b=page.locator(sel).first(); if(await b.count().catch(()=>0)){ await b.click().catch(()=>{}); return; } }
  await target.press('Enter');
}

export async function executeWork(reason='manual'){
  const state=readState();
  const now=Date.now();
  if (state.runtime.executionLockUntil && new Date(state.runtime.executionLockUntil).getTime()>now) throw new Error('EXECUTION_LOCKED');
  if (!state.runtime.steelProfileId) throw new Error('PROFILE_MISSING');
  if (!/^https:\/\/chatgpt\.com\//i.test(state.settings.workUrl||'')) throw new Error('WORK_URL_INVALID');
  if (!String(state.settings.continuePrompt||'').trim()) throw new Error('PROMPT_MISSING');
  updateState({runtime:{executionLockUntil:new Date(now+8*60000).toISOString(),status:'running',lastRunAt:new Date().toISOString(),lastError:null}});
  addLog('execution_started',`开始执行：${reason}`);
  const {client,key,session}=await createSession(state.runtime.steelProfileId,600000);
  let browser;
  try {
    ({browser}=await connectSession(key,session));
    const page=browser.contexts()[0].pages()[0];
    await safeGoto(page,state.settings.workUrl);
    let info=await inspect(page);
    if (info.loggedOut) throw new Error('LOGIN_REQUIRED');
    if (info.limited){ const resetAt=recordLimit(info.text); return {submitted:false,limited:true,resetAt,url:info.url}; }
    if (!info.inputCount){ await page.waitForTimeout(5000); info=await inspect(page); }
    if (!info.inputCount) throw new Error('INPUT_NOT_FOUND');
    await submitPrompt(page,state.settings.continuePrompt);
    await page.waitForTimeout(2500);
    const after=await inspect(page);
    if (after.limited){ const resetAt=recordLimit(after.text); return {submitted:false,limited:true,resetAt,url:after.url}; }
    updateState({runtime:{status:'idle',gptStatus:'available',lastSuccessAt:new Date().toISOString(),lastError:null,limitDetectedAt:null,resetAt:null,executionLockUntil:null}});
    addLog('execution_success','续跑文案已提交','success',{url:after.url});
    return {submitted:true,limited:false,url:after.url};
  } catch(e){
    const msg=e?.message||String(e);
    updateState({runtime:{status:msg==='LOGIN_REQUIRED'?'login_required':'error',lastError:msg,executionLockUntil:null}});
    addLog('execution_failed',msg,'error');
    throw e;
  } finally {
    try{await browser?.close();}catch{}
    await releaseSession(client,session.id);
    updateState({runtime:{executionLockUntil:null}});
  }
}
