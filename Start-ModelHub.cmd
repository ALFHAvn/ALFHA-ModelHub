@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0scripts\modelhub-launcher.ps1" Start
set "MODELHUB_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %MODELHUB_EXIT%
