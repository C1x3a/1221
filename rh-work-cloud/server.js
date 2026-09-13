import express from 'express';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readState, updateState, publicState, addLog } from './lib/store.js';
import { startLoginSession, finishLoginSession, sessionScreenshot, sessionControl, testWork, executeWork, diagnose, readUsage } from './lib/executor.js';

const __dirname=path.dirname(fileURLToPath(import.meta.url));
const app=express();
app.use(express.json({limit:'2mb'}));

const COOKIE='rh_auth';
const secret=process.env.AUTH_SECRET || 'rh-work-cloud-v2-change-this-secret';
function sign(v){return crypto.createHmac('sha256',secret).update(v).digest('hex');}
function parseCookies(req){return Object.fromEntries(String(req.headers.cookie||'').split(';').map(x=>x.trim()).filter(Boolean).map(x=>{const i=x.indexOf('=');return [decodeURIComponent(x.slice(0,i)),decodeURIComponent(x.slice(i+1))]}));}
function authed(req){const c=parseCookies(req)[COOKIE]; if(!c)return false; const [v,s]=c.split('.'); return v==='ok' && s===sign(v);}
function guard(req,res,next){if(!authed(req))return res.status(401).json({error:'UNAUTHORIZED'});next();}
function setCookie(res){const token=`ok.${sign('ok')}`;res.setHeader('Set-Cookie',`${COOKIE}=${encodeURIComponent(token)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${30*86400}; ${process.env.NODE_ENV==='production'?'Secure;':''}`);}
function fail(res,e,route){const msg=e?.message||String(e);console.error(`[${route}]`,msg,e?.stack||'');return res.status(400).json({error:msg,code:e?.code||null});}

app.get('/health',(req,res)=>res.json({ok:true,time:new Date().toISOString(),version:2}));
app.post('/api/login',(req,res)=>{const s=readState(); if(String(req.body?.password||'')!==String(s.settings.adminPassword))return res.status(401).json({error:'密码错误'});setCookie(res);res.json({ok:true});});
app.post('/api/logout',(req,res)=>{res.setHeader('Set-Cookie',`${COOKIE}=; Path=/; Max-Age=0`);res.json({ok:true});});
app.get('/api/state',guard,(req,res)=>res.json(publicState()));
app.post('/api/settings',guard,(req,res)=>{
  const b=req.body||{}; const s=readState();
  const settings={...s.settings};
  for(const k of ['workName','workUrl','continuePrompt','autoContinue','autoRetry','fallbackWindowMinutes']) if(k in b) settings[k]=b[k];
  if(b.schedule) settings.schedule={...settings.schedule,...b.schedule};
  if(typeof b.steelApiKey==='string' && b.steelApiKey && !b.steelApiKey.includes('•')) settings.steelApiKey=b.steelApiKey.trim();
  if(typeof b.adminPassword==='string' && b.adminPassword.length>=6) settings.adminPassword=b.adminPassword;
  updateState({settings}); addLog('settings_saved','配置已保存'); res.json(publicState());
});

app.get('/api/diagnostics',guard,async(req,res)=>{try{res.json(await diagnose())}catch(e){fail(res,e,'diagnostics')}});
app.post('/api/usage/check',guard,async(req,res)=>{try{res.json(await readUsage())}catch(e){fail(res,e,'usage/check')}});
app.post('/api/browser/start',guard,async(req,res)=>{try{res.json(await startLoginSession())}catch(e){fail(res,e,'browser/start')}});
app.post('/api/browser/finish',guard,async(req,res)=>{try{res.json(await finishLoginSession())}catch(e){fail(res,e,'browser/finish')}});
app.get('/api/browser/screenshot',guard,async(req,res)=>{try{res.json(await sessionScreenshot())}catch(e){fail(res,e,'browser/screenshot')}});
app.post('/api/browser/control',guard,async(req,res)=>{try{const a=req.body||{}; const allowed=['click_mouse','type_text','press_key','scroll','wait','take_screenshot']; if(!allowed.includes(a.action))throw new Error('INVALID_ACTION');res.json(await sessionControl(a))}catch(e){fail(res,e,'browser/control')}});
app.post('/api/work/test',guard,async(req,res)=>{try{res.json(await testWork())}catch(e){fail(res,e,'work/test')}});
app.post('/api/work/run',guard,async(req,res)=>{try{res.json(await executeWork(req.body?.reason||'manual'))}catch(e){fail(res,e,'work/run')}});
app.post('/api/limit/manual',guard,(req,res)=>{const s=readState(); const mins=Math.max(1,Number(req.body?.minutes||s.settings.fallbackWindowMinutes||300)); const detectedAt=new Date().toISOString(); const resetAt=new Date(Date.now()+mins*60000).toISOString();updateState({runtime:{status:'waiting',gptStatus:'limited',limitDetectedAt:detectedAt,resetAt}});addLog('limit_manual',`手动开始恢复倒计时：${mins} 分钟`,'warn');res.json(publicState());});
app.post('/api/limit/clear',guard,(req,res)=>{updateState({runtime:{status:'idle',gptStatus:'unknown',limitDetectedAt:null,resetAt:null,lastError:null}});addLog('limit_cleared','已清除恢复倒计时');res.json(publicState());});

let ticking=false;
async function tick(){
  if(ticking)return; ticking=true;
  try{
    const s=readState(); const now=new Date();
    if(s.settings.autoContinue && s.runtime.status==='waiting' && s.runtime.resetAt && new Date(s.runtime.resetAt).getTime()<=Date.now()){
      addLog('recovery_due','恢复倒计时结束，自动尝试继续');
      await executeWork('limit-recovery').catch(e=>console.error('[auto-recovery]',e.message));
    }
    const sc=s.settings.schedule||{};
    if(sc.enabled && sc.time){
      const sh=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).formatToParts(now).reduce((o,p)=>({...o,[p.type]:p.value}),{});
      const date=`${sh.year}-${sh.month}-${sh.day}`, time=`${sh.hour}:${sh.minute}`, weekday=new Intl.DateTimeFormat('en-US',{timeZone:'Asia/Shanghai',weekday:'short'}).format(now);
      let due=false,key='';
      if(sc.kind==='once' && sc.date===date && sc.time===time){due=true;key=`once:${date}:${time}`}
      if(sc.kind==='daily' && sc.time===time){due=true;key=`daily:${date}:${time}`}
      if(sc.kind==='weekdays' && !['Sat','Sun'].includes(weekday) && sc.time===time){due=true;key=`weekdays:${date}:${time}`}
      if(due && s.runtime.lastScheduleKey!==key){updateState({runtime:{lastScheduleKey:key}});addLog('schedule_due',`定时任务触发 ${key}`);await executeWork('schedule').catch(e=>console.error('[schedule]',e.message));}
    }
  }finally{ticking=false;}
}
setInterval(tick,15000);

app.use(express.static(path.join(__dirname,'public')));
app.use((req,res)=>res.sendFile(path.join(__dirname,'public','index.html')));
const port=Number(process.env.PORT||3000);
app.listen(port,'0.0.0.0',()=>console.log(`RH Work Cloud v2 listening on ${port}`));
