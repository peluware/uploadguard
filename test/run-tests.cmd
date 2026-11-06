@echo off
REM Runs the end-to-end suite. Needs Docker, curl and Python.
cd /d "%~dp0"
python run-tests.py %*
