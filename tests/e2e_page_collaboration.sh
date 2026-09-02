#!/usr/bin/env bash
# Brand-creator co-editing — roadmap Phase G (docs/landing-page-builder-roadmap.md §5, §6.1).
#
# Two decisions from §6.1 define the shape, and most of these assertions defend them:
#
#   G4/G5 — a creator with NO confirmed link cannot be given page access, and access is
#           re-checked on every edit rather than only at invite time. Page access is a
#           narrowing of a relationship the brand already approved; without the link check a
#           brand could grant access to any portal identity.
#   G8    — publishing is NEVER a collaborator right. A collaborator shapes a page; releasing
#           it to a domain or social account requires content:publish, which only account
#           members hold.
#   G11   — a creator cannot reach a page they were never invited to.
#
# Simultaneous editing (G.6) is deliberately not built. Two people editing at different times
# is a different problem from two at the same instant, and version history (A.5) already makes
# the former safe by making overwrites recoverable — which G7 checks.
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

api() { # api <method> <path> <token> [body]  — brand side (operator JWT)
  local m="$1" p="$2" t="$3" b="$4"
  if [[ -n "$b" ]]; then
    curl -s -m 60 -X "$m" "$BFF$p" -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
      -d "$b" -o "$SP/.gbody" -w '%{http_code}' > "$SP/.gcode"
  else
    curl -s -m 60 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.gbody" -w '%{http_code}' > "$SP/.gcode"
  fi
  cat "$SP/.gbody"
}
cre() { # cre <method> <path> <creatorToken> [body] — portal side (opaque token)
  local m="$1" p="$2" t="$3" b="$4"
  if [[ -n "$b" ]]; then
    curl -s -m 60 -X "$m" "$BFF$p" -H "Content-Type: application/json" -H "X-Creator-Token: $t" \
      -d "$b" -o "$SP/.gbody" -w '%{http_code}' > "$SP/.gcode"
  else
    curl -s -m 60 -X "$m" "$BFF$p" -H "X-Creator-Token: $t" -o "$SP/.gbody" -w '%{http_code}' > "$SP/.gcode"
  fi
  cat "$SP/.gbody"
}
st() { cat "$SP/.gcode" 2>/dev/null; }

EMAIL="pc.brand.$STAMP@example.test"
CREATOR_EMAIL="pc.creator.$STAMP@example.test"

echo "################ setup: a brand, a page, and a creator with a portal login ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"PC Brand\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"PC Campaign","status":"active"}')" "['id']")
PAGE=$(jqv "$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"PC Page\",\"document\":{\"html\":\"<h1>Brand draft</h1>\"}}")" "['id']")
CREATOR_ROW=$(jqv "$(api POST /api/creators "$TOKEN" '{"handle":"@pc_creator","name":"PC Creator","platform":"instagram"}')" "['id']")

CREATOR_TOKEN=$(curl -s -m 30 -X POST "$BFF/api/creator-portal/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$CREATOR_EMAIL\",\"password\":\"DemoPass123!\",\"displayName\":\"PC Creator\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('token',''))")
IDENTITY=$($PG -c "select id from identity.creator_identities where email='$CREATOR_EMAIL';" | tr -d '\r')
rec SETUP nonempty "$([[ -n "$PAGE" && -n "$CREATOR_TOKEN" && -n "$IDENTITY" ]] && echo nonempty || echo empty)" \
    "brand page + creator portal login exist"

echo "################ G1: a creator has NO pages before being invited ################"
rec G1 0 "$(cre GET /api/creator-portal/pages "$CREATOR_TOKEN" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "the portal page list starts empty"

echo "################ G2: invite is REFUSED without a confirmed link ################"
# The security model of this phase. Without it a brand could grant page access to any portal
# identity, including creators who have never agreed to work with them.
REFUSED=$(api POST "/api/landing-pages/$PAGE/collaborators" "$TOKEN" \
  "{\"creatorIdentityId\":\"$IDENTITY\",\"rights\":\"edit\"}")
rec G2 409 "$(st)" "invite refused: no confirmed relationship yet"
rec G2b true "$(echo "$REFUSED" | grep -qi "confirmed relationship" && echo true || echo false)" \
    "and the refusal explains what is missing"

echo "################ G3: the creator claims, the brand confirms ################"
BRAND_ID=$($PG -c "select brand_id from creator.creators where id='$CREATOR_ROW';" | tr -d '\r')
cre POST /api/creator-portal/claims "$CREATOR_TOKEN" \
  "{\"creatorId\":\"$CREATOR_ROW\",\"brandId\":\"$BRAND_ID\"}" > /dev/null
rec G3 200,201 "$(st)" "creator claims their row"
LINK=$($PG -c "select id from identity.creator_identity_links where creator_identity_id='$IDENTITY' limit 1;" | tr -d '\r')
api POST "/api/creator-portal/claims/$LINK/approve" "$TOKEN" > /dev/null
rec G3b 200,201 "$(st)" "brand approves the claim"
STATUS=$($PG -c "select status from identity.creator_identity_links where id='$LINK';" | tr -d '\r')
rec G3c confirmed "$STATUS" "the link is confirmed"

echo "################ G4: now the invite works ################"
GRANT=$(api POST "/api/landing-pages/$PAGE/collaborators" "$TOKEN" \
  "{\"creatorIdentityId\":\"$IDENTITY\",\"rights\":\"edit\"}")
rec G4 201 "$(st)" "invite accepted once the relationship is confirmed"
GRANT_ID=$(jqv "$GRANT" "['id']")

rec G4b 1 "$(api GET "/api/landing-pages/$PAGE/collaborators" "$TOKEN" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "the brand can see who has access"

echo "################ G5: a creator cannot be granted publish rights ################"
api POST "/api/landing-pages/$PAGE/collaborators" "$TOKEN" \
  "{\"creatorIdentityId\":\"$IDENTITY\",\"rights\":\"publish\"}" > /dev/null
rec G5 400 "$(st)" "rights=publish is refused — publishing stays with the brand"

echo "################ G6: the creator can now see and edit the page ################"
PAGES=$(cre GET /api/creator-portal/pages "$CREATOR_TOKEN")
rec G6 1 "$(echo "$PAGES" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "the page appears in the creator portal"
rec G6b edit "$(echo "$PAGES" | python -c "import sys,json;print(json.load(sys.stdin)[0]['rights'])")" "with edit rights"

EDITED=$(cre PUT "/api/creator-portal/pages/$PAGE" "$CREATOR_TOKEN" \
  '{"document":{"html":"<h1>Brand draft</h1><p>Creator section added</p>","css":""}}')
rec G6c 200 "$(st)" "the creator can save content"
rec G6d true "$(echo "$EDITED" | grep -q "Creator section added" && echo true || echo false)" \
    "and the edit is stored"

echo "################ G7: co-editing is safe because every save is versioned ################"
# This is why simultaneous editing (G.6, a CRDT) can be deferred: an overwrite by either side
# is recoverable.
VERSIONS=$($PG -c "select count(*) from content.landing_template_versions where landing_template_id='$PAGE';" | tr -d '\r')
rec G7 true "$([[ "$VERSIONS" -ge 2 ]] && echo true || echo false)" \
    "the creator's save produced a version too ($VERSIONS total) — overwrites stay recoverable"

echo "################ G8: a collaborator CANNOT publish ################"
# The most important assertion here. A creator sending status/stage in the body must not be
# able to release the page to its public URL.
BEFORE_STATUS=$($PG -c "select status||'|'||stage from content.landing_templates where id='$PAGE';" | tr -d '\r')
cre PUT "/api/creator-portal/pages/$PAGE" "$CREATOR_TOKEN" \
  '{"document":{"html":"<h1>Sneaky</h1>"},"status":"published","stage":"published"}' > /dev/null
rec G8 200 "$(st)" "the save succeeds (the content change is legitimate)"
AFTER_STATUS=$($PG -c "select status||'|'||stage from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec G8b "$BEFORE_STATUS" "$AFTER_STATUS" \
    "but status and stage are UNCHANGED — a collaborator cannot publish by sending a field"

echo "################ G9: a creator cannot create a page of their own (decision #1) ################"
# There is no create route on the portal at all. Every page is brand-owned; a creator with no
# brand relationship has nothing to build.
NO_CREATE=$(curl -s -m 20 -X POST "$BFF/api/creator-portal/pages" -H "Content-Type: application/json" \
  -H "X-Creator-Token: $CREATOR_TOKEN" -d '{"name":"My own page"}' -o /dev/null -w '%{http_code}')
rec G9 401,403,404,405 "$NO_CREATE" "there is no way for a creator to author their own page"

echo "################ G10: an unauthenticated portal call is refused ################"
cre GET /api/creator-portal/pages "not-a-real-token" > /dev/null
rec G10 401 "$(st)" "a bad portal token is unauthorized, not merely empty"

echo "################ G11: a creator cannot reach a page they were not invited to ################"
OTHER_CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"PC Private","status":"active"}')" "['id']")
PRIVATE=$(jqv "$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$OTHER_CAMPAIGN\",\"name\":\"PC Private Page\",\"document\":{\"html\":\"<h1>Private</h1>\"}}")" "['id']")
cre PUT "/api/creator-portal/pages/$PRIVATE" "$CREATOR_TOKEN" '{"document":{"html":"<h1>x</h1>"}}' > /dev/null
rec G11 404 "$(st)" "an uninvited page is 404 — a creator cannot learn which pages exist"

echo "################ G12: revoking access removes it ################"
api DELETE "/api/landing-pages/collaborators/$GRANT_ID" "$TOKEN" > /dev/null
rec G12 200,204 "$(st)" "the brand revokes access"
rec G12b 0 "$(cre GET /api/creator-portal/pages "$CREATOR_TOKEN" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "the page disappears from the creator portal"
cre PUT "/api/creator-portal/pages/$PAGE" "$CREATOR_TOKEN" '{"document":{"html":"<h1>after revoke</h1>"}}' > /dev/null
rec G12c 404 "$(st)" "and they can no longer edit it"

REVOKED_ROW=$($PG -c "select count(*) from content.landing_page_collaborators where id='$GRANT_ID' and revoked_at is not null;" | tr -d '\r')
rec G12d 1 "$REVOKED_ROW" "the grant is marked revoked, not deleted — who had access and when survives"

echo "################ G13: revoking the IDENTITY LINK also removes page access ################"
# One place to cut off a creator, not two. Access hangs off the link rather than duplicating it.
api POST "/api/landing-pages/$PAGE/collaborators" "$TOKEN" \
  "{\"creatorIdentityId\":\"$IDENTITY\",\"rights\":\"edit\"}" > /dev/null
rec G13 201 "$(st)" "re-invited after the earlier revoke"
$PG -c "update identity.creator_identity_links set status='rejected' where id='$LINK';" > /dev/null
rec G13b 0 "$(cre GET /api/creator-portal/pages "$CREATOR_TOKEN" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" \
    "revoking the underlying link removes page access without a second revocation"
cre PUT "/api/creator-portal/pages/$PAGE" "$CREATOR_TOKEN" '{"document":{"html":"<h1>x</h1>"}}' > /dev/null
rec G13c 404 "$(st)" "the edit path re-checks the link on every save, not just at invite time"

echo "################ G14: tenancy ################"
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"pc.other.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"PC Other\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
api GET "/api/landing-pages/$PAGE/collaborators" "$OTHER" > /dev/null
rec G14 404 "$(st)" "another brand cannot list our page's collaborators"
api POST "/api/landing-pages/$PAGE/collaborators" "$OTHER" "{\"creatorIdentityId\":\"$IDENTITY\"}" > /dev/null
rec G14b 404 "$(st)" "nor grant access to our page"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL creator=$CREATOR_EMAIL page=$PAGE identity=$IDENTITY"
