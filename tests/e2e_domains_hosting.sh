#!/usr/bin/env bash
# Domains and hosting — roadmap Phase E (docs/landing-page-builder-roadmap.md §5).
#
# Three decisions from 2026-08-02 shape this, and the assertions defend them:
#
#   #9  The brand pays the registrar directly; the platform never resells. There is no purchase
#       endpoint at all — E1 checks the connect flow takes a domain the brand already owns.
#   #11 Two months of free hosting, measured from FIRST PUBLISH rather than signup (E5, E6).
#       A brand that signs up, explores for six weeks and then publishes should get the full
#       window on the thing being trialled.
#   At expiry a page is UNPUBLISHED, NOT DELETED (E8, E9). It stops serving and returns a clear
#   410; the row, its blocks and its assets stay. Deleting a brand's work because a trial lapsed
#   is the kind of thing that ends a customer relationship permanently.
#
# The registrar is MOCKED. Phase E is the roadmap's highest-external-risk phase precisely
# because it depends on real money, real DNS propagation and a real registrar contract — the one
# phase that cannot be fully tested locally. The adapter reports provider="mock" and E3 asserts
# it, for the same reason the social adapter does in Phase C.
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
      -d "$b" -o "$SP/.ebody" -w '%{http_code}' > "$SP/.ecode"
  else
    curl -s -m 60 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.ebody" -w '%{http_code}' > "$SP/.ecode"
  fi
  cat "$SP/.ebody"
}
st() { cat "$SP/.ecode" 2>/dev/null; }

publish() { # publish <token> <pageId> — walk the legal stage path
  for S in review approved ready_to_publish published; do
    api PUT "/api/landing-pages/$2/stage" "$1" "{\"to\":\"$S\",\"source\":\"builder\"}" > /dev/null
  done
}

EMAIL="dm.brand.$STAMP@example.test"

echo "################ setup ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"DM Brand\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"DM Campaign","status":"active"}')" "['id']")
PAGE_JSON=$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"DM Page\",\"document\":{\"html\":\"<h1>Hosted</h1>\"}}")
PAGE=$(jqv "$PAGE_JSON" "['id']")
SLUG=$(jqv "$PAGE_JSON" "['publicSlug']")
rec SETUP nonempty "$([[ -n "$PAGE" && -n "$SLUG" ]] && echo nonempty || echo empty)" "brand + page"

echo "################ E1: connect a domain the brand already owns ################"
DOMAIN=$(api POST /api/brand-domains "$TOKEN" "{\"domainName\":\"shop-$STAMP.example.com\",\"landingTemplateId\":\"$PAGE\"}")
rec E1 201 "$(st)" "domain connected"
DOMAIN_ID=$(jqv "$DOMAIN" "['id']")
rec E1b pending "$(jqv "$DOMAIN" "['dnsStatus']")" "starts unverified — DNS is not instant"

echo "################ E2: the brand is told exactly which records to create ################"
rec E2 true "$(echo "$DOMAIN" | grep -q "_influencrm-verify" && echo true || echo false)" \
    "a TXT verification record is supplied"
rec E2b true "$(echo "$DOMAIN" | grep -q "CNAME" && echo true || echo false)" "and a CNAME to our hosting"
rec E2c true "$(echo "$DOMAIN" | grep -qi "you keep ownership" && echo true || echo false)" \
    "the note states the brand keeps ownership — the platform never resells (decision #9)"

echo "################ E3: the registrar reports itself honestly ################"
rec E3 mock "$(jqv "$DOMAIN" "['provider']")" \
    "the adapter says 'mock', never a real provider name — a simulated verification must not look real"

echo "################ E4: verification ################"
# A domain whose name contains 'unverified' never verifies, so the polling path stays testable.
PENDING=$(api POST /api/brand-domains "$TOKEN" "{\"domainName\":\"unverified-$STAMP.example.com\"}")
PENDING_ID=$(jqv "$PENDING" "['id']")
NOT_YET=$(api POST "/api/brand-domains/$PENDING_ID/verify" "$TOKEN")
rec E4 200 "$(st)" "an unverified domain returns 200, not an error"
rec E4b false "$(jqv "$NOT_YET" "['verified']" | tr 'A-Z' 'a-z')" \
    "with verified=false — 'not yet' is a normal state a brand polls, not a failure"
rec E4c verifying "$(jqv "$NOT_YET" "['dnsStatus']")" "and the status reflects that it is in progress"

VERIFIED=$(api POST "/api/brand-domains/$DOMAIN_ID/verify" "$TOKEN")
rec E4d true "$(jqv "$VERIFIED" "['verified']" | tr 'A-Z' 'a-z')" "a good domain verifies"
rec E4e active "$(jqv "$VERIFIED" "['dnsStatus']")" "DNS becomes active"
rec E4f active "$(jqv "$VERIFIED" "['sslStatus']")" "and a certificate is issued (E.5)"

echo "################ E4g: SSL can fail independently of DNS ################"
SSLFAIL=$(api POST /api/brand-domains "$TOKEN" "{\"domainName\":\"sslfail-$STAMP.example.com\"}")
SSLFAIL_ID=$(jqv "$SSLFAIL" "['id']")
SSL_RESULT=$(api POST "/api/brand-domains/$SSLFAIL_ID/verify" "$TOKEN")
rec E4g active "$(jqv "$SSL_RESULT" "['dnsStatus']")" "DNS verified"
rec E4h failed "$(jqv "$SSL_RESULT" "['sslStatus']")" \
    "but the certificate failed — the two states are separate so a brand can tell which broke"

echo "################ E5: the hosting clock starts at FIRST PUBLISH, not signup ################"
BEFORE=$($PG -c "select coalesce(hosting_expires_at::text,'NULL') from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec E5 NULL "$BEFORE" "an unpublished page has NO expiry — the clock has not started"

publish "$TOKEN" "$PAGE"
AFTER=$($PG -c "select coalesce(hosting_expires_at::text,'NULL') from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec E5b true "$([[ "$AFTER" != "NULL" ]] && echo true || echo false)" "publishing starts the clock"

DAYS=$($PG -c "select round(extract(epoch from (hosting_expires_at - first_published_at))/86400) from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec E5c 60 "$DAYS" "two months of free hosting (decision #11)"

echo "################ E6: republishing does NOT restart the clock ################"
# Otherwise the trial would be unbounded for anyone who unpublishes and republishes.
FIRST_EXPIRY=$($PG -c "select hosting_expires_at from content.landing_templates where id='$PAGE';" | tr -d '\r')
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"performance_tracking","source":"builder"}' > /dev/null
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"published","source":"builder"}' > /dev/null
SECOND_EXPIRY=$($PG -c "select hosting_expires_at from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec E6 "$FIRST_EXPIRY" "$SECOND_EXPIRY" "the original window survives a republish"

echo "################ E7: a page inside its window serves normally ################"
curl -s -m 20 -o /dev/null -w '%{http_code}' "$BFF/s/$SLUG" > "$SP/.pcode"
rec E7 200 "$(cat "$SP/.pcode")" "the hosted page renders"

echo "################ E8-E9: expiry unpublishes, it does not delete ################"
$PG -c "update content.landing_templates set hosting_expires_at = now() - interval '1 day' where id='$PAGE';" > /dev/null
curl -s -m 20 -o "$SP/.gone" -w '%{http_code}' "$BFF/s/$SLUG" > "$SP/.pcode"
rec E8 410 "$(cat "$SP/.pcode")" \
    "an expired page returns 410 Gone — not a 404 that looks like a bug, nor silent serving"
rec E8b true "$(grep -qi "free hosting period has ended" "$SP/.gone" && echo true || echo false)" \
    "with a message that says what happened"
rec E8c true "$(grep -qi "content is safe" "$SP/.gone" && echo true || echo false)" \
    "and reassures the brand their work is intact"

STILL_THERE=$($PG -c "select count(*) from content.landing_templates where id='$PAGE' and document is not null;" | tr -d '\r')
rec E9 1 "$STILL_THERE" \
    "the page, its blocks and its document all SURVIVE — expiry never deletes a brand's work"

echo "################ E10: extending brings it straight back ################"
# Re-publishing after payment is a stage change, not a rebuild.
api POST "/api/landing-pages/$PAGE/hosting/extend" "$TOKEN" '{"days":30}' > /dev/null
rec E10 200 "$(st)" "hosting extended"
curl -s -m 20 -o /dev/null -w '%{http_code}' "$BFF/s/$SLUG" > "$SP/.pcode"
rec E10b 200 "$(cat "$SP/.pcode")" "the page serves again immediately"

EXTENDED=$($PG -c "select round(extract(epoch from (hosting_expires_at - now()))/86400) from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec E10c 29,30 "$EXTENDED" \
    "an ALREADY-EXPIRED page gets a fresh window from now, not one that starts in the past"

echo "################ E11: input validation ################"
api POST /api/brand-domains "$TOKEN" '{"domainName":"not a domain"}' > /dev/null
rec E11 400 "$(st)" "a malformed domain is refused — the value is echoed into DNS instructions"
api POST /api/brand-domains "$TOKEN" "{\"domainName\":\"https://strip-$STAMP.example.com/path\"}" > /dev/null
rec E11b 201 "$(st)" "a pasted URL is normalized rather than rejected"
NORMALIZED=$($PG -c "select domain_name from content.brand_domains where domain_name like 'strip-$STAMP%';" | tr -d '\r')
rec E11c "strip-$STAMP.example.com" "$NORMALIZED" "scheme and path are stripped"

echo "################ E12: a domain cannot be claimed twice ################"
api POST /api/brand-domains "$TOKEN" "{\"domainName\":\"shop-$STAMP.example.com\"}" > /dev/null
rec E12 409 "$(st)" "connecting an already-connected domain is refused at connect time, not at DNS time"

echo "################ E13: tenancy ################"
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"dm.other.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"DM Other\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
rec E13 0 "$(api GET /api/brand-domains "$OTHER" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "another brand sees none of our domains"
api POST "/api/brand-domains/$DOMAIN_ID/verify" "$OTHER" > /dev/null
rec E13b 404 "$(st)" "nor can it verify one"
api DELETE "/api/brand-domains/$DOMAIN_ID" "$OTHER" > /dev/null
rec E13c 404 "$(st)" "nor disconnect one"
api POST "/api/landing-pages/$PAGE/hosting/extend" "$OTHER" '{"days":365}' > /dev/null
rec E13d 404 "$(st)" "nor extend our hosting"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL page=$PAGE slug=$SLUG domain=shop-$STAMP.example.com"
