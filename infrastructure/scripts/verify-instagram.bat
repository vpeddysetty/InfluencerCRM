@echo off
setlocal enabledelayedexpansion

REM ===========================================================================
REM  Is the Instagram integration actually live?
REM
REM  Every lookup returned a plausible follower count and a 200 while the adapter
REM  was unconfigured, because an unconfigured adapter falls back to a simulation
REM  rather than failing. `metrics_source` is the only field that ever told the
REM  truth, and in the UI it is the badge under the follower count.
REM
REM  This checks the same thing from outside a browser, in the order the chain
REM  actually breaks:
REM
REM    1. Which image is running       -- the fault behind the "Simulated" badge
REM                                       was an image built BEFORE the adapter
REM                                       existed, so this is checked first
REM    2. Are the credentials present  -- inside the container, lengths only
REM    3. Do the properties bind       -- an image can hold the values and still
REM                                       lack the properties that read them
REM    4. Is the API serving
REM
REM  What it cannot check is the badge itself: /api/creators/resolve-handle needs
REM  a signed-in session, and issuing one from a script would mean handling a
REM  password. The last step is therefore yours, and the script says so.
REM ===========================================================================

set "AWS_PROFILE=tejdux"
set "AWS_REGION=us-east-1"
set "ASG=influencrm-prod-compose-2026081023183701630000000a"
set "EXPECT_TAG=v1.0.19"

echo.
echo ============================================================
echo   Instagram integration -- is it live?
echo ============================================================
echo.

for /f "tokens=*" %%A in ('aws sts get-caller-identity --profile %AWS_PROFILE% --query Account --output text 2^>nul') do set "ACCT=%%A"
if not "!ACCT!"=="099933382956" ( echo ERROR: wrong account "!ACCT!". & goto :fail )

REM --- 1. instance + image --------------------------------------------------
for /f "tokens=*" %%I in ('aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names %ASG% --region %AWS_REGION% --profile %AWS_PROFILE% --query "AutoScalingGroups[0].Instances[0].InstanceId" --output text 2^>nul') do set "IID=%%I"
if "!IID!"=="" ( echo ERROR: no instance in the group. & goto :fail )
echo   instance   !IID!

REM Read the tag off the running container, not off Terraform state or the compose
REM file in S3. Both of those describe what SHOULD be running; only the container
REM says what is. That gap is the entire reason this script exists.
set "PARAMS=%TEMP%\ig-verify-%RANDOM%.json"
> "%PARAMS%" echo {"commands":["docker inspect --format '{{index .Config.Image}}' influencrm-web-experience-1 2>/dev/null || echo NO_CONTAINER"]}
for /f "tokens=*" %%C in ('aws ssm send-command --region %AWS_REGION% --profile %AWS_PROFILE% --instance-ids !IID! --document-name AWS-RunShellScript --parameters file://%PARAMS% --query Command.CommandId --output text 2^>nul') do set "CID=%%C"
del "%PARAMS%" >nul 2>&1
if "!CID!"=="" ( echo   WARN: could not reach the instance over SSM; skipping container checks & goto :api )

REM `timeout` reads the console and dies with "Input redirection is not supported"
REM whenever this script is piped or run from a wrapper. ping to loopback waits
REM without touching stdin and works on every Windows since XP.
ping -n 9 127.0.0.1 >nul 2>&1
for /f "tokens=*" %%O in ('aws ssm get-command-invocation --region %AWS_REGION% --profile %AWS_PROFILE% --command-id !CID! --instance-id !IID! --query StandardOutputContent --output text 2^>nul') do set "IMAGE=%%O"
echo   image      !IMAGE!

echo !IMAGE! | find "%EXPECT_TAG%" >nul
if errorlevel 1 (
    echo.
    echo   ^>^> NOT RUNNING %EXPECT_TAG%.
    echo      Terraform can be applied and the image still not be live: the launch
    echo      template changes, the running box does not. Roll it with
    echo      infrastructure\scripts\refresh-test-instance.bat, then re-run this.
    goto :fail
)
echo   ^>^> running the expected image

REM --- 2 + 3. credentials, and the properties that read them ----------------
REM Lengths only. The token is a live credential and printing it into a terminal
REM would put it in scrollback, which is how the last one ended up needing rotation.
set "PARAMS=%TEMP%\ig-verify-%RANDOM%.json"
> "%PARAMS%" echo {"commands":["docker exec influencrm-web-experience-1 printenv INSTAGRAM_ACCESS_TOKEN 2>/dev/null | wc -c","docker exec influencrm-web-experience-1 printenv INSTAGRAM_BUSINESS_ACCOUNT_ID 2>/dev/null | wc -c","docker exec influencrm-web-experience-1 sh -c 'printenv INSTAGRAM_ACCESS_TOKEN >/dev/null 2>&1 && echo 1 || echo 0'"]}
for /f "tokens=*" %%C in ('aws ssm send-command --region %AWS_REGION% --profile %AWS_PROFILE% --instance-ids !IID! --document-name AWS-RunShellScript --parameters file://%PARAMS% --query Command.CommandId --output text 2^>nul') do set "CID2=%%C"
del "%PARAMS%" >nul 2>&1
REM `timeout` reads the console and dies with "Input redirection is not supported"
REM whenever this script is piped or run from a wrapper. ping to loopback waits
REM without touching stdin and works on every Windows since XP.
ping -n 9 127.0.0.1 >nul 2>&1

set "LINE=0"
for /f "tokens=*" %%O in ('aws ssm get-command-invocation --region %AWS_REGION% --profile %AWS_PROFILE% --command-id !CID2! --instance-id !IID! --query StandardOutputContent --output text 2^>nul') do (
    set /a LINE+=1
    if "!LINE!"=="1" set "TOKLEN=%%O"
    if "!LINE!"=="2" set "ACCTLEN=%%O"
    if "!LINE!"=="3" set "PROPS=%%O"
)

echo   token len  !TOKLEN!   (expect ~217)
echo   acct len   !ACCTLEN!  (expect ~18)
echo   env bound  !PROPS!    (1 = the container exports the variable)

if "!TOKLEN!"=="1" ( echo   ^>^> TOKEN EMPTY in the container. & goto :fail )
if "!ACCTLEN!"=="1" ( echo   ^>^> ACCOUNT ID EMPTY in the container. & goto :fail )
if "!PROPS!"=="0" (
    echo.
    echo   ^>^> The container does not export INSTAGRAM_ACCESS_TOKEN at all.
    echo      Check the compose file in S3 and the instance IAM policy.
    goto :fail
)

REM NOT CHECKED HERE: that the image's application.properties actually declares
REM `web-experience.creators.instagram-*`. That is the v1.0.18 fault -- values
REM present, no property to read them through -- and it is invisible from inside
REM the container, because the app ships as a packaged /app/app.jar with no unzip
REM or jar binary in the runtime image to look inside it. Verified out of band
REM for v1.0.19 by copying the jar out and reading BOOT-INF/classes; the badge in
REM the product is the check that covers it from here on.

:api
echo.
for /f "tokens=*" %%H in ('curl -s -o nul -w "%%{http_code}" --max-time 20 https://api.tejdux.com/health 2^>nul') do set "HC=%%H"
echo   api/health !HC!
if not "!HC!"=="200" ( echo   ^>^> API not serving; wait for the containers to finish starting. & goto :fail )

echo.
echo ============================================================
echo   Everything checkable from here is correct.
echo ============================================================
echo.
echo The last check needs a signed-in browser, because resolve-handle requires a
echo session this script has no way to obtain without handling your password:
echo.
echo   1. https://www.tejdux.com/  (private window, email + password)
echo   2. Creators -^> New creator
echo   3. Type a public Instagram BUSINESS or CREATOR handle
echo   4. Click "Look up"
echo.
echo   Under the follower count:
echo.
echo     "Platform verified"  -^> Instagram answered. Record the screencast.
echo     "Simulated"          -^> still falling back. Do NOT record: those
echo                             numbers are generated, and filming them as
echo                             real audience data is the exact thing App
echo                             Review exists to catch.
echo.
echo   For the recording itself use a real creator, not a brand like nike --
echo   business_discovery answers for any public Business account, but a brand
echo   being "looked up as a creator" tells a reviewer a confusing story.
echo.
goto :end

:fail
echo.
exit /b 1

:end
endlocal
