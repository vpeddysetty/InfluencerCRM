#!/usr/bin/env bash
# End-to-end journeys driven through the Digital Presentation Service (:8090),
# exactly as the browser does: httpOnly session cookie, /dps/api/* proxy.
DPS=${DPS:-http://localhost:8090}
. "$(dirname "$0")/local_only_guard.sh"
require_local_target "$DPS"
SP="${E2E_WORKDIR:-$(dirname "$0")}"
OUT="$SP/results.txt"
: > "$OUT"

PASS=0; FAIL=0
declare -a FAILED

# rec <id> <expected> <actual> <desc> [payload]
rec() {
  local id="$1" exp="$2" act="$3" desc="$4" extra="$5"
  if [[ ",$exp," == *",$act,"* ]]; then
    PASS=$((PASS+1)); echo "PASS | $id | $act | $desc" >> "$OUT"
  else
    FAIL=$((FAIL+1)); FAILED+=("$id (exp $exp got $act): $desc")
    echo "FAIL | $id | expected=$exp actual=$act | $desc | $extra" >> "$OUT"
  fi
}

# The browser echoes the XSRF-TOKEN cookie as an X-XSRF-TOKEN header (double-submit).
# Without this every state-changing call is correctly refused with 403.
csrf_of() {
  local jar="$1"
  [[ -f "$SP/$jar" ]] || { echo ""; return; }
  awk '$6=="XSRF-TOKEN"{print $7}' "$SP/$jar" | tail -1
}

# Status is written to a side file rather than parsed out of the body: a JSON body can
# contain anything, and command substitution strips trailing newlines, so any in-band
# marker scheme is fragile.
# api <jar> <method> <path> [body]  -> body to stdout, status to $STATUS
api() {
  local jar="$1" method="$2" path="$3" body="$4"
  local tok; tok=$(csrf_of "$jar")
  if [[ -n "$body" ]]; then
    curl -s -m 30 -b "$SP/$jar" -c "$SP/$jar" -X "$method" "$DPS$path" \
      -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $tok" -d "$body" \
      -o "$SP/.body" -w '%{http_code}' > "$SP/.code"
  else
    curl -s -m 30 -b "$SP/$jar" -c "$SP/$jar" -X "$method" "$DPS$path" \
      -H "X-XSRF-TOKEN: $tok" -o "$SP/.body" -w '%{http_code}' > "$SP/.code"
  fi
  cat "$SP/.body"
}

login() { # login <jar> <email> <pass>
  rm -f "$SP/$1"
  # Prime the CSRF cookie the way a browser does by loading the session endpoint first.
  curl -s -m 20 -c "$SP/$1" -o /dev/null "$DPS/dps/session"
  local tok; tok=$(csrf_of "$1")
  curl -s -m 30 -b "$SP/$1" -c "$SP/$1" -X POST "$DPS/dps/auth/login" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $tok" \
    -d "{\"email\":\"$2\",\"password\":\"$3\"}" -o "$SP/.body" -w '%{http_code}' > "$SP/.code"
  cat "$SP/.body"
}

# api()/login() run inside $( ), so a variable they set is lost with the subshell.
# The status is therefore read back from the file they wrote.
st() { cat "$SP/.code" 2>/dev/null; }

jqv() { echo "$1" | python -c "import sys,json;d=json.load(sys.stdin);print(d$2 if d else '')" 2>/dev/null; }

echo "################ AGENCY OWNER (demo.admin@northstar.test) ################"

# --- A1 login ---
BODY=$(login admin.jar "demo.admin@northstar.test" "DemoPass123!")
rec A1 200 "$(st)" "Agency ADMIN login via DPS"
A_BRAND1=$(jqv "$BODY" "['brandId']")
A_ACCT=$(jqv "$BODY" "['accountId']")
A_UID=$(jqv "$BODY" "['userId']")
A_BRAND2=$(echo "$BODY" | python -c "import sys,json;d=json.load(sys.stdin);print([b['brandId'] for b in d['availableBrands'] if not b['active']][0])" 2>/dev/null)
echo "admin uid=$A_UID acct=$A_ACCT aurora=$A_BRAND1 lumen=$A_BRAND2" >> "$OUT"

# --- A2 session rehydrate ---
B=$(api admin.jar GET /dps/session); rec A2 200 "$(st)" "Session rehydrates from cookie"
rec A2b true "$(jqv "$B" "['authenticated']" | tr 'A-Z' 'a-z')" "Session authenticated=true"

# --- A3 brand list (multi-brand agency) ---
B=$(api admin.jar GET /dps/brands); rec A3 200 "$(st)" "Agency lists brands"
NB=$(echo "$B" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
rec A3b 2 "$NB" "Agency sees exactly 2 brands (switcher visible)"

# --- A4 create creator in Aurora ---
B=$(api admin.jar POST /dps/api/creators '{"handle":"@shared_star","name":"Shared Star","platform":"instagram","followerCount":250000,"engagementRate":4.5,"preferredRate":5000,"currency":"USD","status":"active","email":"shared.star@creators.test"}')
rec A4 200,201 "$(st)" "ADMIN creates creator @shared_star in Aurora" "$B"
A_CREATOR=$(jqv "$B" "['id']")
echo "aurora creator=$A_CREATOR" >> "$OUT"

# The acting user must be recorded from the token, so a write can be attributed later.
AUD=$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A \
  -c "SELECT created_by_user_id FROM creator.creators WHERE id='$A_CREATOR';" 2>/dev/null | tr -d '\r')
rec A4b "$A_UID" "$AUD" "AUDIT: creator records the acting user"

# --- A5 duplicate creator -> 409 ---
B=$(api admin.jar POST /dps/api/creators '{"handle":"@shared_star","name":"Dup","platform":"instagram","preferredRate":1}')
rec A5 409 "$(st)" "Duplicate handle in same brand rejected (409)" "$B"

# --- A6 create campaign in Aurora ---
B=$(api admin.jar POST /dps/api/campaigns '{"name":"Aurora Summer Glow 2026","goal":"awareness","product":"Radiance Serum","budget":50000,"status":"active","campaignType":"paid","objective":"reach","startDate":"2026-08-01","endDate":"2026-09-30"}')
rec A6 200,201 "$(st)" "ADMIN creates campaign in Aurora" "$B"
A_CAMP=$(jqv "$B" "['id']")
echo "aurora campaign=$A_CAMP" >> "$OUT"

# --- A7 assign creator to campaign ---
B=$(api admin.jar POST /dps/api/campaign-creators "{\"campaignId\":\"$A_CAMP\",\"creatorId\":\"$A_CREATOR\",\"status\":\"confirmed\",\"agreedRate\":5000,\"deliverables\":\"3 reels\"}")
rec A7 200,201 "$(st)" "ADMIN assigns creator to campaign" "$B"
A_CC=$(jqv "$B" "['id']")

# --- A8 coupon for the assignment ---
B=$(api admin.jar POST /dps/api/influencer-campaign-codes "{\"campaignId\":\"$A_CAMP\",\"creatorId\":\"$A_CREATOR\",\"code\":\"AURORA-STAR-20\",\"discountType\":\"percentage\",\"discountValue\":20,\"commissionRate\":10,\"status\":\"active\"}")
rec A8 200,201 "$(st)" "ADMIN creates coupon code" "$B"
A_CODE=$(jqv "$B" "['id']")

# --- A9 workflow board ---
# The UI creates a board and then posts its stages separately via /workflow-board-stages/replace;
# the create call itself does not carry them.
STAGES='[{"stageName":"Prospect","position":0},{"stageName":"Outreach","position":1},{"stageName":"Negotiation","position":2},{"stageName":"Contracted","position":3},{"stageName":"In Production","position":4},{"stageName":"Published","position":5},{"stageName":"Paid","position":6}]'
B=$(api admin.jar POST /dps/api/workflow-boards '{"name":"Aurora Creator Pipeline","isActive":true,"position":0}')
rec A9 200,201 "$(st)" "ADMIN creates workflow board" "$B"
A_BOARD=$(jqv "$B" "['id']")

B=$(api admin.jar PUT /dps/api/workflow-board-stages/replace "{\"boardId\":\"$A_BOARD\",\"stages\":$STAGES}")
rec A9b 200,201 "$(st)" "ADMIN defines the board's 7 stages" "$B"
B=$(api admin.jar GET "/dps/api/workflow-board-stages?boardId=$A_BOARD")
A_STAGE=$(echo "$B" | python -c "import sys,json;d=json.load(sys.stdin);i=d if isinstance(d,list) else d.get('items',[]);i=sorted(i,key=lambda s:s.get('position',0));print(i[0]['id'] if i else '')" 2>/dev/null)
NST=$(echo "$B" | python -c "import sys,json;d=json.load(sys.stdin);i=d if isinstance(d,list) else d.get('items',[]);print(len(i))" 2>/dev/null)
rec A9c 7 "${NST:-0}" "All 7 stages persisted and listable"

# --- A10 workflow card ---
B=$(api admin.jar POST /dps/api/workflow-cards "{\"name\":\"Negotiate with @shared_star\",\"boardId\":\"$A_BOARD\",\"stageId\":\"$A_STAGE\",\"campaignId\":\"$A_CAMP\",\"creatorId\":\"$A_CREATOR\",\"status\":\"todo\",\"agreedFee\":5000,\"feeCurrency\":\"USD\"}")
rec A10 200,201 "$(st)" "ADMIN creates workflow card" "$B"
A_CARD=$(jqv "$B" "['id']")

# --- A10b move the card to the next stage (kanban drag) ---
A_STAGE2=$(api admin.jar GET "/dps/api/workflow-board-stages?boardId=$A_BOARD" | python -c "import sys,json;d=json.load(sys.stdin);i=d if isinstance(d,list) else d.get('items',[]);print(i[1]['id'] if len(i)>1 else '')" 2>/dev/null)
if [[ -n "$A_CARD" && -n "$A_STAGE2" ]]; then
  B=$(api admin.jar PUT "/dps/api/workflow-cards/$A_CARD/placement" "{\"boardId\":\"$A_BOARD\",\"stageId\":\"$A_STAGE2\",\"position\":0}")
  rec A10b 200,201 "$(st)" "ADMIN moves card to next stage" "$B"
else
  rec A10b 200 0 "ADMIN moves card to next stage (card/stage missing)"
fi

# --- A11 BRAND SWITCH to Lumen ---
B=$(api admin.jar POST /dps/brands/switch "{\"brandId\":\"$A_BRAND2\"}")
rec A11 200 "$(st)" "ADMIN switches brand to Lumen Fitness" "$B"
NOWB=$(jqv "$B" "['brandId']")
rec A11b "$A_BRAND2" "$NOWB" "Session brandId now Lumen"

# --- A12 tenancy isolation: Aurora creator must NOT be visible in Lumen ---
B=$(api admin.jar GET /dps/api/creators)
SEEN=$(echo "$B" | python -c "import sys,json;d=json.load(sys.stdin);i=d if isinstance(d,list) else d.get('items',[]);print(sum(1 for c in i if c.get('handle')=='@shared_star'))" 2>/dev/null)
rec A12 0 "${SEEN:-x}" "TENANCY: Aurora's @shared_star invisible under Lumen"

# --- A13 same handle can be created in Lumen with different rate ---
B=$(api admin.jar POST /dps/api/creators '{"handle":"@shared_star","name":"Shared Star","platform":"instagram","followerCount":250000,"preferredRate":2000,"currency":"USD","status":"active"}')
rec A13 200,201 "$(st)" "Same handle re-created under Lumen (per-brand rows)" "$B"
L_CREATOR=$(jqv "$B" "['id']")
rec A13b different "$([[ "$L_CREATOR" != "$A_CREATOR" && -n "$L_CREATOR" ]] && echo different || echo same)" "Lumen creator is a distinct row"

# --- A14 direct IDOR attempt: read Aurora creator by id while in Lumen ---
B=$(api admin.jar GET "/dps/api/creators/$A_CREATOR")
rec A14 403,404 "$(st)" "IDOR: cross-brand creator fetch refused" "$B"

# --- A15 switch back, rate preserved ---
B=$(api admin.jar POST /dps/brands/switch "{\"brandId\":\"$A_BRAND1\"}")
rec A15 200 "$(st)" "Switch back to Aurora"
B=$(api admin.jar GET "/dps/api/creators/$A_CREATOR")
rec A15c 200 "$(st)" "Own-brand creator readable after switching back" "$B"
# Rates are checked in the database: the read model does not project preferred_rate, so the
# API response cannot answer whether Lumen's 2000 overwrote Aurora's 5000.
RATE=$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A \
  -c "SELECT COALESCE(preferred_rate,0)::int FROM creator.creators WHERE id='$A_CREATOR';" 2>/dev/null | tr -d '\r')
rec A15b 5000 "${RATE:-0}" "Aurora rate still 5000 (not overwritten by Lumen 2000)"
LRATE=$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A \
  -c "SELECT COALESCE(preferred_rate,0)::int FROM creator.creators WHERE id='$L_CREATOR';" 2>/dev/null | tr -d '\r')
rec A15d 2000 "${LRATE:-0}" "Lumen keeps its own negotiated rate of 2000"

echo "################ ROLE MATRIX ################"

# --- R1 ANALYST read-only ---
BODY=$(login analyst.jar "demo.analyst@northstar.test" "DemoPass123!")
rec R1 200 "$(st)" "ANALYST login"
NBA=$(echo "$BODY" | python -c "import sys,json;print(len(json.load(sys.stdin)['availableBrands']))" 2>/dev/null)
rec R1b 1 "$NBA" "ANALYST scoped to 1 brand (Aurora only)"
B=$(api analyst.jar GET /dps/api/creators); rec R1c 200 "$(st)" "ANALYST can read creators"
B=$(api analyst.jar POST /dps/api/creators '{"handle":"@analyst_should_fail","name":"X","platform":"instagram"}')
rec R1d 403 "$(st)" "ANALYST write refused (403)" "$B"

# --- R2 MARKETER: campaign work yes, finance no ---
BODY=$(login marketer.jar "demo.marketer@northstar.test" "DemoPass123!")
rec R2 200 "$(st)" "MARKETER login"
B=$(api marketer.jar POST /dps/api/creators '{"handle":"@marketer_ok","name":"Marketer Creator","platform":"tiktok","preferredRate":900}')
rec R2b 200,201 "$(st)" "MARKETER can create creator" "$B"
M_CREATOR=$(jqv "$B" "['id']")
B=$(api marketer.jar POST /dps/api/influencer-payouts/create "{\"creatorId\":\"$M_CREATOR\",\"providerKey\":\"manual\"}")
rec R2c 403 "$(st)" "MARKETER payout creation refused (403)" "$B"

# --- R3 MANAGER: approve commission yes, payout no ---
BODY=$(login manager.jar "demo.manager@northstar.test" "DemoPass123!")
rec R3 200 "$(st)" "MANAGER login"
B=$(api manager.jar GET /dps/api/influencer-commissions); rec R3b 200 "$(st)" "MANAGER reads commissions"
B=$(api manager.jar POST /dps/api/influencer-payouts/create "{\"creatorId\":\"$M_CREATOR\",\"providerKey\":\"manual\"}")
rec R3c 403 "$(st)" "SoD: MANAGER cannot create payout (403)" "$B"

# --- R4 FINANCE: payouts yes, creator edits no ---
BODY=$(login finance.jar "demo.finance@northstar.test" "DemoPass123!")
rec R4 200 "$(st)" "FINANCE login"
B=$(api finance.jar GET /dps/api/influencer-payouts); rec R4b 200 "$(st)" "FINANCE reads payouts"
B=$(api finance.jar POST /dps/api/creators '{"handle":"@finance_should_fail","name":"X","platform":"instagram"}')
rec R4c 403 "$(st)" "SoD: FINANCE cannot create creator (403)" "$B"

echo "################ BRAND OWNER (solo) ################"

BOEMAIL="e2e.brand.owner@veridianglow.test"
BODY=$(login brand.jar "$BOEMAIL" "DemoPass123!")
rec B1 200 "$(st)" "Solo brand OWNER login"
B_BRAND=$(jqv "$BODY" "['brandId']")
B_UID=$(jqv "$BODY" "['userId']")
NBB=$(echo "$BODY" | python -c "import sys,json;print(len(json.load(sys.stdin)['availableBrands']))" 2>/dev/null)
rec B1b 1 "$NBB" "Solo brand sees exactly 1 brand (switcher hidden)"
echo "brand owner uid=$B_UID brand=$B_BRAND" >> "$OUT"

B=$(api brand.jar POST /dps/api/creators '{"handle":"@veridian_muse","name":"Veridian Muse","platform":"youtube","followerCount":88000,"engagementRate":6.1,"preferredRate":1500,"currency":"USD","status":"active","email":"muse@creators.test"}')
rec B2 200,201 "$(st)" "OWNER creates creator" "$B"
B_CREATOR=$(jqv "$B" "['id']")

B=$(api brand.jar POST /dps/api/campaigns '{"name":"Veridian Launch Q3","goal":"conversion","product":"Green Tea Cleanser","budget":12000,"status":"active","campaignType":"affiliate","startDate":"2026-08-05","endDate":"2026-10-05"}')
rec B3 200,201 "$(st)" "OWNER creates campaign" "$B"
B_CAMP=$(jqv "$B" "['id']")

B=$(api brand.jar POST /dps/api/campaign-creators "{\"campaignId\":\"$B_CAMP\",\"creatorId\":\"$B_CREATOR\",\"status\":\"confirmed\",\"agreedRate\":1500,\"deliverables\":\"2 videos\"}")
rec B4 200,201 "$(st)" "OWNER assigns creator to campaign" "$B"

B=$(api brand.jar POST /dps/api/influencer-campaign-codes "{\"campaignId\":\"$B_CAMP\",\"creatorId\":\"$B_CREATOR\",\"code\":\"VERIDIAN-MUSE-15\",\"discountType\":\"percentage\",\"discountValue\":15,\"commissionType\":\"percent\",\"commissionValue\":12,\"status\":\"active\"}")
rec B5 200,201 "$(st)" "OWNER creates coupon" "$B"
B_CODE=$(jqv "$B" "['id']")
B_CODESTR=$(jqv "$B" "['code']")

# --- attribution: simulate an order against the coupon ---
B=$(api brand.jar POST /dps/api/attribution/simulate "{\"providerKey\":\"mock\",\"order\":{\"code\":\"${B_CODESTR:-VERIDIAN-MUSE-15}\",\"orderId\":\"E2E-ORDER-1\",\"saleAmount\":420.00,\"discountAmount\":63.00,\"currency\":\"USD\",\"status\":\"completed\"}}")
rec B6 200,201 "$(st)" "OWNER simulates attributed order" "$B"

B=$(api brand.jar GET /dps/api/influencer-sale-attributions)
rec B7 200 "$(st)" "Attribution recorded and listable" "$B"

B=$(api brand.jar GET /dps/api/influencer-commissions)
rec B8 200 "$(st)" "Commission generated from attribution" "$B"
B_COMM=$(echo "$B" | python -c "import sys,json;d=json.load(sys.stdin);i=d if isinstance(d,list) else d.get('items',[]);print(i[0]['id'] if i else '')" 2>/dev/null)

if [[ -n "$B_COMM" ]]; then
  B=$(api brand.jar POST "/dps/api/influencer-commissions/$B_COMM/approve" '{}')
  rec B9 200,201 "$(st)" "OWNER approves commission" "$B"
  # The Payouts page settles per creator: it groups approved commissions and pays one creator.
  B=$(api brand.jar POST /dps/api/influencer-payouts/create "{\"creatorId\":\"$B_CREATOR\",\"providerKey\":\"manual\"}")
  rec B10 200,201 "$(st)" "OWNER creates payout batch" "$B"

  # Status codes alone would not catch a commission that silently computed to zero,
  # so the amounts are asserted against the database.
  dbq() { docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "$1" 2>/dev/null | tr -d '\r'; }
  rec B10b 420.00 "$(dbq "SELECT gross_sale FROM finance.influencer_commissions WHERE id='$B_COMM';")" \
      "Commission gross_sale equals the 420.00 order"
  rec B10c 50.40 "$(dbq "SELECT commission_amount FROM finance.influencer_commissions WHERE id='$B_COMM';")" \
      "Commission is 12% of 420.00 = 50.40"
  rec B10d 50.40 "$(dbq "SELECT total_amount FROM finance.influencer_payouts WHERE brand_id='$B_BRAND' ORDER BY created_at DESC LIMIT 1;")" \
      "Payout total equals the approved commission"
  rec B10e paid "$(dbq "SELECT status FROM finance.influencer_commissions WHERE id='$B_COMM';")" \
      "Commission moves to paid once settled"
  rec B10f 1 "$(dbq "SELECT count(*) FROM finance.influencer_commissions WHERE id='$B_COMM' AND payout_id IS NOT NULL;")" \
      "Commission is linked to its payout"
else
  rec B9 200 0 "OWNER approves commission (no commission found)"
  rec B10 200 0 "OWNER creates payout batch (no commission found)"
fi

# --- workflow for brand owner ---
B=$(api brand.jar POST /dps/api/workflow-boards '{"name":"Veridian Outreach","isActive":true,"position":0}')
rec B11 200,201 "$(st)" "OWNER creates workflow board" "$B"
B_BOARD=$(jqv "$B" "['id']")
B=$(api brand.jar PUT /dps/api/workflow-board-stages/replace "{\"boardId\":\"$B_BOARD\",\"stages\":$STAGES}")
rec B11b 200,201 "$(st)" "OWNER defines the board's stages" "$B"
B=$(api brand.jar GET "/dps/api/workflow-board-stages?boardId=$B_BOARD")
B_STAGE=$(echo "$B" | python -c "import sys,json;d=json.load(sys.stdin);i=d if isinstance(d,list) else d.get('items',[]);i=sorted(i,key=lambda s:s.get('position',0));print(i[0]['id'] if i else '')" 2>/dev/null)
B=$(api brand.jar POST /dps/api/workflow-cards "{\"name\":\"Brief @veridian_muse\",\"boardId\":\"$B_BOARD\",\"stageId\":\"$B_STAGE\",\"campaignId\":\"$B_CAMP\",\"creatorId\":\"$B_CREATOR\",\"status\":\"todo\",\"agreedFee\":1500,\"feeCurrency\":\"USD\"}")
rec B12 200,201 "$(st)" "OWNER creates workflow card" "$B"
B_CARD=$(jqv "$B" "['id']")

# --- content ---
B=$(api brand.jar POST /dps/api/campaign-briefs "{\"campaignId\":\"$B_CAMP\",\"title\":\"Q3 Launch Brief\",\"body\":\"Tone: fresh, botanical.\",\"status\":\"draft\"}")
rec B13 200,201 "$(st)" "OWNER creates campaign brief" "$B"

B=$(api brand.jar GET /dps/api/analytics/influencer-revenue)
rec B14 200 "$(st)" "OWNER reads revenue analytics" "$B"

echo "################ CROSS-TENANT ISOLATION ################"

# brand owner must not see agency data
B=$(api brand.jar GET "/dps/api/creators/$A_CREATOR")
rec X1 403,404 "$(st)" "Brand OWNER cannot read agency creator" "$B"
B=$(api brand.jar GET "/dps/api/campaigns/$A_CAMP")
rec X2 403,404 "$(st)" "Brand OWNER cannot read agency campaign" "$B"
# agency must not see brand owner data
B=$(api admin.jar GET "/dps/api/creators/$B_CREATOR")
rec X3 403,404 "$(st)" "Agency ADMIN cannot read solo-brand creator" "$B"
B=$(api admin.jar GET "/dps/api/campaigns/$B_CAMP")
rec X4 403,404 "$(st)" "Agency ADMIN cannot read solo-brand campaign" "$B"
# unauthenticated
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "$DPS/dps/api/creators")
rec X5 401 "$S" "Unauthenticated API call refused (401)"
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" "$DPS/dps/api/creators" -H "Cookie: dps_session=forged-session-value")
rec X6 401 "$S" "Forged session cookie refused (401)"
# CSRF: a valid session cookie WITHOUT the echoed double-submit header must be refused.
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" -b "$SP/admin.jar" -X POST "$DPS/dps/api/creators" \
     -H "Content-Type: application/json" -d '{"handle":"@csrf_probe","name":"X","platform":"instagram"}')
rec X7 403 "$S" "CSRF: state change without X-XSRF-TOKEN refused (403)"

echo "################ LOGOUT ################"
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" -b "$SP/brand.jar" \
     -H "X-XSRF-TOKEN: $(csrf_of brand.jar)" -X POST "$DPS/dps/auth/logout")
rec L1 204,200 "$S" "Logout succeeds"
B=$(api brand.jar GET /dps/session)
AUTHED=$(jqv "$B" "['authenticated']" | tr 'A-Z' 'a-z')
rec L2 false "$AUTHED" "Session invalid after logout"
S=$(curl -s -m 20 -o /dev/null -w "%{http_code}" -b "$SP/brand.jar" "$DPS/dps/api/creators")
rec L3 401 "$S" "API refused after logout (401)"

# expose ids for the DB report
cat > "$SP/ids.env" <<EOF
A_UID=$A_UID
A_ACCT=$A_ACCT
A_BRAND1=$A_BRAND1
A_BRAND2=$A_BRAND2
A_CREATOR=$A_CREATOR
L_CREATOR=$L_CREATOR
A_CAMP=$A_CAMP
A_CC=$A_CC
A_CODE=$A_CODE
A_BOARD=$A_BOARD
A_CARD=$A_CARD
M_CREATOR=$M_CREATOR
B_UID=$B_UID
B_BRAND=$B_BRAND
B_CREATOR=$B_CREATOR
B_CAMP=$B_CAMP
B_CODE=$B_CODE
B_COMM=$B_COMM
B_BOARD=$B_BOARD
B_CARD=$B_CARD
EOF

echo ""
echo "=========================================="
echo "PASS: $PASS   FAIL: $FAIL"
echo "=========================================="
if (( FAIL )); then printf '%s\n' "${FAILED[@]}"; fi
