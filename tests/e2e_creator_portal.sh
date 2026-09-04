#!/usr/bin/env bash
# Creator portal — roadmap Stage 4 (docs/identity-signup-alignment.md).
#
# A creator is stored as N per-brand rows by design, so one creator login must fan out to many
# creator ids. Tenancy therefore runs the opposite way to everything else: not "this brand owns
# these rows" but "these rows have been confirmed as me". These assertions exist mostly to prove
# a claim alone grants nothing — approval by the brand is what makes it real.
BFF=${BFF:-http://localhost:8081}
. "$(dirname "$0")/local_only_guard.sh"
require_local_target "$BFF"
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

brand_api() { # brand_api <method> <path> <token> [body]
  local m="$1" p="$2" t="$3" b="$4"
  if [[ -n "$b" ]]; then
    curl -s -m 30 -X "$m" "$BFF$p" -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
      -d "$b" -o "$SP/.cbody" -w '%{http_code}' > "$SP/.ccode"
  else
    curl -s -m 30 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.cbody" -w '%{http_code}' > "$SP/.ccode"
  fi
  cat "$SP/.cbody"
}
creator_api() { # creator_api <method> <path> <creatorToken> [body]
  local m="$1" p="$2" t="$3" b="$4"
  if [[ -n "$b" ]]; then
    curl -s -m 30 -X "$m" "$BFF$p" -H "Content-Type: application/json" -H "X-Creator-Token: $t" \
      -d "$b" -o "$SP/.cbody" -w '%{http_code}' > "$SP/.ccode"
  else
    curl -s -m 30 -X "$m" "$BFF$p" -H "X-Creator-Token: $t" -o "$SP/.cbody" -w '%{http_code}' > "$SP/.ccode"
  fi
  cat "$SP/.cbody"
}
st() { cat "$SP/.ccode" 2>/dev/null; }

BRAND_A_EMAIL="cp.brand.a.$STAMP@example.test"
BRAND_B_EMAIL="cp.brand.b.$STAMP@example.test"
CREATOR_EMAIL="cp.creator.$STAMP@example.test"

echo "################ C1: two unrelated brands each hold a row for one creator ################"
TOKEN_A=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$BRAND_A_EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"CP Brand A\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
TOKEN_B=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$BRAND_B_EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"CP Brand B\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

CREATOR_A=$(jqv "$(brand_api POST /api/creators "$TOKEN_A" "{\"handle\":\"@cp_star_$STAMP\",\"name\":\"CP Star\",\"platform\":\"instagram\",\"preferredRate\":5000}")" "['id']")
CREATOR_B=$(jqv "$(brand_api POST /api/creators "$TOKEN_B" "{\"handle\":\"@cp_star_$STAMP\",\"name\":\"CP Star\",\"platform\":\"instagram\",\"preferredRate\":900}")" "['id']")
rec C1 different "$([[ -n "$CREATOR_A" && -n "$CREATOR_B" && "$CREATOR_A" != "$CREATOR_B" ]] && echo different || echo same)" \
    "Same handle exists as two independent per-brand rows"

BRAND_A_ID=$($PG -c "SELECT brand_id FROM creator.creators WHERE id='$CREATOR_A';" | tr -d '\r')
BRAND_B_ID=$($PG -c "SELECT brand_id FROM creator.creators WHERE id='$CREATOR_B';" | tr -d '\r')

echo "################ C2: a creator signs up for the portal ################"
B=$(curl -s -m 30 -X POST "$BFF/api/creator-portal/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$CREATOR_EMAIL\",\"password\":\"DemoPass123!\",\"displayName\":\"CP Star\",\"acceptedTerms\":true}" \
  -o "$SP/.cbody" -w '%{http_code}' > "$SP/.ccode"; cat "$SP/.cbody")
rec C2 201 "$(st)" "Creator can sign up for the portal" "$B"
CREATOR_TOKEN=$(jqv "$B" "['token']")
CREATOR_IDENTITY=$(jqv "$B" "['creatorIdentityId']")
rec C2b ok "$([[ -n "$CREATOR_TOKEN" ]] && echo ok || echo missing)" "Portal session issued"

# A creator is not an operator: they must have no users row and no membership, or brand-scoped
# permission checks would start returning true for them.
rec C2c 0 "$($PG -c "SELECT count(*) FROM identity.users WHERE email='$CREATOR_EMAIL';" | tr -d '\r')" \
    "SEPARATION: creator has no identity.users row"
rec C2d 0 "$($PG -c "SELECT count(*) FROM identity.memberships m JOIN identity.users u ON u.id=m.user_id WHERE u.email='$CREATOR_EMAIL';" | tr -d '\r')" \
    "SEPARATION: creator has no account membership"

echo "################ C3: a claim on its own grants nothing ################"
B=$(creator_api POST /api/creator-portal/claims "$CREATOR_TOKEN" "{\"creatorId\":\"$CREATOR_A\",\"brandId\":\"$BRAND_A_ID\"}")
rec C3 201 "$(st)" "Creator claims Brand A's record" "$B"
rec C3b claimed "$($PG -c "SELECT status FROM identity.creator_identity_links WHERE creator_id='$CREATOR_A';" | tr -d '\r')" \
    "Claim is unverified, not confirmed"

B=$(creator_api GET /api/creator-portal/collaborations "$CREATOR_TOKEN")
COUNT=$(echo "$B" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
rec C4 0 "${COUNT:-x}" "SECURITY: an unconfirmed claim exposes no brand data"

echo "################ C5: the brand approves, and only then is it visible ################"
B=$(brand_api GET /api/creator-portal/pending-claims "$TOKEN_A")
LINK_ID=$(echo "$B" | python -c "import sys,json;d=json.load(sys.stdin);print(d[0]['id'] if d else '')" 2>/dev/null)
rec C5 200 "$(st)" "Brand A sees the pending claim" "$B"

B=$(brand_api POST "/api/creator-portal/claims/$LINK_ID/approve" "$TOKEN_A" '{}')
rec C6 200 "$(st)" "Brand A approves the claim" "$B"
rec C6b confirmed "$($PG -c "SELECT status FROM identity.creator_identity_links WHERE creator_id='$CREATOR_A';" | tr -d '\r')" \
    "Link is confirmed"

B=$(creator_api GET /api/creator-portal/collaborations "$CREATOR_TOKEN")
COUNT=$(echo "$B" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
rec C7 1 "${COUNT:-x}" "Creator now sees exactly the one confirmed collaboration"
rec C7b "CP Brand A" "$(echo "$B" | python -c "import sys,json;print(json.load(sys.stdin)[0]['brandName'])" 2>/dev/null)" \
    "The collaboration names the confirming brand"

echo "################ C8: Brand B is unaffected by Brand A's decision ################"
# The fan-out must not leak: approving one brand's record says nothing about another's.
rec C8 0 "$($PG -c "SELECT count(*) FROM identity.creator_identity_links WHERE creator_id='$CREATOR_B' AND status='confirmed';" | tr -d '\r')" \
    "TENANCY: Brand B's record is not confirmed by Brand A's approval"

B=$(brand_api GET /api/creator-portal/pending-claims "$TOKEN_B")
COUNT=$(echo "$B" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
rec C8b 0 "${COUNT:-x}" "TENANCY: Brand B sees no claims against Brand A's record"

echo "################ C9: a second identity cannot steal a confirmed record ################"
B=$(curl -s -m 30 -X POST "$BFF/api/creator-portal/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"cp.impostor.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"displayName\":\"Impostor\",\"acceptedTerms\":true}")
IMPOSTOR_TOKEN=$(jqv "$B" "['token']")
B=$(creator_api POST /api/creator-portal/claims "$IMPOSTOR_TOKEN" "{\"creatorId\":\"$CREATOR_A\",\"brandId\":\"$BRAND_A_ID\"}")
rec C9 409,400 "$(st)" "SECURITY: an already-confirmed record cannot be claimed by another identity" "$B"

echo "################ C10: portal auth is required ################"
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "$BFF/api/creator-portal/collaborations")
rec C10 401 "$S" "No creator token is refused"
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "$BFF/api/creator-portal/collaborations" -H "X-Creator-Token: forged-value")
rec C10b 401 "$S" "A forged creator token is refused"
# The operator JWT must not work on the portal, and vice versa.
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "$BFF/api/creator-portal/collaborations" -H "X-Creator-Token: $TOKEN_A")
rec C10c 401 "$S" "SEPARATION: an operator JWT is not a creator session"
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "$BFF/api/creators" -H "Authorization: Bearer $CREATOR_TOKEN")
rec C10d 401 "$S" "SEPARATION: a creator token cannot reach the brand API"

echo "################ C11: brand-initiated linking ################"
B=$(brand_api POST /api/creator-portal/invite "$TOKEN_B" "{\"email\":\"$CREATOR_EMAIL\",\"creatorId\":\"$CREATOR_B\"}")
rec C11 201 "$(st)" "Brand B links the creator directly" "$B"
B=$(creator_api GET /api/creator-portal/collaborations "$CREATOR_TOKEN")
COUNT=$(echo "$B" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
rec C11b 2 "${COUNT:-x}" "FAN-OUT: one login now spans two brands' records"

# Housekeeping.
$PG -c "DELETE FROM identity.creator_identities WHERE email IN ('$CREATOR_EMAIL','cp.impostor.$STAMP@example.test');" > /dev/null
$PG -c "DELETE FROM identity.users WHERE email IN ('$BRAND_A_EMAIL','$BRAND_B_EMAIL');" > /dev/null
rm -f "$SP/.cbody" "$SP/.ccode"

echo ""
echo "=========================================="
echo "PASS: $PASS   FAIL: $FAIL"
echo "=========================================="
if (( FAIL )); then printf '%s\n' "${FAILED[@]}"; exit 1; fi
