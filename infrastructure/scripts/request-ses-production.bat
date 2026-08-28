@echo off
REM ===========================================================================
REM  OP-06: request SES production access (sandbox exit).
REM
REM      infrastructure\scripts\request-ses-production.bat --dry-run
REM      infrastructure\scripts\request-ses-production.bat
REM
REM  Launcher only -- the request text and the reasoning live in
REM  request-ses-production.py beside this file. READ THAT FIRST: it explains
REM  why this is a new request rather than an update to the denied case, and
REM  what the description has to contain.
REM
REM  Nothing is submitted without a prompt.
REM ===========================================================================

setlocal

pushd "%~dp0..\.."

REM The repo's .venv comes first, not as a fallback: on this machine Python is
REM installed only inside it, so a PATH-first launcher works in Git Bash and
REM fails from cmd.exe or a double-click -- the case this file exists for.
set "PY_CMD="
if exist ".venv\Scripts\python.exe" set "PY_CMD=.venv\Scripts\python.exe"

if not defined PY_CMD (
    where py >nul 2>nul
    if not errorlevel 1 set "PY_CMD=py"
)
if not defined PY_CMD (
    where python >nul 2>nul
    if not errorlevel 1 set "PY_CMD=python"
)
if not defined PY_CMD (
    echo.
    echo ERROR: no Python found ^(tried .venv, "py", and "python" on PATH^).
    popd
    endlocal
    exit /b 1
)

where aws >nul 2>nul
if not %ERRORLEVEL%==0 (
    echo.
    echo ERROR: the AWS CLI is not on PATH.
    popd
    endlocal
    exit /b 1
)

%PY_CMD% "infrastructure\scripts\request-ses-production.py" %*
set "EXIT_CODE=%ERRORLEVEL%"

popd

REM Pause only when double-clicked, so the window does not vanish.
echo %cmdcmdline% | find /i "%~nx0" >nul
if not errorlevel 1 pause

endlocal
exit /b %EXIT_CODE%
