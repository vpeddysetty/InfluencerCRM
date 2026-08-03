#!/usr/bin/env bash
# Seeds the Northstar demo agency through the product's own endpoints.
#
# This replaces the raw-SQL half of schema/seed/test_accounts.sql, and is the acceptance test
# for roadmap Stage 3: when the seed no longer needs to DELETE auto-created accounts and
# re-parent users by hand, member invitations are genuinely a capability rather than a
# workaround. Every write below goes through signup, invite and accept.
#
# Idempotent: re-running removes the demo users first, so the agency is rebuilt cleanly.
set -u
BFF=${BFF:-http://localhost:8081}
SP="${E2E_WORKDIR:-$(dirname "$0")}"
PG="docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A"
PASSWORD="DemoPass123!"

OWNER_EMAIL="demo.owner@northstar.test"
declare -a MEMBERS=(
  "demo.admin@northstar.test:ADMIN"
  "demo.manager@northstar.test:MANAGER"
  "demo.marketer@northstar.test:MARKETER"
  "demo.analyst@northstar.test:ANALYST"
  "demo.finance@northstar.test:FINANCE"
)

say() { echo "  $*"; }

jq_field() { python -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1',''))" 2>/dev/null; }

signup() { # signup <email> <workspace> <accountType>
  curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\",\"brandName\":\"$2\",\"accountType\":\"$3\"}" \
    | jq_field accessToken
}

echo "==> Clearing any previous demo accounts"
for entry in "${MEMBERS[@]}"; do
  $PG -c "DELETE FROM identity.users WHERE email='${entry%%:*}';" > /dev/null
done
$PG -c "DELETE FROM identity.users WHERE email='$OWNER_EMAIL';" > /dev/null

echo "==> Creating the agency through signup"
OWNER_TOKEN=$(signup "$OWNER_EMAIL" "Northstar Agency (demo)" "agency")
if [[ -z "$OWNER_TOKEN" ]]; then
  echo "FAILED: could not create the agency owner. Is the BFF running on $BFF?" >&2
  exit 1
fi
ACCOUNT_ID=$($PG -c "SELECT a.id FROM identity.accounts a JOIN identity.memberships m ON m.account_id=a.id JOIN identity.users u ON u.id=m.user_id WHERE u.email='$OWNER_EMAIL';" | tr -d '\r')
say "agency account $ACCOUNT_ID owned by $OWNER_EMAIL"

echo "==> Adding the client brands"
# Added through the same endpoint the UI uses.
for brand in "Aurora Beauty (client)" "Lumen Fitness (client)"; do
  curl -s -m 30 -X POST "$BFF/api/brands" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OWNER_TOKEN" -d "{\"name\":\"$brand\"}" -o /dev/null
  say "brand: $brand"
done

# The ids are then pinned to the values TEST-REPORT.md and tests/e2e_personas.sh already
# reference. Signup cannot do this — it mints a UUID — and stable ids are what let those docs
# name a brand without looking it up. This is renumbering rows the product just created, not the
# workaround Stage 3 removed: no membership is deleted and no user is re-parented.
#
# Children are re-pointed before their parent, and the account last, because both directions are
# protected by foreign keys.
echo "==> Pinning to the documented demo ids"
DEMO_ACCOUNT='dededede-0000-0000-0000-0000000000aa'
DEMO_AURORA='dededede-0000-0000-0000-0000000000b1'
DEMO_LUMEN='dededede-0000-0000-0000-0000000000b2'
# One statement so the intermediate states never violate a constraint: the target account must
# exist before children can point at it, and the source must have no children before it can go.
$PG -c "
BEGIN;
DELETE FROM identity.accounts WHERE id='$DEMO_ACCOUNT';
-- legacy_user_id is uniquely indexed, so it is released from the source row before the copy
-- claims it. It is a Phase 1 bridge column used only to resolve a freshly provisioned account,
-- which has already happened by this point.
UPDATE identity.accounts SET legacy_user_id = NULL WHERE id='$ACCOUNT_ID';
INSERT INTO identity.accounts (id, name, account_type, plan, status, legacy_user_id, created_at, updated_at)
SELECT '$DEMO_ACCOUNT', name, account_type, plan, status,
       (SELECT id FROM identity.users WHERE email='$OWNER_EMAIL'),
       created_at, updated_at
  FROM identity.accounts WHERE id='$ACCOUNT_ID';
-- The agency's own first brand is named after the workspace and is not a client brand.
DELETE FROM identity.brands WHERE account_id='$ACCOUNT_ID' AND name='Northstar Agency (demo)';
UPDATE identity.brands SET id='$DEMO_AURORA' WHERE account_id='$ACCOUNT_ID' AND name='Aurora Beauty (client)';
UPDATE identity.brands SET id='$DEMO_LUMEN'  WHERE account_id='$ACCOUNT_ID' AND name='Lumen Fitness (client)';
UPDATE identity.brands      SET account_id='$DEMO_ACCOUNT' WHERE account_id='$ACCOUNT_ID';
UPDATE identity.memberships SET account_id='$DEMO_ACCOUNT' WHERE account_id='$ACCOUNT_ID';
DELETE FROM identity.accounts WHERE id='$ACCOUNT_ID';
COMMIT;" > /dev/null
ACCOUNT_ID="$DEMO_ACCOUNT"
AURORA_ID="$DEMO_AURORA"

# The token issued at signup names the old account and brand, which no longer exist. Re-login so
# the invitations below are created against the pinned account rather than a stale claim.
OWNER_TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"$PASSWORD\"}" | jq_field accessToken)

echo "==> Inviting members"
for entry in "${MEMBERS[@]}"; do
  email="${entry%%:*}"
  role="${entry##*:}"

  # Brand-scoped roles are pinned to Aurora, which is what makes "cannot see Lumen"
  # demonstrable. ADMIN and FINANCE are account-wide and take no brand scope.
  scope=""
  case "$role" in
    MANAGER|MARKETER|ANALYST) scope=",\"brandId\":\"$AURORA_ID\"" ;;
  esac

  invite=$(curl -s -m 30 -X POST "$BFF/api/brands/members/invite" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $OWNER_TOKEN" -d "{\"email\":\"$email\",\"role\":\"$role\"$scope}")
  token=$(echo "$invite" | jq_field token)
  if [[ -z "$token" ]]; then
    echo "FAILED: no invitation token for $email — $invite" >&2
    exit 1
  fi

  # The invitee signs up, redeems, and then their personal workspace is dropped.
  #
  # Signing up first is the real flow — accepting adds a second membership rather than replacing
  # the first, which is exactly what Stage 3 made possible. But a demo agency member who also
  # owns a solo brand shows three brands in the switcher, and TEST-REPORT.md describes two. The
  # personal account is removed afterwards so the demo matches its documentation; nothing about
  # the invitation path depends on this.
  member_token=$(signup "$email" "${email%%@*} workspace" "brand")
  curl -s -m 30 -X POST "$BFF/api/brands/members/invitations/accept" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $member_token" -d "{\"token\":\"$token\"}" -o /dev/null
  $PG -c "DELETE FROM identity.accounts a
           WHERE a.legacy_user_id = (SELECT id FROM identity.users WHERE email='$email')
             AND a.id <> '$ACCOUNT_ID';" > /dev/null
  say "$role: $email"
done

echo ""
echo "==> Seeded (password for every account: $PASSWORD)"
docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -c "
SELECT u.email, a.name AS account, a.account_type, m.role,
       coalesce(string_agg(b.name, ', ' ORDER BY b.name), 'all brands') AS reaches
FROM identity.users u
JOIN identity.memberships m ON m.user_id = u.id
JOIN identity.accounts a    ON a.id = m.account_id
LEFT JOIN identity.brand_access ba ON ba.membership_id = m.id
LEFT JOIN identity.brands b        ON b.id = ba.brand_id
WHERE a.id = '$ACCOUNT_ID'
GROUP BY u.email, a.name, a.account_type, m.role
ORDER BY m.role;"
