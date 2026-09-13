import Steel from 'steel-sdk';
import { chromium } from 'playwright-core';
import { getSteelKey } from './store.js';

export const VIEWPORT = {width:1280, height:800};

export function steelClient(){
  const key = getSteelKey();
  if (!key) throw new Error('STEEL_KEY_MISSING');
  return {client:new Steel({steelAPIKey:key}), key};
}

export async function createSession(profileId=null, timeout=840000){
  const {client,key} = steelClient();
  const session = await client.sessions.create({
    timeout,
    dimensions: VIEWPORT,
    persistProfile: true,
    ...(profileId ? {profileId} : {})
  });
  return {client,key,session};
}

export async function connectSession(key, session){
  const ws = session.websocketUrl || `wss://connect.steel.dev?sessionId=${encodeURIComponent(session.id)}`;
  const sep = ws.includes('?') ? '&' : '?';
  const browser = await chromium.connectOverCDP(`${ws}${sep}apiKey=${encodeURIComponent(key)}`, {timeout:30000});
  const context = browser.contexts()[0] || await browser.newContext();
  const page = context.pages()[0] || await context.newPage();
  return {browser, context, page};
}

export async function releaseSession(client, id){
  try { await client.sessions.release(id); } catch {}
}

export async function computer(sessionId, action){
  const {client} = steelClient();
  return client.sessions.computer(sessionId, action);
}

export function viewerUrl(session){
  return session.debugUrl || session.sessionViewerUrl || session.viewerUrl || '';
}
