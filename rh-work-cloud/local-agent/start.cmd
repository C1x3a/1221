@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist node.exe (
  echo [RH Work] 缺少 node.exe，请使用打包好的 RH-Work-Local-Monitor.zip。
  pause
  exit /b 1
)
echo 正在启动 RH Work 本地监控端...
node.exe agent.js
echo.
echo 监控端已退出。按任意键关闭。
pause >nul
