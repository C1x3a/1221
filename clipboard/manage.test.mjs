import test from 'node:test';
import assert from 'node:assert/strict';
import {migrateController,addNamedDevices,renameDevice,removeDevice,saveGroup,groupSelection} from './manage.mjs';

test('migrates old controller and batch adds unique custom device names',()=>{
 const c=migrateController({devices:[]});const result=addNamedDevices(c,'我的01\n我的02\n我的01');
 assert.equal(result.added.length,2);assert.equal(result.skipped,1);assert.ok(result.added.every(d=>d.id&&d.key));assert.deepEqual(c.groups,[]);
});

test('rename preserves device id and pairing key',()=>{
 const c=migrateController({devices:[]});const d=addNamedDevices(c,'旧名称').added[0],before={id:d.id,key:d.key};renameDevice(c,d.id,'新名称');
 assert.deepEqual({id:d.id,key:d.key},before);assert.equal(d.name,'新名称');
});

test('groups select multiple devices and deletion prunes membership',()=>{
 const c=migrateController({devices:[]});const [a,b,c3]=addNamedDevices(c,'A\nB\nC').added;const g=saveGroup(c,{name:'朋友A',members:[a.id,b.id,b.id,'missing']});
 assert.deepEqual([...groupSelection(c,g.id)],[a.id,b.id]);removeDevice(c,b.id);assert.deepEqual([...groupSelection(c,g.id)],[a.id]);assert.equal(c.devices.length,2);assert.equal(c.devices[1],c3);
});
