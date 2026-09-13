import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';

const dataDir = process.env.DATA_DIR || path.resolve('data');
const file = path.join(dataDir, 'state.json');

const defaults = {
  version: 1,
  settings: {
    steelApiKey: '',
    adminPassword: process.env.ADMIN_PASSWORD || 'chenyuhang',
    workName: 'RH Work',
    workUrl: '',
    continuePrompt: '继续当前未完成的任务。沿用已经完成的内容，不要从头重新开始；读取当前 Work 最近进度后直接继续执行。',
    autoContinue: true,
    autoRetry: true,
    fallbackWindowMinutes: 300,
    schedule: { enabled: false, kind: 'once', date: '', time: '09:00' }
  },
  runtime: {
    status: 'idle',
    gptStatus: 'unknown',
    limitDetectedAt: null,
    resetAt: null,
    lastRunAt: null,
    lastSuccessAt: null,
    lastError: null,
    steelProfileId: null,
    activeSessionId: null,
    activeDebugUrl: null,
    nextScheduledAt: null,
    executionLockUntil: null,
    lastScheduleKey: null
  },
  logs: []
};

function clone(v){ return JSON.parse(JSON.stringify(v)); }
function merge(base, next){
  if (!next || typeof next !== 'object' || Array.isArray(next)) return next ?? base;
  const out = {...base};
  for (const [k,v] of Object.entries(next)) {
    if (v && typeof v === 'object' && !Array.isArray(v) && base?.[k] && typeof base[k] === 'object' && !Array.isArray(base[k])) out[k] = merge(base[k], v);
    else out[k] = v;
  }
  return out;
}

function ensure(){
  fs.mkdirSync(dataDir, {recursive:true});
  if (!fs.existsSync(file)) fs.writeFileSync(file, JSON.stringify(defaults, null, 2));
}

export function readState(){
  ensure();
  try { return merge(clone(defaults), JSON.parse(fs.readFileSync(file,'utf8'))); }
  catch { return clone(defaults); }
}

export function writeState(next){
  ensure();
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(next, null, 2));
  fs.renameSync(tmp, file);
  return next;
}

export function updateState(patch){
  const current = readState();
  const next = merge(current, patch);
  return writeState(next);
}

export function addLog(event, detail='', level='info', meta={}){
  const state = readState();
  state.logs.unshift({id: crypto.randomUUID(), at:new Date().toISOString(), event, detail, level, meta});
  state.logs = state.logs.slice(0, 300);
  writeState(state);
}

export function publicState(){
  const s = readState();
  return {
    settings: {...s.settings, steelApiKey: s.settings.steelApiKey ? '••••••••' : ''},
    runtime: s.runtime,
    logs: s.logs.slice(0, 120),
    steelConnected: Boolean(process.env.STEEL_API_KEY || s.settings.steelApiKey)
  };
}

export function getSteelKey(){
  const s = readState();
  return process.env.STEEL_API_KEY || s.settings.steelApiKey || '';
}
