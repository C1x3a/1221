const { app, BrowserWindow, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const { heartbeat } = require('./services/cloud');

let tray;
let win;

function createWindow(){
  win = new BrowserWindow({
    width:420,
    height:560,
    show:false,
    webPreferences:{nodeIntegration:true,contextIsolation:false}
  });
  win.loadFile(path.join(__dirname,'ui/index.html'));
}

app.whenReady().then(async()=>{
  createWindow();
  tray = new Tray(nativeImage.createEmpty());
  tray.setToolTip('RH Work Monitor');
  tray.setContextMenu(Menu.buildFromTemplate([
    {label:'打开控制面板',click:()=>win.show()},
    {label:'退出',click:()=>app.quit()}
  ]));
  setInterval(()=>heartbeat().catch(()=>{}),20000);
});
