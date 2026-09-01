@echo off
setlocal enabledelayedexpansion

REM ===========================================================================
REM  Re-read Secrets Manager on the running box, without replacing it.
REM
REM  WHY THIS RATHER THAN AN INSTANCE REFRESH. A secret's VALUE is not part of
REM  the launch template -- the template only names the ARN, and the env file is
REM  built at boot by influencrm-secrets.service. So after a `put-secret-value`
REM  the template is byte-identical and refresh-test-instance.bat correctly says
REM  "already on the current template", while the running container still holds
REM  the old environment.
REM
REM  compose-boot.sh.tftpl anticipated this. Its own comment, on the unit
REM  influencrm.service Requires=:
REM
REM      "a secret rotation is picked up by `systemctl restart
REM       influencrm-secrets influencrm` with no redeploy."
REM
REM  That is what this runs. It costs a container restart -- roughly a minute of
REM  API downtime -- instead of terminating the instance and booting a
REM  replacement (3-5 minutes, plus every signed-in user logged out because
REM  there is no Redis and sessions do not survive). Prefer this whenever the
REM  ONLY thing that changed is a secret value.
REM
REM  Use refresh-test-instance.bat instead when the launch template itself
REM  changed -- compose file, IAM policy, user_data. This script would not pick
REM  those up.
REM
REM    infrastructure\scripts\reload-secrets.bat
REM ===========================================================================

set "PROFILE=tejdux"
set "REGION=us-east-1"
set "ASG=influencrm-prod-compose-2026081023183701630000000a"
set "EXPECTED_ACCOUNT=099933382956"

echo.
echo ============================================================
echo   InfluenCRM  --  reload secrets on the running instance
echo ============================================================
echo.

for /f "usebackq delims=" %%A in (`aws sts get-caller-identity --profile %PROFILE% --query Account --output text 2^>nul`) do set "ACCOUNT=%%A"
if not "!ACCOUNT!"=="%EXPECTED_ACCOUNT%" (
    echo   ERROR: expected account %EXPECTED_ACCOUNT%, got "!ACCOUNT!".
    exit /b 1
)
echo   account: !ACCOUNT!  ^(as expected^)

for /f "usebackq delims=" %%I in (`aws autoscaling describe-auto-scaling-groups --auto-scaling-group-name %ASG% --region %REGION% --profile %PROFILE% --query "AutoScalingGroups[0].Instances[0].InstanceId" --output text 2^>nul`) do set "INSTANCE=%%I"

if not defined INSTANCE (
    echo   ERROR: could not find a running instance in %ASG%.
    exit /b 1
)
echo   instance: !INSTANCE!
echo.
echo   Restarting influencrm-secrets then influencrm. The API will be briefly
echo   unavailable while the containers come back.
echo.

REM `systemctl restart influencrm` re-runs ExecStartPre (a compose pull) and
REM then `up -d`, which recreates the containers against the NEW env file. The
REM secrets unit must go first: it is what rewrites that file.
for /f "usebackq delims=" %%C in (`aws ssm send-command --instance-ids !INSTANCE! --document-name "AWS-RunShellScript" --parameters "commands=[\"systemctl restart influencrm-secrets\",\"systemctl restart influencrm\",\"sleep 20\",\"grep -c = /run/influencrm/platform.env\",\"docker ps --format '{{.Names}} {{.Status}}' ^| head -20\"]" --region %REGION% --profile %PROFILE% --query "Command.CommandId" --output text 2^>nul`) do set "CMD=%%C"

if not defined CMD (
    echo   ERROR: could not send the SSM command. Is SSM Agent running on the box?
    exit /b 1
)
echo   ssm command: !CMD!
echo   waiting...

timeout /t 45 /nobreak >nul

aws ssm get-command-invocation --command-id !CMD! --instance-id !INSTANCE! --region %REGION% --profile %PROFILE% --query "{status:Status,out:StandardOutputContent,err:StandardErrorContent}" --output text

echo.
echo   Then confirm the model is actually reachable now:
echo     curl -s https://api.tejdux.com/health
echo   and re-run tests\e2e\probe-op25-verify-sources.mjs to close OP-25.
echo.

endlocal
