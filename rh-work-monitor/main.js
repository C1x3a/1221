const { app, BrowserWindow, Tray, Menu, nativeImage } = require('electron');
const { chromium } = require('playwright-core');

const RH_SERVER = 'https://rh-work-cloud-web-production.up.railway.app';

let win;
let tray;

async function detectBrowser(){
  const browsers = [
    process.env.PROGRAMFILES + '\\\\Microsoft\\\\Edge\\\\Application\\\\msedge.exe',
    process.env.PROGRAMFILES + '\\\\Google\\\\Chrome\\\\Application\\\\chrome.exe'
  ];
  return browsers.find(Boolean) || null;
}

async function createWindow(){
  win = new BrowserWindow({
    width: 420,
    height: 620,
    webPreferences:{nodeIntegration:false}
  });
  win.loadURL(RH_SERVER);
}

app.whenReady().then(async()=>{
  await detectBrowser();
  await createWindow();
  tray = new Tray(nativeImage.createEmpty());
  tray.setToolTip('RH Work Monitor');
  tray.setContextMenu(Menu.buildFromTemplate([
    {label:'打开控制台',click:()=>win.show()},
    {label:'退出',click:()=>app.quit()}
  ]));
});
