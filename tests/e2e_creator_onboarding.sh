#!/usr/bin/env bash
# Creator onboarding — roadmap Phase C (docs/landing-page-builder-roadmap.md §5).
#
# Phase C rests on one decision (roadmap #4): facts come from platform APIs, the model only
# classifies. C3-C5 are the assertions that enforce it — a metric always carries provenance,
# a classification carries its own, and the two never merge. If those ever pass silently
# while the code stops separating them, a brand would be shown a guessed follower count as
# though it were measured.
#
# The platform adapter is MOCKED here: the app registrations are not approved yet (roadmap
# §10.1 — Meta 2-4 weeks, TikTok 5-10 business days, none of it code). The mock reports
# source="mock" and C3 asserts exactly that. A mock claiming platform_api would be worse than
# no adapter at all.
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
      -d "$b" -o "$SP/.obody" -w '%{http_code}' > "$SP/.ocode"
  else
    curl -s -m 60 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.obody" -w '%{http_code}' > "$SP/.ocode"
  fi
  cat "$SP/.obody"
}
st() { cat "$SP/.ocode" 2>/dev/null; }

pub() { # pub <path> <body> — no auth header at all
  curl -s -m 60 -X POST "$BFF$1" -H "Content-Type: application/json" -d "$2" \
    -o "$SP/.pbody" -w '%{http_code}' > "$SP/.pcode"
  cat "$SP/.pbody"
}
pst() { cat "$SP/.pcode" 2>/dev/null; }

EMAIL="cr.brand.$STAMP@example.test"

echo "################ setup ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"CR Brand\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
BRAND=$(curl -s -m 30 "$BFF/api/creators" -H "Authorization: Bearer $TOKEN" > /dev/null; echo ok)
rec SETUP nonempty "$([[ -n "$TOKEN" ]] && echo nonempty || echo empty)" "brand signed up"

echo "################ C1: resolve a handle without persisting ################"
RESOLVED=$(api POST /api/creators/resolve-handle "$TOKEN" '{"platform":"instagram","handle":"@glow_daily"}')
rec C1 200 "$(st)" "handle resolved"
rec C1b true "$(jqv "$RESOLVED" "['resolved']" | tr 'A-Z' 'a-z')" "profile found"
BEFORE=$($PG -c "select count(*) from creator.creators where handle='glow_daily';" | tr -d '\r')
rec C1c 0 "$BEFORE" "resolve-handle persisted NOTHING — looking is not saving"

echo "################ C2: the adapter is deterministic ################"
# Fixtures can only assert exact metrics if the same handle always yields the same numbers.
AGAIN=$(api POST /api/creators/resolve-handle "$TOKEN" '{"platform":"instagram","handle":"@glow_daily"}')
rec C2 "$(jqv "$RESOLVED" "['followerCount']")" "$(jqv "$AGAIN" "['followerCount']")" \
    "same handle, same follower count on a second call"

echo "################ C3: metrics carry honest provenance ################"
# The load-bearing assertion of this phase.
rec C3 mock "$(jqv "$RESOLVED" "['metricsSource']")" \
    "metrics are labelled 'mock' — a simulated adapter must never claim platform_api"
rec C3b nonempty "$([[ -n "$(jqv "$RESOLVED" "['metricsFetchedAt']")" ]] && echo nonempty || echo empty)" \
    "metrics carry a fetch time — a metric with no timestamp cannot be judged current"

echo "################ C4: classification is separate from metrics ################"
rec C4 beauty "$(jqv "$RESOLVED" "['classification']['niche']")" "classified from captions"
rec C4b llm,heuristic "$(jqv "$RESOLVED" "['classification']['source']")" \
    "classification reports its OWN source, distinct from metricsSource"

echo "################ C5: the model never produces a metric ################"
# The failure this guards against: an LLM asked for a follower count returns a confident,
# plausible, wrong number and a brand spends against it.
CLASS_KEYS=$(echo "$RESOLVED" | python -c "
import sys,json
d=json.load(sys.stdin).get('classification') or {}
metric_keys=[k for k in d if k.lower() in
  ('followercount','follower_count','engagementrate','engagement_rate','averageviews','average_views','reach','impressions')]
print(','.join(metric_keys) if metric_keys else 'none')" 2>/dev/null)
rec C5 none "$CLASS_KEYS" "the classification block contains NO metric fields"

echo "################ C6: risk flags come from the captions ################"
RISKY=$(api POST /api/creators/resolve-handle "$TOKEN" '{"platform":"instagram","handle":"@casino_king"}')
RISK_FLAGS=$(jqv "$RISKY" "['classification']['risk_flags']")
rec C6 true "$(echo "$RISK_FLAGS" | grep -q 'gambling' && echo true || echo false)" \
    "a gambling creator is flagged: $RISK_FLAGS"
CLEAN_FLAGS=$(jqv "$RESOLVED" "['classification']['risk_flags']")
rec C6b true "$([[ "$CLEAN_FLAGS" == "[]" ]] && echo true || echo false)" \
    "a clean creator is NOT flagged — the classifier discriminates: $CLEAN_FLAGS"

echo "################ C7: capture a lead ################"
LEAD=$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@casino_king","email":"ck@example.test"}')
rec C7 201 "$(st)" "lead created"
rec C7b lead "$(jqv "$LEAD" "['status']")" "created as a LEAD — onboarding never approves"
rec C7c mock "$(jqv "$LEAD" "['metricsSource']")" "provenance persisted with the metrics"

ROW=$($PG -c "select metrics_source||'|'||coalesce(classification_source,'none')||'|'||coalesce(niche,'none') from creator.creators where handle='casino_king' and status='lead' order by created_at desc limit 1;" | tr -d '\r')
rec C7d true "$([[ "$ROW" == mock\|* ]] && echo true || echo false)" "row in DB carries provenance: $ROW"

echo "################ C8: an unresolvable handle still creates a lead (C.6) ################"
# Platform APIs fail: tokens expire, rate limits bite, private accounts return nothing, and
# app review may not have landed. A creator must never be blocked because an API was down.
MANUAL=$(api POST /api/creators/capture-lead "$TOKEN" '{"platform":"instagram","handle":"@unknown_person","name":"Manual Entry"}')
rec C8 201 "$(st)" "lead created even though the handle did not resolve"
rec C8b manual "$(jqv "$MANUAL" "['metricsSource']")" "labelled manual, not mock"
FOLLOWERS=$(jqv "$MANUAL" "['followerCount']")
rec C8c "" "$FOLLOWERS" \
    "follower count is ABSENT, not zero — zero followers is a real and very different claim"

echo "################ C9: public signup from a published page ################"
CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"CR Campaign","status":"active"}')" "['id']")
SLUG=$(jqv "$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"Signup Page\",\"status\":\"published\",\"document\":{\"html\":\"<h1>Join</h1>\"}}")" "['publicSlug']")
rec C9 nonempty "$([[ -n "$SLUG" ]] && echo nonempty || echo empty)" "published page slug=$SLUG"

# The hostile body: it names another brand, pre-approves itself, and inflates its metrics.
# All three must be ignored — the endpoint takes handle/platform/name/email and nothing else.
SIGNUP=$(pub "/api/public/landing/$SLUG/signup" \
  '{"handle":"@fit_mike","platform":"instagram","email":"mike@example.test","brandId":"00000000-0000-0000-0000-000000000000","status":"approved","followerCount":99999999,"niche":"finance"}')
rec C9b 201 "$(pst)" "signup accepted with NO auth token — a creator applying has no account"
rec C9c lead "$(jqv "$SIGNUP" "['status']")" "body said status=approved; the row is a lead"
rec C9d false "$([[ "$(jqv "$SIGNUP" "['followerCount']")" == "99999999" ]] && echo true || echo false)" \
    "body's inflated followerCount was discarded in favour of the adapter's"
rec C9e false "$([[ "$(jqv "$SIGNUP" "['brandId']")" == "00000000-0000-0000-0000-000000000000" ]] && echo true || echo false)" \
    "body's brandId ignored — the brand comes from the page slug"
rec C9f landing_page "$(jqv "$SIGNUP" "['leadSource']")" "lead attributed to the landing page"

LINKED=$($PG -c "select count(*) from creator.creators c join content.landing_templates t on t.id=c.lead_landing_template_id where t.public_slug='$SLUG';" | tr -d '\r')
rec C9g 1 "$LINKED" "lead is linked back to the page that produced it"

echo "################ C10: only a published page accepts signups ################"
DRAFT_CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"CR Draft","status":"active"}')" "['id']")
DRAFT_SLUG=$(jqv "$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$DRAFT_CAMPAIGN\",\"name\":\"Draft Page\",\"status\":\"draft\",\"document\":{\"html\":\"<h1>x</h1>\"}}")" "['publicSlug']")
pub "/api/public/landing/$DRAFT_SLUG/signup" '{"handle":"@someone","platform":"instagram"}' > /dev/null
rec C10 404 "$(pst)" "a draft page is not an open ingest endpoint"

pub "/api/public/landing/no-such-page/signup" '{"handle":"@someone","platform":"instagram"}' > /dev/null
rec C10b 404 "$(pst)" "an unknown slug is refused"

echo "################ C11: tenancy — one row per (creator, brand) ################"
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"cr.other.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"CR Other\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
OTHER_LEAD=$(api POST /api/creators/capture-lead "$OTHER" '{"platform":"instagram","handle":"@casino_king"}')
rec C11 201 "$(st)" "a second brand can hold its own row for the same handle"
rec C11b false "$([[ "$(jqv "$OTHER_LEAD" "['brandId']")" == "$(jqv "$LEAD" "['brandId']")" ]] && echo true || echo false)" \
    "the two rows belong to different brands"

OTHER_SEES=$(api GET /api/creators "$OTHER")
rec C11c 1 "$(echo "$OTHER_SEES" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo err)" \
    "the second brand sees ONLY its own row, not the first brand's three"

echo "################ C12: an unsupported platform is refused ################"
api POST /api/creators/resolve-handle "$TOKEN" '{"platform":"myspace","handle":"@someone"}' > /dev/null
rec C12 400 "$(st)" "unknown platform rejected with a readable error"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL slug=$SLUG handles=glow_daily,casino_king,fit_mike,unknown_person"
