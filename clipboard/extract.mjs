// Local text matching only. No network request and no identity lookup.
const HAN='\\p{Script=Han}';
const LABELS=/(?:身份证(?:号码|号)?|证件(?:号码|号)?|手机(?:号码|号)?|电话|年龄|性别|地址|备注|编号|姓名|名字|联系人|乘车人|持证人)/u;
const BAD=new Set(['身份证','身份证号','身份证号码','证件号','证件号码','姓名','名字','联系人','乘车人','持证人','手机','电话','地址','备注','信息','报名','登记','收到','请查收','今天','明天','小米','红米','男士','女士','先生','小姐','号码','人员','名单','没有姓名','未知','待确认']);
const validName=name=>new RegExp(`^[${HAN}]{2,8}(?:[·•][${HAN}]{1,12})*$`,'u').test(name)||/^[A-Za-z][A-Za-z .'-]{1,49}$/.test(name);
export const idShape=id=>/^(?:\d{17}[0-9Xx]|\d{15})$/.test(String(id).trim());
function candidates(text){
 const result=[];
 const labelled=new RegExp(`(?:证件姓名|姓名|名字|联系人|乘车人|持证人)\\s*[:：=]?\\s*([${HAN}·•]+|[A-Za-z][A-Za-z .'-]*)`,'gu');
 for(const m of text.matchAll(labelled)){
  let name=m[1],cut=name.search(LABELS);if(cut>=0)name=name.slice(0,cut);name=name.trim();
  if(validName(name)&&!BAD.has(name)){const start=m.index+m[0].indexOf(m[1]);result.push({name,start,end:start+name.length,labelled:true})}
 }
 for(const m of text.matchAll(new RegExp(`[${HAN}]+(?:[·•][${HAN}]+)*`,'gu'))){
  let name=m[0];const prefix=/^(?:姓名|名字|联系人|乘车人|持证人)[:：]?/.exec(name);let offset=prefix?prefix[0].length:0;name=name.slice(offset);const stop=name.search(LABELS);if(stop>=0)name=name.slice(0,stop);
  if(!validName(name)||BAD.has(name)||/(?:地址|号码|电话|姓名|身份证|信息|名单|报名|备注|登记|收到|确认|查收|联系|未知|没有|不存在|手机|小米|红米)/u.test(name))continue;
  const start=m.index+offset;if(result.some(c=>start<c.end&&start+name.length>c.start))continue;
  result.push({name,start,end:start+name.length,labelled:false});
 }
 return result.sort((a,b)=>a.start-b.start);
}
function gapOK(text){
 if(text.length>90||text.split('\n').length>2)return false;
 const stripped=text.replace(/(?:身份证(?:号码|号)?|证件(?:号码|号)?|姓名|名字|联系人|乘车人|持证人|\bID\b|是)/giu,'');
 return /^[\s:：,，|/\\+＝=\-—()（）【】\[\]]*$/.test(stripped);
}
function sameRecord(text,a,b){return !/[\n;；]/.test(text.slice(Math.min(a,b),Math.max(a,b)))}
export function extractPeople(input){
 const text=String(input||'').normalize('NFKC').replace(/\r\n?/g,'\n');
 if(text.length>100000)throw new Error('原文最多 100000 个字符，请分次粘贴');
 const ids=[...text.matchAll(/(?<![0-9A-Za-z])(?:\d{17}[0-9Xx]|\d{15})(?![0-9A-Za-z])/g)].map(m=>({id:m[0].toUpperCase(),start:m.index,end:m.index+m[0].length}));
 const names=candidates(text);
 const edges=ids.map((id,i)=>{
  const before=names.filter(c=>c.end<=id.start&&c.start>=(ids[i-1]?.end||0)&&gapOK(text.slice(c.end,id.start))).at(-1);
  const after=names.find(c=>c.start>=id.end&&c.end<=(ids[i+1]?.start??text.length)&&gapOK(text.slice(id.end,c.start)));
  return {before,after};
 });
 let orientation='';
 if(edges[0]?.before&&!edges.at(-1)?.after)orientation='before';
 else if(!edges[0]?.before&&edges.at(-1)?.after)orientation='after';
 const used=new Set(),pairs=new Set();
 return ids.map((id,i)=>{
  const e=edges[i],available=[e.before,e.after].filter(c=>c&&!used.has(c.start));
  const inline=available.filter(c=>sameRecord(text,id.start,c.start));
  let chosen=null,reason='未识别到姓名，请补填',uncertain=false;
  if(inline.length===1){chosen=inline[0];reason=chosen.labelled?'按姓名标签匹配':'同行邻近匹配，请核对'}
  else if(orientation&&e[orientation]&&!used.has(e[orientation].start)){chosen=e[orientation];reason=chosen.labelled?'按姓名标签匹配':'按相邻顺序匹配，请核对'}
  else if(available.length===1){
   const c=available[0],borrowed=(c===e.after&&edges[i+1]?.before===c)||(c===e.before&&edges[i-1]?.after===c);
   if(!borrowed){chosen=c;reason=c.labelled?'按姓名标签匹配':'相邻匹配，请核对'}
  }else if(available.length>1){reason='前后都有姓名，无法确定对应关系';uncertain=true}
  if(chosen)used.add(chosen.start);
  const name=chosen?.name||'',pair=name+'|'+id.id,duplicate=!!name&&pairs.has(pair);if(name)pairs.add(pair);
  if(duplicate)reason='重复的姓名和号码，默认不选';
  if(id.id.length===15)reason+='；15 位旧号码，请核对';
  return {name,id:id.id,reason,selected:!!name&&!uncertain&&!duplicate,duplicate,source:text.slice(Math.max(0,Math.min(id.start,chosen?.start??id.start)-24),Math.min(text.length,Math.max(id.end,chosen?.end??id.end)+24)).trim()};
 });
}
export function peopleToText(rows){
 const selected=rows.filter(r=>r.selected);if(!selected.length)throw new Error('请先勾选要使用的人员');
 for(const r of selected){if(!String(r.name).trim())throw new Error('所选结果中有姓名为空，请补填');if(!idShape(r.id))throw new Error('所选结果中有身份证格式不完整，请核对')}
 return selected.flatMap(r=>[String(r.name).trim(),String(r.id).trim().toUpperCase()]).join('\n');
}
