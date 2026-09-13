import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import readline from 'node:readline/promises';
import { chromium } from 'playwright-core';

const __dirname=path.dirname(fileURLToPath(import.meta.url));
const DATA=path.join(__dirname,'data');
const CONFIG=path.join(DATA,'config.json');
const LOG=path.join(DATA,'agent.log');
const PROFILE=path.join(DATA,'browser-profile');
const CDP='http://127.0.0.1:9222';
const VERSION='1.0.0';
fs.mkdirSync(DATA,{recursive:true});

function log(...parts){
  const line=`[${new Date().toISOString()}] ${parts.map(x=>typeof x==='string'?x:JSON.stringify(x)).join(' ')}`;
  console.log(line);
  fs.appendFileSync(LOG,line+'\n');
}
function sleep(ms){return new Promise(r=>setTimeout(r,ms));}
function readConfig(){try{return JSON.parse(fs.readFileSync(CONFIG,'utf8'));}catch{return null;}}
function saveConfig(c){fs.writeFileSync(CONFIG,JSON.stringify(c,null,2));}
function commonBrowser(){
  const candidates=[
    process.env.RH_BROWSER_PATH,
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'
  ].filter(Boolean);
  return candidates.find(p=>fs.existsSync(p))||null;
}
async function post(server,route,body={},token=''){
  const r=await fetch(server.replace(/\/$/,'')+route,{method:'POST',headers:{'content-type':'application/json',...(token?{'x-rh-agent-token':token}:{})},body:JSON.stringify(body)});
  const j=await r.json().catch(()=>({}));
  if(!r.ok)throw Object.assign(new Error(j.error||`HTTP ${r.status}`),{status:r.status});
  return j;
}
async function ensureConfig(){
  let c=readConfig();
  if(c?.server && c?.token) return c;
  const rl=readline.createInterface({input:process.stdin,output:process.stdout});
  const server=(await rl.question('RH Work Cloud 地址 [https://rh-work-cloud-web-production.up.railway.app]: ')).trim()||'https://rh-work-cloud-web-production.up.railway.app';
  const password=(await rl.question('控制台密码（首次配对使用）: ')).trim();
  rl.close();
  const paired=await post(server,'/api/local-agent/pair',{password});
  c={server,token:paired.token,deviceId:crypto.randomUUID(),browserPath:commonBrowser(),createdAt:new Date().toISOString()};
  saveConfig(c);
  log('首次配对完成');
  return c;
}

async function cdpReady(){
  try{const r=await fetch(CDP+'/json/version');return r.ok;}catch{return false;}
}
async function launchBrowser(config){
  if(await cdpReady()) return;
  const browserPath=config.browserPath&&fs.existsSync(config.browserPath)?config.browserPath:commonBrowser();
  if(!browserPath)throw new Error('未找到 Edge/Chrome，请安装 Microsoft Edge 或 Chrome');
  config.browserPath=browserPath; saveConfig(config);
  fs.mkdirSync(PROFILE,{recursive:true});
  const args=[
    '--remote-debugging-port=9222',
    '--remote-debugging-address=127.0.0.1',
    `--user-data-dir=${PROFILE}`,
    '--no-first-run',
    '--no-default-browser-check',
    'https://chatgpt.com/'
  ];
  spawn(browserPath,args,{detached:true,stdio:'ignore'}).unref();
  for(let i=0;i<60;i++){if(await cdpReady())return;await sleep(1000);}
  throw new Error('浏览器远程控制端口未启动');
}

let browser=null, context=null, usagePage=null, workPage=null;
async function connectBrowser(config){
  await launchBrowser(config);
  browser=await chromium.connectOverCDP(CDP);
  context=browser.contexts()[0];
  if(!context)throw new Error('未找到浏览器上下文');
  const pages=context.pages();
  usagePage=pages[0]||await context.newPage();
  workPage=pages[1]||await context.newPage();
}
function loggedOut(text,url=''){
  const t=String(text||'').slice(0,3000);
  return /\/auth\//i.test(url)||((/log in|sign up|sign in|登录|注册/i.test(t))&&!(/新聊天|new chat|设置|settings/i.test(t)));
}
async function isLoggedIn(){
  if(!usagePage)return false;
  try{
    await usagePage.goto('https://chatgpt.com/',{waitUntil:'commit',timeout:45000});
    await usagePage.waitForTimeout(2500);
    const text=await usagePage.locator('body').innerText().catch(()=> '');
    return !loggedOut(text,usagePage.url());
  }catch{return false;}
}

function parseReset(text){
  const t=String(text||'').replace(/\s+/g,' ');
  let m=t.match(/(?:(\d+)\s*天)?\s*(?:(\d+)\s*小时)?\s*(?:(\d+)\s*分钟)?\s*后重置/);
  if(m&&(m[1]||m[2]||m[3])) return new Date(Date.now()+((Number(m[1]||0)*24*60)+(Number(m[2]||0)*60)+Number(m[3]||0))*60000).toISOString();
  m=t.match(/resets?\s+in\s+(?:(\d+)\s*days?)?\s*(?:(\d+)\s*hours?)?\s*(?:(\d+)\s*minutes?)?/i);
  if(m&&(m[1]||m[2]||m[3])) return new Date(Date.now()+((Number(m[1]||0)*24*60)+(Number(m[2]||0)*60)+Number(m[3]||0))*60000).toISOString();
  return null;
}
function parseLimit(text,labelRegex){
  const src=String(text||'');
  const m=src.match(labelRegex);
  if(!m)return null;
  const idx=m.index||0;
  const chunk=src.slice(idx,idx+500).replace(/\s+/g,' ').trim();
  let p=chunk.match(/剩余\s*(\d{1,3}(?:\.\d+)?)\s*%/i)||chunk.match(/(\d{1,3}(?:\.\d+)?)\s*%\s*(?:remaining|left)/i);
  const remaining=p?Math.max(0,Math.min(100,Number(p[1]))):null;
  return {remainingPercent:remaining,usedPercent:Number.isFinite(remaining)?100-remaining:null,resetAt:parseReset(chunk),rawCardText:chunk};
}
function parseUsageBody(body){
  const five=parseLimit(body,/5\s*小时限额|5-hour\s*limit|5 hour\s*limit/i);
  const weekly=parseLimit(body,/每周限额|weekly\s*limit|week\s*limit/i);
  return {fiveHour:five,weekly};
}
async function clickIfVisible(page,locator){
  try{if(await locator.isVisible({timeout:1200})){await locator.click({timeout:5000});return true;}}catch{}
  return false;
}
async function openUsageUI(page){
  await page.goto('https://chatgpt.com/',{waitUntil:'commit',timeout:60000});
  await page.waitForTimeout(2500);
  let body=await page.locator('body').innerText().catch(()=> '');
  let parsed=parseUsageBody(body);
  if(parsed.fiveHour||parsed.weekly)return body;

  // ChatGPT 的设置弹窗有时支持 hash 直达，先尝试不破坏页面结构的方式。
  for(const hash of ['#settings/Usage','#settings/usage']){
    await page.evaluate(h=>{location.hash=h;},hash).catch(()=>{});
    await page.waitForTimeout(2000);
    body=await page.locator('body').innerText().catch(()=> '');
    parsed=parseUsageBody(body);
    if(parsed.fiveHour||parsed.weekly)return body;
  }

  const profileSelectors=[
    'button[data-testid="profile-button"]',
    'button[data-testid="accounts-profile-button"]',
    'button[aria-label*="profile" i]',
    'button[aria-label*="account" i]',
    'button[aria-label*="账户"]',
    'button[aria-label*="个人"]'
  ];
  for(const s of profileSelectors){if(await clickIfVisible(page,page.locator(s).first()))break;}
  if(!(await page.getByText('设置',{exact:true}).first().isVisible().catch(()=>false))&&!(await page.getByText('Settings',{exact:true}).first().isVisible().catch(()=>false))){
    const planBtn=page.locator('button').filter({hasText:/Plus|Pro|Free|Go/}).last();
    await clickIfVisible(page,planBtn);
  }
  if(!(await clickIfVisible(page,page.getByText('设置',{exact:true}).first()))) await clickIfVisible(page,page.getByText('Settings',{exact:true}).first());
  await page.waitForTimeout(1200);
  if(!(await clickIfVisible(page,page.getByText('使用情况',{exact:true}).first())))
    if(!(await clickIfVisible(page,page.getByText('Usage',{exact:true}).first())))
      await clickIfVisible(page,page.getByText('Usage & billing',{exact:false}).first());
  await page.waitForTimeout(2200);
  return await page.locator('body').innerText().catch(()=> '');
}
async function readUsage(){
  const body=await openUsageUI(usagePage);
  if(loggedOut(body,usagePage.url()))throw new Error('ChatGPT 登录已失效');
  const parsed=parseUsageBody(body);
  if(!parsed.fiveHour&&!parsed.weekly)throw new Error('没有识别到“5 小时限额 / 每周限额”卡片');
  return {
    fiveHour:parsed.fiveHour||{remainingPercent:null,usedPercent:null,resetAt:null,rawCardText:''},
    weekly:parsed.weekly||{remainingPercent:null,usedPercent:null,resetAt:null,rawCardText:''},
    source:'office-pc-real-browser',
    rawPreview:body.slice(0,5000)
  };
}

const INPUTS=['#prompt-textarea','[data-testid="prompt-textarea"]','textarea','[contenteditable="plaintext-only"]','div[contenteditable="true"][role="textbox"]','.ProseMirror[contenteditable="true"]','div[contenteditable="true"]'];
async function findInput(page){
  for(const sel of INPUTS){
    const loc=page.locator(sel);const count=await loc.count().catch(()=>0);
    for(let i=0;i<count;i++){const x=loc.nth(i);if(await x.isVisible().catch(()=>false))return x;}
  }
  return null;
}
async function inspectWork(url){
  await workPage.goto(url,{waitUntil:'commit',timeout:90000});
  await workPage.waitForTimeout(3000);
  const body=await workPage.locator('body').innerText().catch(()=> '');
  if(loggedOut(body,workPage.url()))throw new Error('ChatGPT 登录已失效');
  const input=await findInput(workPage);
  return {ok:true,url:workPage.url(),hasInput:Boolean(input)};
}
async function sendWork(url,prompt){
  await workPage.goto(url,{waitUntil:'commit',timeout:90000});
  await workPage.waitForTimeout(3000);
  let body=await workPage.locator('body').innerText().catch(()=> '');
  if(loggedOut(body,workPage.url()))throw new Error('ChatGPT 登录已失效');
  if(/您目前已无可用用量|reached.*limit|usage limit|剩余\s*0%/i.test(body)){
    const usage=await readUsage().catch(()=>null);
    return {submitted:false,limited:true,url:workPage.url(),usage};
  }
  let input=await findInput(workPage);
  if(!input){await workPage.waitForTimeout(4000);input=await findInput(workPage);}
  if(!input)throw new Error('没有识别到消息输入框');
  const before=await workPage.locator('[data-message-author-role="user"]').count().catch(()=>0);
  await input.click({timeout:10000});
  const tag=await input.evaluate(el=>el.tagName.toLowerCase()).catch(()=> 'div');
  if(tag==='textarea'||tag==='input')await input.fill(prompt);
  else{
    await workPage.keyboard.press('Control+A').catch(()=>{});
    await workPage.keyboard.press('Backspace').catch(()=>{});
    await workPage.keyboard.insertText(prompt);
  }
  await workPage.waitForTimeout(300);
  let sent=false;
  for(const sel of ['button[data-testid="send-button"]','button[aria-label*="Send"]','button[aria-label*="发送"]']){
    const b=workPage.locator(sel).first();
    if(await b.isVisible().catch(()=>false)&&await b.isEnabled().catch(()=>false)){await b.click();sent=true;break;}
  }
  if(!sent)await workPage.keyboard.press('Enter');
  const deadline=Date.now()+20000;
  while(Date.now()<deadline){
    await workPage.waitForTimeout(700);
    const now=await workPage.locator('[data-message-author-role="user"]').count().catch(()=>0);
    if(now>before)return {submitted:true,limited:false,url:workPage.url()};
    const i=await findInput(workPage);
    if(i){const v=await i.evaluate(el=>('value' in el?el.value:el.innerText)||'').catch(()=> 'x');if(!String(v).trim())return {submitted:true,limited:false,url:workPage.url()};}
    body=await workPage.locator('body').innerText().catch(()=> '');
    if(/您目前已无可用用量|reached.*limit|usage limit/i.test(body)){
      const usage=await readUsage().catch(()=>null);
      return {submitted:false,limited:true,url:workPage.url(),usage};
    }
  }
  throw new Error('发送后没有确认消息已提交');
}

let config, lastError=null, busy=false, loggedIn=false;
function basePayload(){return {deviceId:config.deviceId,hostname:os.hostname(),version:VERSION,browser:path.basename(config.browserPath||''),browserReady:Boolean(browser),chatgptLoggedIn:loggedIn,workReady:loggedIn,lastError};}
async function heartbeat(){
  try{await post(config.server,'/api/local-agent/heartbeat',basePayload(),config.token);}catch(e){lastError=e.message;log('heartbeat error',e.message);}
}
async function reportUsageNow(){
  if(busy||!loggedIn)return;
  busy=true;
  try{
    const usage=await readUsage();
    await post(config.server,'/api/local-agent/usage',{...basePayload(),usage},config.token);
    lastError=null;
    log(`额度：5小时 ${usage.fiveHour?.remainingPercent ?? '?'}%，每周 ${usage.weekly?.remainingPercent ?? '?'}%`);
  }catch(e){lastError=e.message;log('usage error',e.message);}finally{busy=false;}
}
async function handleCommand(cmd){
  busy=true;
  try{
    let result;
    if(cmd.type==='CHECK_USAGE')result={usage:await readUsage()};
    else if(cmd.type==='TEST_WORK')result=await inspectWork(cmd.payload?.url||'');
    else if(cmd.type==='SEND_WORK')result=await sendWork(cmd.payload?.url||'',cmd.payload?.prompt||'');
    else throw new Error(`未知命令 ${cmd.type}`);
    await post(config.server,'/api/local-agent/report',{...basePayload(),commandId:cmd.id,ok:true,result},config.token);
    lastError=null;
  }catch(e){
    lastError=e.message;
    await post(config.server,'/api/local-agent/report',{...basePayload(),commandId:cmd.id,ok:false,error:e.message},config.token).catch(()=>{});
    log('command failed',cmd.type,e.message);
  }finally{busy=false;}
}
async function poll(){
  if(busy)return;
  try{
    const r=await post(config.server,'/api/local-agent/poll',basePayload(),config.token);
    if(r.command)await handleCommand(r.command);
  }catch(e){lastError=e.message;if(e.status===401)log('设备令牌失效，请删除 data/config.json 后重新运行配对');}
}

async function main(){
  console.log('RH Work 本地监控端');
  console.log('关闭本窗口会停止监控。安装开机自启后可隐藏运行。');
  config=await ensureConfig();
  await connectBrowser(config);
  loggedIn=await isLoggedIn();
  if(!loggedIn){
    console.log('\n请在刚打开的 Edge/Chrome 窗口中登录 ChatGPT。登录完成后本程序会自动识别，无需把账号密码交给程序。\n');
  }
  setInterval(async()=>{loggedIn=await isLoggedIn().catch(()=>false);await heartbeat();},20000);
  setInterval(()=>poll(),5000);
  setInterval(()=>reportUsageNow(),60000);
  await heartbeat();
  for(;;){
    if(!loggedIn)loggedIn=await isLoggedIn().catch(()=>false);
    if(loggedIn){await reportUsageNow();break;}
    await sleep(5000);
  }
  log('本地监控端已进入常驻模式');
}

process.on('unhandledRejection',e=>log('unhandledRejection',e?.stack||String(e)));
main().catch(e=>{log('fatal',e?.stack||e.message);console.error('\n启动失败：',e.message);setTimeout(()=>process.exit(1),10000);});
