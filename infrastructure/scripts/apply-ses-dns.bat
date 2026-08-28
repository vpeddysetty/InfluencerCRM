@echo off
REM ===========================================================================
REM  OP-06: authorise SES in the apex SPF record, and publish the DKIM CNAMEs
REM  for inbox.tejdux.com.
REM
REM  Double-click this, or run it from cmd.exe / PowerShell:
REM
REM      infrastructure\scripts\apply-ses-dns.bat
REM      infrastructure\scripts\apply-ses-dns.bat --dry-run
REM
REM  This file is only a launcher. The work is in apply-ses-dns.py beside it,
REM  because the real logic reads the live zone and asks SES for its current
REM  DKIM tokens -- neither of which cmd.exe can do without mangling the JSON.
REM  The .py carries the reasoning; read that before changing anything here.
REM
REM  Every change is additive (UPSERT). The script refuses to proceed if the
REM  batch would touch an MX record or contain a DELETE, prints the diff, and
REM  asks before writing anything.
REM ===========================================================================

setlocal

REM Run from the repo root regardless of where this was invoked, so the paths
REM below hold when the file is double-clicked from Explorer.
pushd "%~dp0..\.."

REM Find Python. The repo's own .venv comes FIRST and is not a fallback: on this
REM machine Python is installed only inside it, so a launcher that checked PATH
REM first would work in Git Bash (where the venv is active) and fail from
REM cmd.exe or a double-click, which is the case this file exists to serve.
REM Checked rather than assumed, because the alternative failure is
REM "'python' is not recognized" printed after the banner has already implied
REM the script is running.
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
    echo        Install Python, or run the bash version instead:
    echo            bash infrastructure/scripts/apply-ses-dns.sh
    popd
    endlocal
    exit /b 1
)

REM The AWS CLI is the other hard dependency, and its absence should be named
REM here rather than surfacing as a confusing traceback from subprocess.
where aws >nul 2>nul
if not %ERRORLEVEL%==0 (
    echo.
    echo ERROR: the AWS CLI is not on PATH.
    echo        Install it, then re-run this script.
    popd
    endlocal
    exit /b 1
)

%PY_CMD% "infrastructure\scripts\apply-ses-dns.py" %*
set "EXIT_CODE=%ERRORLEVEL%"

popd

REM Pause only when double-clicked, so the window does not vanish before the
REM output can be read. When run from an existing console, cmdcmdline contains
REM the invocation and pausing would just be an extra keypress in a script.
echo %cmdcmdline% | find /i "%~nx0" >nul
if not errorlevel 1 pause

endlocal
exit /b %EXIT_CODE%
