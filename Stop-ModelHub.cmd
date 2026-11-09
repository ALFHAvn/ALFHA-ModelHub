@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0scripts\modelhub-launcher.ps1" Stop
set "MODELHUB_EXIT=%ERRORLEVEL%"
if not "%MODELHUB_EXIT%"=="0" pause
exit /b %MODELHUB_EXIT%
