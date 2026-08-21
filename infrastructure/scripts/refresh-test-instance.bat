@echo off
setlocal enabledelayedexpansion

REM ===========================================================================
REM  Roll the TEST instance onto the current launch template.
REM
REM  WHICH ENVIRONMENT. This targets the TEST environment, in account
REM  099933382956. Every resource there is named `influencrm-prod-*` because
REM  `environment` defaults to "prod" in infrastructure/test/terraform -- a
REM  historical accident, not a statement about what the environment is. The
REM  account id below is the real guard; the name never was. See
REM  MASTER-ROADMAP.md section 7.1 for why renaming was declined.
REM
REM  Note that test currently serves the live tejdux.com domain, so "test" here
REM  means "the only environment", not "somewhere safe to break things".
REM
REM  WHY THIS EXISTS. `terraform apply` updates the launch template; it does not
REM  move the running instance onto it. Until an instance refresh runs, the box
REM  keeps serving the OLD version, and any change to the compose file or the
REM  instance's IAM policy is applied-but-not-live. That gap is invisible from
REM  outside: the site stays healthy and serves stale configuration.
REM
REM  WHAT IT COSTS. The ASG terminates the instance and boots a replacement.
REM  Redis was dropped when the platform moved to a single Fargate-style task,
REM  so sessions do not survive: EVERY SIGNED-IN USER IS LOGGED OUT, and the API
REM  is unavailable until the new instance passes its health check (~3-5 min).
REM  Run it deliberately, not casually.
REM
REM  CREDENTIALS. Uses the `tejdux` profile. No key is written into this file --
REM  a secret pasted into a script on disk outlives the reason it was needed. If
REM  the profile is missing, the script runs `aws configure` and you type it in.
REM ===========================================================================

set "AWS_PROFILE=tejdux"
set "AWS_REGION=us-east-1"
set "ASG=influencrm-prod-compose-2026081023183701630000000a"

echo.
echo ============================================================
echo   InfluenCRM  --  TEST instance refresh  (account 099933382956)
echo ============================================================
echo.

REM --- 1. AWS CLI present? -------------------------------------------------
where aws >nul 2>&1
if errorlevel 1 (
    echo ERROR: the AWS CLI is not on PATH.
    echo        Install it from https://aws.amazon.com/cli/ and reopen this window.
    goto :fail
)

REM --- 2. Credentials usable? ----------------------------------------------
REM Checked by calling STS rather than by looking for a credentials file: a file
REM can exist and still hold an expired or wrong-account key, and this is the
REM cheapest call that proves the identity actually works.
echo Checking credentials for profile "%AWS_PROFILE%" ...
for /f "tokens=*" %%A in ('aws sts get-caller-identity --profile %AWS_PROFILE% --query Account --output text 2^>nul') do set "ACCOUNT=%%A"

if not defined ACCOUNT (
    echo.
    echo   No working credentials for profile "%AWS_PROFILE%".
    echo   Starting `aws configure` -- paste the access key ID and secret when asked.
    echo   Region should be %AWS_REGION%, output format json.
    echo.
    aws configure --profile %AWS_PROFILE%
    for /f "tokens=*" %%A in ('aws sts get-caller-identity --profile %AWS_PROFILE% --query Account --output text 2^>nul') do set "ACCOUNT=%%A"
    if not defined ACCOUNT (
        echo ERROR: credentials still not working. Check the key and try again.
        goto :fail
    )
)

REM The account is asserted, not assumed. Pointing this at the wrong account
REM would roll someone else's instance, and the ASG name alone would not catch it.
if not "%ACCOUNT%"=="099933382956" (
    echo.
    echo ERROR: profile "%AWS_PROFILE%" is account %ACCOUNT%, expected 099933382956.
    echo        Refusing to touch an auto-scaling group in the wrong account.
    goto :fail
)
echo   OK -- account %ACCOUNT%

REM --- 2b. Can this identity actually start a refresh? ---------------------
REM Asked BEFORE the confirmation prompt, so a missing permission is not
REM discovered after someone has already agreed to take production down.
REM Simulated rather than attempted: the real call has no dry-run, and finding
REM out by trying it is the one test that cannot be undone.
for /f "tokens=*" %%I in ('aws sts get-caller-identity --profile %AWS_PROFILE% --query Arn --output text 2^>nul') do set "WHOAMI=%%I"
for /f "tokens=*" %%D in ('aws iam simulate-principal-policy --policy-source-arn !WHOAMI! --action-names autoscaling:StartInstanceRefresh --region %AWS_REGION% --profile %AWS_PROFILE% --query "EvaluationResults[0].EvalDecision" --output text 2^>nul') do set "CANREFRESH=%%D"

if defined CANREFRESH (
    if /i not "!CANREFRESH!"=="allowed" (
        echo.
        echo ERROR: !WHOAMI! is not allowed to run autoscaling:StartInstanceRefresh
        echo        ^(policy simulation says: !CANREFRESH!^)
        echo.
        echo        This identity can READ the group but not roll it. Use an identity
        echo        with autoscaling:StartInstanceRefresh, or roll it from the console:
        echo        EC2 -^> Auto Scaling Groups -^> %ASG% -^> Instance refresh
        goto :fail
    )
    echo   OK -- allowed to start an instance refresh
) else (
    REM iam:SimulatePrincipalPolicy is itself a permission. Not having it says
    REM nothing about the refresh, so this is a note rather than a failure.
    echo   note: could not simulate the permission ^(iam:SimulatePrincipalPolicy denied^)
    echo         proceeding anyway -- the refresh call will report its own error
)
echo.

REM --- 3. Show what is about to change -------------------------------------
REM Printed before the prompt so the confirmation is informed. If these two
REM numbers already match, the refresh has nothing to do and you can cancel.
REM The template id lives under MixedInstancesPolicy, NOT the top-level LaunchTemplate
REM key -- that one is null on this group and querying it returns "None", which is how
REM the version line came out blank the first time this script was run.
for /f "tokens=*" %%V in ('aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names %ASG% --region %AWS_REGION% --profile %AWS_PROFILE% --query "AutoScalingGroups[0].Instances[0].LaunchTemplate.Version" --output text 2^>nul') do set "RUNNING=%%V"
for /f "tokens=*" %%L in ('aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names %ASG% --region %AWS_REGION% --profile %AWS_PROFILE% --query "AutoScalingGroups[0].MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId" --output text 2^>nul') do set "LTID=%%L"
for /f "tokens=*" %%N in ('aws ec2 describe-launch-template-versions --launch-template-id !LTID! --region %AWS_REGION% --profile %AWS_PROFILE% --query "LaunchTemplateVersions[0].VersionNumber" --output text 2^>nul') do set "LATEST=%%N"

echo   Instance is running launch template version : !RUNNING!
echo   Current launch template version             : !LATEST!
echo.

if "!RUNNING!"=="!LATEST!" (
    echo   Those match -- the instance is already on the current template.
    echo   A refresh would restart it for no configuration change.
    echo.
)

echo   This will TERMINATE the running instance and boot a replacement.
echo   * every signed-in user is logged out ^(no Redis; sessions do not survive^)
echo   * the API is down until the new instance passes its health check
echo.

set /p "CONFIRM=Type REFRESH to proceed, anything else to cancel: "
if /i not "!CONFIRM!"=="REFRESH" (
    echo.
    echo Cancelled. Nothing was changed.
    goto :end
)

REM --- 4. Do it ------------------------------------------------------------
REM MinHealthyPercentage=0 because the group runs a SINGLE instance: with the
REM default of 90 the refresh cannot start, since taking the only instance out
REM drops healthy capacity to zero. This is what makes the downtime unavoidable
REM rather than a choice.
echo.
echo Starting instance refresh ...
for /f "tokens=*" %%R in ('aws autoscaling start-instance-refresh --auto-scaling-group-name %ASG% --region %AWS_REGION% --profile %AWS_PROFILE% --preferences "{\"MinHealthyPercentage\":0,\"InstanceWarmup\":180}" --query InstanceRefreshId --output text 2^>nul') do set "REFRESH_ID=%%R"

if not defined REFRESH_ID (
    echo ERROR: the refresh did not start. Re-run the command without 2^>nul to see why.
    goto :fail
)

echo   started: !REFRESH_ID!
echo.

REM --- 5. Watch it ---------------------------------------------------------
echo Polling until it finishes ^(Ctrl+C is safe -- the refresh continues^) ...
echo.

:poll
for /f "tokens=*" %%S in ('aws autoscaling describe-instance-refreshes --auto-scaling-group-name %ASG% --region %AWS_REGION% --profile %AWS_PROFILE% --instance-refresh-ids !REFRESH_ID! --query "InstanceRefreshes[0].Status" --output text 2^>nul') do set "STATUS=%%S"
for /f "tokens=*" %%P in ('aws autoscaling describe-instance-refreshes --auto-scaling-group-name %ASG% --region %AWS_REGION% --profile %AWS_PROFILE% --instance-refresh-ids !REFRESH_ID! --query "InstanceRefreshes[0].PercentageComplete" --output text 2^>nul') do set "PCT=%%P"

echo   !STATUS!  !PCT!%%

if /i "!STATUS!"=="Successful"  goto :done
if /i "!STATUS!"=="Failed"      goto :refresh_failed
if /i "!STATUS!"=="Cancelled"   goto :refresh_failed

timeout /t 20 /nobreak >nul
goto :poll

:refresh_failed
echo.
echo REFRESH !STATUS!. The old instance may still be serving.
echo Check the console output:
echo   aws ec2 get-console-output --instance-id ^<id^> --query Output --output text --region %AWS_REGION% --profile %AWS_PROFILE%
goto :fail

:done
echo.
echo ============================================================
echo   Refresh complete.
echo ============================================================
echo.
echo Now verify, in this order:
echo.
echo   1. curl https://api.tejdux.com/health          ^(expect 200^)
echo   2. Sign in at https://www.tejdux.com/
echo   3. Creators -^> New creator -^> type a handle -^> "Look up"
echo.
echo   The badge under the follower count is the thing that matters:
echo.
echo     "Platform verified"  -^> Instagram answered. Safe to record.
echo     "Simulated"          -^> the secrets did not reach the BFF.
echo                             STOP. Do not record simulated numbers
echo                             as though they were real audience data.
echo.
goto :end

:fail
echo.
exit /b 1

:end
endlocal
