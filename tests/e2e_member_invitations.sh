#!/usr/bin/env bash
# Member invitations — roadmap Stage 3 (docs/identity-signup-alignment.md).
#
# The acceptance test for this stage is that a user can be put on someone else's account
# through the product. Before it, the only mechanism was what test_accounts.sql does:
# sign the user up, DELETE the solo account the signup created, and re-parent them by hand.
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
    -d "{\"email\":\"$1\",\"password\":\"DemoPass123!\",\"brandName\":\"$2\",\"accountType\":\"$3\",\"acceptedTerms\":true}" \
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

# Signup provisions every account on `free`, which since M2.3 is deliberately single-user — one
# seat, and roles unassignable. Every test below is about having a TEAM, so the account is put on
# the agency plan first. Done in SQL rather than through the API because there is no billing
# provider in a local run, and the alternative is asserting the plan wall over and over instead of
# the invitation behaviour this file exists to cover.
#
# The plan is read live on every check (EntitlementService reads the DAO, never the JWT), so this
# takes effect immediately and the token minted above stays valid.
OWNER_ACCT_EARLY=$($PG -c "SELECT a.id FROM identity.accounts a JOIN identity.memberships m ON m.account_id=a.id JOIN identity.users u ON u.id=m.user_id WHERE u.email='$OWNER_EMAIL' AND a.account_type='agency';" | tr -d '\r')
$PG -c "UPDATE identity.accounts SET plan='agency' WHERE id='$OWNER_ACCT_EARLY';" > /dev/null
rec I1b agency "$($PG -c "SELECT plan FROM identity.accounts WHERE id='$OWNER_ACCT_EARLY';" | tr -d '\r')" \
    "Account is on a plan that permits a team (free is single-user by design)"

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

echo "################ I17: inviting a whole team at once ################"
# The batch deliberately contains every case that behaves differently from a loop of single
# invites: a duplicate in two capitalisations, someone already on the account, and someone with a
# live invitation already outstanding.
B=$(api POST /api/brands/members/invite/bulk "$OWNER_TOKEN" "{\"invitations\":[
  {\"email\":\"bulk.a.$STAMP@example.test\",\"role\":\"ANALYST\"},
  {\"email\":\"bulk.b.$STAMP@example.test\",\"role\":\"MARKETER\"},
  {\"email\":\"BULK.A.$STAMP@EXAMPLE.TEST\",\"role\":\"ADMIN\"},
  {\"email\":\"$MEMBER_EMAIL\",\"role\":\"ANALYST\"},
  {\"email\":\"dup.$STAMP@example.test\",\"role\":\"ANALYST\"}
]}")
rec I17 200 "$(st)" "A batch is processed and reports per-row outcomes" "$B"
rec I17a 2 "$(jqv "$B" "['invited']")" "Two new addresses were invited"
rec I17b 3 "$(jqv "$B" "['skipped']")" "Duplicate, existing member and outstanding invitation all skipped"
rec I17c 0 "$(jqv "$B" "['failed']")" "Nothing failed"

# The duplicate is the case a loop would get wrong: the DAO revokes an existing pending invitation
# before inserting, so sending both rows would leave ONE live invitation while the seat arithmetic
# had charged for two.
rec I17d 1 "$($PG -c "SELECT count(*) FROM identity.member_invitations WHERE account_id='$OWNER_ACCT' AND email='bulk.a.$STAMP@example.test' AND status='pending';" | tr -d '\r')" \
    "A duplicated address produced exactly one live invitation"
rec I17e ANALYST "$($PG -c "SELECT role FROM identity.member_invitations WHERE account_id='$OWNER_ACCT' AND email='bulk.a.$STAMP@example.test' AND status='pending';" | tr -d '\r')" \
    "The first occurrence won, so the later ADMIN row did not widen the grant"
# Re-inviting someone who already has a live invitation must not silently revoke their token: while
# nothing is emailed, the copy an admin already forwarded by hand may be the only one in existence.
rec I17f pending "$($PG -c "SELECT status FROM identity.member_invitations WHERE account_id='$OWNER_ACCT' AND email='dup.$STAMP@example.test' AND status='pending';" | tr -d '\r')" \
    "An already-outstanding invitation was left alone rather than reissued"

# The response is the one place fifty credentials could leak at once.
rec I17g 0 "$(echo "$B" | grep -c '"token"')" "SECURITY: the bulk response carries no tokens"

echo "################ I18: a batch is refused whole, never half-applied ################"
BEFORE=$($PG -c "SELECT count(*) FROM identity.member_invitations WHERE account_id='$OWNER_ACCT';" | tr -d '\r')
B=$(api POST /api/brands/members/invite/bulk "$OWNER_TOKEN" "{\"invitations\":[
  {\"email\":\"fine.$STAMP@example.test\",\"role\":\"ANALYST\"},
  {\"email\":\"boss.$STAMP@example.test\",\"role\":\"OWNER\"}
]}")
rec I18 400 "$(st)" "An OWNER row refuses the batch" "$B"
rec I18b "$BEFORE" "$($PG -c "SELECT count(*) FROM identity.member_invitations WHERE account_id='$OWNER_ACCT';" | tr -d '\r')" \
    "Nothing was written — the valid row in the refused batch was not created either"

B=$(api POST /api/brands/members/invite/bulk "$OWNER_TOKEN" "{\"invitations\":[
  {\"email\":\"not-an-email\",\"role\":\"ANALYST\"}
]}")
rec I19 400 "$(st)" "An unreadable address refuses the batch" "$B"

B=$(api POST /api/brands/members/invite/bulk "$OWNER_TOKEN" '{"invitations":[]}')
rec I20 400 "$(st)" "An empty batch is refused" "$B"

echo "################ I21: resending replaces the link ################"
RESEND_ID=$($PG -c "SELECT id FROM identity.member_invitations WHERE account_id='$OWNER_ACCT' AND email='bulk.a.$STAMP@example.test' AND status='pending';" | tr -d '\r')
OLD_HASH=$($PG -c "SELECT token_hash FROM identity.member_invitations WHERE id='$RESEND_ID';" | tr -d '\r')
B=$(api POST "/api/brands/members/invitations/$RESEND_ID/resend" "$OWNER_TOKEN" '{}')
rec I21 200 "$(st)" "OWNER resends an invitation created in bulk" "$B"
rec I21a ok "$([[ -n "$(jqv "$B" "['token']")" ]] && echo ok || echo missing)" "Resend returns a usable token"
NEW_HASH=$($PG -c "SELECT token_hash FROM identity.member_invitations WHERE id='$RESEND_ID';" | tr -d '\r')
rec I21b differs "$([[ "$OLD_HASH" != "$NEW_HASH" ]] && echo differs || echo same)" \
    "The stored hash changed, so the previous link no longer works"
# Rotating in place rather than revoke-and-recreate: the id an admin clicked has to survive, and a
# resend must not read in the audit trail as a revocation.
rec I21c pending "$($PG -c "SELECT status FROM identity.member_invitations WHERE id='$RESEND_ID';" | tr -d '\r')" \
    "The same invitation was rotated, not revoked and replaced"

echo "################ I22: invitations are reachable only by their own account ################"
# Before the account check existed, an id was the entire authorization: any user holding
# member:remove could revoke or resend an invitation belonging to a stranger's account.
B=$(api POST "/api/brands/members/invitations/$RESEND_ID/revoke" "$OUTSIDER_TOKEN" '{}')
rec I22 404 "$(st)" "SECURITY: an outsider cannot revoke another account's invitation" "$B"
rec I22a pending "$($PG -c "SELECT status FROM identity.member_invitations WHERE id='$RESEND_ID';" | tr -d '\r')" \
    "The invitation survived the attempt"
B=$(api POST "/api/brands/members/invitations/$RESEND_ID/resend" "$OUTSIDER_TOKEN" '{}')
rec I22b 404 "$(st)" "SECURITY: an outsider cannot resend another account's invitation" "$B"

# Housekeeping.
for e in "$OWNER_EMAIL" "$MEMBER_EMAIL" "$OUTSIDER_EMAIL" "revoke.me.$STAMP@example.test"; do
  $PG -c "DELETE FROM identity.users WHERE email='$e';" > /dev/null
done
$PG -c "DELETE FROM identity.member_invitations WHERE email LIKE '%.$STAMP@example.test';" > /dev/null
rm -f "$SP/.ibody" "$SP/.icode"

echo ""
echo "=========================================="
echo "PASS: $PASS   FAIL: $FAIL"
echo "=========================================="
if (( FAIL )); then printf '%s\n' "${FAILED[@]}"; exit 1; fi
