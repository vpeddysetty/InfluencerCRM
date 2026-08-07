#!/usr/bin/env bash
# Observability — roadmap Phase H (docs/landing-page-builder-roadmap.md §5).
#
# The roadmap says this "should begin immediately and independently. The platform currently has
# no error tracking and no metrics, which is a gap regardless of whether the builder ships."
#
# The assertions that matter are about EXPOSURE, not about counters. An observability endpoint
# is an information-disclosure surface: `env` and `configprops` leak configuration including
# secret names, and `heapdump` leaks their values. O3 and O4 check those stay closed even when
# the caller holds a valid token — being authenticated is not the same as being entitled to a
# heap dump.
BFF=${BFF:-http://localhost:8081}
MGMT=${MGMT:-http://localhost:9081}
SP="${E2E_WORKDIR:-$(dirname "$0")}"
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

code() { curl -s -m 15 -o /dev/null -w '%{http_code}' "$@"; }

echo "################ setup ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"ob.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"OB Brand\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
rec SETUP nonempty "$([[ -n "$TOKEN" ]] && echo nonempty || echo empty)" "brand signed up"

echo "################ O1: health is reachable without a token ################"
# A liveness probe cannot hold one. This is the whole reason it is whitelisted.
rec O1 200 "$(code "$MGMT/actuator/health")" "an anonymous probe can check liveness"
BODY=$(curl -s -m 15 "$MGMT/actuator/health")
rec O1b true "$(echo "$BODY" | grep -q '"status":"UP"' && echo true || echo false)" "and gets a status"
rec O1c true "$(echo "$BODY" | grep -qiE 'datasource|redis|diskspace' && echo false || echo true)" \
    "but NOT which dependency is failing — show-details=when-authorized keeps that back"

echo "################ O2: metrics require a token ################"
rec O2 401 "$(code "$MGMT/actuator/metrics")" "anonymous metrics are refused"
rec O2b 200 "$(code -H "Authorization: Bearer $TOKEN" "$MGMT/actuator/metrics")" "an authenticated caller can read them"

echo "################ O3-O4: sensitive endpoints stay closed ################"
# Not merely authenticated — absent. `env` and `configprops` list configuration keys (including
# the NAMES of secrets), and `heapdump` hands over their values.
for e in env beans configprops heapdump threaddump loggers; do
  rec O3 404 "$(code -H "Authorization: Bearer $TOKEN" "$MGMT/actuator/$e")" \
      "$e is not exposed even WITH a valid token"
done

echo "################ O5: the app port serves no actuator ################"
# A separate management port means exposing the application does not expose its internals by
# the same route.
rec O5 401,404 "$(code "$BFF/actuator/metrics")" "the public app port does not serve metrics"

echo "################ O6: domain counters record what matters ################"
CAMPAIGN=$(curl -s -m 30 -X POST "$BFF/api/campaigns" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d '{"name":"OB Campaign","status":"active"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
PAGE=$(curl -s -m 30 -X POST "$BFF/api/landing-templates/save" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"OB Page\",\"document\":{\"html\":\"<h1>x</h1>\"}}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['id'])")

# One legal transition and one refused, so both tag values exist.
curl -s -m 20 -X PUT "$BFF/api/landing-pages/$PAGE/stage" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d '{"to":"review","source":"builder"}' -o /dev/null
curl -s -m 20 -X PUT "$BFF/api/landing-pages/$PAGE/stage" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d '{"to":"published","source":"builder"}' -o /dev/null

TRANSITIONS=$(curl -s -m 15 -H "Authorization: Bearer $TOKEN" "$MGMT/actuator/metrics/influencrm.landing.stage_transition")
rec O6 true "$(echo "$TRANSITIONS" | grep -q "influencrm.landing.stage_transition" && echo true || echo false)" \
    "stage transitions are counted"
rec O6b true "$(echo "$TRANSITIONS" | grep -q "accepted" && echo true || echo false)" "tagged accepted"
rec O6c true "$(echo "$TRANSITIONS" | grep -q "refused" && echo true || echo false)" \
    "and refused — a rise here means the transition map disagrees with how people work"

echo "################ O7: metrics are tagged by service, not by tenant ################"
# Tenant-cardinality tags are the standard way to make a metrics backend fall over. Per-brand
# questions belong in the database, where the audit trails already answer them.
rec O7 true "$(echo "$TRANSITIONS" | grep -qE '"tag": ?"service"' && echo true || echo false)" \
    "a service tag exists so one scrape target can hold several services"
rec O7b true "$(echo "$TRANSITIONS" | grep -qiE '"tag": ?"(brand|brandid|tenant)"' && echo false || echo true)" \
    "and there is NO brand tag — unbounded cardinality is how a metrics store dies"

echo "################ O8: the vetting counter separates rule from human ################"
curl -s -m 60 -X POST "$BFF/api/creators/capture-lead" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d '{"platform":"instagram","handle":"@ob_creator"}' -o /dev/null
VETTING=$(curl -s -m 15 -H "Authorization: Bearer $TOKEN" "$MGMT/actuator/metrics/influencrm.vetting.decision")
rec O8 true "$(echo "$VETTING" | grep -qE '"tag": ?"by"' && echo true || echo false)" \
    "decisions are tagged rule vs human — the asymmetry in C2 is worth watching in aggregate too"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=ob.$STAMP@example.test management_port=$MGMT"
