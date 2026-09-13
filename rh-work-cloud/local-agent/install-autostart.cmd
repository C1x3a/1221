@echo off
chcp 65001 >nul
cd /d "%~dp0"
set "VBS=%~dp0start-hidden.vbs"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$startup=[Environment]::GetFolderPath('Startup');$ws=New-Object -ComObject WScript.Shell;$sc=$ws.CreateShortcut((Join-Path $startup 'RH Work Local Monitor.lnk'));$sc.TargetPath=$env:WINDIR+'\System32\wscript.exe';$sc.Arguments='\"%VBS%\"';$sc.WorkingDirectory='%~dp0';$sc.Save()"
if errorlevel 1 (
  echo 安装失败。
  pause
  exit /b 1
)
echo 已设置为 Windows 登录后自动启动 RH Work 本地监控端。
echo 建议同时在 Windows 电源设置中把“睡眠”改成“从不”。
pause
