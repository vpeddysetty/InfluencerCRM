#!/usr/bin/env bash
# Wait for the API to come back after a deploy — and GIVE UP, loudly, if it does not.
#
#   ./infrastructure/scripts/wait-for-api.sh                 # defaults to api.tejdux.com
#   ./infrastructure/scripts/wait-for-api.sh https://... 900 # explicit host and timeout
#
# WHY THIS EXISTS AS A SCRIPT AND NOT A ONE-LINER.
#
#   The one-liner it replaces was `until curl -s ... = 200; do sleep 20; done`, and it has no
#   ceiling: a stack that will never start looks exactly like one that is still booting. On
#   2026-08-31 that difference cost two hours of downtime. The ASG reported the refresh Successful,
#   the instance was healthy, `docker ps` was empty, and the loop kept polling — so nothing said the
#   deploy had failed until somebody asked.
#
#   The failure it has to survive is a `docker compose pull` timing out against ECR during boot.
#   The boot script now retries that three times; this is the second half of the same fix, on the
#   deploying side rather than the booting side.
#
# WHY IT WAITS FOR CONSECUTIVE SUCCESSES.
#
#   During a roll the old instance can still answer while the new one is coming up, so a single 200
#   proves nothing about what is about to serve traffic. Three in a row across the poll interval is
#   enough to tell "back" from "not gone yet".
set -uo pipefail

URL="${1:-https://api.tejdux.com/health}"
TIMEOUT="${2:-900}"     # 15 minutes: a cold instance pulling twelve images is slow, but not this slow.
INTERVAL=15
NEEDED=3

started=$(date +%s)
streak=0

printf 'waiting for %s (up to %ss)\n' "$URL" "$TIMEOUT"

while :; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$URL" 2>/dev/null || echo 000)

    if [ "$code" = "200" ]; then
        streak=$((streak + 1))
        if [ "$streak" -ge "$NEEDED" ]; then
            printf 'API healthy after %ss\n' "$(( $(date +%s) - started ))"
            exit 0
        fi
    else
        # Reset rather than decrement: two 200s either side of a 502 is not "nearly healthy", it is
        # a stack still deciding, and treating it as progress is how a flapping deploy passes.
        streak=0
    fi

    elapsed=$(( $(date +%s) - started ))
    if [ "$elapsed" -ge "$TIMEOUT" ]; then
        printf '\nFAILED: %s did not return 200 within %ss (last code %s)\n' "$URL" "$TIMEOUT" "$code" >&2
        printf 'The instance may be healthy while the platform is down — the ASG checks the box,\n' >&2
        printf 'not the containers. Check, in this order:\n' >&2
        printf '  1. systemctl is-active influencrm      (inactive => the unit never started)\n' >&2
        printf '  2. journalctl -u influencrm -n 40      (an ECR pull timeout is the usual cause)\n' >&2
        printf '  3. docker ps                           (empty => nothing is serving)\n' >&2
        printf 'Recovery is usually `systemctl start influencrm`, which succeeds if the pull was\n' >&2
        printf 'the only problem.\n' >&2
        exit 1
    fi

    printf '  %ss elapsed, last code %s\n' "$elapsed" "$code"
    sleep "$INTERVAL"
done
