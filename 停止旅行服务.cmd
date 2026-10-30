@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if exist "%~dp0runtime\node\node.exe" (set "TASK_NODE=%~dp0runtime\node\node.exe") else (set "TASK_NODE=node")
"%TASK_NODE%" "%~dp0tools\windows\travel-launcher.mjs" stop
echo.
pause
