@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -Command "Start-Process -FilePath (Join-Path (Get-Location) 'openrad-desktop.exe') -WorkingDirectory (Get-Location).Path -Verb RunAs"
