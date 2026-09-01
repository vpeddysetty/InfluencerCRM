@echo off
setlocal enabledelayedexpansion

REM ===========================================================================
REM  Put the OpenAI key into Secrets Manager (roadmap OP-27).
REM
REM  WHAT WAS WRONG. `influencrm-prod/openai-api-key` has never held a value --
REM  its own description says so: "Unset: OpenAIAdvisor reports itself
REM  unavailable and mapping falls back to the deterministic matcher." So every
REM  LLM path in agent_service has been returning source="heuristic" since
REM  2026-08-09, and creator vetting -- niche and risk flags -- has been running
REM  on substring matching in production.
REM
REM  WHY NOBODY NOTICED. The fallback is by design and never errors: no 500, no
REM  log line, no failed request. `fetch_secret` in compose-boot.sh.tftpl treats
REM  an empty secret as a warning to `logger` and skips the line, so the agent
REM  container starts happily with OPENAI_API_KEY simply absent and
REM  `_llm_classify` returns None on every call. A brand sees a plausible niche
REM  produced by keyword matching. Commit 29a8c3a adds `llm` to the agent's
REM  /health so this state is observable rather than inferable -- but that ships
REM  with the next image build, whereas this script fixes the key NOW.
REM
REM  WHICH ENVIRONMENT. Account 099933382956, the only environment, serving the
REM  live tejdux.com. Resources are named `influencrm-prod-*`; see
REM  MASTER-ROADMAP.md section 7.1. The account check below is the real guard.
REM
REM  THE KEY comes from the gitignored .env at the repo root. It is deliberately
REM  never echoed by this script. Verified before writing: it is absent from git
REM  history (`git log --all -S` finds nothing) and from every tracked file, so
REM  it is not the exposed key the secret's description warns about -- that one
REM  was already rotated.
REM
REM  AFTER THIS RUNS the secret is set, but the RUNNING container still has the
REM  old (absent) environment: fetch_secret executes at boot, not continuously.
REM  Roll the instance to pick it up -- refresh-test-instance.bat in this
REM  directory does exactly that.
REM
REM    infrastructure\scripts\set-openai-key.bat
REM ===========================================================================

set "PROFILE=tejdux"
set "REGION=us-east-1"
set "SECRET_ID=influencrm-prod/openai-api-key"
set "EXPECTED_ACCOUNT=099933382956"

REM Resolve the repo root from this script's location, so it runs from anywhere.
set "REPO_ROOT=%~dp0..\.."
set "ENV_FILE=%REPO_ROOT%\.env"

echo.
echo   Secret : %SECRET_ID%
echo   Region : %REGION%
echo   Profile: %PROFILE%
echo.

if not exist "%ENV_FILE%" (
    echo   ERROR: %ENV_FILE% not found. The key is read from there and is not stored in the repo.
    exit /b 1
)

REM ---- 1. the account guard, before anything is written ---------------------
REM  Same reasoning as account-guard.tf: the environment is identified by its
REM  account id, never by a resource name. Writing a production secret into the
REM  wrong account would be silent and hard to notice.
for /f "usebackq delims=" %%A in (`aws sts get-caller-identity --profile %PROFILE% --query Account --output text 2^>nul`) do set "ACCOUNT=%%A"

if not "!ACCOUNT!"=="%EXPECTED_ACCOUNT%" (
    echo   ERROR: expected account %EXPECTED_ACCOUNT%, got "!ACCOUNT!".
    echo          Refusing to write a secret into an unexpected account.
    exit /b 1
)
echo   account: !ACCOUNT!  ^(as expected^)

REM ---- 2. read the key, without ever printing it -----------------------------
set "OPENAI_KEY="
for /f "usebackq tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
    if /i "%%A"=="OPENAI_API_KEY" set "OPENAI_KEY=%%B"
)

if not defined OPENAI_KEY (
    echo   ERROR: no OPENAI_API_KEY line in %ENV_FILE%.
    exit /b 1
)

REM Strip surrounding quotes if the .env used them.
set "OPENAI_KEY=!OPENAI_KEY:"=!"

REM A shape check, not a validity check -- a truncated paste is the likely error
REM here, and it would fail exactly like an empty secret: silently.
echo !OPENAI_KEY! | findstr /b "sk-" >nul
if errorlevel 1 (
    echo   ERROR: the value does not start with "sk-". Refusing to write it.
    exit /b 1
)

REM ---- 3. write it ----------------------------------------------------------
echo   writing the secret...
aws secretsmanager put-secret-value ^
    --secret-id "%SECRET_ID%" ^
    --secret-string "!OPENAI_KEY!" ^
    --region %REGION% ^
    --profile %PROFILE% ^
    --query "VersionStages" ^
    --output text

if errorlevel 1 (
    echo.
    echo   FAILED to write the secret. Nothing was changed.
    exit /b 1
)

REM ---- 4. confirm it is non-empty, without revealing it ----------------------
for /f "usebackq delims=" %%L in (`aws secretsmanager get-secret-value --secret-id "%SECRET_ID%" --region %REGION% --profile %PROFILE% --query "length(SecretString)" --output text 2^>nul`) do set "LEN=%%L"

echo.
echo   stored secret length: !LEN!
echo.
echo   The secret is set. The RUNNING container still has the old environment --
echo   fetch_secret runs at boot, not continuously. Roll the instance:
echo.
echo       infrastructure\scripts\refresh-test-instance.bat
echo.
echo   Then confirm on the agent's /health that llm reports "available",
echo   and re-run tests\e2e\probe-op25-verify-sources.mjs to close OP-25.
echo.

endlocal
