#!/usr/bin/env bash
# End-to-end for AI campaign-page authoring (roadmap PR-35).
#
#   BFF=http://localhost:8081 ./tests/e2e_campaign_page_generation.sh
#
# Exercises the whole flow against a running stack, in the order a user meets it: brief in →
# drafts out → compare → rewrite one section → regenerate one card → save the chosen draft →
# schedule the publish → cancel it.
#
# WHY THIS EXISTS ALONGSIDE THE UNIT TESTS. The unit tests prove each decision in isolation with
# hand-built inputs. They cannot prove that the endpoints are routed, that the JSON shape the UI
# sends is the shape the service reads, that the tenancy stamp survives the controller, or that a
# generated draft's blocks are actually accepted by the landing-template save path that already
# existed. Every one of those is a seam between components that only a real request crosses.
#
# Runs against the TEMPLATE generator by default, which is the shipped configuration — so this
# suite passes with no API key and no model spend. That is deliberate: a test that needs a
# credential is a test that stops being run.
set -uo pipefail

BFF="${BFF:-http://localhost:8081}"
STAMP="$(date +%s)"
PASS=0
FAIL=0

rec() {
    local name="$1" expected="$2" actual="$3" note="${4:-}"
    if [ "$expected" = "$actual" ]; then
        PASS=$((PASS + 1))
        printf '  ok   %-46s %s\n' "$name" "$note"
    else
        FAIL=$((FAIL + 1))
        printf '  FAIL %-46s expected=%s actual=%s %s\n' "$name" "$expected" "$actual" "$note"
    fi
}

api() {
    local method="$1" path="$2" token="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -s -m 60 -X "$method" "$BFF$path" \
            -H "Content-Type: application/json" -H "Authorization: Bearer $token" -d "$body"
    else
        curl -s -m 60 -X "$method" "$BFF$path" -H "Authorization: Bearer $token"
    fi
}

# Status-only variant, for asserting a refusal rather than reading a body.
status() {
    local method="$1" path="$2" token="$3" body="${4:-}"
    curl -s -m 60 -o /dev/null -w '%{http_code}' -X "$method" "$BFF$path" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $token" \
        ${body:+-d "$body"}
}

# Reads a value out of a JSON body by a dotted path: `jqv variants.0.headline`.
#
# Deliberately not `eval` on a Python expression: the quoting has to survive a shell function, a
# command substitution and curl's own arguments, and it does not — the expression arrives mangled
# and every read silently returns empty, which reads as "the server returned nothing".
# A dotted path has no quotes in it at all.
jqv() {
    python -c '
import sys, json
try:
    node = json.load(sys.stdin)
except Exception:
    sys.exit(0)
# The landing-templates list returns a BARE ARRAY unless pagination is requested, and an
# {items:[...]} envelope when it is. A path written for one shape silently reads nothing from the
# other — which looked like a projection bug until the response was printed. Unwrap here so the
# paths below describe the data, not the envelope.
if isinstance(node, dict) and "items" in node and sys.argv[1].startswith("items."):
    pass
elif isinstance(node, list) and sys.argv[1].startswith("items."):
    node = {"items": node}
for part in sys.argv[1].split("."):
    if part == "":
        continue
    try:
        node = node[int(part)] if part.isdigit() else node[part]
    except Exception:
        sys.exit(0)
print(len(node) if sys.argv[2:] == ["len"] else node)
' "$1" "${2:-}" 2>/dev/null
}

echo "################ setup ################"
EMAIL="cpg.brand.$STAMP@example.test"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"Trailhead\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
    | jqv accessToken)
[ -n "$TOKEN" ] || { echo "FATAL: could not sign up (is the stack running?)"; exit 1; }

CAMPAIGN=$(api POST /api/campaigns "$TOKEN" '{"name":"Winter Trails","status":"active"}' | jqv id)
rec setup.campaign nonempty "$([ -n "$CAMPAIGN" ] && echo nonempty || echo empty)" "campaign=$CAMPAIGN"

echo "################ 1: generate drafts from a brief ################"
BRIEF="{\"goal\":\"Launch the winter trail collection\",\"audience\":\"Hikers 25-40\",\"offer\":\"15% off the first order\",\"creatorHandle\":\"@northbound\",\"campaignId\":\"$CAMPAIGN\",\"proofPoints\":[\"Recycled fabric\",\"Two-year guarantee\"]}"
GEN=$(api POST /api/campaign-pages/generate "$TOKEN" "$BRIEF")
COUNT=$(echo "$GEN" | jqv variants len)
rec generate.returns_drafts true "$([ "${COUNT:-0}" -ge 1 ] && echo true || echo false)" "variants=$COUNT"
rec generate.names_generator template "$(echo "$GEN" | jqv generator)" "shipped default is the template generator"

# Every draft must be renderable by the EXISTING renderer, which is what makes "use this draft"
# a plain save rather than a new persistence path.
HAS_BLOCKS=$(echo "$GEN" | jqv variants.0.blocks len)
rec generate.carries_blocks true "$([ "${HAS_BLOCKS:-0}" -ge 1 ] && echo true || echo false)" "blocks=$HAS_BLOCKS"
HERO=$(echo "$GEN" | jqv variants.0.sections.0.type)
rec generate.opens_with_hero hero "$HERO"

# The enrichment reads the campaign record for a campaign the token's brand owns.
ENRICHED=$(echo "$GEN" | jqv variants.0.headline)
rec generate.headline_nonempty nonempty "$([ -n "$ENRICHED" ] && echo nonempty || echo empty)" "\"$ENRICHED\""

echo "################ 2: a brief with no goal is refused ################"
rec generate.requires_goal 400 "$(status POST /api/campaign-pages/generate "$TOKEN" '{"audience":"Hikers"}')"

echo "################ 3: rewrite one section ################"
# "shorter" is the one rewrite the template generator can do honestly, so it exercises the success
# path without a model. Everything else it refuses, which case 4 covers.
SECTION='{"type":"richText","title":"Offer","body":"First sentence here. Second one follows."}'
RW=$(api POST /api/campaign-pages/sections/rewrite "$TOKEN" \
    "{\"goal\":\"Launch the winter trail collection\",\"section\":$SECTION,\"instruction\":\"make it shorter\"}")
rec rewrite.applied true "$(echo "$RW" | jqv rewritten | tr 'A-Z' 'a-z')"
rec rewrite.shortened "First sentence here." "$(echo "$RW" | jqv section.body)"
rec rewrite.keeps_type richText "$(echo "$RW" | jqv section.type)" "type comes from the request"

echo "################ 4: an impossible rewrite is reported, not faked ################"
RW2=$(api POST /api/campaign-pages/sections/rewrite "$TOKEN" \
    "{\"goal\":\"Launch\",\"section\":$SECTION,\"instruction\":\"make it wittier and more poetic\"}")
rec rewrite.honest_refusal false "$(echo "$RW2" | jqv rewritten | tr 'A-Z' 'a-z')" "no model configured"
rec rewrite.gives_reason nonempty "$([ -n "$(echo "$RW2" | jqv detail)" ] && echo nonempty || echo empty)"

echo "################ 5: the coupon block cannot be rewritten ################"
rec rewrite.refuses_coupon 400 "$(status POST /api/campaign-pages/sections/rewrite "$TOKEN" \
    '{"goal":"Launch","section":{"type":"couponBlock","title":"Coupon","body":""}}')" "renders a live code"

echo "################ 6: regenerate skips a headline already seen ################"
SEEN=$(echo "$GEN" | jqv variants.0.headline)
# The payload is built by Python, not by shell interpolation: generated headlines contain an
# em-dash, and hand-quoting non-ASCII through bash into curl produced a malformed body and a 400
# that looked like an endpoint failure. Anything echoing server text back must be encoded, not
# spliced.
REGEN_BODY=$(SEEN="$SEEN" python -c '
import json, os
print(json.dumps({
    "goal": "Launch the winter trail collection",
    "offer": "15% off the first order",
    "creatorHandle": "@northbound",
    "seenHeadlines": [os.environ["SEEN"]],
}))')
REGEN=$(api POST /api/campaign-pages/variants/regenerate "$TOKEN" "$REGEN_BODY")
NEW=$(echo "$REGEN" | jqv variants.0.headline)
rec regenerate.returns_one 1 "$(echo "$REGEN" | jqv variants len)"
rec regenerate.is_different true "$([ "$NEW" != "$SEEN" ] && echo true || echo false)" "\"$NEW\""

echo "################ 7: save the chosen draft through the existing path ################"
BLOCKS=$(echo "$GEN" | python -c "import sys,json;print(json.dumps(json.load(sys.stdin)['variants'][0]['blocks']))")
SAVED=$(api POST /api/landing-templates/save "$TOKEN" \
    "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"Generated page\",\"status\":\"draft\",\"blocks\":$BLOCKS}")
TEMPLATE=$(echo "$SAVED" | jqv id)
rec save.accepts_generated_blocks nonempty "$([ -n "$TEMPLATE" ] && echo nonempty || echo empty)" "template=$TEMPLATE"
rec save.kept_blocks true "$([ "$(echo "$SAVED" | jqv blocks len)" -ge 1 ] && echo true || echo false)"

echo "################ 8: schedule the publish ################"
FUTURE=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=6)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
SCHED=$(api PUT "/api/landing-pages/$TEMPLATE/schedule" "$TOKEN" "{\"publishAt\":\"$FUTURE\"}")
rec schedule.accepted nonempty "$([ -n "$(echo "$SCHED" | jqv scheduledPublishAt)" ] && echo nonempty || echo empty)" "at=$FUTURE"

# The projection must carry the column back, or the next unrelated save silently cancels it.
RELOAD=$(api GET "/api/landing-templates?campaignId=$CAMPAIGN" "$TOKEN")
rec schedule.survives_reload nonempty \
    "$([ -n "$(echo "$RELOAD" | jqv items.0.scheduledPublishAt )" ] && echo nonempty || echo empty)" \
    "projection allow-list"

echo "################ 8b: an ordinary save does not cancel the schedule ################"
# The DAO's PUT replaces the row and does not null-guard this column, so a BFF caller that omits
# it silently un-schedules the launch. This is the regression that guards that.
api POST /api/landing-templates/save "$TOKEN"     "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"Edited after scheduling\",\"status\":\"draft\",\"blocks\":$BLOCKS}" > /dev/null
STILL=$(api GET "/api/landing-templates?campaignId=$CAMPAIGN" "$TOKEN" | jqv items.0.scheduledPublishAt)
rec schedule.survives_edit nonempty "$([ -n "$STILL" ] && echo nonempty || echo empty)"     "a builder edit must not un-schedule a launch"

echo "################ 9: a past time is refused ################"
PAST=$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
rec schedule.refuses_past 400 "$(status PUT "/api/landing-pages/$TEMPLATE/schedule" "$TOKEN" "{\"publishAt\":\"$PAST\"}")" \
    "a wrong date, not a request to publish now"

echo "################ 10: cancel the schedule ################"
rec schedule.cancelled 200 "$(status DELETE "/api/landing-pages/$TEMPLATE/schedule" "$TOKEN")"
# Cleared means the field is ABSENT, not the string "None": the projection omits a null rather
# than emitting one, so an empty read is the pass condition here.
AFTER=$(api GET "/api/landing-templates?campaignId=$CAMPAIGN" "$TOKEN" | jqv items.0.scheduledPublishAt)
rec schedule.cleared empty "$([ -z "$AFTER" ] && echo empty || echo "$AFTER")"     "the pending time is consumed, not left behind"

echo "################ 11: unauthenticated callers cost no model spend ################"
rec auth.rejects_anonymous 401 "$(curl -s -m 30 -o /dev/null -w '%{http_code}' -X POST \
    "$BFF/api/campaign-pages/generate" -H "Content-Type: application/json" -d "$BRIEF")"

echo
echo "################ result ################"
echo "  passed: $PASS   failed: $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
