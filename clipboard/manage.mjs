import {token} from './core.mjs';
export function cleanName(value){const name=String(value||'').trim();if(!name)throw new Error('名称不能为空');if(name.length>60)throw new Error('名称最多 60 个字符');return name}
export function migrateController(controller){
 if(!Array.isArray(controller.devices))controller.devices=[];
 const ids=new Set(controller.devices.map(d=>d.id));
 controller.groups=(Array.isArray(controller.groups)?controller.groups:[]).filter(g=>g&&typeof g.id==='string'&&typeof g.name==='string'&&Array.isArray(g.members)).map(g=>({...g,members:[...new Set(g.members)].filter(id=>ids.has(id))}));
 return controller;
}
export function addNamedDevices(controller,text){
 const names=String(text).split(/\r?\n/).map(x=>x.trim()).filter(Boolean);if(!names.length)throw new Error('请填写至少一个手机名称');names.forEach(cleanName);
 const existing=new Set(controller.devices.map(d=>d.name.toLocaleLowerCase())),added=[];let skipped=0;
 for(const name of names){const key=name.toLocaleLowerCase();if(existing.has(key)){skipped++;continue}const device={id:'d'+token(12),name,key:token(32)};controller.devices.push(device);added.push(device);existing.add(key)}
 return {added,skipped};
}
export function renameDevice(controller,id,value){const name=cleanName(value);if(controller.devices.some(d=>d.id!==id&&d.name.toLocaleLowerCase()===name.toLocaleLowerCase()))throw new Error('已有同名手机，请换一个名称');const d=controller.devices.find(d=>d.id===id);if(!d)throw new Error('这部手机已经被删除');d.name=name;return d}
export function removeDevice(controller,id){controller.devices=controller.devices.filter(d=>d.id!==id);for(const g of controller.groups)g.members=g.members.filter(x=>x!==id)}
export function saveGroup(controller,{id,name,members}){name=cleanName(name);if(controller.groups.some(g=>g.id!==id&&g.name.toLocaleLowerCase()===name.toLocaleLowerCase()))throw new Error('已有同名分组，请换一个名称');const known=new Set(controller.devices.map(d=>d.id)),group={id:id||'g'+token(12),name,members:[...new Set(members)].filter(x=>known.has(x))};const i=controller.groups.findIndex(g=>g.id===id);if(i>=0)controller.groups[i]=group;else controller.groups.push(group);return group}
export function groupSelection(controller,id){return new Set((controller.groups.find(g=>g.id===id)?.members||[]).filter(id=>controller.devices.some(d=>d.id===id)))}
