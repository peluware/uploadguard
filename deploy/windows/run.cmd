@echo off
REM Runs UploadGuard in the foreground, reading .env from this directory if it is there.
REM This is what the scheduled task calls, and also what you run by hand to try it locally.
setlocal

cd /d "%~dp0"

if not exist "uploadguard.jar" (
    echo uploadguard.jar is not in %CD%.
    echo Build it with "mvn clean package" and copy target\uploadguard.jar here,
    echo or run the project's own run.cmd to build and run from the sources.
    exit /b 1
)

REM KEY=VALUE per line, # for comments
if exist ".env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
)

if not defined UPSTREAM_URL (
    echo UPSTREAM_URL is not set. Put it in "%CD%\.env" or in the environment.
    exit /b 1
)

java -Djdk.httpclient.allowRestrictedHeaders=host -Xmx256m -jar "%CD%\uploadguard.jar"
