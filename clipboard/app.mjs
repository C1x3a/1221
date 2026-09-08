import {TTL,DEVICE_NAMES,token,parseLines,validBatch,pairingCode,readPair,seal,unseal,copyBatch} from './core.mjs';
const $=id=>document.getElementById(id);
const PREF='c1clip.preferences.v1',SESSION='c1clip.session.v1';
let storageFailed=false;
function load(store,key,fallback){try{return JSON.parse(store.getItem(key)||'null')||fallback}catch{return fallback}}
let prefs=load(localStorage,PREF,{}),work=load(sessionStorage,SESSION,{outbox:[],inbox:[],drafts:{},shared:'',compose:'same'});
work.outbox=(Array.isArray(work.outbox)?work.outbox:[]).filter(x=>validBatch(x.batch));
work.inbox=(Array.isArray(work.inbox)?work.inbox:[]).filter(x=>validBatch(x.batch));
work.drafts=work.drafts||{};
let role='controller',peer=null,generation=0,peerReady=false,phoneConn=null,retryAt=0,copying=false,sending=false,stopCopy=false,selectedBatch=null,installPrompt=null,toastTimer;
const channels=new Map(),selected=new Set(),receipts=new Map();
function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n}
function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4500)}
function save(){try{localStorage.setItem(PREF,JSON.stringify(prefs));sessionStorage.setItem(SESSION,JSON.stringify(work))}catch{if(!storageFailed){storageFailed=true;toast('浏览器未能保存本机状态，关闭页面后可能需要重新配对。')}}}
function feedback(text,error=false){$('send-result').textContent=text;$('send-result').className='feedback'+(error?' error':'')}
function connection(text,bad=false){$('connection-text').textContent=text;$('connection').className='connection'+(bad?' bad':'')}
function ensureController(){if(!prefs.controller){prefs.controller={host:'c1clip-'+token(18),devices:DEVICE_NAMES.map(name=>({id:'d'+token(12),name,key:token(32)}))};save()}if(!selected.size)prefs.controller.devices.forEach(d=>selected.add(d.id))}
function pairFor(device){return {v:1,host:prefs.controller.host,device:device.id,key:device.key,name:device.name}}
function pairURL(pair){return new URL('./',location.href).href+'#pair='+pairingCode(pair)}
function finished(status){return ['copied','confirmed','deleted'].includes(status)}
function currentPhoneBatch(){return work.inbox.find(b=>b.batch.id===selectedBatch)}
function clearExpired(){const now=Date.now();work.outbox=work.outbox.filter(b=>b.batch.createdAt>now-TTL);work.inbox=work.inbox.filter(b=>b.batch.createdAt>now-TTL);save()}
function makePeer(id){return new Peer(id,{debug:0,secure:true,config:{iceServers:[{urls:'stun:stun.cloudflare.com:3478'},{urls:'stun:stun.l.google.com:19302'}]}})}
function alive(link){return !!link&&link.open&&link.authenticated&&Date.now()-(link.lastSeen||0)<35000}
function shutdown(){generation++;peerReady=false;for(const link of channels.values()){link.stale=true;link.close()}channels.clear();if(phoneConn){phoneConn.stale=true;phoneConn.close();phoneConn=null}if(peer){peer.destroy();peer=null}retryAt=0}
async function sendPacket(link,payload){
 if(!link?.open||link.stale)throw new Error('连接已断开');
 const packet=await seal(link.secret,payload);
 if(!link.open||link.stale)throw new Error('连接已断开');
 link.send(packet);
}
function attachSerial(link,handler){link.chain=Promise.resolve();link.on('data',data=>{link.chain=link.chain.then(async()=>{if(link.stale)return;const msg=await unseal(link.secret,data);link.lastSeen=Date.now();await handler(msg)}).catch(()=>{link.stale=true;link.close()})})}
function updateNetwork(){
 if(role==='controller'){
  const n=[...channels.values()].filter(alive).length;$('online-count').textContent=n+' / 4 在线';
  if(peerReady)connection(n?'控制台已连接，'+n+' 部手机在线':'控制台已就绪，等待手机扫码连接');
  for(const d of prefs.controller?.devices||[]){const s=$('device-state-'+d.id);if(s){s.textContent=alive(channels.get(d.id))?'在线':'未连接';s.className='device-status'+(alive(channels.get(d.id))?' online':'')}}
 }else if(alive(phoneConn))connection('已连接电脑，可以接收信息');
}
function networkError(err){
 const messages={'browser-incompatible':'当前浏览器不支持设备直连，请在手机浏览器中打开配对链接。','unavailable-id':'这个电脑控制台已在其他标签页打开。请关闭重复页面后重新连接。','peer-unavailable':'电脑尚未在线，请保持电脑发送页打开。','network':'连接服务暂时不可达，请稍后点“重新连接”。','server-error':'连接服务暂时不可达，请稍后重试。','socket-error':'连接服务已断开，正在等待重连。'};
 connection(messages[err.type]||'连接未建立，请检查网络后重新连接。',true);
}
function bootNetwork(){
 shutdown();const gen=generation;
 if(!window.Peer||!window.RTCPeerConnection||!crypto.subtle){connection('当前浏览器暂不支持直连，请在支持 HTTPS 和 WebRTC 的手机浏览器中打开。',true);return}
 if(role==='phone'&&!prefs.phone){connection('等待配对：请扫描电脑上对应手机的二维码');return}
 connection(role==='controller'?'正在连接控制台…':'正在连接电脑…');
 try{peer=makePeer(role==='controller'?prefs.controller.host:undefined)}catch(err){networkError(err);return}
 const thisPeer=peer;
 thisPeer.on('open',()=>{if(gen!==generation)return;peerReady=true;updateNetwork();if(role==='phone')connectPhone()});
 thisPeer.on('connection',link=>{if(gen!==generation||role!=='controller'){link.close();return}acceptPhone(link)});
 thisPeer.on('disconnected',()=>{if(gen!==generation)return;peerReady=false;connection('连接服务已断开；已有手机直连可能仍可用。',true)});
 thisPeer.on('error',err=>{if(gen===generation)networkError(err)});
 setTimeout(()=>{if(gen===generation&&!peerReady)connection('连接服务响应较慢，请点击“重新连接”重试。',true)},18000);
}
function acceptPhone(link){
 const d=prefs.controller.devices.find(d=>d.id===link.metadata?.device);
 if(!d){link.on('open',()=>link.close());return}
 link.secret=d.key;link.sid=token(18);link.lastSeen=Date.now();link.device=d.id;
 const timer=setTimeout(()=>{if(!link.authenticated)link.close()},15000);
 link.on('open',()=>sendPacket(link,{t:'challenge',sid:link.sid}).catch(()=>link.close()));
 attachSerial(link,async msg=>{
  if(msg.sid!==link.sid)return;
  if(!link.authenticated){
   if(msg.t!=='hello')return;
   const old=channels.get(d.id);if(old&&old!==link){old.stale=true;old.close()}
   link.authenticated=true;channels.set(d.id,link);clearTimeout(timer);
   await sendPacket(link,{t:'ready',sid:link.sid,name:d.name});updateNetwork();flushDevice(d.id);return;
  }
  if(msg.t==='ping'){await sendPacket(link,{t:'pong',sid:link.sid});return}
  if(msg.t==='pong')return;
  if(msg.t==='receipt'){
   const row=work.outbox.find(x=>x.device===d.id&&x.batch.id===msg.id);
   if(!row||!['received','copying','copied','confirmed','error','deleted'].includes(msg.status))return;
   if(msg.status==='received'&&['copying','copied','confirmed','deleted'].includes(row.status))return;
   if(!Number.isInteger(msg.copied)||msg.copied<0||msg.copied>row.batch.items.length)return;
   if(msg.status==='copied'&&msg.copied!==row.batch.items.length)return;
   if(msg.status==='confirmed'&&msg.copied!==row.batch.items.length)return;
   row.status=msg.status;row.copied=msg.copied;row.error=typeof msg.error==='string'?msg.error.slice(0,180):'';save();renderOutbox();
  }
 });
 link.on('close',()=>{clearTimeout(timer);if(channels.get(d.id)===link){channels.delete(d.id);updateNetwork()}});
 link.on('error',()=>link.close());
}
function connectPhone(){
 if(role!=='phone'||!prefs.phone||!peerReady||peer?.destroyed||phoneConn?.open)return;
 if(phoneConn){phoneConn.stale=true;phoneConn.close()}
 retryAt=Date.now();const pair=prefs.phone;
 const link=peer.connect(pair.host,{metadata:{v:1,device:pair.device},serialization:'json',reliable:true});phoneConn=link;link.secret=pair.key;link.lastSeen=Date.now();
 connection('正在连接电脑，请保持两端页面打开…');
 const timer=setTimeout(()=>{if(phoneConn===link&&!link.authenticated){connection('未能连上电脑：请确认电脑在线。当前网络也可能不支持直连。',true);link.close()}},18000);
 attachSerial(link,async msg=>{
  if(msg.t==='challenge'&&!link.authenticated){if(typeof msg.sid!=='string'||msg.sid.length>64)return;link.sid=msg.sid;await sendPacket(link,{t:'hello',sid:link.sid});return}
  if(!link.sid||msg.sid!==link.sid)return;
  if(msg.t==='ready'){
   link.authenticated=true;clearTimeout(timer);connection('已连接电脑，可以接收信息');
   for(const entry of work.inbox.filter(e=>e.device===pair.device))await phoneReceipt(entry);
   for(const entry of receipts.values())await sendPacket(link,{...entry,sid:link.sid});
   return;
  }
  if(!link.authenticated)return;
  if(msg.t==='ping'){await sendPacket(link,{t:'pong',sid:link.sid});return}if(msg.t==='pong')return;
  if(msg.t==='batch'){
   if(!validBatch(msg.batch))return;
   const previous=work.inbox.find(e=>e.batch.id===msg.batch.id&&e.device===pair.device);
   if(previous){await phoneReceipt(previous);return}
   if(receipts.has(msg.batch.id)){await sendPacket(link,{...receipts.get(msg.batch.id),sid:link.sid});return}
   clearExpired();
   if(work.inbox.filter(e=>!finished(e.status)).length>=12){await sendPacket(link,{t:'receipt',sid:link.sid,id:msg.batch.id,status:'error',copied:0,error:'手机有 12 批待处理内容，请先处理后在电脑点重试'});return}
   if(work.inbox.length>=24)work.inbox=work.inbox.filter(e=>!finished(e.status));
   const entry={device:pair.device,batch:msg.batch,status:'received',copied:0};work.inbox.push(entry);save();
   if(!copying&&(!selectedBatch||finished(currentPhoneBatch()?.status)))selectedBatch=entry.batch.id;
   renderInbox();await phoneReceipt(entry);toast('收到 '+entry.batch.items.length+' 条独立信息');
  }
 });
 link.on('close',()=>{clearTimeout(timer);if(phoneConn===link){phoneConn=null;connection('与电脑的连接已断开，页面在前台时会尝试重连。',true)}});
 link.on('error',()=>link.close());
}
async function phoneReceipt(entry){if(!alive(phoneConn))return;try{await sendPacket(phoneConn,{t:'receipt',sid:phoneConn.sid,id:entry.batch.id,status:entry.status,copied:entry.copied||0,error:entry.error||''})}catch{}}
async function flushDevice(id){
 const link=channels.get(id);if(!alive(link))return;
 for(const row of work.outbox.filter(e=>e.device===id&&['queued','sent'].includes(e.status))){
  if(Date.now()-(row.lastAttempt||0)<5000)continue;
  row.lastAttempt=Date.now();
  try{await sendPacket(link,{t:'batch',sid:link.sid,batch:row.batch});if(row.status==='queued')row.status='sent'}catch{row.status='queued'}
 }
 save();renderOutbox();
}
function renderDevices(){
 $('devices').replaceChildren(...prefs.controller.devices.map(d=>{
  const card=el('div','device'+(selected.has(d.id)?' selected':'')),top=el('div','device-top'),check=el('input');check.type='checkbox';check.id='select-'+d.id;check.checked=selected.has(d.id);
  const name=el('label','',d.name);name.htmlFor=check.id;top.append(check,name);
  check.addEventListener('change',()=>{check.checked?selected.add(d.id):selected.delete(d.id);card.classList.toggle('selected',check.checked);renderDifferent();updateCompose()});
  const bottom=el('div','device-bottom'),status=el('span','device-status','未连接');status.id='device-state-'+d.id;const button=el('button','small ghost','配对');button.onclick=()=>openPair(d);bottom.append(status,button);card.append(top,bottom);return card;
 }));updateNetwork();
}
function renderDifferent(){
 $('different-compose').replaceChildren(...prefs.controller.devices.filter(d=>selected.has(d.id)).map(d=>{const wrap=el('div'),label=el('label','',d.name+' · 每行一条'),input=el('textarea');input.id='draft-'+d.id;input.rows=4;input.maxLength=17000;input.placeholder='填写只发给这部手机的内容';input.value=work.drafts[d.id]||'';input.spellcheck=false;label.htmlFor=input.id;input.oninput=()=>{work.drafts[d.id]=input.value;save();updateCompose()};wrap.append(label,input);return wrap}));
}
function updateCompose(){
 const same=work.compose!=='different';$('same-mode').setAttribute('aria-pressed',String(same));$('different-mode').setAttribute('aria-pressed',String(!same));$('same-compose').hidden=!same;$('different-compose').hidden=same;
 const count=String(work.shared||'').split(/\r?\n/).filter(x=>x.trim()).length;
 $('item-count').textContent=same?count+' 条':selected.size+' 部手机';
 $('preview').replaceChildren();if(same&&count){for(let i=0;i<Math.min(count,20);i++)$('preview').append(el('span','','第 '+(i+1)+' 条'))}
 $('send').textContent='发送给 '+selected.size+' 部手机';$('send').disabled=!selected.size||sending;
}
const statusText={queued:'等待连接 · 保存在当前电脑标签页',sent:'已发出 · 等待手机回执',received:'手机已接收 · 等待点击复制',copying:'手机正在逐条复制',copied:'逐条复制完成 · 历史待手机确认',confirmed:'手机已确认全部进入历史',error:'需要处理',deleted:'手机已删除这批内容'};
function renderOutbox(){
 if(!prefs.controller)return;
 const box=$('outbox');box.replaceChildren();box.className=work.outbox.length?'':'empty';
 if(!work.outbox.length){box.textContent='发送后，在这里查看每部手机的接收和复制进度。';return}
 for(const row of [...work.outbox].reverse()){
  const name=prefs.controller.devices.find(d=>d.id===row.device)?.name;if(!name)continue;
  const item=el('div','receipt'),head=el('div','receipt-head');head.append(el('span','',name+' · '+row.batch.items.length+' 条'),el('time','',new Date(row.batch.createdAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})));
  const body=el('div','receipt-state',(statusText[row.status]||'等待连接')+(row.copied?'（'+row.copied+'/'+row.batch.items.length+'）':'')+(row.error?'：'+row.error:''));item.append(head,body);
  if(row.status==='error'){const retry=el('button','ghost small','重试发送');retry.onclick=()=>{row.status='queued';row.error='';row.lastAttempt=0;save();flushDevice(row.device);renderOutbox()};item.append(retry)}
  box.append(item);
 }
}
async function sendSelected(){
 if(!selected.size||sending)return;
 clearExpired();const prepared=[];
 try{for(const d of prefs.controller.devices.filter(x=>selected.has(x.id))){if(work.outbox.filter(x=>x.device===d.id&&!finished(x.status)).length>=12)throw new Error(d.name+' 已有 12 批未结束记录，请先处理');let items;try{items=parseLines(work.compose==='different'?work.drafts[d.id]||'':work.shared||'')}catch(err){throw new Error(d.name+'：'+err.message)}prepared.push({device:d.id,batch:{id:token(18),createdAt:Date.now(),items},status:'queued',copied:0})}}
 catch(err){feedback(err.message,true);return}
 work.outbox.push(...prepared);save();renderOutbox();feedback('已为 '+prepared.length+' 部手机建立发送任务。请查看下方接收进度。');
 sending=true;updateCompose();try{for(const row of prepared)await flushDevice(row.device)}finally{sending=false;updateCompose()}
}
function openPair(device){
 $('pair-title').textContent=device.name+' · 配对';$('pair-link').value=pairURL(pairFor(device));$('pair-status').textContent=alive(channels.get(device.id))?'这部手机已在线。':'请保持电脑页面打开。';
 const qr=new LocalQRCode(-1,1);qr.addData($('pair-link').value);qr.make();const n=qr.getModuleCount(),cell=6,margin=4,canvas=$('qr');canvas.width=canvas.height=(n+margin*2)*cell;const ctx=canvas.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.fillStyle='#102441';for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(qr.isDark(y,x))ctx.fillRect((x+margin)*cell,(y+margin)*cell,cell,cell);
 $('pair-dialog').showModal();
}
function renderInbox(){
 if(copying)return;
 const entries=work.inbox.filter(e=>e.device===prefs.phone?.device);
 if(!entries.find(e=>e.batch.id===selectedBatch))selectedBatch=entries.find(e=>!finished(e.status))?.batch.id||entries.at(-1)?.batch.id||null;
 $('inbox-count').textContent=entries.length+' 批';$('batch-list').replaceChildren();
 if(entries.length>1){const tabs=el('div','batch-tabs');for(const e of entries){const b=el('button','',new Date(e.batch.createdAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})+' · '+e.batch.items.length+' 条');b.setAttribute('aria-pressed',String(e.batch.id===selectedBatch));b.onclick=()=>{selectedBatch=e.batch.id;renderInbox()};tabs.append(b)}$('batch-list').append(tabs)}
 const entry=currentPhoneBatch();$('copy-settings').hidden=!entry;const items=$('inbox-items');items.replaceChildren();
 if(!entry){items.className='empty';items.textContent='等待电脑发送内容…';return}
 items.className='';entry.batch.items.forEach((text,i)=>{const item=el('div','clip-item'),body=el('div','clip-text',text),state=el('span','clip-state',i<entry.copied?'已复制':'');state.id='clip-state-'+i;item.append(el('span','clip-number',String(i+1).padStart(2,'0')),body,state);items.append(item)});
 $('copy-all').textContent='一键逐条复制 '+entry.batch.items.length+' 条';$('copy-all').disabled=false;$('copy-progress').max=entry.batch.items.length;$('copy-progress').value=entry.copied||0;$('copy-status').textContent=entry.error|| (entry.status==='confirmed'?'你已确认：这批内容已全部进入输入法历史。':entry.status==='copied'?'已完成逐条复制，请到输入法检查历史。':'内容已收到，点击下方按钮开始逐条复制。');
 $('confirm-history').disabled=entry.copied!==entry.batch.items.length;
}
async function writeClipboard(text){
 if($('copy-method').value==='modern'){
  if(!navigator.clipboard?.writeText)throw new Error('当前浏览器不支持标准复制，请切换兼容方式');
  await navigator.clipboard.writeText(text);return;
 }
 const previous=document.activeElement,field=document.createElement('textarea');field.value=text;field.style.cssText='position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;font-size:16px';document.body.append(field);
 let ok=false;try{field.focus({preventScroll:true});field.select();field.setSelectionRange(0,text.length);ok=document.execCommand('copy')}finally{field.remove();previous?.focus({preventScroll:true})}
 if(!ok)throw new Error('浏览器未完成复制，请重新点击或更换复制方式');
}
async function startCopy(){
 const entry=currentPhoneBatch();if(!entry||copying)return;
 copying=true;stopCopy=false;entry.copied=0;entry.error='';entry.status='copying';save();
 $('copy-all').disabled=true;$('stop-copy').hidden=false;$('stop-copy').disabled=false;$('confirm-history').disabled=true;
 for(const id of ['interval','copy-method','clear-batch','mode-controller','mode-phone','change-pair'])$(id).disabled=true;
 $('copy-progress').value=0;entry.batch.items.forEach((_,i)=>$('clip-state-'+i).textContent='');
 let wakeLock;const delay=Number($('interval').value);
 // The first write happens within this click; do not await network or a permission query first.
 const resultPromise=copyBatch(entry.batch.items,{write:writeClipboard,delay,canContinue:()=>!stopCopy&&document.visibilityState==='visible'&&document.hasFocus(),onProgress:n=>{entry.copied=n;$('copy-progress').value=n;$('clip-state-'+(n-1)).textContent='已复制';$('copy-status').textContent='正在逐条复制 '+n+' / '+entry.batch.items.length+'，请保持本页打开';save();phoneReceipt(entry)}});
 navigator.wakeLock?.request?.('screen').then(lock=>{if(!copying)lock.release();else wakeLock=lock}).catch(()=>{});
 const result=await resultPromise;
 entry.copied=result.copied;entry.status=result.error?'error':'copied';entry.error=result.error||'';copying=false;
 if(wakeLock)wakeLock.release().catch(()=>{});
 $('stop-copy').hidden=true;for(const id of ['interval','copy-method','clear-batch','mode-controller','mode-phone','change-pair'])$(id).disabled=false;
 save();renderInbox();phoneReceipt(entry);
 if(result.error)$('copy-status').textContent='完成 '+result.copied+'/'+result.total+' 条。'+result.error;
}
function enterPhone(pair){
 if(copying){toast('请先停止当前复制');return}
 if(prefs.phone?.device!==pair.device||prefs.phone?.host!==pair.host){work.inbox=[];selectedBatch=null;receipts.clear()}
 prefs.phone=pair;prefs.role='phone';save();location.hash='pair='+pairingCode(pair);setRole('phone');
}
function setRole(next){
 if(copying)return;role=next;prefs.role=next;save();
 $('controller').hidden=next!=='controller';$('phone').hidden=next!=='phone';$('mode-controller').setAttribute('aria-pressed',String(next==='controller'));$('mode-phone').setAttribute('aria-pressed',String(next==='phone'));
 if(next==='controller'){ensureController();$('shared-text').value=work.shared||'';renderDevices();renderDifferent();updateCompose();renderOutbox()}
 else{$('phone-setup').hidden=!!prefs.phone;$('phone-workspace').hidden=!prefs.phone;if(prefs.phone){$('phone-name').textContent=prefs.phone.name;renderInbox()}}
 bootNetwork();
}
async function simpleCopy(text){if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(text);return}throw new Error('请长按链接文本手动复制')}
$('mode-controller').onclick=()=>setRole('controller');$('mode-phone').onclick=()=>setRole('phone');$('reconnect').onclick=()=>{if(copying){toast('复制结束后再重连');return}bootNetwork()};
$('select-all').onclick=()=>{if(selected.size===4)selected.clear();else prefs.controller.devices.forEach(d=>selected.add(d.id));renderDevices();renderDifferent();updateCompose()};
$('shared-text').oninput=e=>{work.shared=e.target.value;save();updateCompose()};
$('same-mode').onclick=()=>{work.compose='same';save();updateCompose()};$('different-mode').onclick=()=>{work.compose='different';save();renderDifferent();updateCompose()};
$('send').onclick=sendSelected;
$('clear-history').onclick=()=>{work.outbox=work.outbox.filter(r=>!finished(r.status));save();renderOutbox()};
$('close-pair').onclick=()=>$('pair-dialog').close();$('copy-link').onclick=()=>simpleCopy($('pair-link').value).then(()=>$('pair-status').textContent='连接链接已复制，请在对应手机打开。').catch(err=>$('pair-status').textContent=err.message);
$('join').onclick=()=>{try{enterPhone(readPair($('pair-input').value))}catch(err){toast(err.message)}};
$('change-pair').onclick=()=>{if(copying)return;if(!confirm('更换配对后，将清除本页已收到的内容。继续吗？'))return;shutdown();delete prefs.phone;work.inbox=[];selectedBatch=null;receipts.clear();save();history.replaceState(null,'',location.pathname+'#phone');setRole('phone')};
$('copy-all').addEventListener('pointerdown',e=>{if(document.activeElement===$('keyboard-input'))e.preventDefault()});$('copy-all').onclick=startCopy;
$('stop-copy').onclick=()=>{stopCopy=true;$('stop-copy').disabled=true;$('copy-status').textContent='正在停止…'};
$('confirm-history').onclick=()=>{const entry=currentPhoneBatch();if(!entry||copying||entry.copied!==entry.batch.items.length)return;entry.status='confirmed';save();renderInbox();phoneReceipt(entry)};
$('clear-batch').onclick=()=>{const entry=currentPhoneBatch();if(!entry||copying)return;const receipt={t:'receipt',id:entry.batch.id,status:'deleted',copied:entry.copied||0};receipts.set(entry.batch.id,receipt);if(receipts.size>100)receipts.delete(receipts.keys().next().value);if(alive(phoneConn))sendPacket(phoneConn,{...receipt,sid:phoneConn.sid}).catch(()=>{});work.inbox=work.inbox.filter(e=>e!==entry);save();renderInbox()};
for(const id of ['interval','copy-method']){$(id).value=prefs[id]||$(id).value;$(id).onchange=()=>{prefs[id]=$(id).value;save()}}
document.addEventListener('visibilitychange',()=>{if(document.visibilityState!=='visible'){if(copying)stopCopy=true}else{if(role==='phone'&&!alive(phoneConn)){retryAt=0;connectPhone()}updateNetwork()}});
window.addEventListener('blur',()=>{if(copying)stopCopy=true});
window.addEventListener('beforeunload',event=>{if(copying||(role==='controller'&&work.outbox.some(e=>!finished(e.status)))){event.preventDefault();event.returnValue=''}});
window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();installPrompt=event});
$('install').onclick=async()=>{if(installPrompt){await installPrompt.prompt();installPrompt=null}else{toast(/MicroMessenger/i.test(navigator.userAgent)?'请将配对链接在手机浏览器打开，再从浏览器菜单添加到桌面。':'在浏览器菜单中选择“添加到主屏幕”或“安装应用”。')}};
window.addEventListener('online',()=>{if(!copying)bootNetwork()});
setInterval(()=>{
 if(document.visibilityState!=='visible')return;
 if(peer?.disconnected&&!peer.destroyed){try{peer.reconnect()}catch{}}
 if(role==='controller'){
  for(const [id,link] of channels){if(link.open){if(Date.now()-link.lastSeen>35000){link.close();continue}sendPacket(link,{t:'ping',sid:link.sid}).catch(()=>{});flushDevice(id)}}updateNetwork();
 }else{
  if(alive(phoneConn))sendPacket(phoneConn,{t:'ping',sid:phoneConn.sid}).catch(()=>{});
  else if(Date.now()-retryAt>20000){if(phoneConn){phoneConn.stale=true;phoneConn.close();phoneConn=null}connectPhone()}
 }
},6000);
setInterval(()=>{if(!copying){clearExpired();if(role==='controller')renderOutbox();else renderInbox()}},60000);
if('serviceWorker'in navigator)navigator.serviceWorker.register('./sw.js',{scope:'./'}).catch(()=>{});
try{
 if(location.hash.startsWith('#pair=')){const pair=readPair(location.hash);if(prefs.phone?.device!==pair.device||prefs.phone?.host!==pair.host||prefs.phone?.key!==pair.key){work.inbox=[];selectedBatch=null}prefs.phone=pair;prefs.role='phone';save()}
 role=location.hash==='#phone'?'phone':prefs.role==='phone'?'phone':'controller';
 setRole(role);
}catch(err){connection('无法读取连接信息：'+err.message,true);$('controller').hidden=true;$('phone').hidden=false;$('phone-setup').hidden=false}
