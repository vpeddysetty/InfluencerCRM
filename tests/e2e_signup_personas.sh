#!/usr/bin/env bash
# Signup personas — roadmap Stage 1 (docs/identity-signup-alignment.md).
#
# Asserts that a signup can create both account types the identity model supports, that the
# solo-brand path is unchanged, and that an unsupported request is REFUSED rather than quietly
# downgraded. The last part is the actual defect this stage fixes: `accountType: "agency"` used
# to return 200 and a brand account.
DPS=${DPS:-http://localhost:8090}
. "$(dirname "$0")/local_only_guard.sh"
require_local_target "$DPS"
SP="${E2E_WORKDIR:-$(dirname "$0")}"
PG="docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A"
STAMP=$(date +%s)
PASS=0; FAIL=0
declare -a FAILED

rec() { # rec <id> <expected> <actual> <desc>
  if [[ ",$2," == *",$3,"* ]]; then
    PASS=$((PASS+1)); echo "PASS | $1 | $3 | $4"
  else
    FAIL=$((FAIL+1)); FAILED+=("$1 (exp $2 got $3): $4"); echo "FAIL | $1 | expected=$2 actual=$3 | $4"
  fi
}

# Signs up through the DPS exactly as the browser does: session cookie plus the XSRF
# double-submit header. Body -> stdout, status -> .code
signup() { # signup <jar> <json>
  local jar="$1" body="$2"
  rm -f "$SP/$jar"
  curl -s -m 20 -c "$SP/$jar" -o /dev/null "$DPS/dps/session"
  local tok; tok=$(awk '$6=="XSRF-TOKEN"{print $7}' "$SP/$jar" | tail -1)
  curl -s -m 30 -b "$SP/$jar" -c "$SP/$jar" -X POST "$DPS/dps/auth/signup" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $tok" \
    -d "$body" -o "$SP/.sbody" -w '%{http_code}' > "$SP/.scode"
  cat "$SP/.sbody"
}
st() { cat "$SP/.scode" 2>/dev/null; }
jqv() { echo "$1" | python -c "import sys,json;d=json.load(sys.stdin);print(d$2 if d else '')" 2>/dev/null; }

BRAND_EMAIL="stage1.brand.$STAMP@example.test"
AGENCY_EMAIL="stage1.agency.$STAMP@example.test"

echo "################ S1: solo brand signup (regression) ################"
B=$(signup s1brand.jar "{\"email\":\"$BRAND_EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"Stage1 Brand Co\",\"accountType\":\"brand\",\"acceptedTerms\":true}")
rec S1  200,201 "$(st)" "Brand signup succeeds"
rec S1b OWNER "$(jqv "$B" "['role']")" "Brand signup yields OWNER"
BRAND_UID=$(jqv "$B" "['userId']")
rec S1c brand "$($PG -c "SELECT a.account_type FROM identity.accounts a JOIN identity.memberships m ON m.account_id=a.id WHERE m.user_id='$BRAND_UID';" | tr -d '\r')" \
    "DB: account_type is brand"
rec S1d 1 "$($PG -c "SELECT count(*) FROM identity.brands b JOIN identity.memberships m ON m.account_id=b.account_id WHERE m.user_id='$BRAND_UID';" | tr -d '\r')" \
    "DB: exactly one brand provisioned"

echo "################ S2: agency signup (the new capability) ################"
B=$(signup s1agency.jar "{\"email\":\"$AGENCY_EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"Stage1 Northstar Agency\",\"accountType\":\"agency\",\"acceptedTerms\":true}")
rec S2  200,201 "$(st)" "Agency signup succeeds"
AGENCY_UID=$(jqv "$B" "['userId']")
AGENCY_BRAND=$(jqv "$B" "['brandId']")
rec S2b agency "$($PG -c "SELECT a.account_type FROM identity.accounts a JOIN identity.memberships m ON m.account_id=a.id WHERE m.user_id='$AGENCY_UID';" | tr -d '\r')" \
    "DB: account_type is agency (was silently 'brand' before Stage 1)"
rec S2c OWNER "$(jqv "$B" "['role']")" "Agency creator is OWNER of their own account"
rec S2d 1 "$($PG -c "SELECT count(*) FROM identity.brands b JOIN identity.memberships m ON m.account_id=b.account_id WHERE m.user_id='$AGENCY_UID';" | tr -d '\r')" \
    "DB: agency starts with one brand; clients are added later"
rec S2e "Stage1 Northstar Agency" "$(jqv "$B" "['brandName']")" "Agency's first brand takes the workspace name"

echo "################ S3: unsupported requests are refused, not coerced ################"
B=$(signup s1creator.jar "{\"email\":\"stage1.creator.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"Nope\",\"accountType\":\"creator\",\"acceptedTerms\":true}")
rec S3 400 "$(st)" "accountType=creator refused (creators are not accounts)"
rec S3b 0 "$($PG -c "SELECT count(*) FROM identity.users WHERE email='stage1.creator.$STAMP@example.test';" | tr -d '\r')" \
    "No user is left behind by the refused signup"

B=$(signup s1typo.jar "{\"email\":\"stage1.typo.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"Nope\",\"accountType\":\"Agencyy\"}")
rec S4 400 "$(st)" "Misspelled accountType refused rather than defaulted"

B=$(signup s1unknown.jar "{\"email\":\"stage1.unknown.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"Nope\",\"role\":\"ADMIN\"}")
rec S5 400 "$(st)" "Unknown field (role) refused rather than ignored"

echo "################ S6: the two new accounts are isolated ################"
rec S6 0 "$($PG -c "SELECT count(*) FROM identity.brands b JOIN identity.memberships m ON m.account_id=b.account_id WHERE m.user_id='$BRAND_UID' AND b.id='$AGENCY_BRAND';" | tr -d '\r')" \
    "TENANCY: brand owner cannot reach the agency's brand"
rec S6b 0 "$($PG -c "SELECT count(*) FROM identity.memberships m JOIN identity.accounts a ON a.id=m.account_id WHERE m.user_id='$AGENCY_UID' AND a.id='dededede-0000-0000-0000-0000000000aa';" | tr -d '\r')" \
    "TENANCY: new agency is not a member of the demo agency"

echo "################ S7: default is unchanged for existing clients ################"
B=$(signup s1default.jar "{\"email\":\"stage1.default.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"Stage1 Default Co\"}")
rec S7 200,201 "$(st)" "Signup without accountType still works"
DEF_UID=$(jqv "$B" "['userId']")
rec S7b brand "$($PG -c "SELECT a.account_type FROM identity.accounts a JOIN identity.memberships m ON m.account_id=a.id WHERE m.user_id='$DEF_UID';" | tr -d '\r')" \
    "Omitted accountType still defaults to brand"

# Housekeeping: these accounts exist only for this run.
for e in "$BRAND_EMAIL" "$AGENCY_EMAIL" "stage1.default.$STAMP@example.test"; do
  $PG -c "DELETE FROM identity.users WHERE email='$e';" > /dev/null
done
rm -f "$SP"/s1*.jar "$SP/.sbody" "$SP/.scode"

echo ""
echo "=========================================="
echo "PASS: $PASS   FAIL: $FAIL"
echo "=========================================="
if (( FAIL )); then printf '%s\n' "${FAILED[@]}"; exit 1; fi
