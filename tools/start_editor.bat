@echo off
setlocal
chcp 65001 >nul
set PYTHONUTF8=1
title Tianwen Studio

set "PY_CMD="
python --version >nul 2>&1 && set "PY_CMD=python"
if not defined PY_CMD (
  py -3 --version >nul 2>&1 && set "PY_CMD=py -3"
)
if not defined PY_CMD (
  echo [ERROR] Python 3 not found in PATH. Please install Python 3.
  pause
  exit /b 1
)

%PY_CMD% "%~dp0tianwen_studio.py" %*
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo.
  echo [Tianwen Studio exited with code %EXIT_CODE%]
  pause
  exit /b %EXIT_CODE%
)
