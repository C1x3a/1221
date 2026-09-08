import test from 'node:test';
import assert from 'node:assert/strict';
import {extractPeople,peopleToText} from './extract.mjs';

const A='11010519491231002X';
const B='440524188001010014';

test('extracts labelled name and IDs into alternating independent lines',()=>{
 const rows=extractPeople(`订单内容：姓名：张三，身份证号：${A}\n联系人 李四 / 证件号码 ${B}`);
 assert.deepEqual(rows.map(r=>[r.name,r.id,r.selected]),[['张三',A,true],['李四',B,true]]);
 assert.equal(peopleToText(rows),`张三\n${A}\n李四\n${B}`);
});

test('supports ID first and name first records',()=>{
 const rows=extractPeople(`${A} 张三\n李四 ${B}`);
 assert.deepEqual(rows.map(r=>r.name),['张三','李四']);
 assert.ok(rows.every(r=>r.selected));
});

test('preserves duplicate IDs as separate detected rows but deselects duplicate pair',()=>{
 const rows=extractPeople(`张三 ${A}\n张三 ${A}`);
 assert.equal(rows.length,2);assert.equal(rows[0].selected,true);assert.equal(rows[1].selected,false);assert.equal(rows[1].duplicate,true);
});

test('does not silently pair unrelated note text as a name',()=>{
 const rows=extractPeople(`备注：明天登记\n${A}\n姓名：李四 身份证：${B}`);
 assert.equal(rows[0].name,'');assert.equal(rows[0].selected,false);assert.equal(rows[1].name,'李四');
});

test('rejects selected incomplete results',()=>{
 assert.throws(()=>peopleToText([{name:'',id:A,selected:true}]));
 assert.throws(()=>peopleToText([{name:'张三',id:'123',selected:true}]));
});
