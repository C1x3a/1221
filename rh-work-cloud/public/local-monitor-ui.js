(()=>{
  const q=(u)=>{
    if(u && Number.isFinite(Number(u.remainingPercent))) return `剩余 ${Number(u.remainingPercent)}%`;
    if(u && Number.isFinite(Number(u.usedPercent))) return `已用 ${Number(u.usedPercent)}%`;
    return '未读取';
  };
  const badge=(ok,yes='在线',no='离线')=>`<span class="pill" style="${ok?'background:#effbf5;color:#187d55':'background:#fff0f0;color:#ad3e3e'}">${ok?yes:no}</span>`;
  const age=(v)=>{if(!v)return '—';const s=Math.max(0,Math.floor((Date.now()-new Date(v).getTime())/1000));return s<60?`${s} 秒前`:s<3600?`${Math.floor(s/60)} 分钟前`:`${Math.floor(s/3600)} 小时前`;};
  const localOnline=()=>Boolean(state?.localAgentOnline || state?.runtime?.localAgent?.online);
  const rt=()=>state?.runtime?.localAgent||{};
  const canControl=()=>localOnline() && rt().chatgptLoggedIn;

  render=function(){
    const s=state.settings||{}, r=state.runtime||{}, u=state.usage||{}, a=rt();
    const online=localOnline(), logged=Boolean(a.chatgptLoggedIn), ready=online&&logged;
    document.querySelector('#app').innerHTML=`<main class="shell">
      <header class="top"><div class="brand"><div class="logo">RH</div><div><h1>RH Work 远程监控中心</h1><p>公司电脑真实 ChatGPT · 手机网页远程监控与控制</p></div></div><div class="clock" id="clock"></div></header>
      <div id="notice"></div>
      <section class="status">
        <article class="metric"><span>公司电脑</span><b>${online?'在线':'离线'}</b><small>${a.hostname?esc(a.hostname):'等待监控端连接'}</small></article>
        <article class="metric"><span>ChatGPT</span><b>${logged?'已登录':'未就绪'}</b><small>${a.browserReady?esc(a.browser||'浏览器已连接'):'等待真实浏览器'}</small></article>
        <article class="metric"><span>5 小时限额</span><b>${esc(q(u.fiveHour))}</b><small>${u.fiveHour?.resetAt?'重置：'+esc(cnTime(u.fiveHour.resetAt)):'未读取重置时间'}</small></article>
        <article class="metric"><span>每周限额</span><b>${esc(q(u.weekly))}</b><small>${u.weekly?.resetAt?'重置：'+esc(cnTime(u.weekly.resetAt)):'未读取重置时间'}</small></article>
      </section>
      <div class="columns"><div class="stack">
        <section class="card"><div class="title"><div><h2>公司电脑监控端</h2><p>网页一直保留。公司电脑只负责读取真实 ChatGPT 和执行发送，手机通过这个网页查看和控制。</p></div><button class="btn outline" onclick="localRefresh()">刷新状态</button></div>
          <div class="switch"><div><b>监控端连接</b><span>最后心跳：${esc(age(a.lastSeenAt))}</span></div>${badge(online)}</div>
          <div class="switch"><div><b>真实 ChatGPT 登录</b><span>${a.chatgptLoggedIn?'公司电脑的真实登录会话可用':'明天在公司电脑监控浏览器中登录一次 ChatGPT'}</span></div>${badge(logged,'已登录','未登录')}</div>
          <div class="switch"><div><b>浏览器控制</b><span>${a.browserReady?`已连接 ${esc(a.browser||'Edge/Chrome')}`:'等待本地浏览器'}</span></div>${badge(Boolean(a.browserReady),'就绪','未就绪')}</div>
          ${a.lastError?`<div class="notice error" style="margin-top:12px">本地端：${esc(a.lastError)}</div>`:''}
          <div class="hint" style="margin-top:12px">设备版本：${esc(a.version||'—')}　设备 ID：${esc(a.deviceId||'未配对')}</div>
        </section>

        <section class="card"><div class="title"><div><h2>真实额度</h2><p>读取来源应显示 office-pc-real-browser，数值直接来自公司电脑中的“设置 → 使用情况”。</p></div><button class="btn primary" onclick="localCheckUsage()" ${ready?'':'disabled'}>立即读取</button></div>
          <div class="grid2"><div class="field"><label>5 小时限额</label><input disabled value="${esc(q(u.fiveHour))} · ${u.fiveHour?.resetAt?'重置 '+esc(cnTime(u.fiveHour.resetAt)):'重置时间未读取'}"></div><div class="field"><label>每周限额</label><input disabled value="${esc(q(u.weekly))} · ${u.weekly?.resetAt?'重置 '+esc(cnTime(u.weekly.resetAt)):'重置时间未读取'}"></div></div>
          <div class="hint">最近读取：${esc(cnTime(u.checkedAt))}　来源：${esc(u.source||'—')}</div>
          ${(u.fiveHour?.rawCardText||u.weekly?.rawCardText)?`<div class="hint" style="margin-top:10px;padding:12px;border:1px solid #e5e7eb;border-radius:10px"><b>公司电脑读取原文</b><br>5 小时：${esc(u.fiveHour?.rawCardText||'未识别')}<br><br>每周：${esc(u.weekly?.rawCardText||'未识别')}</div>`:''}
        </section>

        <section class="card"><div class="title"><div><h2>Work 远程控制</h2><p>手机点击后，命令由 Railway 下发给公司电脑；公司电脑使用真实登录会话执行。</p></div></div>
          <div class="grid2"><div class="field"><label>Work 名称</label><input id="workName" value="${esc(s.workName||'')}"></div><div class="field"><label>Work URL</label><input id="workUrl" value="${esc(s.workUrl||'')}" placeholder="https://chatgpt.com/c/..."></div></div>
          <div class="field"><label>续跑文案</label><textarea id="prompt">${esc(s.continuePrompt||'')}</textarea></div>
          <div class="row"><button class="btn primary" onclick="localSave()">保存</button><button class="btn outline" onclick="localTestWork()" ${ready?'':'disabled'}>测试 Work</button><button class="btn" onclick="localRunWork()" ${ready?'':'disabled'}>立即继续</button></div>
          ${!ready?`<div class="hint" style="margin-top:10px;color:#a96800">公司电脑监控端未在线并登录 ChatGPT，因此远程发送按钮暂时禁用，避免误走云端浏览器。</div>`:''}
        </section>
      </div><div class="stack">
        <section class="card"><div class="title"><div><h2>自动续跑</h2><p>公司电脑在线时持续读取额度；5 小时额度恢复后可自动向绑定 Work 发送续跑文案。</p></div></div>
          ${toggle('autoContinue','恢复后自动继续','额度恢复且公司电脑在线时自动执行',s.autoContinue)}
          ${toggle('autoRetry','失败后继续监控','发送失败不会丢失任务配置',s.autoRetry)}
          <button class="btn primary" style="margin-top:12px" onclick="localSave()">保存自动设置</button>
        </section>
        <section class="card"><div class="title"><div><h2>定时继续</h2><p>北京时间。网页不用保持打开，公司电脑监控端保持运行即可。</p></div></div>
          ${toggle('scheduleEnabled','启用定时任务','一次 / 每天 / 工作日',s.schedule?.enabled)}
          <div class="field"><label>类型</label><select id="schedKind"><option value="once" ${s.schedule?.kind==='once'?'selected':''}>一次</option><option value="daily" ${s.schedule?.kind==='daily'?'selected':''}>每天</option><option value="weekdays" ${s.schedule?.kind==='weekdays'?'selected':''}>工作日</option></select></div>
          <div class="grid2"><div class="field"><label>日期</label><input id="schedDate" type="date" value="${esc(s.schedule?.date||'')}"></div><div class="field"><label>北京时间</label><input id="schedTime" type="time" value="${esc(s.schedule?.time||'09:00')}"></div></div>
          <button class="btn primary" onclick="localSave()">保存定时设置</button>
        </section>
        <section class="card"><div class="title"><div><h2>运行日志</h2><p>手机端自动刷新</p></div><button class="btn outline" onclick="localRefresh(true)">立即刷新</button></div><div class="log">${(state.logs||[]).length?(state.logs||[]).map(l=>`<div class="logItem"><time>${esc(cnTime(l.at))}</time><b>${esc(l.event)}</b><div class="detail">${esc(l.detail)}</div></div>`).join(''):'<div class="hint" style="padding:18px">暂无日志</div>'}</div></section>
        <section class="card"><button class="btn outline" onclick="logout()">退出控制中心</button></section>
      </div></div>
    </main>`;
    wireToggles();tickClock();
  };

  window.localSave=async()=>{try{
    const body={workName:$('#workName')?.value?.trim()||'',workUrl:$('#workUrl')?.value?.trim()||'',continuePrompt:$('#prompt')?.value||'',executionMode:'local-only',autoContinue:document.querySelector('[data-toggle="autoContinue"]')?.classList.contains('on')??true,autoRetry:document.querySelector('[data-toggle="autoRetry"]')?.classList.contains('on')??true,schedule:{enabled:document.querySelector('[data-toggle="scheduleEnabled"]')?.classList.contains('on')??false,kind:$('#schedKind')?.value||'once',date:$('#schedDate')?.value||'',time:$('#schedTime')?.value||'09:00'}};
    state=await api('/api/settings',{method:'POST',body:JSON.stringify(body)});render();notice('配置已保存，公司电脑在线后由真实浏览器执行');
  }catch(e){notice(e.message,'error')}};

  async function waitResult(msg){notice(msg);await new Promise(r=>setTimeout(r,1800));await localRefresh(true);}
  window.localCheckUsage=async()=>{try{const r=await api('/api/usage/check',{method:'POST',body:'{}'});await waitResult(r.queued?'已向公司电脑下发额度读取命令…':'额度已读取');}catch(e){notice(e.message,'error')}};
  window.localTestWork=async()=>{try{await localSave();const r=await api('/api/work/test',{method:'POST',body:'{}'});await waitResult(r.queued?'已向公司电脑下发 Work 测试命令…':'Work 测试完成');}catch(e){notice(e.message,'error')}};
  window.localRunWork=async()=>{try{await localSave();const r=await api('/api/work/run',{method:'POST',body:JSON.stringify({reason:'mobile-dashboard'})});await waitResult(r.queued?'已向公司电脑下发续跑命令…':'续跑已执行');}catch(e){notice(e.message,'error')}};
  window.localRefresh=async(force=false)=>{try{const editing=['INPUT','TEXTAREA','SELECT'].includes(document.activeElement?.tagName);state=await api('/api/state');if(force||!editing)render();}catch(e){if(e.code===401||e.message==='UNAUTHORIZED')loginView();}};

  clearInterval(window.__rhLocalRefresh);
  window.__rhLocalRefresh=setInterval(()=>localRefresh(false),5000);
  if(typeof state!=='undefined'&&state)render();
})();
