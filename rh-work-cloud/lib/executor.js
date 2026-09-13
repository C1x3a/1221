import { createSession, connectSession, releaseSession, computer, viewerUrl, steelClient } from './steel.js';
import { readState, updateState, addLog } from './store.js';

const INPUTS = [
  '#prompt-textarea',
  '[data-testid="prompt-textarea"]',
  'textarea',
  '[contenteditable="plaintext-only"]',
  'div[contenteditable="true"][role="textbox"]',
  '.ProseMirror[contenteditable="true"]',
  'div[contenteditable="true"]'
];
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
function cleanText(v=''){ return String(v).replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').trim(); }
function isInteractiveWorkUrl(url=''){
  return /^https:\/\/chatgpt\.com\//i.test(url) && !/\/share\//i.test(url);
}
function friendlyError(code){
  const map={
    STEEL_KEY_MISSING:'请先填写并保存 Steel API Key',
    PROFILE_MISSING:'尚未建立 ChatGPT 云浏览器身份，请先点“建立/登录 ChatGPT”完成一次登录',
    WORK_URL_MISSING:'请先填写 Work URL 并保存',
    WORK_URL_INVALID:'Work URL 无效，请使用真实可交互的 ChatGPT 对话/Work 地址，不要使用 /share/ 分享链接',
    LOGIN_REQUIRED:'ChatGPT 登录状态无效，请重新打开云浏览器登录',
    INPUT_NOT_FOUND:'已打开 Work，但未识别到可输入的消息框',
    SEND_NOT_CONFIRMED:'已尝试发送，但页面没有确认消息提交成功',
    ACTIVE_LOGIN_SESSION:'当前登录浏览器仍在打开，请先完成登录并保存状态',
    EXECUTION_LOCKED:'已有一个 Work 执行任务正在运行'
  };
  return map[code] || code;
}

function parseResetFromText(text){
  const compact = cleanText(text);
  let m = compact.match(/(?:reset(?:s)?|try again|available again|重置|恢复)[^\n]{0,120}?(?:in\s*)?(?:(\d+)\s*(?:hours?|hrs?|小时))?[^\n]{0,60}?(?:(\d+)\s*(?:minutes?|mins?|分钟))?/i);
  if (m && (m[1] || m[2])) return new Date(Date.now() + (Number(m[1]||0)*60 + Number(m[2]||0))*60000).toISOString();
  m = compact.match(/(?:today|今天|今日)\s*(?:at\s*)?([01]?\d|2[0-3]):([0-5]\d)/i);
  if (m){ const d=new Date(); d.setHours(Number(m[1]),Number(m[2]),0,0); if(d.getTime()<Date.now()) d.setDate(d.getDate()+1); return d.toISOString(); }
  m = compact.match(/(?:tomorrow|明天|明日)\s*(?:at\s*)?([01]?\d|2[0-3]):([0-5]\d)/i);
  if (m){ const d=new Date(); d.setDate(d.getDate()+1); d.setHours(Number(m[1]),Number(m[2]),0,0); return d.toISOString(); }
  return null;
}

function loggedOut(url,text){
  const s=cleanText(text).slice(0,2500);
  return /\/auth\//i.test(url) || ((/log in|sign in|登录|注册/i.test(s)) && !(/log out|退出登录|settings|设置/i.test(s)));
}

async function safeGoto(page,url){
  let last;
  for (const timeout of [35000,70000,120000]) {
    try {
      await page.goto(url,{waitUntil:'commit',timeout});
      await page.waitForTimeout(3500);
      return;
    } catch (e) { last=e; }
  }
  throw last;
}

async function inspect(page){
  const url=page.url();
  const text=await page.locator('body').innerText().catch(()=> '');
  const input=await findVisibleInput(page);
  return {url,text,input,limited:bodyHasLimit(text),loggedOut:loggedOut(url,text)};
}

async function findVisibleInput(page){
  for (const sel of INPUTS){
    const loc=page.locator(sel);
    const count=await loc.count().catch(()=>0);
    for(let i=0;i<count;i++){
      const item=loc.nth(i);
      if(await item.isVisible().catch(()=>false)) return item;
    }
  }
  return null;
}

function recordLimit(text){
  const state=readState();
  const detectedAt=new Date().toISOString();
  const explicit=parseResetFromText(text);
  const resetAt=explicit || new Date(Date.now() + Number(state.settings.fallbackWindowMinutes||300)*60000).toISOString();
  updateState({runtime:{status:'waiting',gptStatus:'limited',limitDetectedAt:detectedAt,resetAt,lastError:null}});
  addLog('limit_detected',`检测到额度限制；${explicit?'页面给出了恢复时间':'页面未给出明确恢复时间，使用兜底重试时间'}`,'warn',{resetAt,explicit:Boolean(explicit)});
  return resetAt;
}

function percentageNear(text, labels){
  const src=cleanText(text);
  for(const label of labels){
    const i=src.toLowerCase().indexOf(label.toLowerCase());
    if(i<0) continue;
    const chunk=src.slice(Math.max(0,i-80),i+240);
    let m=chunk.match(/(\d{1,3}(?:\.\d+)?)\s*%\s*(?:remaining|left|剩余|可用)/i);
    if(m){ const remaining=Math.min(100,Number(m[1])); return {remainingPercent:remaining,usedPercent:Math.max(0,100-remaining),label:chunk}; }
    m=chunk.match(/(?:used|已用|使用)\s*(\d{1,3}(?:\.\d+)?)\s*%/i) || chunk.match(/(\d{1,3}(?:\.\d+)?)\s*%\s*(?:used|已用|使用)/i);
    if(m){ const used=Math.min(100,Number(m[1])); return {usedPercent:used,remainingPercent:Math.max(0,100-used),label:chunk}; }
    m=chunk.match(/(\d{1,3}(?:\.\d+)?)\s*%/);
    if(m){ return {usedPercent:null,remainingPercent:null,label:chunk,displayPercent:Number(m[1])}; }
  }
  return {usedPercent:null,remainingPercent:null,label:null};
}

function resetNear(text, labels){
  const src=cleanText(text);
  for(const label of labels){
    const i=src.toLowerCase().indexOf(label.toLowerCase());
    if(i<0) continue;
    const chunk=src.slice(Math.max(0,i-80),i+320);
    const parsed=parseResetFromText(chunk);
    if(parsed) return parsed;
  }
  return null;
}

function parseUsageText(text){
  const fiveLabels=['5-hour','5 hour','five-hour','5 小时','5小时'];
  const weekLabels=['weekly','week','每周','周用量','7 day','7-day'];
  const five=percentageNear(text,fiveLabels);
  const weekly=percentageNear(text,weekLabels);
  five.resetAt=resetNear(text,fiveLabels);
  weekly.resetAt=resetNear(text,weekLabels);
  const src=cleanText(text);
  const relevant=/usage|allowance|5-hour|weekly|用量|额度|5 小时|每周/i.test(src);
  return {relevant,fiveHour:five,weekly,rawPreview:src.slice(0,3500)};
}

async function openUsagePanel(page){
  await safeGoto(page,'https://chatgpt.com/');
  const firstText=await page.locator('body').innerText().catch(()=> '');
  if(loggedOut(page.url(),firstText)) throw new Error('LOGIN_REQUIRED');

  const hashCandidates=['#settings/Usage','#settings/usage','#settings/usage-and-billing'];
  for(const hash of hashCandidates){
    await page.evaluate(h=>{location.hash=h;},hash).catch(()=>{});
    await page.waitForTimeout(2500);
    const t=await page.locator('body').innerText().catch(()=> '');
    if(parseUsageText(t).relevant) return {text:t,source:`hash:${hash}`};
  }

  const settingsTexts=['Settings','设置'];
  for(const txt of settingsTexts){
    const item=page.getByText(txt,{exact:true}).first();
    if(await item.isVisible().catch(()=>false)){ await item.click().catch(()=>{}); await page.waitForTimeout(1600); break; }
  }
  const usageTexts=['Usage & billing','Usage','用量与计费','用量','使用情况'];
  for(const txt of usageTexts){
    const item=page.getByText(txt,{exact:false}).first();
    if(await item.isVisible().catch(()=>false)){ await item.click().catch(()=>{}); await page.waitForTimeout(2200); const t=await page.locator('body').innerText().catch(()=> ''); if(parseUsageText(t).relevant) return {text:t,source:`ui:${txt}`}; }
  }

  const text=await page.locator('body').innerText().catch(()=> '');
  return {text,source:'chatgpt-page'};
}

async function withProfileSession(fn, timeout=420000){
  const state=readState();
  if(!state.runtime.steelProfileId) throw new Error('PROFILE_MISSING');
  if(state.runtime.activeSessionId) throw new Error('ACTIVE_LOGIN_SESSION');
  const {client,key,session}=await createSession(state.runtime.steelProfileId,timeout);
  let browser;
  try{
    ({browser}=await connectSession(key,session));
    const page=browser.contexts()[0].pages()[0];
    return await fn({page,session});
  }finally{
    try{await browser?.close();}catch{}
    await releaseSession(client,session.id);
  }
}

export async function startLoginSession(){
  const state=readState();
  if (state.runtime.activeSessionId) await finishLoginSession().catch(()=>{});
  const {session,key}=await createSession(state.runtime.steelProfileId,840000);
  const profileId=session.profileId || state.runtime.steelProfileId || null;
  const debug=viewerUrl(session);
  updateState({runtime:{activeSessionId:session.id,activeDebugUrl:debug,status:'login_waiting',steelProfileId:profileId,profileStatus:profileId?'created':'missing',lastError:null}});
  try {
    const {browser,page}=await connectSession(key,session);
    await safeGoto(page,'https://chatgpt.com/').catch(()=>{});
    await browser.close().catch(()=>{});
  } catch {}
  addLog('login_session_started',profileId?'Steel Profile 已建立，等待完成 ChatGPT 登录':'云浏览器已启动，但未拿到 Profile ID',profileId?'info':'warn');
  return {sessionId:session.id,debugUrl:debug,profileId};
}

export async function finishLoginSession(){
  const state=readState();
  if (!state.runtime.activeSessionId) return {ok:true,profileId:state.runtime.steelProfileId};
  const {client}=steelClient();
  await releaseSession(client,state.runtime.activeSessionId);
  updateState({runtime:{activeSessionId:null,activeDebugUrl:null,status:'idle',profileStatus:state.runtime.steelProfileId?'saved':'missing'}});
  addLog('login_session_saved','登录会话已释放，正在使用同一个 Steel Profile 保存登录状态');
  return {ok:true,profileId:state.runtime.steelProfileId};
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

export async function diagnose(){
  const state=readState();
  const result={
    steelConfigured:Boolean(process.env.STEEL_API_KEY || state.settings.steelApiKey),
    profileReady:Boolean(state.runtime.steelProfileId),
    workUrlConfigured:Boolean(state.settings.workUrl),
    workUrlInteractive:isInteractiveWorkUrl(state.settings.workUrl||''),
    chatgptLoggedIn:false,
    inputDetected:false,
    error:null
  };
  if(!result.steelConfigured){result.error=friendlyError('STEEL_KEY_MISSING'); return result;}
  if(!result.profileReady){result.error=friendlyError('PROFILE_MISSING'); return result;}
  try{
    await withProfileSession(async({page})=>{
      await safeGoto(page,state.settings.workUrl && result.workUrlInteractive ? state.settings.workUrl : 'https://chatgpt.com/');
      const info=await inspect(page);
      result.chatgptLoggedIn=!info.loggedOut;
      result.inputDetected=Boolean(info.input);
      result.currentUrl=info.url;
      if(info.loggedOut) result.error=friendlyError('LOGIN_REQUIRED');
    },240000);
  }catch(e){ result.error=friendlyError(e.message||String(e)); }
  return result;
}

export async function readUsage(){
  const checkedAt=new Date().toISOString();
  try{
    const data=await withProfileSession(async({page})=>{
      const opened=await openUsagePanel(page);
      const parsed=parseUsageText(opened.text);
      if(!parsed.relevant) throw new Error('USAGE_PANEL_NOT_FOUND');
      return {...parsed,source:opened.source};
    },360000);
    const usage={status:'ok',checkedAt,source:data.source,fiveHour:data.fiveHour,weekly:data.weekly,rawPreview:data.rawPreview,error:null};
    updateState({usage});
    addLog('usage_read','已从 ChatGPT Usage 页面读取额度信息','success',{source:data.source});
    return usage;
  }catch(e){
    const msg=e.message||String(e);
    const usage={status:'error',checkedAt,source:null,fiveHour:{usedPercent:null,remainingPercent:null,resetAt:null,label:null},weekly:{usedPercent:null,remainingPercent:null,resetAt:null,label:null},rawPreview:'',error:friendlyError(msg==='USAGE_PANEL_NOT_FOUND'?'未能在当前 ChatGPT 页面找到 Usage 信息':msg)};
    updateState({usage});
    addLog('usage_read_failed',usage.error,'error');
    throw Object.assign(new Error(usage.error),{code:msg});
  }
}

export async function testWork(){
  const state=readState();
  if (!isInteractiveWorkUrl(state.settings.workUrl||'')) throw new Error(state.settings.workUrl?'WORK_URL_INVALID':'WORK_URL_MISSING');
  return withProfileSession(async({page})=>{
    await safeGoto(page,state.settings.workUrl);
    const info=await inspect(page);
    if (info.loggedOut) throw new Error('LOGIN_REQUIRED');
    if (info.limited){ const resetAt=recordLimit(info.text); return {ok:true,limited:true,url:info.url,hasInput:Boolean(info.input),resetAt}; }
    updateState({runtime:{gptStatus:'available',status:'idle',lastError:null,profileStatus:'ready'}});
    addLog('work_test_ok',`Work 已打开${info.input?'，已识别消息输入框':'，但暂未识别消息输入框'}`,'info',{url:info.url});
    return {ok:true,limited:false,url:info.url,hasInput:Boolean(info.input)};
  },360000);
}

async function submitPrompt(page,prompt){
  let input=await findVisibleInput(page);
  if(!input){
    await page.waitForTimeout(5000);
    input=await findVisibleInput(page);
  }
  if(!input) throw new Error('INPUT_NOT_FOUND');

  const beforeCount=await page.locator('[data-message-author-role="user"]').count().catch(()=>0);
  await input.scrollIntoViewIfNeeded().catch(()=>{});
  await input.click({timeout:10000});
  const tag=await input.evaluate(el=>el.tagName.toLowerCase()).catch(()=> 'div');
  if(tag==='textarea' || tag==='input'){
    await input.fill(prompt).catch(async()=>{ await page.keyboard.insertText(prompt); });
  }else{
    await page.keyboard.press(process.platform==='darwin'?'Meta+A':'Control+A').catch(()=>{});
    await page.keyboard.press('Backspace').catch(()=>{});
    await page.keyboard.insertText(prompt);
  }
  await page.waitForTimeout(300);

  const buttons=[
    'button[data-testid="send-button"]',
    'button[aria-label*="Send"]',
    'button[aria-label*="发送"]',
    'button[aria-label*="Submit"]'
  ];
  let clicked=false;
  for (const sel of buttons){
    const b=page.locator(sel).first();
    if(await b.isVisible().catch(()=>false) && await b.isEnabled().catch(()=>false)){
      await b.click(); clicked=true; break;
    }
  }
  if(!clicked) await page.keyboard.press('Enter');

  const deadline=Date.now()+20000;
  while(Date.now()<deadline){
    await page.waitForTimeout(700);
    const nowCount=await page.locator('[data-message-author-role="user"]').count().catch(()=>0);
    if(nowCount>beforeCount) return true;
    const current=await findVisibleInput(page);
    if(current){
      const val=await current.evaluate(el=>('value' in el?el.value:el.innerText)||'').catch(()=> 'x');
      if(!String(val).trim()) return true;
    }
    const body=await page.locator('body').innerText().catch(()=> '');
    if(bodyHasLimit(body)) return 'limited';
  }
  throw new Error('SEND_NOT_CONFIRMED');
}

export async function executeWork(reason='manual'){
  const state=readState();
  const now=Date.now();
  if (state.runtime.executionLockUntil && new Date(state.runtime.executionLockUntil).getTime()>now) throw new Error('EXECUTION_LOCKED');
  if (!state.runtime.steelProfileId) throw new Error('PROFILE_MISSING');
  if (!isInteractiveWorkUrl(state.settings.workUrl||'')) throw new Error(state.settings.workUrl?'WORK_URL_INVALID':'WORK_URL_MISSING');
  if (!String(state.settings.continuePrompt||'').trim()) throw new Error('PROMPT_MISSING');
  if(state.runtime.activeSessionId) throw new Error('ACTIVE_LOGIN_SESSION');
  updateState({runtime:{executionLockUntil:new Date(now+8*60000).toISOString(),status:'running',lastRunAt:new Date().toISOString(),lastError:null}});
  addLog('execution_started',`开始执行：${reason}`);
  try {
    const result=await withProfileSession(async({page})=>{
      await safeGoto(page,state.settings.workUrl);
      let info=await inspect(page);
      if (info.loggedOut) throw new Error('LOGIN_REQUIRED');
      if (info.limited){ const resetAt=recordLimit(info.text); return {submitted:false,limited:true,resetAt,url:info.url}; }
      const sent=await submitPrompt(page,state.settings.continuePrompt);
      if(sent==='limited'){
        const body=await page.locator('body').innerText().catch(()=> '');
        const resetAt=recordLimit(body);
        return {submitted:false,limited:true,resetAt,url:page.url()};
      }
      await page.waitForTimeout(1200);
      const after=await inspect(page);
      if (after.limited){ const resetAt=recordLimit(after.text); return {submitted:false,limited:true,resetAt,url:after.url}; }
      return {submitted:true,limited:false,url:after.url};
    },600000);
    if(result.submitted){
      updateState({runtime:{status:'idle',gptStatus:'available',profileStatus:'ready',lastSuccessAt:new Date().toISOString(),lastError:null,limitDetectedAt:null,resetAt:null,executionLockUntil:null}});
      addLog('execution_success','续跑文案已确认提交','success',{url:result.url});
    }
    return result;
  } catch(e){
    const code=e?.message||String(e);
    const msg=friendlyError(code);
    updateState({runtime:{status:code==='LOGIN_REQUIRED'?'login_required':'error',lastError:msg,executionLockUntil:null}});
    addLog('execution_failed',msg,'error',{code});
    throw Object.assign(new Error(msg),{code});
  } finally {
    updateState({runtime:{executionLockUntil:null}});
  }
}
