(()=>{
  function quotaText(u){
    if(u && Number.isFinite(Number(u.usedPercent))) return `已用 ${Number(u.usedPercent)}%`;
    if(u && Number.isFinite(Number(u.remainingPercent))) return `剩余 ${Number(u.remainingPercent)}%`;
    if(u && Number.isFinite(Number(u.displayPercent))) return `${Number(u.displayPercent)}%（页面原值）`;
    return '未读取';
  }

  try{ remain=quotaText; }catch{}

  function findQuotaSection(){
    return [...document.querySelectorAll('section.card')].find(s=>s.querySelector('h2')?.textContent?.includes('读取真实额度'));
  }

  function apply(){
    try{
      document.querySelectorAll('.metric > span').forEach(el=>{
        if(el.textContent.trim()==='5 小时剩余额度') el.textContent='5 小时限额';
        if(el.textContent.trim()==='每周剩余额度') el.textContent='每周限额';
      });

      const u=state?.usage||{};
      const section=findQuotaSection();
      if(!section) return;
      const fields=[...section.querySelectorAll('.field')];
      const five=fields.find(f=>f.querySelector('label')?.textContent?.trim()==='5 小时');
      const week=fields.find(f=>f.querySelector('label')?.textContent?.trim()==='每周');
      const fi=five?.querySelector('input');
      const wi=week?.querySelector('input');
      if(fi) fi.value=`${quotaText(u.fiveHour)} · 重置 ${cnTime(u.fiveHour?.resetAt)}`;
      if(wi) wi.value=`${quotaText(u.weekly)} · 重置 ${cnTime(u.weekly?.resetAt)}`;

      let raw=section.querySelector('#usageRawCards');
      if(!raw){
        raw=document.createElement('div');
        raw.id='usageRawCards';
        raw.className='hint';
        raw.style.marginTop='12px';
        raw.style.padding='12px';
        raw.style.border='1px solid #e5e7eb';
        raw.style.borderRadius='10px';
        section.appendChild(raw);
      }
      const fr=u.fiveHour?.rawCardText||'';
      const wr=u.weekly?.rawCardText||'';
      raw.innerHTML=`<b>ChatGPT 页面原文</b><br>5 小时：${esc(fr||'未识别到对应卡片')}<br><br>每周：${esc(wr||'未识别到对应卡片')}`;
    }catch{}
  }

  try{
    const originalRender=render;
    render=function(){ originalRender(); setTimeout(apply,0); };
  }catch{}

  const observer=new MutationObserver(()=>apply());
  observer.observe(document.documentElement,{subtree:true,childList:true});
  setTimeout(apply,300);
})();
