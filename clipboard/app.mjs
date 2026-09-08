import {TTL,DEVICE_NAMES,PUBLIC_TRANSPORT,token,parseLines,hasSendableText,replaceDeviceEntries,validBatch,validPair,validTransport,pairingCode,readPair,seal,unseal,copyBatch} from './core.mjs';
import {migrateController,addNamedDevices,renameDevice,removeDevice,saveGroup,groupSelection} from './manage.mjs';
import {extractIdentitySegments,peopleToText,idShape} from './extract.mjs';
import {orderDevices,assignSegments} from './assign.mjs';
import {createSyncCode,syncKey,fetchSettings,putSettings} from './sync.mjs';
const $=id=>document.getElementById(id);
const PREF='c1clip.preferences.v1',SESSION='c1clip.session.v1';
let storageFailed=false;
function load(store,key,fallback){try{return JSON.parse(store.getItem(key)||'null')||fallback}catch{return fallback}}
let prefs=load(localStorage,PREF,{}),work=load(sessionStorage,SESSION,{outbox:[],inbox:[],drafts:{},shared:'',compose:'same'});
work.outbox=(Array.isArray(work.outbox)?work.outbox:[]).filter(x=>validBatch(x.batch));
work.inbox=(Array.isArray(work.inbox)?work.inbox:[]).filter(x=>validBatch(x.batch));
const latestByDevice=rows=>[...rows].sort((a,b)=>b.batch.createdAt-a.batch.createdAt).filter((row,index,all)=>all.findIndex(item=>item.device===row.device)===index);
work.outbox=latestByDevice(work.outbox);work.inbox=latestByDevice(work.inbox);
work.drafts=work.drafts||{};
let role='controller',mqttClients=[],generation=0,mqttReady=false,phoneConn=null,copying=false,sending=false,stopCopy=false,installPrompt=null,toastTimer;
const channels=new Map(),selected=new Set(),receipts=new Map();
let selectedInitialized=false,activeGroup=work.activeGroup||null,editingDevice=null,editingGroup=null,groupMembers=new Set(),extractRows=[],extractSegments=[],assignmentRows=[],extractTimer,viewTimer,syncTimer,syncBusy=false,syncDirty=false,syncConflict=null,syncNotice='';
function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n}
function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4500)}
function save(){try{localStorage.setItem(PREF,JSON.stringify(prefs));sessionStorage.setItem(SESSION,JSON.stringify(work))}catch{if(!storageFailed){storageFailed=true;toast('浏览器未能保存本机状态，关闭页面后可能需要重新配对。')}}}
function feedback(text,error=false){$('send-result').textContent=text;$('send-result').className='feedback'+(error?' error':'')}
function connection(text,bad=false){$('connection-text').textContent=text;$('connection').className='connection'+(bad?' bad':'')}
function publicTransport(){return {urls:[...PUBLIC_TRANSPORT.urls],username:'',password:''}}
function ensureController(){if(!prefs.controller)prefs.controller={host:'c1clip-'+token(18),devices:DEVICE_NAMES.map(name=>({id:'d'+token(12),name,key:token(32)})),transport:publicTransport()};migrateController(prefs.controller);if(!validTransport(prefs.controller.transport))prefs.controller.transport=publicTransport();if(!selectedInitialized){const ids=new Set(prefs.controller.devices.map(d=>d.id));(Array.isArray(work.selected)?work.selected:[...ids]).filter(id=>ids.has(id)).forEach(id=>selected.add(id));selectedInitialized=true;if(!prefs.controller.groups.some(g=>g.id===activeGroup))activeGroup=null}save()}
function syncTime(value){if(!value)return '';const date=new Date(value);return Number.isNaN(date.getTime())?'':date.toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})}
function renderSync(){
 const enabled=!!prefs.sync?.code;$('sync-actions').hidden=enabled;$('sync-connected').hidden=!enabled;$('sync-conflict').hidden=!syncConflict;
 $('sync-badge').textContent=enabled?(syncBusy?'同步中':syncConflict?'待处理':syncDirty?'待同步':'已同步'):'仅本机';$('sync-badge').className='count'+(enabled&&!syncConflict?'':' neutral');
 $('sync-status').textContent=enabled?(syncNotice||(syncConflict?'另一台电脑也修改了设备设置，请在下方选择保留版本。':syncDirty?'本机修改正在等待上传…':'已连接共享设置'+(syncTime(prefs.sync.updatedAt)?' · '+syncTime(prefs.sync.updatedAt):''))):'手机名称、配对和分组目前只保存在这台电脑。';
 for(const id of ['sync-enable','sync-join','sync-copy-code','sync-refresh','sync-disable','sync-use-cloud','sync-use-local'])$(id).disabled=syncBusy;
}
function reconcileController(){const ids=new Set(prefs.controller.devices.map(d=>d.id));for(const id of [...selected])if(!ids.has(id))selected.delete(id);if(!selected.size)prefs.controller.devices.forEach(d=>selected.add(d.id));if(!prefs.controller.groups.some(g=>g.id===activeGroup))activeGroup=null;saveSelection()}
function applySyncedController(controller,{restart=true}={}){const oldNetwork=JSON.stringify([prefs.controller?.host,prefs.controller?.transport]);prefs.controller=controller;migrateController(prefs.controller);if(!validTransport(prefs.controller.transport))prefs.controller.transport=publicTransport();reconcileController();save();if(role==='controller'&&$('controller')&&!$('controller').hidden){renderDevices();renderDifferent();updateCompose();renderOutbox();if(restart||oldNetwork!==JSON.stringify([controller.host,controller.transport]))bootNetwork()}}
function controllerChanged(){save();if(!prefs.sync?.code)return;syncDirty=true;syncConflict=null;syncNotice='';renderSync();clearTimeout(syncTimer);syncTimer=setTimeout(()=>pushSharedSettings(),900)}
function missingSharedSettings(err){return err?.message==='没有找到这个同步码对应的设置'}
async function seedSharedSettings(){
 const result=await putSettings(prefs.sync.code,0,prefs.controller);prefs.sync.revision=result.revision;prefs.sync.updatedAt=result.updatedAt;syncDirty=false;syncConflict=null;syncNotice='';save();return result;
}
async function pushSharedSettings(expectedRevision=prefs.sync?.revision||0){
 if(!prefs.sync?.code||syncBusy)return;syncBusy=true;syncNotice='正在加密并保存设置…';renderSync();
 try{const result=await putSettings(prefs.sync.code,expectedRevision,prefs.controller);prefs.sync.revision=result.revision;prefs.sync.updatedAt=result.updatedAt;syncDirty=false;syncConflict=null;syncNotice='';save();toast('共享设置已更新')}
 catch(err){if(err.name==='SyncConflict'){syncConflict={revision:err.revision};syncNotice=''}else syncNotice=err.message}
 finally{syncBusy=false;renderSync()}
}
async function pullSharedSettings({force=false,restart=true}={}){
 if(!prefs.sync?.code||syncBusy)return;syncBusy=true;syncNotice='正在读取共享设置…';renderSync();
 try{const remote=await fetchSettings(prefs.sync.code);if(remote.revision>(prefs.sync.revision||0)){if(syncDirty&&!force){syncConflict={revision:remote.revision};syncNotice=''}else{applySyncedController(remote.controller,{restart});prefs.sync.revision=remote.revision;prefs.sync.updatedAt=remote.updatedAt;syncDirty=false;syncConflict=null;syncNotice='';save();if(restart)toast('已载入另一台电脑的最新设置')}}else{prefs.sync.revision=remote.revision;prefs.sync.updatedAt=remote.updatedAt;syncNotice='';save();if(syncDirty)queueMicrotask(()=>pushSharedSettings())}}
 catch(err){if(missingSharedSettings(err)&&(prefs.sync?.revision||0)>0){try{await seedSharedSettings();toast('旧同步设置已迁移到新服务器')}catch(seedError){syncNotice=seedError.message}}else syncNotice=err.message}
 finally{syncBusy=false;renderSync()}
}
function openSyncJoin(){syncKey(prefs.sync?.code||createSyncCode());$('sync-dialog-title').textContent='输入已有同步码';$('sync-dialog-help').textContent='把另一台电脑上复制的同步码粘贴到这里。读取后会替换本机的手机名称、配对和分组。';$('sync-code').readOnly=false;$('sync-code').value='';$('sync-error').textContent='';$('sync-submit').hidden=false;$('sync-submit').textContent='读取共享设置';$('sync-dialog-copy').hidden=true;$('sync-dialog').showModal();$('sync-code').focus()}
async function enableSharedSettings(){
 if(syncBusy)return;ensureController();syncBusy=true;syncNotice='正在创建共享设置…';renderSync();const code=createSyncCode();
 try{const result=await putSettings(code,0,prefs.controller);prefs.sync={code,revision:result.revision,updatedAt:result.updatedAt};syncDirty=false;syncNotice='';save();renderSync();$('sync-dialog-title').textContent='同步已启用';$('sync-dialog-help').textContent='在家里、公司或工作室的电脑输入下面这串同步码，即可恢复同一套手机和分组。';$('sync-code').readOnly=true;$('sync-code').value=code;$('sync-error').textContent='请妥善保存；知道同步码的人可以修改这套设备设置。';$('sync-submit').hidden=true;$('sync-dialog-copy').hidden=false;$('sync-dialog').showModal()}
 catch(err){syncNotice=err.message}
 finally{syncBusy=false;renderSync()}
}
async function joinSharedSettings(event){event.preventDefault();if(syncBusy)return;$('sync-error').textContent='';let code;try{code=syncKey($('sync-code').value).clean}catch(err){$('sync-error').textContent=err.message;return}syncBusy=true;$('sync-submit').disabled=true;$('sync-submit').textContent='正在读取…';try{const remote=await fetchSettings(code);if(!confirm('读取成功。继续后，本机的手机名称、配对和分组会替换为共享版本；填写内容和发送记录不受影响。'))return;prefs.sync={code,revision:remote.revision,updatedAt:remote.updatedAt};syncDirty=false;syncConflict=null;applySyncedController(remote.controller);save();$('sync-dialog').close();toast('已连接共享设置，共 '+remote.controller.devices.length+' 台手机') }catch(err){$('sync-error').textContent=err.message}finally{syncBusy=false;$('sync-submit').disabled=false;$('sync-submit').textContent='读取共享设置';renderSync()}}
function saveSelection(){work.selected=[...selected];work.activeGroup=activeGroup;save()}
function scheduleOutbox(){if(viewTimer)return;viewTimer=setTimeout(()=>{viewTimer=null;save();renderOutbox()},60)}
function pairFor(device){return {v:2,host:prefs.controller.host,device:device.id,key:device.key,name:device.name,broker:{urls:[...prefs.controller.transport.urls],username:prefs.controller.transport.username,password:prefs.controller.transport.password}}}
function pairURL(pair){return new URL('./',location.href).href+'#pair='+pairingCode(pair)}
function finished(status){return ['copied','confirmed','deleted'].includes(status)}
function currentPhoneBatch(){return work.inbox.find(b=>b.device===prefs.phone?.device)}
function clearExpired(){const now=Date.now();work.outbox=work.outbox.filter(b=>b.batch.createdAt>now-TTL);work.inbox=work.inbox.filter(b=>b.batch.createdAt>now-TTL);save()}
function alive(link){return !!link&&link.open&&link.authenticated&&Date.now()-(link.lastSeen||0)<35000}
function transport(){return role==='controller'?prefs.controller?.transport:prefs.phone?.broker}
function topicBase(pair=role==='controller'?prefs.controller:prefs.phone){return 'c1clip/v2/'+pair.host}
function phoneInTopic(pair=prefs.phone){return topicBase(pair)+'/device/'+pair.device+'/in'}
function phoneOutTopic(pair=prefs.phone){return topicBase(pair)+'/device/'+pair.device+'/out'}
function controllerInTopic(){return topicBase()+'/device/+/out'}
function deviceInTopic(id){return topicBase()+'/device/'+id+'/in'}
function activeBrokerCount(){return mqttClients.filter(client=>client.c1ready&&client.connected).length}
function shutdown(){
 generation++;mqttReady=false;channels.clear();phoneConn=null;
 const clients=mqttClients;mqttClients=[];for(const client of clients){client.removeAllListeners();try{client.end(true)}catch{}}
}
async function publishEncrypted(topic,secret,payload){
 const clients=mqttClients.filter(client=>client.c1ready&&client.connected);if(!mqttReady||!clients.length)throw new Error('连接服务已断开');
 const packet=await seal(secret,payload),body=JSON.stringify(packet);
 await Promise.any(clients.map(client=>new Promise((resolve,reject)=>client.publish(topic,body,{qos:1,retain:false},err=>err?reject(err):resolve())))).catch(()=>{throw new Error('连接服务已断开')});
}
function decodePacket(payload){return JSON.parse(new TextDecoder().decode(payload))}
function updateNetwork(){
 if(role==='controller'){
  const n=[...channels.values()].filter(alive).length;$('online-count').textContent=n+' / '+(prefs.controller?.devices.length||0)+' 在线';
  if(mqttReady)connection((activeBrokerCount()>1?'双节点在线，':'连接服务正常，')+(n?n+' 部手机在线':'等待手机上线'));
  for(const d of prefs.controller?.devices||[]){const s=$('device-state-'+d.id);if(s){s.textContent=alive(channels.get(d.id))?'在线':'未连接';s.className='device-status'+(alive(channels.get(d.id))?' online':'')}}
 }else if(alive(phoneConn))connection('已连接电脑，可以接收信息');
}
function networkError(err){
 const text=String(err?.message||err||'');
 if(/not authorized|bad user|password|connack/i.test(text))connection('连接服务拒绝登录，请检查用户名和密码。',true);
 else connection('连接服务暂时不可达，正在自动重连免费节点。',true);
}
function bootNetwork(){
 shutdown();const gen=generation;
 if(!window.mqtt||!crypto.subtle){connection('当前浏览器版本过旧，无法使用加密连接服务。',true);return}
 if(role==='phone'&&!prefs.phone){connection('等待配对：请扫描电脑上对应手机的二维码');return}
 if(role==='phone'&&!validPair(prefs.phone)){connection('这部手机使用旧版配对，请回电脑重新扫码。',true);return}
 if(!validTransport(transport())){connection(role==='phone'?'这部手机使用旧版配对，请回电脑重新扫码。':'连接设置不完整，请重新填写。',true);return}
 transport().urls.forEach((_,index)=>startBroker(gen,index));
}
function refreshMqttState(){
 const ready=activeBrokerCount();mqttReady=ready>0;if(!ready){channels.clear();phoneConn=null;updateNetwork()}else if(role==='controller')updateNetwork();
}
function startBroker(gen,index){
 if(gen!==generation)return;const config=transport(),url=config.urls[index];
 connection('正在连接免费节点…');let client;try{client=window.mqtt.connect(url,{clientId:'c1clip_'+token(12),username:config.username||undefined,password:config.password||undefined,protocolVersion:4,clean:true,keepalive:25,connectTimeout:12000,reconnectPeriod:4000,resubscribe:true})}catch(err){networkError(err);return}
 client.c1ready=false;client.c1index=index;mqttClients.push(client);
 client.on('connect',()=>{
  if(gen!==generation||!mqttClients.includes(client))return;const topic=role==='controller'?controllerInTopic():phoneInTopic();
  client.subscribe(topic,{qos:1},err=>{if(gen!==generation||!mqttClients.includes(client))return;if(err){networkError(err);return}client.c1ready=true;refreshMqttState();if(role==='phone')connectPhone();else updateNetwork()});
 });
 client.on('message',(topic,payload)=>{if(gen!==generation||!mqttClients.includes(client))return;(role==='controller'?handleControllerMessage(topic,payload):handlePhoneMessage(topic,payload)).catch(()=>{})});
 client.on('reconnect',()=>{if(gen===generation&&mqttClients.includes(client)){client.c1ready=false;refreshMqttState();if(!mqttReady)connection('连接中断，正在自动重连…',true)}});
 client.on('offline',()=>{if(gen===generation&&mqttClients.includes(client)){client.c1ready=false;refreshMqttState();if(!mqttReady)connection('免费节点暂时不可达，正在自动重连…',true)}});
 client.on('close',()=>{if(gen===generation&&mqttClients.includes(client)){client.c1ready=false;refreshMqttState()}});
 client.on('error',err=>{if(gen===generation&&mqttClients.includes(client)&&!mqttReady)networkError(err)});
}
async function sendToPhone(device,payload){const link=channels.get(device.id);if(!alive(link))throw new Error('手机未连接');await publishEncrypted(deviceInTopic(device.id),device.key,{...payload,session:link.session})}
async function sendToController(payload){const pair=prefs.phone;if(!mqttReady||!phoneConn)return;await publishEncrypted(phoneOutTopic(pair),pair.key,{...payload,session:phoneConn.session})}
async function handleControllerMessage(topic,payload){
 const parts=topic.split('/');if(parts.length!==6||parts[0]!=='c1clip'||parts[1]!=='v2'||parts[2]!==prefs.controller.host||parts[3]!=='device'||parts[5]!=='out')return;
 const d=prefs.controller.devices.find(d=>d.id===parts[4]);if(!d)return;let msg;try{msg=await unseal(d.key,decodePacket(payload))}catch{return}
 if(typeof msg.session!=='string'||msg.session.length<12||msg.session.length>64)return;
 let link=channels.get(d.id);if(!link||link.session!==msg.session){link={device:d.id,session:msg.session,open:true,authenticated:true,lastSeen:Date.now(),flushing:false};channels.set(d.id,link)}else link.lastSeen=Date.now();
 if(msg.t==='hello'||msg.t==='ping'){await sendToPhone(d,{t:msg.t==='hello'?'ready':'pong',name:d.name});updateNetwork();flushDevice(d.id);return}
 if(msg.t==='receipt'){
  const row=work.outbox.find(x=>x.device===d.id&&x.batch.id===msg.id);
  if(!row||!['received','copying','copied','confirmed','error','deleted'].includes(msg.status))return;
  if(msg.status==='received'&&['copying','copied','confirmed','deleted'].includes(row.status))return;
  if(!Number.isInteger(msg.copied)||msg.copied<0||msg.copied>row.batch.items.length)return;
  if(['copied','confirmed'].includes(msg.status)&&msg.copied!==row.batch.items.length)return;
  row.status=msg.status;row.copied=msg.copied;row.error=typeof msg.error==='string'?msg.error.slice(0,180):'';scheduleOutbox();
 }
}
async function handlePhoneMessage(topic,payload){
 const pair=prefs.phone;if(topic!==phoneInTopic(pair)||!phoneConn)return;let msg;try{msg=await unseal(pair.key,decodePacket(payload))}catch{return}
 if(msg.session!==phoneConn.session)return;phoneConn.lastSeen=Date.now();
 if(msg.t==='ready'){
  receiveName(msg.name);phoneConn.authenticated=true;connection('已连接电脑，可以接收信息');
  const current=currentPhoneBatch();if(current)await phoneReceipt(current);
  for(const entry of receipts.values())await sendToController(entry);return;
 }
 if(msg.t==='pong'){phoneConn.authenticated=true;connection('已连接电脑，可以接收信息');return}
 if(!phoneConn.authenticated)return;
 if(msg.t==='name'){receiveName(msg.name);return}
 if(msg.t==='batch'){
  if(!validBatch(msg.batch))return;
  const previous=currentPhoneBatch();if(previous?.batch.id===msg.batch.id){await phoneReceipt(previous);return}
  if(previous&&previous.batch.createdAt>=msg.batch.createdAt)return;
  if(receipts.has(msg.batch.id)){await sendToController(receipts.get(msg.batch.id));return}
  clearExpired();
  if(copying)stopCopy=true;
  const entry={device:pair.device,batch:msg.batch,status:'received',copied:0};work.inbox=replaceDeviceEntries(work.inbox,entry);save();
  renderInbox();await phoneReceipt(entry);toast('收到 '+entry.batch.items.length+' 条独立信息');
 }
}
function connectPhone(){
 if(role!=='phone'||!prefs.phone||!mqttReady)return;if(!phoneConn)phoneConn={session:token(18),open:true,authenticated:false,lastSeen:Date.now()};phoneConn.lastAttempt=Date.now();
 connection('连接服务正常，正在等待电脑回应…');sendToController({t:'hello'}).catch(()=>{});
}
async function phoneReceipt(entry){if(!alive(phoneConn))return;try{await sendToController({t:'receipt',id:entry.batch.id,status:entry.status,copied:entry.copied||0,error:entry.error||''})}catch{}}
async function flushDevice(id){
 const link=channels.get(id);if(!alive(link)||link.flushing)return;
 const pending=work.outbox.filter(e=>e.device===id&&['queued','sent'].includes(e.status)&&Date.now()-(e.lastAttempt||0)>=5000);if(!pending.length)return;
 link.flushing=true;try{for(const row of pending){
  if(Date.now()-(row.lastAttempt||0)<5000)continue;
  row.lastAttempt=Date.now();
  const device=prefs.controller.devices.find(d=>d.id===id);if(!device)continue;
  try{await sendToPhone(device,{t:'batch',batch:row.batch});if(row.status==='queued')row.status='sent'}catch{row.status='queued'}
 }}finally{link.flushing=false;scheduleOutbox()}
}
function renderDevices(){
 const query=$('device-search').value.trim().toLocaleLowerCase(),visible=orderDevices(prefs.controller.devices.filter(d=>d.name.toLocaleLowerCase().includes(query)));
 $('devices').replaceChildren(...visible.map(d=>{
  const card=el('div','device'+(selected.has(d.id)?' selected':'')),top=el('div','device-top'),check=el('input');check.type='checkbox';check.id='select-'+d.id;check.checked=selected.has(d.id);
  const name=el('label','',d.name);name.htmlFor=check.id;top.append(check,name);
  check.addEventListener('change',()=>{check.checked?selected.add(d.id):selected.delete(d.id);activeGroup=null;saveSelection();card.classList.toggle('selected',check.checked);renderGroups();renderDifferent();updateCompose();reconcileAssignments()});
  const bottom=el('div','device-bottom'),status=el('span','device-status','未连接');status.id='device-state-'+d.id;const buttons=el('div','device-buttons'),pair=el('button','small ghost','配对'),manage=el('button','small ghost','管理');pair.onclick=()=>openPair(d);manage.onclick=()=>openDevice(d);manage.setAttribute('aria-label','管理 '+d.name);buttons.append(pair,manage);bottom.append(status,buttons);card.append(top,bottom);return card;
 }));if(!visible.length)$('devices').append(el('p','muted',prefs.controller.devices.length?'没有匹配的手机':'还没有手机，点击“添加手机”开始。'));renderGroups();updateTargets();updateNetwork();if(extractSegments.length)reconcileAssignments();
}
function renderGroups(){
 $('groups').replaceChildren(...prefs.controller.groups.map(g=>{const wrap=el('div','group-chip'),choose=el('button','small',g.name+' · '+g.members.length),edit=el('button','small ghost','管理');choose.setAttribute('aria-pressed',String(activeGroup===g.id));choose.onclick=()=>selectGroup(g.id);edit.setAttribute('aria-label','管理分组 '+g.name);edit.onclick=()=>openGroup(g);wrap.append(choose,edit);return wrap}));
 if(!prefs.controller.groups.length)$('groups').append(el('p','muted','勾选手机后新建分组，下次点组名即可选中整组。'));
 $('selected-count').textContent='已选 '+selected.size+' / '+prefs.controller.devices.length+' 台';
}
function selectGroup(id){activeGroup=id;selected.clear();groupSelection(prefs.controller,id).forEach(id=>selected.add(id));saveSelection();renderDevices();renderDifferent();updateCompose()}
function openDevice(device=null){editingDevice=device?.id||null;$('device-dialog-title').textContent=device?'管理手机':'批量添加手机';$('device-add-fields').hidden=!!device;$('device-edit-fields').hidden=!device;$('device-delete').hidden=!device;$('device-save').textContent=device?'保存名称':'添加手机';$('device-name').value=device?.name||'';$('device-names').value='';$('device-error').textContent='';$('device-dialog').showModal()}
function submitDevice(event){
 event.preventDefault();try{
  if(editingDevice){const d=renameDevice(prefs.controller,editingDevice,$('device-name').value);controllerChanged();if(alive(channels.get(d.id)))sendToPhone(d,{t:'name',name:d.name}).catch(()=>{});toast('名称已更新，原有配对保留')}
  else{const result=addNamedDevices(prefs.controller,$('device-names').value);if(!result.added.length)throw new Error('这些名称已存在，请填写新的手机名称');selected.clear();result.added.forEach(d=>selected.add(d.id));activeGroup=null;saveSelection();controllerChanged();toast('已添加并选中 '+result.added.length+' 台手机'+(result.skipped?'，跳过 '+result.skipped+' 个重复名称':''))}
  $('device-dialog').close();renderDevices();renderDifferent();updateCompose();renderOutbox();
 }catch(err){$('device-error').textContent=err.message}
}
function deleteDevice(){
 const d=prefs.controller.devices.find(d=>d.id===editingDevice);if(!d)return;
 if(!confirm('删除“'+d.name+'”？将断开配对、移出所有分组，并清除电脑上这部手机的发送记录。'))return;
 removeDevice(prefs.controller,d.id);channels.delete(d.id);selected.delete(d.id);delete work.drafts[d.id];work.outbox=work.outbox.filter(x=>x.device!==d.id);saveSelection();controllerChanged();$('device-dialog').close();renderDevices();renderDifferent();updateCompose();renderOutbox();toast('已删除这部手机。以后可重新添加并扫码配对。');
}
function receiveName(name){if(!prefs.phone||typeof name!=='string'||!name.trim()||name.length>60)return;prefs.phone.name=name.trim();$('phone-name').textContent=prefs.phone.name;history.replaceState(null,'',pairURL(prefs.phone));save()}
function openGroup(group=null){editingGroup=group?.id||null;groupMembers=new Set(group?group.members:selected);$('group-name').value=group?.name||'';$('group-dialog-title').textContent=group?'管理分组':'新建分组';$('group-delete').hidden=!group;$('group-error').textContent='';renderGroupMembers();$('group-dialog').showModal()}
function renderGroupMembers(){
 $('group-members').replaceChildren(...prefs.controller.devices.map(d=>{const label=el('label','member-row'),check=el('input');check.type='checkbox';check.checked=groupMembers.has(d.id);check.onchange=()=>check.checked?groupMembers.add(d.id):groupMembers.delete(d.id);label.append(check,el('span','',d.name));return label}));if(!prefs.controller.devices.length)$('group-members').append(el('p','muted','还没有手机，可先保存空分组。'));
}
function submitGroup(event){event.preventDefault();try{const group=saveGroup(prefs.controller,{id:editingGroup,name:$('group-name').value,members:[...groupMembers]});controllerChanged();$('group-dialog').close();if(activeGroup===group.id)selectGroup(group.id);else renderGroups();toast('分组已保存，点击组名即可选中 '+group.members.length+' 台手机')}catch(err){$('group-error').textContent=err.message}}
function deleteGroup(){const group=prefs.controller.groups.find(g=>g.id===editingGroup);if(!group)return;if(!confirm('删除分组“'+group.name+'”？其中的手机和配对会保留。'))return;prefs.controller.groups=prefs.controller.groups.filter(g=>g.id!==group.id);if(activeGroup===group.id)activeGroup=null;saveSelection();controllerChanged();$('group-dialog').close();renderGroups()}
function updateTargets(){const current=$('extract-target').value;$('extract-target').replaceChildren();const shared=el('option','','多机相同内容');shared.value='shared';$('extract-target').append(shared);for(const d of orderDevices(prefs.controller.devices)){const option=el('option','',d.name+' · 单独内容');option.value=d.id;$('extract-target').append(option)}$('extract-target').value=current==='shared'||prefs.controller.devices.some(d=>d.id===current)?current:'shared'}
function runExtraction(){clearTimeout(extractTimer);try{extractSegments=extractIdentitySegments($('extract-source').value);extractRows=extractSegments.flatMap(segment=>segment.people.map(row=>Object.assign(row,{segmentId:segment.id,segmentOrder:segment.order})));renderExtraction();rebuildAssignments()}catch(err){$('extract-summary').textContent=err.message}}
function usableSegments(){return extractSegments.filter(segment=>{const rows=segment.people.filter(row=>row.selected);return rows.length&&rows.every(row=>String(row.name).trim()&&idShape(row.id))})}
function segmentTitle(segment){const names=segment.people.filter(row=>row.selected&&row.name.trim()).map(row=>row.name.trim());return '第 '+segment.order+' 段 · '+segment.label+(names.length?' · '+names.join('、'):'')}
function extractionSummary(){const n=extractRows.filter(r=>r.selected).length,missing=extractRows.filter(r=>!r.name.trim()).length,usable=usableSegments().length;$('extract-summary').textContent=extractRows.length?'识别 '+extractSegments.length+' 段、'+extractRows.length+' 人；'+usable+' 段可分配，选中 '+n+' 人'+(missing?'；'+missing+' 项姓名待补填':''):'没有识别到身份证信息。请让每个组合之间空一行。';$('extract-apply').disabled=!n}
function renderSegmentOverview(){$('segment-overview').replaceChildren(...extractSegments.map(segment=>{const chip=el('div','segment-chip'),count=segment.people.filter(row=>row.selected).length;chip.append(el('strong','',segmentTitle(segment)),el('span','',count+' / '+segment.people.length+' 人已选'));return chip}))}
function reconcileAssignments(){
 if(!extractSegments.length)return;const existing=new Map(assignmentRows.map(row=>[row.deviceId,row.segmentId])),valid=new Set(usableSegments().map(segment=>segment.id));
 assignmentRows=orderDevices(prefs.controller.devices.filter(device=>selected.has(device.id))).map(device=>({deviceId:device.id,segmentId:valid.has(existing.get(device.id))?existing.get(device.id):''}));renderAssignmentList();
}
function renderAssignmentList(){
 const segments=usableSegments(),valid=new Set(segments.map(segment=>segment.id)),source=$('assignment-source'),currentSource=source.value;source.replaceChildren(...segments.map(segment=>{const option=el('option','',segmentTitle(segment));option.value=segment.id;return option}));source.value=valid.has(currentSource)?currentSource:segments[0]?.id||'';
 $('assignment-count').textContent=assignmentRows.length+' 台';$('assignment-list').replaceChildren(...assignmentRows.map(row=>{const device=prefs.controller.devices.find(item=>item.id===row.deviceId),wrap=el('div','assignment-row'),name=el('strong','',device?.name||'已删除手机'),picker=el('select');const none=el('option','','不发送');none.value='';picker.append(none,...segments.map(segment=>{const option=el('option','',segmentTitle(segment));option.value=segment.id;return option}));picker.value=valid.has(row.segmentId)?row.segmentId:'';row.segmentId=picker.value;picker.setAttribute('aria-label',(device?.name||'手机')+'选择人员资料');picker.onchange=()=>{row.segmentId=picker.value;renderAssignmentSummary()};wrap.append(name,picker);return wrap}));renderAssignmentSummary();
}
function renderAssignmentSummary(){
 const assigned=assignmentRows.filter(row=>row.segmentId).length,used=new Set(assignmentRows.map(row=>row.segmentId).filter(Boolean)),available=usableSegments().length,unassigned=Math.max(0,available-used.size),blank=assignmentRows.length-assigned;
 $('assignment-summary').textContent=assignmentRows.length?'已为 '+assigned+' / '+assignmentRows.length+' 台选择资料'+(blank?'；'+blank+' 台保持不发送':'')+(unassigned?'；还有 '+unassigned+' 段未使用':'')+'。可在下方逐台修改。':'请先在左侧选择要使用的手机。';$('assignment-apply').disabled=!assigned;
}
function rebuildAssignments(){
 const segments=usableSegments(),devices=prefs.controller.devices.filter(device=>selected.has(device.id)),mode=$('assignment-mode').value;$('assignment-same-wrap').hidden=mode!=='same';assignmentRows=assignSegments(devices,segments.map(segment=>segment.id),{mode,sameId:$('assignment-source').value});renderAssignmentList();
}
function refreshExtraction(){extractionSummary();renderSegmentOverview();reconcileAssignments()}
function renderExtraction(){
 $('extract-results').hidden=!extractRows.length;$('extract-rows').replaceChildren(...extractRows.map((r,i)=>{
  const row=el('tr'),selectCell=el('td'),check=el('input');check.type='checkbox';check.checked=r.selected;check.setAttribute('aria-label','选择第 '+(i+1)+' 人');check.onchange=()=>{r.selected=check.checked;refreshExtraction()};selectCell.append(check);
  const nameCell=el('td'),name=el('input');name.value=r.name;name.maxLength=60;name.placeholder='请补填姓名';name.setAttribute('aria-label','第 '+(i+1)+' 人姓名');name.oninput=()=>{r.name=name.value;refreshExtraction()};nameCell.append(name);
  const idCell=el('td'),id=el('input');id.value=r.id;id.maxLength=18;id.spellcheck=false;id.autocomplete='off';id.setAttribute('aria-label','第 '+(i+1)+' 人身份证');id.oninput=()=>{r.id=id.value;refreshExtraction()};idCell.append(id);
  const reason=el('td','extract-reason'),label=el('div','','第 '+r.segmentOrder+' 段 · '+r.reason),details=el('details'),summary=el('summary','','查看原文片段');details.append(summary,el('p','',r.source));reason.append(label,details);row.append(selectCell,nameCell,idCell,reason);return row;
 }));extractionSummary();renderSegmentOverview();
}
function applyExtraction(){
 try{const text=peopleToText(extractRows);if(text.length>17000)throw new Error('结果过长，请少选一些人员分次填入');const target=$('extract-target').value;
  if(target==='shared'){work.shared=text;work.compose='same';$('shared-text').value=text}
  else{if(!prefs.controller.devices.some(d=>d.id===target))throw new Error('目标手机已删除，请重新选择');work.drafts[target]=text;work.compose='different';if(!selected.has(target)){selected.add(target);activeGroup=null;saveSelection()}renderDevices();renderDifferent()}
  save();updateCompose();feedback('已填入 '+extractRows.filter(r=>r.selected).length+' 人的姓名与身份证，分别作为独立条目。请检查接收手机后点击发送。');$('send').scrollIntoView({behavior:'smooth',block:'center'});
 }catch(err){$('extract-summary').textContent=err.message}
}
function applyAssignments(){
 try{const byId=new Map(usableSegments().map(segment=>[segment.id,segment])),assigned=assignmentRows.filter(row=>row.segmentId&&byId.has(row.segmentId));if(!assigned.length)throw new Error('请先给至少一部手机选择人员资料');for(const row of assignmentRows){const segment=byId.get(row.segmentId);work.drafts[row.deviceId]=segment?peopleToText(segment.people):''}work.compose='different';save();renderDifferent();updateCompose();feedback('已为 '+assigned.length+' 部手机填入不同发送框；未分配的手机不会发送。请核对后点击发送。');$('send').scrollIntoView({behavior:'smooth',block:'center'})}catch(err){$('assignment-summary').textContent=err.message}
}
function renderDifferent(){
 $('different-compose').replaceChildren(...orderDevices(prefs.controller.devices.filter(d=>selected.has(d.id))).map(d=>{const wrap=el('div'),label=el('label','',d.name+' · 每行一条（留空不发送）'),input=el('textarea');input.id='draft-'+d.id;input.rows=4;input.maxLength=17000;input.placeholder='填写只发给这部手机的内容；留空则跳过';input.value=work.drafts[d.id]||'';input.spellcheck=false;label.htmlFor=input.id;input.oninput=()=>{work.drafts[d.id]=input.value;save();updateCompose()};wrap.append(label,input);return wrap}));
}
function updateCompose(){
 const same=work.compose!=='different';$('same-mode').setAttribute('aria-pressed',String(same));$('different-mode').setAttribute('aria-pressed',String(!same));$('same-compose').hidden=!same;$('different-compose').hidden=same;
 const count=String(work.shared||'').split(/\r?\n/).filter(x=>x.trim()).length;
 const eligible=same?(hasSendableText(work.shared)?selected.size:0):prefs.controller.devices.filter(d=>selected.has(d.id)&&hasSendableText(work.drafts[d.id])).length;
 $('item-count').textContent=same?count+' 条':eligible+' 部有内容';
 $('preview').replaceChildren();if(same&&count){for(let i=0;i<Math.min(count,20);i++)$('preview').append(el('span','','第 '+(i+1)+' 条'))}
 $('send').textContent='发送给 '+eligible+' 部手机';$('send').disabled=!eligible||sending;
 $('selected-count').textContent='已选 '+selected.size+' / '+(prefs.controller?.devices.length||0)+' 台';
}
const statusText={queued:'等待连接 · 保存在当前电脑标签页',sent:'已发出 · 等待手机回执',received:'手机已接收 · 等待点击复制',copying:'手机正在逐条复制',copied:'逐条复制完成 · 历史待手机确认',confirmed:'手机已确认全部进入历史',error:'需要处理',deleted:'手机已清空当前内容'};
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
 clearExpired();const prepared=[],targets=prefs.controller.devices.filter(x=>selected.has(x.id));let skipped=0;
 try{for(const d of targets){const source=work.compose==='different'?work.drafts[d.id]||'':work.shared||'';if(!hasSendableText(source)){skipped++;continue}let items;try{items=parseLines(source,240)}catch(err){throw new Error(d.name+'：'+err.message)}const previous=work.outbox.find(row=>row.device===d.id),createdAt=Math.max(Date.now(),(previous?.batch.createdAt||0)+1);prepared.push({device:d.id,batch:{id:token(18),createdAt,items},status:'queued',copied:0})}}
 catch(err){feedback(err.message,true);return}
 if(!prepared.length){feedback('所选手机的发送内容均为空，本次没有发送。',true);return}
 work.outbox=replaceDeviceEntries(work.outbox,prepared);save();renderOutbox();feedback('已向 '+prepared.length+' 部手机发送最新内容'+(skipped?'；跳过 '+skipped+' 部空白手机。':'。')+' 新内容会覆盖旧内容。');
 sending=true;updateCompose();try{for(const device of new Set(prepared.map(x=>x.device)))await flushDevice(device)}finally{sending=false;updateCompose()}
}
function openPair(device){
 $('pair-title').textContent=device.name+' · 配对';$('pair-link').value=pairURL(pairFor(device));$('pair-status').textContent=alive(channels.get(device.id))?'这部手机已在线。':'请保持电脑页面打开。';
 const qr=new LocalQRCode(-1,1);qr.addData($('pair-link').value);qr.make();const n=qr.getModuleCount(),cell=6,margin=4,canvas=$('qr');canvas.width=canvas.height=(n+margin*2)*cell;const ctx=canvas.getContext('2d');ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.fillStyle='#102441';for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(qr.isDark(y,x))ctx.fillRect((x+margin)*cell,(y+margin)*cell,cell,cell);
 $('pair-dialog').showModal();
}
function renderInbox(){
 if(copying)return;
 const entry=currentPhoneBatch();$('copy-settings').hidden=!entry;const items=$('inbox-items');items.replaceChildren();
 $('batch-list').replaceChildren();$('inbox-count').textContent=entry?entry.batch.items.length+' 条 · '+new Date(entry.batch.createdAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'}):'等待接收';
 if(!entry){items.className='empty';items.textContent='等待电脑发送内容…';return}
 items.className='';entry.batch.items.forEach((text,i)=>{const item=el('div','clip-item'),body=el('div','clip-text',text),state=el('span','clip-state',i<entry.copied?'已复制':'');state.id='clip-state-'+i;item.append(el('span','clip-number',String(i+1).padStart(2,'0')),body,state);items.append(item)});
 $('copy-all').textContent='一键逐条复制 '+entry.batch.items.length+' 条';$('copy-all').disabled=false;$('copy-progress').max=entry.batch.items.length;$('copy-progress').value=entry.copied||0;$('copy-status').textContent=entry.error|| (entry.status==='confirmed'?'你已确认：当前内容已全部进入输入法历史。':entry.status==='copied'?'已完成逐条复制，请到输入法检查历史。':'内容已收到，点击下方按钮开始逐条复制。');
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
 const stillCurrent=currentPhoneBatch()===entry;save();renderInbox();if(stillCurrent)phoneReceipt(entry);
 if(stillCurrent&&result.error)$('copy-status').textContent='完成 '+result.copied+'/'+result.total+' 条。'+result.error;else if(!stillCurrent)toast('已切换到电脑刚发来的最新信息');
}
function enterPhone(pair){
 if(copying){toast('请先停止当前复制');return}
 if(prefs.phone?.device!==pair.device||prefs.phone?.host!==pair.host||JSON.stringify(prefs.phone?.broker)!==JSON.stringify(pair.broker)){work.inbox=[];receipts.clear()}
 prefs.phone=pair;prefs.role='phone';save();location.hash='pair='+pairingCode(pair);setRole('phone');
}
function setRole(next){
 if(copying)return;role=next;prefs.role=next;save();
 $('controller').hidden=next!=='controller';$('phone').hidden=next!=='phone';$('mode-controller').setAttribute('aria-pressed',String(next==='controller'));$('mode-phone').setAttribute('aria-pressed',String(next==='phone'));
 document.querySelector('.mode-switch').hidden=next==='phone'&&!!prefs.phone;$('network-settings').hidden=next!=='controller';
 if(next==='controller'){ensureController();$('shared-text').value=work.shared||'';renderDevices();renderDifferent();updateCompose();renderOutbox();renderSync()}
 else{$('phone-setup').hidden=!!prefs.phone;$('phone-workspace').hidden=!prefs.phone;if(prefs.phone){$('phone-name').textContent=prefs.phone.name;renderInbox()}}
 bootNetwork();
}
async function simpleCopy(text){if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(text);return}throw new Error('请长按链接文本手动复制')}
function normalizeBrokerUrl(value){
 const text=String(value||'').trim();if(!text)throw new Error('请填写主 WSS 地址');let url;try{url=new URL(text)}catch{throw new Error('WSS 地址格式不正确')}
 if(url.protocol!=='wss:'||!url.hostname||url.username||url.password)throw new Error('连接地址必须以 wss:// 开头');if(url.pathname==='/')url.pathname='/mqtt';url.hash='';url.search='';return url.href.replace(/\/$/,'')
}
function openNetworkSettings(){
 ensureController();const config=prefs.controller.transport;$('broker-url').value=config.urls[0]||'';$('broker-backup-url').value=config.urls[1]||'';$('broker-user').value=config.username||'';$('broker-password').value=config.password||'';$('broker-show-password').checked=false;$('broker-password').type='password';$('network-error').textContent='';$('network-dialog').showModal();
}
function saveNetworkSettings(event){
 event.preventDefault();$('network-error').textContent='';try{
  const urls=[normalizeBrokerUrl($('broker-url').value)];if($('broker-backup-url').value.trim())urls.push(normalizeBrokerUrl($('broker-backup-url').value));
  const next={urls:[...new Set(urls)],username:$('broker-user').value.trim(),password:$('broker-password').value};if(!validTransport(next))throw new Error('连接服务设置不正确');
  const changed=JSON.stringify(next)!==JSON.stringify(prefs.controller.transport);prefs.controller.transport=next;if(changed){channels.clear();controllerChanged()}else save();$('network-dialog').close();bootNetwork();toast(changed?'连接设置已保存，请让手机重新扫码配对':'正在重新连接');
 }catch(err){$('network-error').textContent=err.message}
}
function restorePublicNetwork(){
 const config=publicTransport();$('broker-url').value=config.urls[0];$('broker-backup-url').value=config.urls[1];$('broker-user').value='';$('broker-password').value='';$('network-error').textContent='已恢复免费公共节点，点击“保存并连接”生效。';
}
$('mode-controller').onclick=()=>setRole('controller');$('mode-phone').onclick=()=>setRole('phone');$('reconnect').onclick=()=>{if(copying){toast('复制结束后再重连');return}bootNetwork()};
$('network-settings').onclick=openNetworkSettings;$('network-form').onsubmit=saveNetworkSettings;$('network-public').onclick=restorePublicNetwork;$('broker-show-password').onchange=e=>$('broker-password').type=e.target.checked?'text':'password';
$('select-all').onclick=()=>{prefs.controller.devices.forEach(d=>selected.add(d.id));activeGroup=null;saveSelection();renderDevices();renderDifferent();updateCompose()};
$('select-none').onclick=()=>{selected.clear();activeGroup=null;saveSelection();renderDevices();renderDifferent();updateCompose()};
$('device-search').oninput=renderDevices;$('add-device').onclick=()=>openDevice();$('device-form').onsubmit=submitDevice;$('device-delete').onclick=deleteDevice;
$('new-group').onclick=()=>openGroup();$('group-form').onsubmit=submitGroup;$('group-delete').onclick=deleteGroup;
$('sync-enable').onclick=enableSharedSettings;$('sync-join').onclick=openSyncJoin;$('sync-form').onsubmit=joinSharedSettings;
$('sync-copy-code').onclick=()=>simpleCopy(prefs.sync?.code||'').then(()=>toast('同步码已复制')).catch(err=>toast(err.message));$('sync-dialog-copy').onclick=()=>simpleCopy($('sync-code').value||prefs.sync?.code||'').then(()=>toast('同步码已复制')).catch(err=>$('sync-error').textContent=err.message);
$('sync-refresh').onclick=()=>{syncDirty?pushSharedSettings():pullSharedSettings()};
$('sync-disable').onclick=()=>{if(!confirm('停止这台电脑的自动同步？云端设置和其他电脑不会删除，以后仍可用同步码重新连接。'))return;delete prefs.sync;syncDirty=false;syncConflict=null;syncNotice='';save();renderSync();toast('这台电脑已停止同步')};
$('sync-use-cloud').onclick=()=>pullSharedSettings({force:true});$('sync-use-local').onclick=()=>pushSharedSettings(syncConflict?.revision||prefs.sync?.revision||0);
$('group-all').onclick=()=>{groupMembers=new Set(prefs.controller.devices.map(d=>d.id));renderGroupMembers()};$('group-none').onclick=()=>{groupMembers.clear();renderGroupMembers()};
document.querySelectorAll('[data-close]').forEach(button=>button.onclick=()=>$(button.dataset.close).close());
$('extract-source').oninput=()=>{clearTimeout(extractTimer);extractTimer=setTimeout(runExtraction,400)};$('extract-run').onclick=runExtraction;
$('extract-clear').onclick=()=>{clearTimeout(extractTimer);$('extract-source').value='';extractRows=[];extractSegments=[];assignmentRows=[];$('extract-results').hidden=true;$('extract-rows').replaceChildren();$('segment-overview').replaceChildren();$('assignment-list').replaceChildren();$('extract-summary').textContent='每段资料之间空一行，系统会识别单人、双人或多人组合。'};
$('extract-all').onclick=()=>{extractRows.forEach(r=>r.selected=!!r.name.trim()&&idShape(r.id));renderExtraction();reconcileAssignments()};$('extract-none').onclick=()=>{extractRows.forEach(r=>r.selected=false);renderExtraction();reconcileAssignments()};$('extract-apply').onclick=applyExtraction;
$('assignment-mode').onchange=()=>{ $('assignment-same-wrap').hidden=$('assignment-mode').value!=='same';rebuildAssignments()};$('assignment-source').onchange=()=>{if($('assignment-mode').value==='same')rebuildAssignments()};$('assignment-build').onclick=rebuildAssignments;$('assignment-apply').onclick=applyAssignments;
$('shared-text').oninput=e=>{work.shared=e.target.value;save();updateCompose()};
$('same-mode').onclick=()=>{work.compose='same';save();updateCompose()};$('different-mode').onclick=()=>{work.compose='different';save();renderDifferent();updateCompose()};
$('send').onclick=sendSelected;
$('clear-history').onclick=()=>{work.outbox=work.outbox.filter(r=>!finished(r.status));save();renderOutbox()};
$('close-pair').onclick=()=>$('pair-dialog').close();$('copy-link').onclick=()=>simpleCopy($('pair-link').value).then(()=>$('pair-status').textContent='连接链接已复制，请在对应手机打开。').catch(err=>$('pair-status').textContent=err.message);
$('join').onclick=()=>{try{enterPhone(readPair($('pair-input').value))}catch(err){toast(err.message)}};
$('change-pair').onclick=()=>{if(copying)return;if(!confirm('更换配对后，将清除本页已收到的内容。继续吗？'))return;shutdown();delete prefs.phone;work.inbox=[];receipts.clear();save();history.replaceState(null,'',location.pathname+'#phone');setRole('phone')};
$('copy-all').addEventListener('pointerdown',e=>{if(document.activeElement===$('keyboard-input'))e.preventDefault()});$('copy-all').onclick=startCopy;
$('stop-copy').onclick=()=>{stopCopy=true;$('stop-copy').disabled=true;$('copy-status').textContent='正在停止…'};
$('confirm-history').onclick=()=>{const entry=currentPhoneBatch();if(!entry||copying||entry.copied!==entry.batch.items.length)return;entry.status='confirmed';save();renderInbox();phoneReceipt(entry)};
$('clear-batch').onclick=()=>{const entry=currentPhoneBatch();if(!entry||copying)return;const receipt={t:'receipt',id:entry.batch.id,status:'deleted',copied:entry.copied||0};receipts.set(entry.batch.id,receipt);if(receipts.size>100)receipts.delete(receipts.keys().next().value);if(alive(phoneConn))sendToController(receipt).catch(()=>{});work.inbox=work.inbox.filter(e=>e!==entry);save();renderInbox()};
for(const id of ['interval','copy-method']){$(id).value=prefs[id]||$(id).value;$(id).onchange=()=>{prefs[id]=$(id).value;save()}}
document.addEventListener('visibilitychange',()=>{if(document.visibilityState!=='visible'){if(copying)stopCopy=true}else{if(!mqttReady)bootNetwork();else if(role==='phone'&&!alive(phoneConn))connectPhone();if(role==='controller'&&prefs.sync?.code&&!syncDirty)pullSharedSettings();updateNetwork()}});
window.addEventListener('blur',()=>{if(copying)stopCopy=true});
window.addEventListener('beforeunload',event=>{if(copying||(role==='controller'&&work.outbox.some(e=>!finished(e.status)))){event.preventDefault();event.returnValue=''}});
window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();installPrompt=event});
$('install').onclick=async()=>{if(installPrompt){await installPrompt.prompt();installPrompt=null}else{toast(/MicroMessenger/i.test(navigator.userAgent)?'请将配对链接在手机浏览器打开，再从浏览器菜单添加到桌面。':'在浏览器菜单中选择“添加到主屏幕”或“安装应用”。')}};
window.addEventListener('online',()=>{if(!copying)bootNetwork()});
setInterval(()=>{
 if(document.visibilityState!=='visible')return;
 if(role==='controller'){
  for(const [id,link] of channels){if(Date.now()-link.lastSeen>35000){channels.delete(id);continue}flushDevice(id)}updateNetwork();
 }else{
  if(alive(phoneConn))sendToController({t:'ping'}).catch(()=>{});
  else if(mqttReady&&(!phoneConn||Date.now()-(phoneConn.lastAttempt||0)>12000))connectPhone()
 }
},6000);
setInterval(()=>{if(!copying){clearExpired();if(role==='controller')renderOutbox();else renderInbox()}},60000);
setInterval(()=>{if(document.visibilityState==='visible'&&role==='controller'&&prefs.sync?.code&&!syncBusy&&!syncDirty)pullSharedSettings()},20000);
if('serviceWorker'in navigator)navigator.serviceWorker.register('./sw.js',{scope:'./'}).catch(()=>{});
async function initialize(){try{
 if(location.hash.startsWith('#pair=')){const pair=readPair(location.hash);if(prefs.phone?.device!==pair.device||prefs.phone?.host!==pair.host||prefs.phone?.key!==pair.key||JSON.stringify(prefs.phone?.broker)!==JSON.stringify(pair.broker))work.inbox=[];prefs.phone=pair;prefs.role='phone';save()}
 role=location.hash==='#phone'||prefs.role==='phone'?'phone':'controller';
 if(role==='controller'){ensureController();if(prefs.sync?.code){syncNotice='正在读取共享设置…';renderSync();try{const remote=await fetchSettings(prefs.sync.code);if(remote.revision>(prefs.sync.revision||0)){prefs.controller=remote.controller;migrateController(prefs.controller);reconcileController()}prefs.sync.revision=remote.revision;prefs.sync.updatedAt=remote.updatedAt;syncNotice='';save()}catch(err){if(missingSharedSettings(err)&&(prefs.sync.revision||0)>0){try{await seedSharedSettings()}catch(seedError){syncNotice=seedError.message}}else syncNotice=err.message}}}
 setRole(role);renderSync();
}catch(err){connection('无法读取连接信息：'+err.message,true);$('controller').hidden=true;$('phone').hidden=false;$('phone-setup').hidden=false}}
initialize();
