@echo off
REM Removes the UploadGuard scheduled task. The files are left where they are: on Windows the install
REM directory is wherever you put it, so deleting it is your call, not this script's.
REM
REM   uninstall.cmd
setlocal

net session >nul 2>&1
if errorlevel 1 (
    echo Run this from an elevated prompt ^(Run as administrator^).
    exit /b 1
)

schtasks /query /tn "UploadGuard" >nul 2>&1
if errorlevel 1 (
    echo = no UploadGuard task registered
) else (
    echo - stopping
    schtasks /end /tn "UploadGuard" >nul 2>&1
    echo - scheduled task UploadGuard
    schtasks /delete /tn "UploadGuard" /f >nul || (echo Could not remove the task. & exit /b 1)
)

echo done
echo The files in "%~dp0" were left untouched. Remove the directory by hand if you want it gone.
