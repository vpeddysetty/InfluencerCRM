#!/usr/bin/env bash
# Workflow board stage identity — regression suite.
#
# The bug this pins down: PUT /workflow-board-stages/replace used to delete every stage on
# the board and re-insert with fresh ids. workflow_cards.stage_id is `on delete set null`, so
# renaming ONE stage silently unplaced EVERY card on the board — including cards sitting in
# stages the user never touched. No error, no warning; the cards simply vanished from the
# board and reappeared in the unplaced list.
#
# W3 is the assertion that would have caught it. W5 is its necessary counterpart: a stage the
# user genuinely deletes SHOULD unplace its cards, and a fix that just stopped deleting
# would be wrong in the other direction.
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

api() { # api <method> <path> <token> [body]
  local m="$1" p="$2" t="$3" b="$4"
  if [[ -n "$b" ]]; then
    curl -s -m 30 -X "$m" "$BFF$p" -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
      -d "$b" -o "$SP/.wbody" -w '%{http_code}' > "$SP/.wcode"
  else
    curl -s -m 30 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.wbody" -w '%{http_code}' > "$SP/.wcode"
  fi
  cat "$SP/.wbody"
}
st() { cat "$SP/.wcode" 2>/dev/null; }

EMAIL="ws.brand.$STAMP@example.test"

echo "################ setup: board, three stages, one placed card ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"WS Brand\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
BOARD=$(jqv "$(api POST /api/workflow-boards "$TOKEN" '{"name":"WS Board","isActive":true}')" "['id']")
STAGES=$(api PUT /api/workflow-board-stages/replace "$TOKEN" \
  "{\"boardId\":\"$BOARD\",\"stages\":[{\"stageName\":\"Outreach\",\"position\":0},{\"stageName\":\"Negotiating\",\"position\":1},{\"stageName\":\"Live\",\"position\":2}]}")
echo "$STAGES" > "$SP/.wstages"
rec SETUP 3 "$(echo "$STAGES" | python -c "import sys,json;print(len(json.load(sys.stdin)))")" "three stages created"

S0=$(python -c "import json;print(json.load(open(r'$SP/.wstages'))[0]['id'])")
S1=$(python -c "import json;print(json.load(open(r'$SP/.wstages'))[1]['id'])")

CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"WS Campaign","status":"active"}')" "['id']")
CREATOR=$(jqv "$(api POST /api/creators "$TOKEN" '{"handle":"@ws_creator","name":"WS Creator","platform":"instagram"}')" "['id']")
CARD=$(jqv "$(api POST /api/workflow-cards "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"creatorId\":\"$CREATOR\",\"name\":\"WS Card\",\"boardId\":\"$BOARD\",\"stageId\":\"$S0\"}")" "['id']")
PLACED=$($PG -c "select stage_id from workflow.workflow_cards where id='$CARD';" | tr -d '\r')
rec SETUP2 "$S0" "$PLACED" "card placed in the FIRST stage"

echo "################ W1: renaming a stage keeps its id ################"
# If ids churn on every save, nothing can hold a stable reference to a stage — not a card,
# not a stage mapping, not an automation rule.
RENAMED=$(api PUT /api/workflow-board-stages/replace "$TOKEN" "$(python -c "
import json
s=json.load(open(r'$SP/.wstages'))
s[2]['stageName']='Published'
print(json.dumps({'boardId':'$BOARD','stages':[{'id':x['id'],'stageName':x['stageName'],'position':i} for i,x in enumerate(s)]}))")")
rec W1 200 "$(st)" "rename accepted"
rec W1b "$S0" "$(echo "$RENAMED" | python -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")" \
    "the first stage kept its id through the save"
rec W1c Published "$(echo "$RENAMED" | python -c "import sys,json;print(json.load(sys.stdin)[2]['stageName'])")" \
    "the third stage was actually renamed"

echo "################ W2: no duplicate stages were created ################"
COUNT=$($PG -c "select count(*) from workflow.workflow_board_stages where board_id='$BOARD';" | tr -d '\r')
rec W2 3 "$COUNT" "still three stages — update in place, not delete-and-recreate"

echo "################ W3: THE BUG — the card is still placed ################"
STILL=$($PG -c "select coalesce(stage_id::text,'ORPHANED') from workflow.workflow_cards where id='$CARD';" | tr -d '\r')
rec W3 "$S0" "$STILL" "renaming an UNRELATED stage did not unplace the card"

echo "################ W4: reordering also preserves placement ################"
REORDERED=$(api PUT /api/workflow-board-stages/replace "$TOKEN" "$(python -c "
import json
s=json.load(open(r'$SP/.wstages'))
s[2]['stageName']='Published'
s.reverse()
print(json.dumps({'boardId':'$BOARD','stages':[{'id':x['id'],'stageName':x['stageName'],'position':i} for i,x in enumerate(s)]}))")")
rec W4 200 "$(st)" "reorder accepted"
AFTER_REORDER=$($PG -c "select coalesce(stage_id::text,'ORPHANED') from workflow.workflow_cards where id='$CARD';" | tr -d '\r')
rec W4b "$S0" "$AFTER_REORDER" "card still placed after reordering the whole board"
POS=$($PG -c "select position from workflow.workflow_board_stages where id='$S0';" | tr -d '\r')
rec W4c 2 "$POS" "the stage did move — position updated, identity did not"

echo "################ W5: a genuinely deleted stage DOES unplace its cards ################"
# The counterpart to W3. A fix that simply stopped deleting would be wrong here: when a user
# really removes a stage, its cards must come off it.
api PUT /api/workflow-board-stages/replace "$TOKEN" "$(python -c "
import json
s=json.load(open(r'$SP/.wstages'))
keep=[x for x in s if x['id']!='$S0']
print(json.dumps({'boardId':'$BOARD','stages':[{'id':x['id'],'stageName':x['stageName'],'position':i} for i,x in enumerate(keep)]}))")" > /dev/null
rec W5 200 "$(st)" "deletion accepted"
GONE=$($PG -c "select count(*) from workflow.workflow_board_stages where id='$S0';" | tr -d '\r')
rec W5b 0 "$GONE" "the removed stage is actually gone"
UNPLACED=$($PG -c "select coalesce(stage_id::text,'unplaced') from workflow.workflow_cards where id='$CARD';" | tr -d '\r')
rec W5c unplaced "$UNPLACED" "its card was correctly unplaced — deletion still means deletion"

echo "################ W6: a stage id from another board is not honoured ################"
# Otherwise a caller could rename or steal a stage it does not own by quoting its id.
OTHER_BOARD=$(jqv "$(api POST /api/workflow-boards "$TOKEN" '{"name":"WS Other","isActive":false}')" "['id']")
api PUT /api/workflow-board-stages/replace "$TOKEN" \
  "{\"boardId\":\"$OTHER_BOARD\",\"stages\":[{\"id\":\"$S1\",\"stageName\":\"Hijacked\",\"position\":0}]}" > /dev/null
rec W6 200 "$(st)" "request accepted"
S1_NAME=$($PG -c "select stage_name from workflow.workflow_board_stages where id='$S1';" | tr -d '\r')
rec W6b Negotiating "$S1_NAME" "the other board's stage was NOT renamed"
S1_BOARD=$($PG -c "select board_id from workflow.workflow_board_stages where id='$S1';" | tr -d '\r')
rec W6c "$BOARD" "$S1_BOARD" "and it was NOT moved to the other board"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL board=$BOARD card=$CARD"
