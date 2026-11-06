@echo off
REM Installs UploadGuard as a scheduled task that starts with Windows. Runs in place: this script
REM lives in the install directory together with the jar, and registers what is next to it.
REM
REM   install.cmd            register and start
REM
REM Idempotent: re-registering replaces the existing task.
REM
REM NOTE: unlike the Linux installer, this does NOT create a dedicated service account. The task runs
REM as SYSTEM. Windows has no equivalent of a system user with no login that a batch file can set up
REM safely, so if you need that isolation, run it on Linux or use a proper service wrapper.
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if errorlevel 1 (
    echo Run this from an elevated prompt ^(Run as administrator^).
    exit /b 1
)

where java >nul 2>&1 || (echo java not found on PATH & exit /b 1)
if not exist "uploadguard.jar" (echo uploadguard.jar is missing from %CD% & exit /b 1)

if exist ".env" (
    echo = .env ^(left as it is^)
) else (
    echo + .env
    copy /y "env.example" ".env" >nul
    set NEEDS_CONFIG=1
)

echo + scheduled task UploadGuard
schtasks /create /tn "UploadGuard" /tr "\"%CD%\run.cmd\"" /sc onstart /ru SYSTEM /rl HIGHEST /f >nul || (
    echo Could not register the task.
    exit /b 1
)

if defined NEEDS_CONFIG (
    echo.
    echo   Set UPSTREAM_URL before starting:
    echo       notepad "%CD%\.env"
    echo       schtasks /run /tn "UploadGuard"
    echo.
    exit /b 0
)

echo + starting
schtasks /end /tn "UploadGuard" >nul 2>&1
schtasks /run /tn "UploadGuard" >nul || (echo Could not start the task. & exit /b 1)

schtasks /query /tn "UploadGuard" /fo list | findstr /i "TaskName Status"
