@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if exist "%~dp0runtime\node\node.exe" (set "TASK_NODE=%~dp0runtime\node\node.exe") else (set "TASK_NODE=node")
"%TASK_NODE%" "%~dp0tools\windows\restore-private.mjs" "%~dp0..\MY_PLAN-private-configs.enc" "%~dp0..\私有配置解密密码.txt"
echo.
pause
