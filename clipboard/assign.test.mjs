import test from 'node:test';
import assert from 'node:assert/strict';
import {orderDevices,assignSegments} from './assign.mjs';

const devices=[{id:'c',name:'手机 10'},{id:'a',name:'手机 01'},{id:'x',name:'备用机'},{id:'b',name:'手机 02'}];

test('orders numbered phones as 01, 02, 10 before unnumbered names',()=>{
 assert.deepEqual(orderDevices(devices).map(device=>device.id),['a','b','c','x']);
});

test('different mode randomizes information while keeping phone order stable',()=>{
 const result=assignSegments(devices,['s1','s2','s3'],{mode:'different',random:()=>0});
 assert.deepEqual(result.map(row=>row.deviceId),['a','b','c','x']);
 assert.deepEqual(result.map(row=>row.segmentId),['s2','s3','s1','']);
});

test('same mode assigns one selected segment to every chosen phone',()=>{
 const result=assignSegments(devices,['s1','s2'],{mode:'same',sameId:'s2'});
 assert.deepEqual(result.map(row=>row.segmentId),['s2','s2','s2','s2']);
});
