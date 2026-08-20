@echo off
setlocal
title Electric Emporium - Catalogue Manager
cd /d "%~dp0"

rem Prefer the local helper: it lets you paste image links, which the browser
rem cannot download by itself. If Python is not installed we still open the
rem manager directly - everything works except pasting links.

set PY=
where py >nul 2>nul && set PY=py
if not defined PY where python >nul 2>nul && set PY=python

if defined PY (
  echo Starting the Catalogue Manager...
  %PY% "%~dp0server.py"
  goto :eof
)

echo Python was not found, so image links cannot be downloaded.
echo Opening the Catalogue Manager anyway - drag and drop still works.
echo.
start "" "%~dp0index.html"
pause
