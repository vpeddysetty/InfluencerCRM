#!/usr/bin/env bash
# Landing page builder — roadmap Phase A (docs/landing-page-builder-roadmap.md §5).
#
# The builder replaced a typed-block renderer that was safe by construction: it emitted a
# fixed set of tags and escaped every value, so stored data could never become markup.
# GrapesJS output IS markup, so that guarantee had to be rebuilt as allow-list filtering
# at render time. Most of the assertions here exist for that reason — A6-A13 are the
# security regression suite, and they matter more than the happy path above them.
#
# The other thing under test is that adopting the builder did not break pages that predate
# it (A14): `document` and `blocks` are separate columns and the renderer picks per page.
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
    curl -s -m 30 -X "$m" "$BFF$p" -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
      -d "$b" -o "$SP/.lbody" -w '%{http_code}' > "$SP/.lcode"
  else
    curl -s -m 30 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.lbody" -w '%{http_code}' > "$SP/.lcode"
  fi
  cat "$SP/.lbody"
}
st() { cat "$SP/.lcode" 2>/dev/null; }

pub() { # pub <path> -> body, status in $SP/.pcode
  curl -s -m 30 "$BFF$1" -o "$SP/.pbody" -w '%{http_code}' > "$SP/.pcode"
  cat "$SP/.pbody"
}
pst() { cat "$SP/.pcode" 2>/dev/null; }

EMAIL="lb.brand.$STAMP@example.test"

echo "################ setup: brand + campaign ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"LB Brand\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"LB Campaign","status":"active"}')" "['id']")
rec SETUP nonempty "$([[ -n "$CAMPAIGN" ]] && echo nonempty || echo empty)" "campaign=$CAMPAIGN"

echo "################ A1: save a GrapesJS document ################"
DOC_HTML='<section class=\"hero\"><h1>Summer Drop</h1><p>Hi {{creator.name}}, use {{coupon.code}}</p><a href=\"https://example.com\">Shop</a></section>'
DOC_CSS='.hero{padding:40px;background:#eef2ff}h1{color:#4338ca}'
SAVED=$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"Builder Page\",\"status\":\"published\",\"stage\":\"published\",\"document\":{\"html\":\"$DOC_HTML\",\"css\":\"$DOC_CSS\"}}")
rec A1 200 "$(st)" "Saved a visual-builder document"
SLUG=$(jqv "$SAVED" "['publicSlug']")
rec A1b nonempty "$([[ -n "$SLUG" ]] && echo nonempty || echo empty)" "slug=$SLUG"

echo "################ A2: document + stage round-trip through the API ################"
# The BFF response shape strips unknown fields, so a column existing in the DB is not
# enough — it has to be added to the shape or the builder can never read its own document.
rec A2 true "$(jqv "$SAVED" "['document']['html']" | grep -q 'Summer Drop' && echo true || echo false)" \
    "document.html survives the round-trip"
rec A2b published "$(jqv "$SAVED" "['stage']")" "stage persisted (not silently defaulted)"

echo "################ A3: the document is real jsonb, not a quoted string ################"
DOC_TYPE=$($PG -c "select jsonb_typeof(document) from content.landing_templates where public_slug='$SLUG';" | tr -d '\r')
rec A3 object "$DOC_TYPE" "stored as a jsonb object"

echo "################ A4: every save appends a version (A.5) ################"
V1=$($PG -c "select count(*) from content.landing_template_versions v join content.landing_templates t on t.id=v.landing_template_id where t.public_slug='$SLUG';" | tr -d '\r')
rec A4 1 "$V1" "first save wrote version 1"

api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"Second Edit\",\"status\":\"published\",\"stage\":\"published\",\"document\":{\"html\":\"<h1>v2</h1>\",\"css\":\"\"}}" > /dev/null
V2=$($PG -c "select count(*) from content.landing_template_versions v join content.landing_templates t on t.id=v.landing_template_id where t.public_slug='$SLUG';" | tr -d '\r')
rec A4b 2 "$V2" "second save appended version 2"

echo "################ A5: restore writes forward, it does not rewind ################"
# Deleting later versions would destroy the record of what was undone — the one thing
# history exists for. A restore must therefore ADD a version, not remove any.
RESTORED=$(api POST /api/landing-templates/versions/1/restore "$TOKEN" "{\"campaignId\":\"$CAMPAIGN\"}")
rec A5 200 "$(st)" "restored v1"
rec A5b true "$(echo "$RESTORED" | grep -q 'Summer Drop' && echo true || echo false)" "v1 content is back"
V3=$($PG -c "select count(*) from content.landing_template_versions v join content.landing_templates t on t.id=v.landing_template_id where t.public_slug='$SLUG';" | tr -d '\r')
rec A5c 3 "$V3" "restore appended v3 rather than deleting v2"
rec A5d draft "$(jqv "$RESTORED" "['status']")" "restore returns a draft — it must not silently republish"

echo "################ A6-A13: the public page is an XSS surface ################"
# Re-publish with a hostile document. Each assertion below is a payload class that the
# old escape-everything renderer handled for free and the sanitizer now has to catch.
XSS_HTML='<div><h1>Safe</h1><script>alert(1)</script><img src=x onerror=alert(2)><a href=\"javascript:alert(3)\">bad</a><iframe src=\"https://evil.test\"></iframe><form action=\"https://evil.test\"><input name=\"password\"></form></div>'
api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"XSS Probe\",\"status\":\"published\",\"stage\":\"published\",\"document\":{\"html\":\"$XSS_HTML\",\"css\":\".a{width:expression(alert(4))}\"}}" > /dev/null

PAGE=$(pub "/s/$SLUG")
rec A6 200 "$(pst)" "published brand page renders"
rec A7  absent "$(echo "$PAGE" | grep -q '<script'    && echo present || echo absent)" "script tag stripped"
rec A8  absent "$(echo "$PAGE" | grep -q 'onerror'    && echo present || echo absent)" "inline event handler stripped"
rec A9  absent "$(echo "$PAGE" | grep -q 'javascript:' && echo present || echo absent)" "javascript: URL stripped"
rec A10 absent "$(echo "$PAGE" | grep -q '<iframe'    && echo present || echo absent)" "iframe stripped"
rec A11 absent "$(echo "$PAGE" | grep -qi '<form'     && echo present || echo absent)" "form stripped"
rec A12 absent "$(echo "$PAGE" | grep -q 'expression(' && echo present || echo absent)" "CSS expression() dropped"
rec A13 present "$(echo "$PAGE" | grep -q '<h1>Safe</h1>' && echo present || echo absent)" \
    "legitimate markup survives — the filter is an allow-list, not a blanket escape"

echo "################ A14: pages built before the builder still render ################"
# The regression that would matter most: `document` and `blocks` are separate columns and
# the renderer picks per page, so a pre-builder page must be untouched by all of the above.
LEGACY_CAMP=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"LB Legacy","status":"active"}')" "['id']")
LEGACY=$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$LEGACY_CAMP\",\"name\":\"Legacy Page\",\"status\":\"published\",\"blocks\":[{\"type\":\"hero\",\"text\":\"Legacy hero\"},{\"type\":\"legal\",\"text\":\"#ad\"}]}")
LEGACY_SLUG=$(jqv "$LEGACY" "['publicSlug']")
LEGACY_NULL=$($PG -c "select document is null from content.landing_templates where public_slug='$LEGACY_SLUG';" | tr -d '\r')
rec A14 t "$LEGACY_NULL" "a block-only page has NO document — nothing was backfilled"

LEGACY_PAGE=$(pub "/s/$LEGACY_SLUG")
rec A14b 200 "$(pst)" "legacy page still renders"
rec A14c present "$(echo "$LEGACY_PAGE" | grep -q 'class="wrap"' && echo present || echo absent)" \
    "rendered by the ORIGINAL typed-block renderer (its .wrap container proves the path)"
rec A14d present "$(echo "$LEGACY_PAGE" | grep -q 'Legacy hero' && echo present || echo absent)" "legacy block content intact"

echo "################ A15: drafts are not publicly readable ################"
# Otherwise 'draft' means nothing: anyone with the slug could read unreleased work.
api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$LEGACY_CAMP\",\"name\":\"Legacy Page\",\"status\":\"draft\",\"blocks\":[{\"type\":\"hero\",\"text\":\"Legacy hero\"}]}" > /dev/null
pub "/s/$LEGACY_SLUG" > /dev/null
rec A15 404 "$(pst)" "an unpublished page is 404, not readable"

echo "################ A16: an unknown stage is refused ################"
api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"Bad Stage\",\"stage\":\"not_a_stage\",\"document\":{\"html\":\"<p>x</p>\"}}" > /dev/null
rec A16 400 "$(st)" "unknown stage rejected at the API, not at the DB constraint"

echo "################ A17: version history is tenant-scoped ################"
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"lb.other.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"LB Other\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
OTHER_VERS=$(api GET "/api/landing-templates/versions?campaignId=$CAMPAIGN" "$OTHER")
rec A17 0 "$(echo "$OTHER_VERS" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo err)" \
    "another brand sees no versions for a campaign it does not own"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL campaign=$CAMPAIGN slug=$SLUG legacy_slug=$LEGACY_SLUG"
