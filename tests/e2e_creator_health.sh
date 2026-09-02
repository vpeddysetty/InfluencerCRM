#!/usr/bin/env bash
# Creator health monitoring — roadmap Phase C3 (docs/landing-page-builder-roadmap.md §5).
#
# Vetting is a gate; this is the relationship after it. A creator approved at 50k followers who
# quietly declines to 5k is a live problem, and before this nothing would notice.
#
# The assertions that matter most are again about what the system will NOT do:
#
#   H3  — the first reading is a baseline, not a decline (no alert on day one)
#   H7  — a refresh does not re-raise an alert that is already open (alert fatigue)
#   H8  — an alert never changes a creator's standing (roadmap #13)
#   H9  — an alert cannot be resolved by "revoking" anything
#
# Roadmap #13, decided 2026-08-02: a decline raises a flag and a HUMAN decides whether to keep,
# pause or end the relationship. A creator mid-campaign has delivered work, may be owed money,
# and may have declined other offers to take this one.
BFF=${BFF:-http://localhost:8081}
SP="${E2E_WORKDIR:-$(dirname "$0")}"
PG="docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A"
STAMP=$(date +%s)
PASS=0; FAIL=0
declare -a FAILED

rec() {
  if [[ ",$2," == *",$3,"* ]]; then
    PASS=$((PASS+1)); echo "PASS | $1 | $3 | $4"
  else
    FAIL=$((FAIL+1)); FAILED+=("$1 (exp $2 got $3): $4"); echo "FAIL | $1 | expected=$2 actual=$3 | $4"
  fi
}

jqv() { echo "$1" | python -c "import sys,json;d=json.load(sys.stdin);print(d$2 if d else '')" 2>/dev/null; }

api() { # api <method> <path> <token> [body]
  local m="$1" p="$2" t="$3" b="$4"
  if [[ -n "$b" ]]; then
    curl -s -m 60 -X "$m" "$BFF$p" -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
      -d "$b" -o "$SP/.hbody" -w '%{http_code}' > "$SP/.hcode"
  else
    curl -s -m 60 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.hbody" -w '%{http_code}' > "$SP/.hcode"
  fi
  cat "$SP/.hbody"
}
st() { cat "$SP/.hcode" 2>/dev/null; }

# The mock adapter is deterministic, so a handle always returns the same figures. A decline is
# therefore staged by moving the BASELINE snapshot rather than the current reading — which is
# also closer to reality, where the past is fixed and the present moves.
stage_decline() { $PG -c "update creator.creator_metric_snapshots set follower_count=$2, engagement_rate=$3 where creator_id='$1';" > /dev/null; }

EMAIL="ch.brand.$STAMP@example.test"

echo "################ setup ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"CH Brand\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
CREATOR=$(jqv "$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@glow_daily"}')" "['id']")
rec SETUP nonempty "$([[ -n "$CREATOR" ]] && echo nonempty || echo empty)" "creator captured"

echo "################ H1: thresholds default before a brand sets any ################"
THRESHOLDS=$(api GET /api/health-thresholds "$TOKEN")
rec H1 200 "$(st)" "thresholds readable"
rec H1b 20.0 "$(jqv "$THRESHOLDS" "['followerDropPct']")" \
    "defaults apply — useful alerts before a brand has thought about thresholds"
rec H1c true "$(jqv "$THRESHOLDS" "['isDefault']" | tr 'A-Z' 'a-z')" "and are labelled as defaults"

SAVED=$(api POST /api/health-thresholds "$TOKEN" \
  '{"followerDropPct":15.0,"engagementDropPct":25.0,"inactiveDays":30,"windowDays":30}')
rec H1d 200 "$(st)" "a brand can set its own — a 20% drop means different things at 5k and 5M"

echo "################ H2-H3: the first reading is a baseline ################"
FIRST=$(api POST "/api/creators/$CREATOR/health/refresh" "$TOKEN")
rec H2 200 "$(st)" "refresh accepted"
rec H2b true "$(jqv "$FIRST" "['refreshed']" | tr 'A-Z' 'a-z')" "metrics were re-read"
rec H3 0 "$(echo "$FIRST" | python -c "import sys,json;print(len(json.load(sys.stdin)['alerts']))")" \
    "NO alert on the first reading — a baseline is not a decline"

SNAPSHOTS=$($PG -c "select count(*) from creator.creator_metric_snapshots where creator_id='$CREATOR';" | tr -d '\r')
rec H3b 1 "$SNAPSHOTS" "one snapshot written (C3.2)"

echo "################ H4: a decline raises alerts ################"
stage_decline "$CREATOR" 500000 8.00
SECOND=$(api POST "/api/creators/$CREATOR/health/refresh" "$TOKEN")
ALERT_COUNT=$(echo "$SECOND" | python -c "import sys,json;print(len(json.load(sys.stdin)['alerts']))")
rec H4 2 "$ALERT_COUNT" "both a follower drop and an engagement drop are detected"
rec H4b true "$(echo "$SECOND" | grep -q "Followers down" && echo true || echo false)" \
    "the summary states the numbers — an alert a brand cannot check is one they learn to ignore"

echo "################ H5: snapshots accumulate, they do not overwrite ################"
SNAP2=$($PG -c "select count(*) from creator.creator_metric_snapshots where creator_id='$CREATOR';" | tr -d '\r')
rec H5 2,3 "$SNAP2" "history grows with each refresh — without it there is no trend"

HISTORY=$(api GET "/api/creators/$CREATOR/health/history" "$TOKEN")
rec H5b true "$(echo "$HISTORY" | python -c "import sys,json;print('true' if len(json.load(sys.stdin))>=2 else 'false')")" \
    "the trend view returns the series, not just the current number (C3.6)"

echo "################ H6: growth is not a decline ################"
# A -40% "drop" in a digest would read as a fall to anyone scanning it, so growth must produce
# no alert at all rather than a negative one.
GROW_CREATOR=$(jqv "$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@fit_mike"}')" "['id']")
api POST "/api/creators/$GROW_CREATOR/health/refresh" "$TOKEN" > /dev/null
stage_decline "$GROW_CREATOR" 1000 0.50
GROWN=$(api POST "/api/creators/$GROW_CREATOR/health/refresh" "$TOKEN")
GROWN_ALERTS=$(echo "$GROWN" | python -c "
import sys,json
print(len([a for a in json.load(sys.stdin)['alerts'] if a['alertType'] in ('follower_drop','engagement_drop')]))")
rec H6 0 "$GROWN_ALERTS" "a creator who GREW raises no decline alert"

echo "################ H7: alert fatigue ################"
# An alert nobody reads is worse than no alert, because it looks like coverage. A weekly
# refresh must not re-raise a warning that is already sitting open.
stage_decline "$CREATOR" 500000 8.00
api POST "/api/creators/$CREATOR/health/refresh" "$TOKEN" > /dev/null
stage_decline "$CREATOR" 500000 8.00
api POST "/api/creators/$CREATOR/health/refresh" "$TOKEN" > /dev/null
OPEN_FOLLOWER=$($PG -c "select count(*) from creator.creator_health_alerts where creator_id='$CREATOR' and alert_type='follower_drop';" | tr -d '\r')
rec H7 1 "$OPEN_FOLLOWER" "three refreshes, still ONE follower-drop alert"

echo "################ H8: an alert never changes the creator's standing ################"
STANDING=$($PG -c "select vetting_status from creator.creators where id='$CREATOR';" | tr -d '\r')
rec H8 under_review "$STANDING" \
    "vetting status is untouched by alerts — nothing auto-revokes (roadmap #13)"

echo "################ H9: an alert cannot revoke ################"
ALERT_ID=$($PG -c "select id from creator.creator_health_alerts where creator_id='$CREATOR' and alert_type='follower_drop' limit 1;" | tr -d '\r')
REFUSED=$(api PUT "/api/health-alerts/$ALERT_ID" "$TOKEN" '{"status":"revoked"}')
rec H9 400 "$(st)" "there is no revoke status"
rec H9b true "$(echo "$REFUSED" | grep -qi "decision for a person" && echo true || echo false)" \
    "and the refusal says why rather than just failing validation"

echo "################ H10: acknowledge, snooze, act (C3.5) ################"
ACK=$(api PUT "/api/health-alerts/$ALERT_ID" "$TOKEN" '{"status":"acknowledged","note":"Seasonal dip, keeping them"}')
rec H10 200 "$(st)" "an alert can be acknowledged"
rec H10b acknowledged "$(jqv "$ACK" "['status']")" "status recorded"
rec H10c true "$([[ -n "$(jqv "$ACK" "['resolvedByUserId']")" ]] && echo true || echo false)" \
    "with a user id — 'we saw it and kept them' is a decision worth recording too"

SNOOZE_ID=$($PG -c "select id from creator.creator_health_alerts where creator_id='$CREATOR' and alert_type='engagement_drop' limit 1;" | tr -d '\r')
SNOOZED=$(api PUT "/api/health-alerts/$SNOOZE_ID" "$TOKEN" '{"status":"snoozed"}')
rec H10d snoozed "$(jqv "$SNOOZED" "['status']")" "or snoozed"
rec H10e true "$([[ -n "$(jqv "$SNOOZED" "['snoozedUntil']")" ]] && echo true || echo false)" \
    "with an end date — a snooze with no end is a dismissal wearing a different name"

echo "################ H11: acknowledging frees the slot for a future alert ################"
# The partial unique index is on OPEN alerts only, so once handled the same condition can alert
# again later. Otherwise a creator could only ever be flagged once.
stage_decline "$CREATOR" 900000 8.00
api POST "/api/creators/$CREATOR/health/refresh" "$TOKEN" > /dev/null
TOTAL_FOLLOWER=$($PG -c "select count(*) from creator.creator_health_alerts where creator_id='$CREATOR' and alert_type='follower_drop';" | tr -d '\r')
rec H11 2 "$TOTAL_FOLLOWER" "a NEW alert is raised after the previous one was handled"

echo "################ H12: the alert queue ################"
QUEUE=$(api GET "/api/health-alerts?status=open" "$TOKEN")
rec H12 200 "$(st)" "open alerts are listable for a digest"

echo "################ H13: an unresolvable handle is not a decline ################"
# Alerting here would punish a private account or an expired token rather than a real fall.
GHOST=$(jqv "$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@unknown_person","name":"Ghost"}')" "['id']")
GHOST_REFRESH=$(api POST "/api/creators/$GHOST/health/refresh" "$TOKEN")
rec H13 false "$(jqv "$GHOST_REFRESH" "['refreshed']" | tr 'A-Z' 'a-z')" "the refresh reports it could not read"
rec H13b 0 "$(echo "$GHOST_REFRESH" | python -c "import sys,json;print(len(json.load(sys.stdin)['alerts']))")" \
    "and raises NO alert — unreadable is not declining"

echo "################ H14: tenancy ################"
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"ch.other.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"CH Other\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
rec H14 0 "$(api GET /api/health-alerts "$OTHER" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "another brand sees none of our alerts"
api POST "/api/creators/$CREATOR/health/refresh" "$OTHER" > /dev/null
rec H14b 404 "$(st)" "nor can it refresh our creator"
api GET "/api/creators/$CREATOR/health/history" "$OTHER" > /dev/null
rec H14c 404 "$(st)" "nor read our metric history"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL creator=$CREATOR"
