#!/usr/bin/env bash
# Stage automation and bidirectional Kanban sync — roadmap Phase D (§4, §5 Phase D).
#
# Decision #8 made the board WRITABLE in both directions: dragging a card changes the page
# stage, and changing the page stage moves the card. Two writable state machines that must
# agree is the shape that eventually produces a card in "Published" for a page still in draft.
# §4 sets out four rules to stop that, and this suite is those rules:
#
#   Rule 1 (content owns the transition) — D8, D9. A drag is a COMMAND content may refuse,
#          not a direct write. D9 is the important half: a refused drag must leave the card
#          where it was, because compensating a UI drag is far worse than refusing it.
#   Rule 2 (not every transition is legal) — D3.
#   Rule 3 (some transitions need more than a stage change) — D4.
#   Rule 4 (source tagging + idempotency) — D6, D7, D10.
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
      -d "$b" -o "$SP/.dbody" -w '%{http_code}' > "$SP/.dcode"
  else
    curl -s -m 30 -X "$m" "$BFF$p" -H "Authorization: Bearer $t" -o "$SP/.dbody" -w '%{http_code}' > "$SP/.dcode"
  fi
  cat "$SP/.dbody"
}
st() { cat "$SP/.dcode" 2>/dev/null; }

EMAIL="sa.brand.$STAMP@example.test"

echo "################ setup: board, stages, mappings, a page-tracking card ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"SA Brand\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

BOARD=$(jqv "$(api POST /api/workflow-boards "$TOKEN" '{"name":"SA Board","isActive":true}')" "['id']")
api PUT /api/workflow-board-stages/replace "$TOKEN" \
  "{\"boardId\":\"$BOARD\",\"stages\":[{\"stageName\":\"Draft\",\"position\":0},{\"stageName\":\"Review\",\"position\":1},{\"stageName\":\"Live\",\"position\":2}]}" > "$SP/.dstages"
S0=$(python -c "import json;print(json.load(open(r'$SP/.dstages'))[0]['id'])")
S1=$(python -c "import json;print(json.load(open(r'$SP/.dstages'))[1]['id'])")
S2=$(python -c "import json;print(json.load(open(r'$SP/.dstages'))[2]['id'])")
rec SETUP nonempty "$([[ -n "$S0" && -n "$S1" && -n "$S2" ]] && echo nonempty || echo empty)" "three board stages"

CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"SA Campaign","status":"active"}')" "['id']")
PAGE=$(jqv "$(api POST /api/landing-templates/save "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"name\":\"SA Page\",\"document\":{\"html\":\"<h1>Content</h1>\"}}")" "['id']")
CREATOR=$(jqv "$(api POST /api/creators "$TOKEN" '{"handle":"@sa_creator","name":"SA Creator","platform":"instagram"}')" "['id']")
CARD=$(jqv "$(api POST /api/workflow-cards "$TOKEN" \
  "{\"campaignId\":\"$CAMPAIGN\",\"creatorId\":\"$CREATOR\",\"name\":\"SA Card\",\"boardId\":\"$BOARD\",\"stageId\":\"$S0\",\"landingTemplateId\":\"$PAGE\"}")" "['id']")
TRACKS=$($PG -c "select coalesce(landing_template_id::text,'none') from workflow.workflow_cards where id='$CARD';" | tr -d '\r')
rec SETUP2 "$PAGE" "$TRACKS" "card tracks the landing page"

echo "################ D1: the stage vocabulary is published ################"
STAGES=$(api GET /api/landing-pages/stages "$TOKEN")
rec D1 8 "$(echo "$STAGES" | python -c "import sys,json;print(len(json.load(sys.stdin)['stages']))")" \
    "eight page stages"
rec D1b true "$(echo "$STAGES" | python -c "
import sys,json
d=json.load(sys.stdin)['allowed']
print('true' if 'published' not in d['draft'] else 'false')")" \
    "the map says draft cannot reach published directly — the UI can grey out illegal drops"

echo "################ D2-D3: rule 2, the transition map is enforced ################"
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"published","source":"builder"}' > /dev/null
rec D3 409 "$(st)" "draft -> published refused: a gate is a product decision, not a drag gesture"
REASON=$(api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"published","source":"builder"}')
rec D3b true "$(echo "$REASON" | grep -q "Allowed from" && echo true || echo false)" \
    "the refusal names the legal targets, so the UI can explain the snap-back"

for S in review approved ready_to_publish published; do
  api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" "{\"to\":\"$S\",\"source\":\"builder\"}" > /dev/null
  rec D2 200 "$(st)" "legal step -> $S"
done

echo "################ D5: reaching 'published' actually publishes ################"
# Otherwise the board reports Published while the public URL 404s — precisely the divergence
# this phase exists to prevent, just expressed through `status` instead of `stage`.
ROW=$($PG -c "select stage||'|'||status from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec D5 "published|published" "$ROW" "stage AND status both published — the page serves"

echo "################ D4: rule 3, an empty page cannot be published ################"
# A page with no content reaching Published would deploy a blank page. The check has to
# happen at the command boundary, before anything moves, so nothing needs compensating.
EMPTY_CAMPAIGN=$(jqv "$(api POST /api/campaigns "$TOKEN" '{"name":"SA Empty","status":"active"}')" "['id']")
EMPTY_PAGE=$(jqv "$(api POST /api/landing-templates/save "$TOKEN" "{\"campaignId\":\"$EMPTY_CAMPAIGN\",\"name\":\"SA Empty Page\"}")" "['id']")
for S in review approved ready_to_publish; do
  api PUT "/api/landing-pages/$EMPTY_PAGE/stage" "$TOKEN" "{\"to\":\"$S\",\"source\":\"builder\"}" > /dev/null
done
api PUT "/api/landing-pages/$EMPTY_PAGE/stage" "$TOKEN" '{"to":"published","source":"builder"}' > /dev/null
rec D4 409 "$(st)" "an empty page is refused publication"
EMPTY_STAGE=$($PG -c "select stage from content.landing_templates where id='$EMPTY_PAGE';" | tr -d '\r')
rec D4b ready_to_publish "$EMPTY_STAGE" "and it did NOT move — the refusal happened before the write"

echo "################ D6: rule 4, every transition is logged with its origin ################"
COUNT=$($PG -c "select count(*) from workflow.stage_transitions where landing_template_id='$PAGE';" | tr -d '\r')
rec D6 4 "$COUNT" "four legal moves, four rows — refusals are not logged as transitions"
SOURCES=$($PG -c "select distinct source from workflow.stage_transitions where landing_template_id='$PAGE';" | tr -d '\r')
rec D6b builder "$SOURCES" "each row records where the change came from"

echo "################ D7: rule 4, idempotency ################"
# The outbox is at-least-once, so a retried or duplicated command must be absorbed rather
# than moving a card a second time.
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"performance_tracking","source":"api"}' > /dev/null
KEY="sa-key-$STAMP"
for i in 1 2 3; do
  api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" \
    "{\"to\":\"published\",\"source\":\"api\",\"idempotencyKey\":\"$KEY\"}" > /dev/null
  rec D7 200 "$(st)" "repeat $i accepted (a retry is not an error)"
done
KEY_ROWS=$($PG -c "select count(*) from workflow.stage_transitions where idempotency_key='$KEY';" | tr -d '\r')
rec D7b 1 "$KEY_ROWS" "three identical commands wrote ONE transition row"

echo "################ D8-D9: rule 1, a board drag is a command content may refuse ################"
# Reset to draft so the illegal drag below is genuinely illegal.
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"ready_to_publish","source":"builder"}' > /dev/null
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"approved","source":"builder"}' > /dev/null
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"review","source":"builder"}' > /dev/null
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"draft","source":"builder"}' > /dev/null

for pair in "draft:$S0" "review:$S1" "published:$S2"; do
  PS=${pair%%:*}; SID=${pair##*:}
  api POST /api/stage-mappings "$TOKEN" "{\"boardId\":\"$BOARD\",\"pageStage\":\"$PS\",\"stageId\":\"$SID\"}" > /dev/null
done

BEFORE=$($PG -c "select coalesce(stage_id::text,'none') from workflow.workflow_cards where id='$CARD';" | tr -d '\r')
api PUT "/api/workflow-cards/$CARD/placement" "$TOKEN" "{\"boardId\":\"$BOARD\",\"stageId\":\"$S2\",\"position\":0}" > /dev/null
rec D9 409 "$(st)" "dragging to the Live column from draft is REFUSED"
AFTER=$($PG -c "select coalesce(stage_id::text,'none') from workflow.workflow_cards where id='$CARD';" | tr -d '\r')
rec D9b "$BEFORE" "$AFTER" "the card did NOT move — nothing to compensate"
PAGE_STAGE=$($PG -c "select stage from content.landing_templates where id='$PAGE';" | tr -d '\r')
rec D9c draft "$PAGE_STAGE" "and the page did not move either"

api PUT "/api/workflow-cards/$CARD/placement" "$TOKEN" "{\"boardId\":\"$BOARD\",\"stageId\":\"$S1\",\"position\":0}" > /dev/null
rec D8 200 "$(st)" "a LEGAL drag (Draft -> Review) is accepted"
rec D8b review "$($PG -c "select stage from content.landing_templates where id='$PAGE';" | tr -d '\r')" \
    "the drag drove the PAGE stage — the board is writable (decision #8)"
rec D8c "$S1" "$($PG -c "select stage_id from workflow.workflow_cards where id='$CARD';" | tr -d '\r')" \
    "and the card is where it was dropped"

echo "################ D10: rule 4, a board-originated change does not echo ################"
BOARD_ROWS=$($PG -c "select count(*) from workflow.stage_transitions where landing_template_id='$PAGE' and source='board';" | tr -d '\r')
rec D10 1 "$BOARD_ROWS" "one board-sourced transition, not two — the card was not pushed back"

echo "################ D11: the reverse direction ################"
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"draft","source":"builder"}' > /dev/null
rec D11 200 "$(st)" "builder moves the page back to draft"
rec D11b "$S0" "$($PG -c "select stage_id from workflow.workflow_cards where id='$CARD';" | tr -d '\r')" \
    "the CARD followed the page to the Draft column — sync runs both ways"

echo "################ D12: stage mappings upsert rather than duplicate ################"
api POST /api/stage-mappings "$TOKEN" "{\"boardId\":\"$BOARD\",\"pageStage\":\"draft\",\"stageId\":\"$S1\"}" > /dev/null
MAPPINGS=$($PG -c "select count(*) from workflow.stage_mappings where board_id='$BOARD' and page_stage='draft';" | tr -d '\r')
rec D12 1 "$MAPPINGS" "re-saving a mapping updates it — a unique constraint covers (board, stage)"
# Put it back so the suite leaves consistent data behind.
api POST /api/stage-mappings "$TOKEN" "{\"boardId\":\"$BOARD\",\"pageStage\":\"draft\",\"stageId\":\"$S0\"}" > /dev/null

echo "################ D13: tenancy ################"
# placeCard previously performed NO authorization at all: any caller could place any card by
# id. Phase D closes that, so it is worth pinning.
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"sa.other.$STAMP@example.test\",\"password\":\"DemoPass123!\",\"brandName\":\"SA Other\",\"accountType\":\"brand\",\"acceptedTerms\":true}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
api PUT "/api/workflow-cards/$CARD/placement" "$OTHER" "{\"boardId\":\"$BOARD\",\"stageId\":\"$S2\"}" > /dev/null
rec D13 404 "$(st)" "another brand cannot place our card"
api PUT "/api/landing-pages/$PAGE/stage" "$OTHER" '{"to":"review","source":"api"}' > /dev/null
rec D13b 404 "$(st)" "another brand cannot change our page's stage"
api GET "/api/landing-pages/$PAGE/transitions" "$OTHER" > /dev/null
rec D13c 404 "$(st)" "another brand cannot read our transition log"

NO_AUTH=$(curl -s -m 20 -X PUT "$BFF/api/workflow-cards/$CARD/placement" -H "Content-Type: application/json" \
  -d "{\"boardId\":\"$BOARD\",\"stageId\":\"$S2\"}" -o /dev/null -w '%{http_code}')
rec D13d 401 "$NO_AUTH" "and an unauthenticated placement is refused outright"

rec D13e "$S0" "$($PG -c "select stage_id from workflow.workflow_cards where id='$CARD';" | tr -d '\r')" \
    "after every cross-tenant attempt the card is untouched"

echo "################ D14: an unknown stage or source is refused ################"
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"not_a_stage","source":"builder"}' > /dev/null
rec D14 400 "$(st)" "unknown stage rejected"
api PUT "/api/landing-pages/$PAGE/stage" "$TOKEN" '{"to":"review","source":"gremlin"}' > /dev/null
rec D14b 400 "$(st)" "unknown source rejected — an unrecognised origin could suppress the wrong echo"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL board=$BOARD page=$PAGE card=$CARD"
