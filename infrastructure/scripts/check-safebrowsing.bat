@echo off
setlocal enabledelayedexpansion

REM ===========================================================================
REM  Has the Safe Browsing flag lifted?
REM
REM  On 2026-08-16 Google flagged tejdux.com as "Social engineering" — pages that
REM  "attempt to trick users into doing something dangerous". It is a false
REM  positive with a specific cause: the OAuth callback accepts a Facebook
REM  authorization code and 302s to a login page on another host, on a domain
REM  registered days earlier. That shape is indistinguishable from an OAuth
REM  phishing kit to a classifier that cannot read intent.
REM
REM  A review was requested the same day. This script exists because the answer
REM  cannot be read off an HTTP status: every one of these hosts returns 200 (or
REM  a correct 401) while Chrome is still showing a full-page red interstitial.
REM  The flag lives in the browser layer, not the response.
REM
REM  WHY IT MATTERS FOR THE SCREENCAST. A Meta reviewer clicking "Continue with
REM  Facebook" hits the same interstitial. Recording before it clears puts a
REM  security warning in the middle of the one flow the review is about.
REM ===========================================================================

echo.
echo ============================================================
echo   Safe Browsing status  --  tejdux.com
echo ============================================================
echo.

REM Reachability first. It proves nothing about the flag, but a host that is down
REM is a different problem and should not be mistaken for a warning.
echo   Reachability (not the flag — see below):
for %%D in (tejdux.com www.tejdux.com api.tejdux.com) do (
    for /f "tokens=*" %%C in ('curl -s -o nul -w "%%{http_code}" --max-time 20 "https://%%D/" 2^>nul') do (
        echo     %%D  HTTP %%C
    )
)
echo.
echo   api.tejdux.com returning 401 is CORRECT — it is an authenticated API,
echo   not a page. Only a connection failure would be a problem there.
echo.

echo ============================================================
echo   The actual check
echo ============================================================
echo.
echo Safe Browsing is enforced by the browser, so no HTTP request from a script
echo can see it. Two ways to read the real status:
echo.
echo   1. TRANSPARENCY REPORT — no login. Opening now in your browser.
echo      "No unsafe content found" means it has cleared.
echo.
echo   2. A PRIVATE WINDOW at https://www.tejdux.com/ — if no red interstitial
echo      appears, it has lifted for real traffic. This is the one that counts,
echo      because it is what a Meta reviewer will experience.
echo.
echo   Search Console shows the authoritative state under Security Issues, but it
echo   can lag the actual lift by hours in both directions.
echo.

start "" "https://transparencyreport.google.com/safe-browsing/search?url=tejdux.com"
ping -n 3 127.0.0.1 >nul 2>&1
start "" "https://transparencyreport.google.com/safe-browsing/search?url=api.tejdux.com"

echo   Opened both. Read them, then:
echo.
echo     CLEARED  -^> pre-flight in docs\screencast-script.md, then record.
echo     STILL FLAGGED -^> wait. Do NOT resubmit the review — a second request
echo                      while one is pending resets your place in the queue.
echo.
echo   Reviews usually clear in 24-72 hours. If it is still flagged after three
echo   days, check Search Console for a DENIED review: a denial means Google
echo   found something beyond the blank-body issue that was already fixed, and
echo   that is worth investigating before requesting another.
echo.

endlocal
