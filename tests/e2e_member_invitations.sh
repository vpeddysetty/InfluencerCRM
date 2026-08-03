#!/usr/bin/env bash
# Member invitations — roadmap Stage 3 (docs/identity-signup-alignment.md).
#
# The acceptance test for this stage is that a user can be put on someone else's account
# through the product. Before it, the only mechanism was what test_accounts.sql does:
# sign the user up, DELETE the solo account the signup created, and re-parent them by hand.
BFF=http://localhost:8081
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

api() { # api <method> <path> <token> [body]  -> body to stdout, status to .code
  local method="$1" path="$2" token="$3" body="$4"
  if [[ -n "$body" ]]; then
    curl -s -m 30 -X "$method" "$BFF$path" -H "Content-Type: application/json" \
      -H "Authorization: Bearer $token" -d "$body" -o "$SP/.ibody" -w '%{http_code}' > "$SP/.icode"
  else
    curl -s -m 30 -X "$method" "$BFF$path" -H "Authorization: Bearer $token" \
      -o "$SP/.ibody" -w '%{http_code}' > "$SP/.icode"
  fi
  cat "$SP/.ibody"
}
st() { cat "$SP/.icode" 2>/dev/null; }
jqv() { echo "$1" | python -c "import sys,json;d=json.load(sys.stdin);print(d$2 if d else '')" 2>/dev/null; }

signup() { # signup <email> <brand> <type> -> access token
  curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"password\":\"DemoPass123!\",\"brandName\":\"$2\",\"accountType\":\"$3\"}" \
    | python -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null
}
login() {
  curl -s -m 30 -X POST "$BFF/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"password\":\"DemoPass123!\"}" \
    | python -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null
}

OWNER_EMAIL="inv.owner.$STAMP@example.test"
MEMBER_EMAIL="inv.member.$STAMP@example.test"
OUTSIDER_EMAIL="inv.outsider.$STAMP@example.test"

echo "################ I1: an agency owner invites a marketer ################"
OWNER_TOKEN=$(signup "$OWNER_EMAIL" "Invite Test Agency" "agency")
rec I1 ok "$([[ -n "$OWNER_TOKEN" ]] && echo ok || echo missing)" "Agency owner signed up"

B=$(api POST /api/brands/members/invite "$OWNER_TOKEN" "{\"email\":\"$MEMBER_EMAIL\",\"role\":\"MARKETER\"}")
rec I2 201 "$(st)" "OWNER can invite" "$B"
TOKEN=$(jqv "$B" "['token']")
INV_ID=$(echo "$B" | python -c "import sys,json;print(json.load(sys.stdin)['invitation']['id'])" 2>/dev/null)
rec I2b ok "$([[ -n "$TOKEN" ]] && echo ok || echo missing)" "Invite returns a one-time token"

# The token is a credential: only its hash may exist in the database.
rec I3 0 "$($PG -c "SELECT count(*) FROM identity.member_invitations WHERE token_hash='$TOKEN';" | tr -d '\r')" \
    "SECURITY: raw token is NOT stored (only its hash)"
rec I3b 1 "$($PG -c "SELECT count(*) FROM identity.member_invitations WHERE id='$INV_ID' AND status='pending';" | tr -d '\r')" \
    "Invitation persisted as pending"

echo "################ I4: the invitee joins the INVITING account ################"
MEMBER_TOKEN=$(signup "$MEMBER_EMAIL" "Ignored Solo Brand" "brand")
rec I4 ok "$([[ -n "$MEMBER_TOKEN" ]] && echo ok || echo missing)" "Invitee has their own signup"

B=$(api POST /api/brands/members/invitations/accept "$MEMBER_TOKEN" "{\"token\":\"$TOKEN\"}")
rec I5 200 "$(st)" "Invitee accepts the invitation" "$B"

OWNER_ACCT=$($PG -c "SELECT a.id FROM identity.accounts a JOIN identity.memberships m ON m.account_id=a.id JOIN identity.users u ON u.id=m.user_id WHERE u.email='$OWNER_EMAIL' AND a.account_type='agency';" | tr -d '\r')
rec I6 MARKETER "$($PG -c "SELECT m.role FROM identity.memberships m JOIN identity.users u ON u.id=m.user_id WHERE u.email='$MEMBER_EMAIL' AND m.account_id='$OWNER_ACCT';" | tr -d '\r')" \
    "Invitee is a MARKETER on the agency account"
rec I6b accepted "$($PG -c "SELECT status FROM identity.member_invitations WHERE id='$INV_ID';" | tr -d '\r')" \
    "Invitation is marked accepted"
rec I6c 2 "$($PG -c "SELECT count(*) FROM identity.memberships m JOIN identity.users u ON u.id=m.user_id WHERE u.email='$MEMBER_EMAIL';" | tr -d '\r')" \
    "Invitee keeps their own account and gains the agency one"
# A MARKETER is not account-wide: without an explicit brand_access row they hold a membership
# and still see zero brands, which looks like a broken account rather than a permissions rule.
rec I6d 1 "$($PG -c "SELECT count(*) FROM identity.brand_access ba JOIN identity.memberships m ON m.id=ba.membership_id JOIN identity.users u ON u.id=m.user_id WHERE u.email='$MEMBER_EMAIL' AND m.account_id='$OWNER_ACCT';" | tr -d '\r')" \
    "Brand-scoped role receives brand_access so the brand is actually reachable"

echo "################ I7: a spent token cannot be reused ################"
B=$(api POST /api/brands/members/invitations/accept "$MEMBER_TOKEN" "{\"token\":\"$TOKEN\"}")
rec I7 400,409 "$(st)" "Re-using an accepted token is refused" "$B"

echo "################ I8: an invitation is addressed, not bearer-only ################"
OUTSIDER_TOKEN=$(signup "$OUTSIDER_EMAIL" "Outsider Co" "brand")
B=$(api POST /api/brands/members/invite "$OWNER_TOKEN" "{\"email\":\"someone.else.$STAMP@example.test\",\"role\":\"ANALYST\"}")
STOLEN=$(jqv "$B" "['token']")
B=$(api POST /api/brands/members/invitations/accept "$OUTSIDER_TOKEN" "{\"token\":\"$STOLEN\"}")
rec I8 400 "$(st)" "A forwarded token cannot be redeemed by a different email" "$B"

echo "################ I9: authorization on the invite surface ################"
# The invitee is OWNER of their own solo brand and MARKETER on the agency, so a plain login
# resolves to their own account where inviting is legitimate. The role under test only applies
# once the session is switched to the agency's brand — checking the token before that would
# assert against the wrong tenant and pass for the wrong reason.
MEMBER_TOKEN2=$(login "$MEMBER_EMAIL")
AGENCY_BRAND=$($PG -c "SELECT b.id FROM identity.brands b WHERE b.account_id='$OWNER_ACCT' LIMIT 1;" | tr -d '\r')
MEMBER_AGENCY_TOKEN=$(api POST /api/brands/switch "$MEMBER_TOKEN2" "{\"brandId\":\"$AGENCY_BRAND\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)
rec I9a ok "$([[ -n "$MEMBER_AGENCY_TOKEN" ]] && echo ok || echo missing)" "Member can switch into the agency brand"

B=$(api POST /api/brands/members/invite "$MEMBER_AGENCY_TOKEN" "{\"email\":\"nope.$STAMP@example.test\",\"role\":\"ANALYST\"}")
rec I9 403 "$(st)" "MARKETER cannot invite while acting on the agency (no member:invite)" "$B"

B=$(api POST /api/brands/members/invite "$OWNER_TOKEN" "{\"email\":\"owner.grant.$STAMP@example.test\",\"role\":\"OWNER\"}")
rec I10 400 "$(st)" "OWNER cannot be granted by invitation" "$B"

B=$(api POST /api/brands/members/invite "$OWNER_TOKEN" "{\"email\":\"bad.role.$STAMP@example.test\",\"role\":\"SUPERUSER\"}")
rec I11 400 "$(st)" "Unknown role refused" "$B"

echo "################ I12: role change and removal ################"
MEMBER_UID=$($PG -c "SELECT id FROM identity.users WHERE email='$MEMBER_EMAIL';" | tr -d '\r')
B=$(api PUT "/api/brands/members/$MEMBER_UID" "$OWNER_TOKEN" '{"role":"ANALYST"}')
rec I12 200 "$(st)" "OWNER changes a member's role" "$B"
rec I12b ANALYST "$($PG -c "SELECT role FROM identity.memberships WHERE user_id='$MEMBER_UID' AND account_id='$OWNER_ACCT';" | tr -d '\r')" \
    "Role change persisted"

OWNER_UID=$($PG -c "SELECT id FROM identity.users WHERE email='$OWNER_EMAIL';" | tr -d '\r')
B=$(api PUT "/api/brands/members/$OWNER_UID" "$OWNER_TOKEN" '{"role":"ANALYST"}')
rec I13 400 "$(st)" "An owner cannot demote themselves and lock the account out" "$B"

echo "################ I14: revoking a pending invitation ################"
B=$(api POST /api/brands/members/invite "$OWNER_TOKEN" "{\"email\":\"revoke.me.$STAMP@example.test\",\"role\":\"ANALYST\"}")
REV_TOKEN=$(jqv "$B" "['token']")
REV_ID=$(echo "$B" | python -c "import sys,json;print(json.load(sys.stdin)['invitation']['id'])" 2>/dev/null)
B=$(api POST "/api/brands/members/invitations/$REV_ID/revoke" "$OWNER_TOKEN" '{}')
rec I14 200 "$(st)" "OWNER revokes a pending invitation" "$B"
rec I14b revoked "$($PG -c "SELECT status FROM identity.member_invitations WHERE id='$REV_ID';" | tr -d '\r')" \
    "Invitation marked revoked"

REVOKEE_TOKEN=$(signup "revoke.me.$STAMP@example.test" "Revokee Co" "brand")
B=$(api POST /api/brands/members/invitations/accept "$REVOKEE_TOKEN" "{\"token\":\"$REV_TOKEN\"}")
rec I15 400,409 "$(st)" "A revoked token cannot be redeemed" "$B"

echo "################ I16: re-inviting replaces the live token ################"
B=$(api POST /api/brands/members/invite "$OWNER_TOKEN" "{\"email\":\"dup.$STAMP@example.test\",\"role\":\"ANALYST\"}")
FIRST=$(jqv "$B" "['token']")
B=$(api POST /api/brands/members/invite "$OWNER_TOKEN" "{\"email\":\"dup.$STAMP@example.test\",\"role\":\"ANALYST\"}")
rec I16 201 "$(st)" "Re-inviting the same email succeeds" "$B"
rec I16b 1 "$($PG -c "SELECT count(*) FROM identity.member_invitations WHERE account_id='$OWNER_ACCT' AND email='dup.$STAMP@example.test' AND status='pending';" | tr -d '\r')" \
    "Only one live invitation remains, so revoking is not defeated by a stale token"

# Housekeeping.
for e in "$OWNER_EMAIL" "$MEMBER_EMAIL" "$OUTSIDER_EMAIL" "revoke.me.$STAMP@example.test"; do
  $PG -c "DELETE FROM identity.users WHERE email='$e';" > /dev/null
done
rm -f "$SP/.ibody" "$SP/.icode"

echo ""
echo "=========================================="
echo "PASS: $PASS   FAIL: $FAIL"
echo "=========================================="
if (( FAIL )); then printf '%s\n' "${FAILED[@]}"; exit 1; fi
