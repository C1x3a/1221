import { createSession, connectSession, releaseSession } from './steel.js';
import { readState, updateState, addLog } from './store.js';

function clean(v=''){
  return String(v).replace(/\u00a0/g,' ').replace(/\r/g,'').replace(/[ \t]+/g,' ').trim();
}

function loggedOut(url,text){
  const s=clean(text).slice(0,2600);
  return /\/auth\//i.test(url) || ((/log in|sign in|登录|注册/i.test(s)) && !(/settings|设置|log out|退出登录/i.test(s)));
}

async function safeGoto(page,url){
  let last;
  for(const timeout of [35000,70000,120000]){
    try{
      await page.goto(url,{waitUntil:'commit',timeout});
      await page.waitForTimeout(4200);
      return;
    }catch(e){ last=e; }
  }
  throw last;
}

function parseReset(cardText){
  const text=clean(cardText);
  let m=text.match(/(?:resets?|reset|available again|try again|重置|恢复)[^\n]{0,80}?(?:in\s*)?(?:(\d+)\s*(?:hours?|hrs?|小时))?[^\n]{0,40}?(?:(\d+)\s*(?:minutes?|mins?|分钟))?/i);
  if(m && (m[1] || m[2])){
    return new Date(Date.now()+(Number(m[1]||0)*60+Number(m[2]||0))*60000).toISOString();
  }
  m=text.match(/(?:today|今天|今日)\s*(?:at\s*)?([01]?\d|2[0-3]):([0-5]\d)/i);
  if(m){
    const d=new Date(); d.setHours(Number(m[1]),Number(m[2]),0,0); if(d.getTime()<Date.now())d.setDate(d.getDate()+1); return d.toISOString();
  }
  m=text.match(/(?:tomorrow|明天|明日)\s*(?:at\s*)?([01]?\d|2[0-3]):([0-5]\d)/i);
  if(m){
    const d=new Date(); d.setDate(d.getDate()+1); d.setHours(Number(m[1]),Number(m[2]),0,0); return d.toISOString();
  }
  return null;
}

function parseCard(cardText){
  const text=clean(cardText);
  const out={usedPercent:null,remainingPercent:null,displayPercent:null,resetAt:parseReset(text),label:text.slice(0,700),rawCardText:text};

  let m=text.match(/(?:remaining|left|剩余|可用额度|可用)\s*[:：]?\s*(\d{1,3}(?:\.\d+)?)\s*%/i)
    || text.match(/(\d{1,3}(?:\.\d+)?)\s*%\s*(?:remaining|left|剩余|可用额度|可用)/i);
  if(m){
    const remaining=Math.min(100,Math.max(0,Number(m[1])));
    out.remainingPercent=remaining;
    out.usedPercent=100-remaining;
    return out;
  }

  m=text.match(/(?:used|已用|已使用|使用量)\s*[:：]?\s*(\d{1,3}(?:\.\d+)?)\s*%/i)
    || text.match(/(\d{1,3}(?:\.\d+)?)\s*%\s*(?:used|已用|已使用|使用量)/i);
  if(m){
    const used=Math.min(100,Math.max(0,Number(m[1])));
    out.usedPercent=used;
    out.remainingPercent=100-used;
    return out;
  }

  m=text.match(/(\d{1,3}(?:\.\d+)?)\s*%/);
  if(m) out.displayPercent=Math.min(100,Math.max(0,Number(m[1])));
  return out;
}

async function extractCard(page,terms){
  return page.evaluate((terms)=>{
    const lowerTerms=terms.map(x=>x.toLowerCase());
    const visible=el=>{
      const r=el.getBoundingClientRect();
      const s=getComputedStyle(el);
      return r.width>0 && r.height>0 && s.display!=='none' && s.visibility!=='hidden';
    };
    const all=[...document.querySelectorAll('body *')];
    const candidates=[];
    for(const el of all){
      if(!visible(el)) continue;
      const own=(el.innerText||'').trim();
      if(!own || own.length>240) continue;
      const low=own.toLowerCase();
      if(!lowerTerms.some(t=>low.includes(t))) continue;
      let p=el;
      for(let depth=0;depth<7 && p;depth++,p=p.parentElement){
        const text=(p.innerText||'').trim();
        if(text.length>0 && text.length<=1200 && /\d{1,3}(?:\.\d+)?\s*%/.test(text)){
          const l=text.toLowerCase();
          if(lowerTerms.some(t=>l.includes(t))){
            candidates.push({text,length:text.length,depth});
            break;
          }
        }
      }
    }
    candidates.sort((a,b)=>a.length-b.length || a.depth-b.depth);
    return candidates[0]?.text || '';
  },terms).catch(()=> '');
}

async function extractUsage(page){
  const body=await page.locator('body').innerText().catch(()=> '');
  if(loggedOut(page.url(),body)) throw new Error('LOGIN_REQUIRED');
  const fiveText=await extractCard(page,['5-hour','5 hour','five-hour','5 小时','5小时']);
  const weeklyText=await extractCard(page,['weekly','weekly limit','week limit','每周','周限额','周用量']);
  return {body,fiveText,weeklyText};
}

async function tryUsageRoutes(page){
  const attempts=[];
  const direct=[
    'https://chatgpt.com/codex/settings/usage',
    'https://chatgpt.com/settings/usage'
  ];
  for(const url of direct){
    try{
      await safeGoto(page,url);
      const x=await extractUsage(page);
      attempts.push({url:page.url(),five:Boolean(x.fiveText),weekly:Boolean(x.weeklyText)});
      if(x.fiveText || x.weeklyText) return {...x,source:page.url(),attempts};
    }catch(e){
      if(e.message==='LOGIN_REQUIRED') throw e;
      attempts.push({url,error:e.message||String(e)});
    }
  }

  await safeGoto(page,'https://chatgpt.com/');
  const first=await page.locator('body').innerText().catch(()=> '');
  if(loggedOut(page.url(),first)) throw new Error('LOGIN_REQUIRED');

  for(const hash of ['#settings/Usage','#settings/usage','#settings/usage-and-billing']){
    await page.evaluate(h=>{location.hash=h;},hash).catch(()=>{});
    await page.waitForTimeout(3000);
    const x=await extractUsage(page);
    attempts.push({url:`https://chatgpt.com/${hash}`,five:Boolean(x.fiveText),weekly:Boolean(x.weeklyText)});
    if(x.fiveText || x.weeklyText) return {...x,source:`https://chatgpt.com/${hash}`,attempts};
  }

  for(const settingsText of ['Settings','设置']){
    const b=page.getByText(settingsText,{exact:true}).first();
    if(await b.isVisible().catch(()=>false)){
      await b.click().catch(()=>{});
      await page.waitForTimeout(1800);
      break;
    }
  }
  for(const usageText of ['Usage & billing','Usage','用量与计费','用量','使用情况']){
    const b=page.getByText(usageText,{exact:false}).first();
    if(await b.isVisible().catch(()=>false)){
      await b.click().catch(()=>{});
      await page.waitForTimeout(2600);
      const x=await extractUsage(page);
      attempts.push({url:`ui:${usageText}`,five:Boolean(x.fiveText),weekly:Boolean(x.weeklyText)});
      if(x.fiveText || x.weeklyText) return {...x,source:`ui:${usageText}`,attempts};
    }
  }

  const x=await extractUsage(page);
  return {...x,source:page.url(),attempts};
}

function friendly(code){
  const map={
    STEEL_KEY_MISSING:'请先填写并保存 Steel API Key',
    PROFILE_MISSING:'尚未建立 Steel Profile，请先完成 ChatGPT 云浏览器登录',
    ACTIVE_LOGIN_SESSION:'当前登录浏览器仍打开，请先点“完成登录并保存状态”',
    LOGIN_REQUIRED:'ChatGPT 登录状态已失效，请重新登录',
    USAGE_CARD_NOT_FOUND:'没有识别到 ChatGPT 的 5 小时/每周额度卡片；本次不会返回猜测值'
  };
  return map[code]||code;
}

export async function readUsageStrict(){
  const state=readState();
  const checkedAt=new Date().toISOString();
  if(!state.runtime.steelProfileId) throw new Error(friendly('PROFILE_MISSING'));
  if(state.runtime.activeSessionId) throw new Error(friendly('ACTIVE_LOGIN_SESSION'));

  let client,session,browser;
  try{
    ({client,session}=await createSession(state.runtime.steelProfileId,360000));
    const key=(await import('./store.js')).getSteelKey();
    ({browser}=await connectSession(key,session));
    const page=browser.contexts()[0].pages()[0];
    const found=await tryUsageRoutes(page);
    if(!found.fiveText && !found.weeklyText) throw Object.assign(new Error('USAGE_CARD_NOT_FOUND'),{attempts:found.attempts});

    const fiveHour=found.fiveText?parseCard(found.fiveText):{usedPercent:null,remainingPercent:null,displayPercent:null,resetAt:null,label:null,rawCardText:''};
    const weekly=found.weeklyText?parseCard(found.weeklyText):{usedPercent:null,remainingPercent:null,displayPercent:null,resetAt:null,label:null,rawCardText:''};
    const usage={status:'ok',checkedAt,source:found.source,fiveHour,weekly,rawPreview:clean(found.body).slice(0,5000),attempts:found.attempts,error:null};
    updateState({usage});

    const show=v=>Number.isFinite(v.remainingPercent)?`剩余 ${v.remainingPercent}%`:Number.isFinite(v.usedPercent)?`已用 ${v.usedPercent}%`:Number.isFinite(v.displayPercent)?`页面原值 ${v.displayPercent}%`:'未识别';
    addLog('usage_read',`额度读取：5小时 ${show(fiveHour)}；每周 ${show(weekly)}`,'success',{source:found.source,fiveRaw:fiveHour.rawCardText,weeklyRaw:weekly.rawCardText});
    return usage;
  }catch(e){
    const code=e.message||String(e);
    const error=friendly(code);
    const usage={status:'error',checkedAt,source:null,fiveHour:{usedPercent:null,remainingPercent:null,displayPercent:null,resetAt:null,label:null,rawCardText:''},weekly:{usedPercent:null,remainingPercent:null,displayPercent:null,resetAt:null,label:null,rawCardText:''},rawPreview:'',attempts:e.attempts||[],error};
    updateState({usage});
    addLog('usage_read_failed',error,'error',{attempts:e.attempts||[]});
    throw Object.assign(new Error(error),{code});
  }finally{
    try{await browser?.close();}catch{}
    if(client&&session)try{await releaseSession(client,session.id);}catch{}
  }
}
