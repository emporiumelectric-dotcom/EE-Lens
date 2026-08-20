@echo off
setlocal
title Electric Emporium - Catalogue Manager (phone sync ON)
cd /d "%~dp0"

rem Same manager, but reachable from the phone over the shop's Wi-Fi.
rem The window shows the address and the six-digit pairing code to type once
rem on the phone. Close the window to switch sync off again.

set PY=
where py >nul 2>nul && set PY=py
if not defined PY where python >nul 2>nul && set PY=python

if defined PY (
  %PY% "%~dp0server.py" --sync
  goto :eof
)

echo Python was not found, so phone sync cannot run.
echo Use "EE Lens Manager.bat" instead - drag and drop still works.
echo.
pause
