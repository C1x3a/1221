function sequenceNumber(name){const matches=String(name||'').match(/\d+/g);return matches?.length?Number(matches.at(-1)):Number.POSITIVE_INFINITY}

export function orderDevices(devices){
 const collator=new Intl.Collator('zh-CN',{numeric:true,sensitivity:'base'});
 return [...devices].map((device,index)=>({device,index,number:sequenceNumber(device.name)})).sort((a,b)=>a.number-b.number||collator.compare(a.device.name,b.device.name)||a.index-b.index).map(row=>row.device);
}

export function shuffled(values,random=Math.random){
 const copy=[...values];
 for(let i=copy.length-1;i>0;i--){const j=Math.floor(Math.max(0,Math.min(.999999999,Number(random()))) * (i+1));[copy[i],copy[j]]=[copy[j],copy[i]]}
 return copy;
}

export function assignSegments(devices,segmentIds,{mode='different',sameId='',random=Math.random}={}){
 const ordered=orderDevices(devices),ids=[...segmentIds];
 if(mode==='same'){const chosen=ids.includes(sameId)?sameId:ids[0]||'';return ordered.map(device=>({deviceId:device.id,segmentId:chosen}))}
 const randomized=shuffled(ids,random);
 return ordered.map((device,index)=>({deviceId:device.id,segmentId:randomized[index]||''}));
}
