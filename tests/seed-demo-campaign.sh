#!/usr/bin/env bash
# Seed a demo brand with campaigns, creators, coupons and an AI-generated landing page.
#
#   BFF=https://api.tejdux.com ./tests/seed-demo-campaign.sh
#
# Prints the sign-in credentials and the public page URLs at the end, so the result can be checked
# in the product rather than only in a response body.
#
# WHY A SCRIPT AND NOT CLICKING. The point is to prove the whole chain works against a deployed
# environment: signup, campaign, creators, per-creator coupons, AI generation, and the save path
# that turns a generated draft into an ordinary landing template. Doing it by hand proves the UI
# renders; doing it this way proves the API contract the UI depends on.
set -uo pipefail

BFF="${BFF:-https://api.tejdux.com}"
STAMP="$(date +%m%d-%H%M)"
EMAIL="demo.trailhead.$STAMP@tejdux.test"
PASSWORD="DemoPass123!"

api() {
    local method="$1" path="$2" token="$3" body="${4:-}"
    if [ -n "$body" ]; then
        curl -s -m 120 -X "$method" "$BFF$path" -H "Content-Type: application/json" \
            -H "Authorization: Bearer $token" -d "$body"
    else
        curl -s -m 120 -X "$method" "$BFF$path" -H "Authorization: Bearer $token"
    fi
}

# Reads a value by dotted path. Tolerates the bare-array vs {items:[...]} shapes the list
# endpoints use depending on whether pagination was requested.
jqv() {
    python -c '
import sys, json
try: node = json.load(sys.stdin)
except Exception: sys.exit(0)
if isinstance(node, list) and sys.argv[1].startswith("items."): node = {"items": node}
for part in sys.argv[1].split("."):
    if part == "": continue
    try: node = node[int(part)] if part.isdigit() else node[part]
    except Exception: sys.exit(0)
print(node)
' "$1" 2>/dev/null
}

step() { printf '\n=== %s\n' "$1"; }

step "Signing up $EMAIL"
SIGNUP=$(curl -s -m 60 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"brandName\":\"Trailhead Collection\",\"accountType\":\"brand\",\"acceptedTerms\":true}")
TOKEN=$(echo "$SIGNUP" | jqv accessToken)
[ -n "$TOKEN" ] || { echo "FATAL: signup failed: $(echo "$SIGNUP" | head -c 300)"; exit 1; }
echo "  brand: $(echo "$SIGNUP" | jqv brandName)  plan: $(echo "$SIGNUP" | jqv plan)"

step "Creating campaigns"
CAMPAIGNS=()
for spec in "Winter Trails 2026|active" "Spring Base Layers|active"; do
    NAME="${spec%%|*}"; STATUS="${spec##*|}"
    ID=$(api POST /api/campaigns "$TOKEN" "{\"name\":\"$NAME\",\"status\":\"$STATUS\",\"campaignType\":\"product_launch\"}" | jqv id)
    CAMPAIGNS+=("$ID")
    echo "  $NAME -> $ID"
done
CAMPAIGN="${CAMPAIGNS[0]}"

step "Creating creators"
declare -a CREATORS=()
# name|handle|platform|rate — a spread of platforms so the generated copy has real context to use.
for spec in "Sam Okonjo|northbound|instagram|450" "Priya Raman|trailmix|youtube|900" "Alex Chen|summitdaily|tiktok|300"; do
    IFS='|' read -r NAME HANDLE PLATFORM RATE <<< "$spec"
    ID=$(api POST /api/creators "$TOKEN" \
        "{\"name\":\"$NAME\",\"handle\":\"$HANDLE\",\"platform\":\"$PLATFORM\",\"status\":\"active\",\"preferredRate\":$RATE}" | jqv id)
    CREATORS+=("$ID|$NAME|$HANDLE")
    echo "  $NAME (@$HANDLE, $PLATFORM) -> $ID"
done

step "Issuing one e-coupon per creator"
# One code per creator on the campaign: this is what makes the landing page personalise, because
# the public URL resolves a coupon and renders that creator's own code.
i=0
for entry in "${CREATORS[@]}"; do
    CID="${entry%%|*}"; REST="${entry#*|}"; CNAME="${REST%%|*}"; CHANDLE="${REST##*|}"
    CODE="$(echo "$CHANDLE" | tr 'a-z' 'A-Z')15"
    OUT=$(api POST /api/influencer-campaign-codes "$TOKEN" \
        "{\"campaignId\":\"$CAMPAIGN\",\"creatorId\":\"$CID\",\"code\":\"$CODE\",\"discountType\":\"percent\",\"discountValue\":15,\"landingUrl\":\"https://trailhead.example.com/winter\"}")
    echo "  $CNAME -> code $CODE  (id $(echo "$OUT" | jqv id))"
    i=$((i+1))
done

step "Generating landing page drafts with AI"
BRIEF=$(python -c '
import json, sys
print(json.dumps({
  "campaignId": sys.argv[1],
  "goal": "Launch the Winter Trails 2026 collection to cold-weather hikers",
  "audience": "Hikers and trail runners 25-45 who walk through winter",
  "offer": "15% off the first order",
  "creatorHandle": "@northbound",
  "brandTone": "Warm, practical, understated",
  "ctaPreference": "Shop the winter collection",
  "proofPoints": ["Recycled insulation", "Two-year repair guarantee", "Tested to -20C"],
}))' "$CAMPAIGN")
GEN=$(api POST /api/campaign-pages/generate "$TOKEN" "$BRIEF")
GENERATOR=$(echo "$GEN" | jqv generator)
FALLBACK=$(echo "$GEN" | jqv fallback)
COUNT=$(python -c "import sys,json;print(len(json.load(sys.stdin).get('variants',[])))" <<< "$GEN")
echo "  generator: $GENERATOR   fallback: $FALLBACK   drafts: $COUNT"
if [ "$GENERATOR" != "anthropic" ]; then
    echo "  NOTE: not AI-generated. detail: $(echo "$GEN" | jqv detail)"
fi
python -c "
import sys, json
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
d = json.load(sys.stdin)
for v in d.get('variants', []):
    print('   -', v['headline'][:70], '| score', v['score'])
" <<< "$GEN"

step "Saving the best draft as the campaign's landing page"
BEST=$(python -c "
import sys, json
d = json.load(sys.stdin)
v = max(d['variants'], key=lambda x: x['score'])
print(json.dumps({'blocks': v['blocks'], 'headline': v['headline']}))
" <<< "$GEN")
BLOCKS=$(python -c "import sys,json;print(json.dumps(json.load(sys.stdin)['blocks']))" <<< "$BEST")
HEADLINE=$(python -c "import sys,json;print(json.load(sys.stdin)['headline'])" <<< "$BEST")
SAVED=$(api POST /api/landing-templates/save "$TOKEN" \
    "$(python -c '
import json, sys
print(json.dumps({"campaignId": sys.argv[1], "name": "Winter Trails landing page",
                  "status": "published", "stage": "published", "blocks": json.loads(sys.argv[2])}))
' "$CAMPAIGN" "$BLOCKS")")
SLUG=$(echo "$SAVED" | jqv publicSlug)
echo "  saved: $(echo "$SAVED" | jqv id)   slug: $SLUG"
echo "  headline: $HEADLINE"

step "Public pages (one per creator)"
CODES=$(api GET "/api/influencer-campaign-codes?campaignId=$CAMPAIGN" "$TOKEN")
python -c "
import sys, json, os
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
d = json.load(sys.stdin)
items = d if isinstance(d, list) else d.get('items', [])
base, slug = os.environ['BFF'], os.environ['SLUG']
for c in items:
    ps = c.get('publicSlug')
    print('   ', c.get('code'), '->', f'{base}/s/{slug}/{ps}' if ps else '(no slug yet)')
" <<< "$CODES"

cat <<SUMMARY

==================================================================
  SIGN IN AND VALIDATE
==================================================================
  URL:       https://www.tejdux.com
  Email:     $EMAIL
  Password:  $PASSWORD

  Campaign:  Winter Trails 2026
  Page:      $BFF/s/$SLUG
  Generator: $GENERATOR

  In the app: Content -> pick "Winter Trails 2026" -> the landing page is
  already there. Open "Start from a campaign goal" to generate fresh drafts,
  rewrite a section, or schedule the publish.
==================================================================
SUMMARY
