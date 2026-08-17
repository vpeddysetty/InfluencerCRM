@echo off
setlocal enabledelayedexpansion

REM ===========================================================================
REM  Ship v1.0.19: the release that actually contains the Instagram adapter.
REM
REM  WHY THIS RELEASE EXISTS. Every handle lookup showed a "Simulated" badge even
REM  though the token, the account id, the IAM policy and the compose file were
REM  all correct. The running image was the problem: v1.0.18 was pushed at
REM  13:22 on 2026-08-15 and the commit adding the Instagram property bindings
REM  landed at 15:49 -- two and a half hours later. The container had no
REM  `web-experience.creators.instagram-*` properties at all, so Spring bound
REM  empty strings, isConfigured() returned false, and every lookup fell back to
REM  the simulation no matter what environment variables it was handed.
REM
REM  Nothing about that is visible from outside: a simulated lookup returns a
REM  plausible follower count and a 200. The badge is the only thing that ever
REM  said otherwise, which is why the last step here is to go and read it.
REM
REM  THREE STEPS, IN THIS ORDER:
REM    1. terraform apply   -- points the compose file at v1.0.19
REM    2. deploy the shell  -- the UI half of the same release
REM    3. instance refresh  -- what actually makes 1 live
REM
REM  Order matters. Refreshing before the apply boots the instance on the OLD
REM  tag and wastes the outage. This script enforces the order; it does not ask
REM  you to remember it.
REM
REM  The refresh is deliberately NOT run from here -- see the end of the file.
REM ===========================================================================

set "AWS_PROFILE=tejdux"
set "AWS_REGION=us-east-1"
set "TF=C:\AI\InfluencerCRM\.tools\terraform.exe"
set "TFDIR=C:\AI\InfluencerCRM\infrastructure\test\terraform"
set "UIDIR=C:\AI\InfluencerCRM\InfluencerUI"
set "BUCKET=influencrm-prod-ui-099933382956"
set "SHELL_DIST=E3GALL4Q8H611Y"
set "PLAN=tfplan-v11019"

echo.
echo ============================================================
echo   Deploy v1.0.19  --  Instagram adapter + creator fixes
echo ============================================================
echo.

REM --- preflight ------------------------------------------------------------
where aws >nul 2>&1
if errorlevel 1 ( echo ERROR: AWS CLI not on PATH. & goto :fail )
if not exist "%TF%" ( echo ERROR: terraform not at %TF% & goto :fail )

for /f "tokens=*" %%A in ('aws sts get-caller-identity --profile %AWS_PROFILE% --query Account --output text 2^>nul') do set "ACCT=%%A"
if not "!ACCT!"=="099933382956" (
    echo ERROR: profile "%AWS_PROFILE%" resolves to account "!ACCT!", expected 099933382956.
    goto :fail
)
echo   account !ACCT! OK

REM The plan is consumed by `terraform apply` and cannot be replayed. If a previous
REM run already applied it, this file is gone and the plan must be regenerated --
REM which is correct, because a stale plan should never be applied blind.
if not exist "%TFDIR%\%PLAN%" (
    echo.
    echo   No saved plan at %TFDIR%\%PLAN%
    echo   It was either already applied or never generated. Regenerate with:
    echo.
    echo     cd %TFDIR%
    echo     "%TF%" plan -lock=false -var-file=prod.tfvars -var "image_tag=v1.0.19" -out=%PLAN%
    echo.
    echo   Review it before applying: expect "0 to add, 2 to change, 0 to destroy".
    goto :fail
)
echo   saved plan present

REM Refuse to ship a UI that was never built. An empty dist/ would sync a deletion
REM of the live site rather than a release of it -- `aws s3 sync --delete` is happy
REM to remove everything if given nothing.
if not exist "%UIDIR%\dist\index.html" (
    echo.
    echo ERROR: no build at %UIDIR%\dist\index.html
    echo        Run `npm run build` in %UIDIR% first. Syncing an empty dist with
    echo        --delete would empty the live bucket.
    goto :fail
)
echo   shell build present
echo.

echo   This applies to PRODUCTION and then serves a new UI.
echo   It does NOT restart the instance -- step 3 is separate and is what
echo   causes the outage.
echo.
set /p "GO=Type DEPLOY to proceed, anything else to cancel: "
if /i not "!GO!"=="DEPLOY" ( echo. & echo Cancelled. Nothing changed. & goto :end )

REM --- 1. terraform ---------------------------------------------------------
echo.
echo ==^> 1/2  terraform apply
cd /d "%TFDIR%"
"%TF%" apply -no-color -lock=false "%PLAN%"
if errorlevel 1 ( echo. & echo ERROR: terraform apply failed. Nothing further attempted. & goto :fail )
echo   compose file now points at v1.0.19

REM --- 2. shell UI ----------------------------------------------------------
REM Two passes: hashed assets are immutable and cached for a year; index.html keeps
REM its name across releases and must never be cached, or browsers keep loading the
REM previous release's hashed assets, which --delete has just removed.
echo.
echo ==^> 2/2  shell UI
cd /d "%UIDIR%"

aws s3 sync dist/ "s3://%BUCKET%/shell/" --region %AWS_REGION% --delete ^
    --exclude "index.html" --cache-control "public,max-age=31536000,immutable" --only-show-errors
if errorlevel 1 ( echo ERROR: asset sync failed. & goto :fail )

aws s3 cp dist/index.html "s3://%BUCKET%/shell/index.html" --region %AWS_REGION% ^
    --cache-control "no-cache,must-revalidate" --content-type "text/html" --only-show-errors
if errorlevel 1 ( echo ERROR: index.html upload failed. & goto :fail )

for /f "tokens=*" %%I in ('aws cloudfront create-invalidation --distribution-id %SHELL_DIST% --paths "/index.html" "/" --region %AWS_REGION% --query Invalidation.Id --output text 2^>nul') do set "INV=%%I"
echo   uploaded and invalidated (!INV!)

REM --- done -----------------------------------------------------------------
echo.
echo ============================================================
echo   Applied and deployed. NOT YET LIVE.
echo ============================================================
echo.
echo The instance is still running the previous image. Terraform updated the
echo launch template; only an instance refresh moves the running box onto it.
echo.
echo   Step 3:  infrastructure\scripts\refresh-prod-instance.bat
echo.
echo That step logs everyone out and takes the API down for a few minutes, so it
echo is left as a deliberate, separate action rather than something this script
echo does while you are reading its output.
echo.
echo Afterwards, verify with:
echo.
echo   infrastructure\scripts\verify-instagram.bat
echo.
goto :end

:fail
echo.
exit /b 1

:end
endlocal
