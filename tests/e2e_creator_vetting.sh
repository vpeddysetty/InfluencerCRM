#!/usr/bin/env bash
# Per-brand creator vetting — roadmap Phase C2 (docs/landing-page-builder-roadmap.md §5).
#
# This phase decides whether a real person is rejected from a brand's programme, so most of
# these assertions are about what the system will NOT do:
#
#   V2  — a rule cannot approve, even when it asks to (roadmap #5)
#   V3  — silence is not endorsement: an unmatched creator goes to a human, not to approved
#   V9  — a rule cannot overturn a human decision
#   V11 — a missing metric never matches, so a failed lookup is not a rejection
#
# The asymmetry is deliberate and worth restating: rejection is reversible and at worst costs a
# brand one partnership, while approval grants access to briefs, assets and eventually money —
# and is what a brand will be asked to justify.
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
      -d "$b" -o "$SP/.vbody" -w '%{http_code}' > "$SP/.vcode"
  else
    curl -s -m 60 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.vbody" -w '%{http_code}' > "$SP/.vcode"
  fi
  cat "$SP/.vbody"
}
st() { cat "$SP/.vcode" 2>/dev/null; }

EMAIL="vt.brand.$STAMP@example.test"

echo "################ setup ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"VT Brand\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
rec SETUP nonempty "$([[ -n "$TOKEN" ]] && echo nonempty || echo empty)" "brand signed up"

echo "################ V1: rules are per brand and ordered ################"
R1=$(api POST /api/vetting-rules "$TOKEN" \
  '{"name":"No gambling","position":0,"action":"reject","reason":"Brand safety: gambling content",
    "condition":{"attribute":"risk_flags","operator":"contains","value":"gambling"}}')
rec V1 201 "$(st)" "reject rule created"
api POST /api/vetting-rules "$TOKEN" \
  '{"name":"Too small","position":1,"action":"reject","reason":"Under 5,000 followers",
    "condition":{"attribute":"follower_count","operator":"lt","value":5000}}' > /dev/null
rec V1b 201 "$(st)" "second rule created"

RULES=$(api GET /api/vetting-rules "$TOKEN")
rec V1c 2 "$(echo "$RULES" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" "both rules listed"
rec V1d "No gambling" "$(echo "$RULES" | python -c "import sys,json;print(json.load(sys.stdin)[0]['name'])")" \
    "returned in position order — first match wins, so precedence must be the brand's choice"

echo "################ V2: a rule can never approve ################"
# The decision this whole phase rests on. Refused at the API with an explanation, and the DB
# check constraint would refuse it too — two layers, because this is the one automated action
# that would be expensive to get wrong.
REFUSED=$(api POST /api/vetting-rules "$TOKEN" \
  '{"name":"Auto approve","position":2,"action":"approve",
    "condition":{"attribute":"follower_count","operator":"gt","value":1000}}')
rec V2 400 "$(st)" "a rule with action=approve is refused"
rec V2b true "$(echo "$REFUSED" | grep -qi "cannot approve" && echo true || echo false)" \
    "and the refusal explains why rather than just failing validation"

echo "################ V3-V5: rules run on lead creation (C2.3) ################"
RISKY=$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@bet_master"}')
rec V3 201 "$(st)" "lead captured"
RISKY_ID=$(jqv "$RISKY" "['id']")
rec V3b rejected "$(jqv "$RISKY" "['vettingStatus']")" \
    "a gambling creator is auto-rejected without anyone touching it"

CLEAN=$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@glow_daily"}')
CLEAN_ID=$(jqv "$CLEAN" "['id']")
rec V4 under_review "$(jqv "$CLEAN" "['vettingStatus']")" \
    "a clean creator goes to a HUMAN, never to approved — silence is not endorsement"

echo "################ V5: the audit trail answers 'why' (C2.5) ################"
HISTORY=$(api GET "/api/creators/$RISKY_ID/vetting/history" "$TOKEN")
rec V5 1 "$(echo "$HISTORY" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" "one decision recorded"
rec V5b "No gambling" "$(echo "$HISTORY" | python -c "import sys,json;print(json.load(sys.stdin)[0]['ruleName'])")" \
    "the event names the rule that fired"
rec V5c true "$(echo "$HISTORY" | grep -q "Brand safety" && echo true || echo false)" \
    "and carries the reason a creator would be shown"
SNAP=$($PG -c "select snapshot->>'followerCount' is not null from creator.vetting_events where creator_id='$RISKY_ID' limit 1;" | tr -d '\r')
rec V5d t "$SNAP" \
    "the event snapshots the creator as they were — a later refresh must not make the decision look arbitrary"

echo "################ V6: the review queue (C2.6) ################"
QUEUE=$(api GET /api/vetting/queue "$TOKEN")
rec V6 1 "$(echo "$QUEUE" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "only the unresolved creator is queued; the rejected one is not"

echo "################ V7: the dry-run (C2.4) ################"
# The roadmap calls this out as mattering more than it looks: a rule that would silently reject
# most of a brand's roster should be discovered BEFORE it is switched on.
DRY=$(api POST /api/vetting-rules/dry-run "$TOKEN" \
  '{"condition":{"attribute":"follower_count","operator":"lt","value":300000}}')
rec V7 200 "$(st)" "dry-run accepted"
rec V7b true "$(echo "$DRY" | python -c "
import sys,json;d=json.load(sys.stdin)
print('true' if d['totalCreators']>0 and 'matchedPercent' in d else 'false')")" \
    "reports totals AND a percentage — '66% of your roster' is what actually stops someone"
DRY_BEFORE=$($PG -c "select count(*) from creator.vetting_events where brand_id in (select brand_id from creator.creators where id='$CLEAN_ID');" | tr -d '\r')
api POST /api/vetting-rules/dry-run "$TOKEN" \
  '{"condition":{"attribute":"follower_count","operator":"lt","value":300000}}' > /dev/null
DRY_AFTER=$($PG -c "select count(*) from creator.vetting_events where brand_id in (select brand_id from creator.creators where id='$CLEAN_ID');" | tr -d '\r')
rec V7c "$DRY_BEFORE" "$DRY_AFTER" "a dry-run changes NOTHING — no statuses moved, no events written"

echo "################ V8-V9: only a human approves, and rules cannot overturn them ################"
APPROVED=$(api PUT "/api/creators/$CLEAN_ID/vetting" "$TOKEN" \
  '{"status":"approved","reason":"Checked manually, good fit"}')
rec V8 200 "$(st)" "a human can approve"
rec V8b approved "$(jqv "$APPROVED" "['vettingStatus']")" "status is approved"
rec V8c true "$([[ -n "$(jqv "$APPROVED" "['vettingDecidedByUserId']")" ]] && echo true || echo false)" \
    "the approval carries a user id — an approval always has someone's name against it"

api POST "/api/creators/$CLEAN_ID/vetting/evaluate" "$TOKEN" > /dev/null
AFTER=$(api GET "/api/creators/$CLEAN_ID/vetting/history" "$TOKEN")
STILL=$($PG -c "select vetting_status from creator.creators where id='$CLEAN_ID';" | tr -d '\r')
rec V9 approved "$STILL" \
    "re-running the rules does NOT overturn a human decision"

echo "################ V10: a disabled rule stops firing ################"
RULE_ID=$(jqv "$RULES" "[0]['id']")
$PG -c "update creator.vetting_rules set enabled=false where id='$RULE_ID';" > /dev/null
ANOTHER=$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@casino_night"}')
rec V10 under_review "$(jqv "$ANOTHER" "['vettingStatus']")" \
    "with the gambling rule disabled, the same kind of creator now reaches a human instead"
$PG -c "update creator.vetting_rules set enabled=true where id='$RULE_ID';" > /dev/null

echo "################ V11: a missing metric never matches ################"
# C.6 resurfacing: an unresolved handle leaves follower_count null. Treating null as 0 would
# make "follower_count < 5000" reject every creator whose platform lookup failed.
UNKNOWN=$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@unknown_person","name":"Manual"}')
rec V11 under_review "$(jqv "$UNKNOWN" "['vettingStatus']")" \
    "a creator with NO metrics is queued, not rejected by the follower-count rule"

echo "################ V12: quality reports (C2.8) ################"
REPORT=$(api POST "/api/creators/$RISKY_ID/quality-report" "$TOKEN" \
  '{"category":"fake_followers","detail":"Engagement looks bought"}')
rec V12 201 "$(st)" "a brand can dispute audience quality"
SNAPSHOT=$($PG -c "select signal_snapshot->>'followerCount' is not null from creator.creator_quality_reports where creator_id='$RISKY_ID' limit 1;" | tr -d '\r')
rec V12b t "$SNAPSHOT" \
    "the report snapshots what OUR signal said — that is what makes it a labelled example"

echo "################ V13: tenancy ################"
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"vt.other.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"VT Other\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
OTHER_RULES=$(api GET /api/vetting-rules "$OTHER")
rec V13 0 "$(echo "$OTHER_RULES" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "another brand sees none of our rules — vetting policy is per brand"
api PUT "/api/creators/$CLEAN_ID/vetting" "$OTHER" '{"status":"rejected"}' > /dev/null
rec V13b 404 "$(st)" "another brand cannot change our creator's vetting status"
api GET "/api/creators/$CLEAN_ID/vetting/history" "$OTHER" > /dev/null
rec V13c 404 "$(st)" "nor read our audit trail"
STILL2=$($PG -c "select vetting_status from creator.creators where id='$CLEAN_ID';" | tr -d '\r')
rec V13d approved "$STILL2" "and our creator is untouched after those attempts"

echo "################ V14: an invalid status is refused ################"
api PUT "/api/creators/$CLEAN_ID/vetting" "$TOKEN" '{"status":"blessed"}' > /dev/null
rec V14 400 "$(st)" "an unknown vetting status is rejected"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL rejected=$RISKY_ID approved=$CLEAN_ID"
