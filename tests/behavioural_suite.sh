#!/usr/bin/env bash
# Full behavioural suite against the running stack.
# Emits PASS/FAIL per case so the results can be pasted straight into the report.
B="http://localhost:8081"
DAO="https://localhost:8443"
SVC="wdZcoE9N3fhGST9heyi4QAXRTiFGWC5jKHhdyl05Pk8"
PASS=0; FAIL=0

jqv() { python -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1',''))" 2>/dev/null; }

chk() { # chk <label> <actual> <expected>
  if [ "$2" = "$3" ]; then printf "  PASS  %-52s %s\n" "$1" "$2"; PASS=$((PASS+1));
  else printf "  FAIL  %-52s got=%s want=%s\n" "$1" "$2" "$3"; FAIL=$((FAIL+1)); fi
}
chkn() { # chk not-equal (e.g. "not 403")
  if [ "$2" != "$3" ]; then printf "  PASS  %-52s %s\n" "$1" "$2"; PASS=$((PASS+1));
  else printf "  FAIL  %-52s got=%s (must differ)\n" "$1" "$2"; FAIL=$((FAIL+1)); fi
}
login() {
  curl -s -X POST "$B/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"DemoPass123!\"}" --max-time 30
}
code() { curl -s -o /dev/null -w '%{http_code}' "$@" --max-time 30; }

echo "=============================================================="
echo " A. AUTHENTICATION & SECURITY FLOOR"
echo "=============================================================="
chk "unauthenticated GET /api/creators -> 401" "$(code "$B/api/creators")" "401"
chk "userId injection (no token) -> 401" "$(code "$B/api/creators?userId=11111111-1111-1111-1111-111111111111")" "401"
chk "forged bearer token -> 401" "$(code -H 'Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.fake.sig' "$B/api/creators")" "401"
chk "DAO reachable directly without service token -> 401" "$(curl -sk -o /dev/null -w '%{http_code}' "$DAO/creators" --max-time 30)" "401"
chk "DAO with wrong service token -> 401" "$(curl -sk -o /dev/null -w '%{http_code}' -H 'X-Service-Token: wrong' "$DAO/creators" --max-time 30)" "401"
chk "DAO with correct service token -> 200" "$(curl -sk -o /dev/null -w '%{http_code}' -H "X-Service-Token: $SVC" "$DAO/creators" --max-time 30)" "200"
chk "public health endpoint -> 200" "$(code "$B/health")" "200"

echo
echo "=============================================================="
echo " B. SOLO BRAND (the original single-brand product)"
echo "=============================================================="
SOLO="solo.$(date +%s)@example.com"
R=$(curl -s -X POST "$B/api/auth/signup" -H 'Content-Type: application/json' -d "{\"email\":\"$SOLO\",\"password\":\"DemoPass123!\",\"brandName\":\"Solo Demo Co\"}" --max-time 30)
ST=$(echo "$R" | jqv accessToken); SB=$(echo "$R" | jqv brandId)
chk "signup issues an access token" "$([ -n "$ST" ] && echo yes || echo no)" "yes"
chk "signup auto-provisions a brand" "$([ -n "$SB" ] && echo yes || echo no)" "yes"
chk "solo account role is OWNER" "$(echo "$R" | jqv role)" "OWNER"
chk "solo sees exactly 1 brand (switcher hidden)" "$(curl -s -H "Authorization: Bearer $ST" "$B/api/brands" --max-time 30 | python -c 'import sys,json;print(len(json.load(sys.stdin)))')" "1"
chk "OWNER can create a creator" "$(code -X POST "$B/api/creators" -H "Authorization: Bearer $ST" -H 'Content-Type: application/json' -d '{"platform":"instagram","handle":"@solo_demo","status":"active"}')" "200"
chk "OWNER can create a campaign" "$(code -X POST "$B/api/campaigns" -H "Authorization: Bearer $ST" -H 'Content-Type: application/json' -d '{"name":"Solo Demo Campaign","status":"draft","campaignType":"paid"}')" "200"
chk "OWNER can create a workflow board" "$(code -X POST "$B/api/workflow-boards" -H "Authorization: Bearer $ST" -H 'Content-Type: application/json' -d '{"name":"Solo Board","isActive":true,"position":0}')" "200"
for ep in creators campaigns workflow-boards influencer-campaign-codes influencer-commissions influencer-payouts brands; do
  chk "OWNER GET /api/$ep" "$(code -H "Authorization: Bearer $ST" "$B/api/$ep")" "200"
done

echo
echo "=============================================================="
echo " C. AGENCY MULTI-BRAND"
echo "=============================================================="
AR=$(login demo.admin@northstar.test)
AT=$(echo "$AR" | jqv accessToken)
chk "agency ADMIN can log in" "$([ -n "$AT" ] && echo yes || echo no)" "yes"
chk "ADMIN role resolves" "$(echo "$AR" | jqv role)" "ADMIN"
BRANDS=$(curl -s -H "Authorization: Bearer $AT" "$B/api/brands" --max-time 30)
chk "ADMIN reaches both client brands" "$(echo "$BRANDS" | python -c 'import sys,json;print(len(json.load(sys.stdin)))')" "2"
AURORA=$(echo "$BRANDS" | python -c "import sys,json;print(next((b['brandId'] for b in json.load(sys.stdin) if 'Aurora' in b['brandName']),''))")
LUMEN=$(echo "$BRANDS"  | python -c "import sys,json;print(next((b['brandId'] for b in json.load(sys.stdin) if 'Lumen'  in b['brandName']),''))")

H="@shared_star_$(date +%s)"
chk "same handle under brand A (rate 5000)" "$(code -X POST "$B/api/creators" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $AURORA" -H 'Content-Type: application/json' -d "{\"platform\":\"instagram\",\"handle\":\"$H\",\"status\":\"active\",\"preferredRate\":5000}")" "200"
chk "same handle under brand B (rate 2000)" "$(code -X POST "$B/api/creators" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $LUMEN"  -H 'Content-Type: application/json' -d "{\"platform\":\"instagram\",\"handle\":\"$H\",\"status\":\"active\",\"preferredRate\":2000}")" "200"
chk "duplicate handle WITHIN one brand -> 409" "$(code -X POST "$B/api/creators" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $AURORA" -H 'Content-Type: application/json' -d "{\"platform\":\"instagram\",\"handle\":\"$H\",\"status\":\"active\"}")" "409"
# Coupon codes are per-brand unique. Generating one needs a real campaign + creator in that
# brand, so build them first rather than asserting on an incomplete payload.
CODE="SUMMER$(date +%s)"
CAMP_A=$(curl -s -X POST "$B/api/campaigns" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $AURORA" -H 'Content-Type: application/json' -d '{"name":"Coupon Test A","status":"active","campaignType":"affiliate campaigns"}' --max-time 30 | jqv id)
CAMP_B=$(curl -s -X POST "$B/api/campaigns" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $LUMEN"  -H 'Content-Type: application/json' -d '{"name":"Coupon Test B","status":"active","campaignType":"affiliate campaigns"}' --max-time 30 | jqv id)
CRE_A=$(curl -s -X POST "$B/api/creators" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $AURORA" -H 'Content-Type: application/json' -d "{\"platform\":\"instagram\",\"handle\":\"@cpn_a_$(date +%s)\",\"status\":\"active\"}" --max-time 30 | jqv id)
CRE_B=$(curl -s -X POST "$B/api/creators" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $LUMEN"  -H 'Content-Type: application/json' -d "{\"platform\":\"instagram\",\"handle\":\"@cpn_b_$(date +%s)\",\"status\":\"active\"}" --max-time 30 | jqv id)
chk "same coupon code in brand A" "$(code -X POST "$B/api/coupons/generate" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $AURORA" -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\",\"campaignId\":\"$CAMP_A\",\"creatorId\":\"$CRE_A\"}")" "201"
chk "same coupon code in brand B" "$(code -X POST "$B/api/coupons/generate" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $LUMEN"  -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\",\"campaignId\":\"$CAMP_B\",\"creatorId\":\"$CRE_B\"}")" "201"
FOREIGN=$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select id from identity.brands where account_id <> 'dededede-0000-0000-0000-0000000000aa' limit 1" 2>/dev/null | tr -d '\r')
chk "brand outside the account -> 403" "$(code -H "Authorization: Bearer $AT" -H "X-Brand-Id: $FOREIGN" "$B/api/creators")" "403"
chk "malformed X-Brand-Id -> 403" "$(code -H "Authorization: Bearer $AT" -H "X-Brand-Id: not-a-uuid" "$B/api/creators")" "403"
SW=$(curl -s -X POST "$B/api/brands/switch" -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d "{\"brandId\":\"$LUMEN\"}" --max-time 30)
chk "brand switch re-mints the token" "$([ -n "$(echo "$SW" | jqv accessToken)" ] && echo yes || echo no)" "yes"
chk "switched token names the new brand" "$(echo "$SW" | jqv brandId)" "$LUMEN"

echo
echo "=============================================================="
echo " D. ROLE-BASED ACCESS CONTROL"
echo "=============================================================="
NT=$(login demo.analyst@northstar.test | jqv accessToken)
chk "ANALYST can log in" "$([ -n "$NT" ] && echo yes || echo no)" "yes"
chk "ANALYST sees only its scoped brand" "$(curl -s -H "Authorization: Bearer $NT" "$B/api/brands" --max-time 30 | python -c 'import sys,json;print(len(json.load(sys.stdin)))')" "1"
chk "ANALYST GET creators -> 200" "$(code -H "Authorization: Bearer $NT" "$B/api/creators")" "200"
chk "ANALYST POST creator -> 403" "$(code -X POST "$B/api/creators" -H "Authorization: Bearer $NT" -H 'Content-Type: application/json' -d '{"platform":"tiktok","handle":"@nope","status":"active"}')" "403"
chk "ANALYST POST campaign -> 403" "$(code -X POST "$B/api/campaigns" -H "Authorization: Bearer $NT" -H 'Content-Type: application/json' -d '{"name":"Nope","status":"draft","campaignType":"paid"}')" "403"
chk "ANALYST create payout batch -> 403" "$(code -X POST "$B/api/influencer-payouts/create" -H "Authorization: Bearer $NT" -H 'Content-Type: application/json' -d '{"creatorId":"00000000-0000-0000-0000-000000000001"}')" "403"
chk "ANALYST create brand -> 403" "$(code -X POST "$B/api/brands" -H "Authorization: Bearer $NT" -H 'Content-Type: application/json' -d '{"name":"Nope"}')" "403"
chk "ANALYST out-of-scope brand -> 403" "$(code -H "Authorization: Bearer $NT" -H "X-Brand-Id: $LUMEN" "$B/api/creators")" "403"

KT=$(login demo.marketer@northstar.test | jqv accessToken)
chk "MARKETER POST creator -> 200" "$(code -X POST "$B/api/creators" -H "Authorization: Bearer $KT" -H 'Content-Type: application/json' -d "{\"platform\":\"tiktok\",\"handle\":\"@mk_$(date +%s)\",\"status\":\"active\"}")" "200"
chk "MARKETER approve commission -> 403" "$(code -X POST "$B/api/influencer-commissions/00000000-0000-0000-0000-000000000001/approve" -H "Authorization: Bearer $KT" -H 'Content-Type: application/json' -d '{}')" "403"
chk "MARKETER create payout batch -> 403" "$(code -X POST "$B/api/influencer-payouts/create" -H "Authorization: Bearer $KT" -H 'Content-Type: application/json' -d '{"creatorId":"00000000-0000-0000-0000-000000000001"}')" "403"

echo
echo "  -- separation of duties --"
MT=$(login demo.manager@northstar.test | jqv accessToken)
FT=$(login demo.finance@northstar.test | jqv accessToken)
chkn "MANAGER approve commission (allowed)" "$(code -X POST "$B/api/influencer-commissions/00000000-0000-0000-0000-000000000001/approve" -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' -d '{}')" "403"
chk  "MANAGER create payout batch -> 403" "$(code -X POST "$B/api/influencer-payouts/create" -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' -d '{"creatorId":"00000000-0000-0000-0000-000000000001"}')" "403"
chkn "FINANCE create payout batch (allowed)" "$(code -X POST "$B/api/influencer-payouts/create" -H "Authorization: Bearer $FT" -H 'Content-Type: application/json' -d '{"creatorId":"00000000-0000-0000-0000-000000000001"}')" "403"
chk  "FINANCE write creator -> 403" "$(code -X POST "$B/api/creators" -H "Authorization: Bearer $FT" -H 'Content-Type: application/json' -d '{"platform":"tiktok","handle":"@fin_nope","status":"active"}')" "403"

echo
echo "=============================================================="
echo " E. SESSION LIFECYCLE"
echo "=============================================================="
SR=$(login demo.admin@northstar.test)
A1=$(echo "$SR" | jqv accessToken); R1=$(echo "$SR" | jqv refreshToken)
RR=$(curl -s -X POST "$B/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$R1\"}" --max-time 30)
A2=$(echo "$RR" | jqv accessToken); R2=$(echo "$RR" | jqv refreshToken)
chk "refresh issues a NEW access token" "$([ "$A1" != "$A2" ] && [ -n "$A2" ] && echo yes || echo no)" "yes"
chk "refresh token rotates on use" "$([ "$R1" != "$R2" ] && [ -n "$R2" ] && echo yes || echo no)" "yes"
chk "refreshed token works" "$(code -H "Authorization: Bearer $A2" "$B/api/creators")" "200"
chkn "replaying the old refresh token is rejected" "$(code -X POST "$B/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$R1\"}")" "200"
chk "logout -> 204" "$(code -X POST "$B/api/auth/logout" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$R2\"}")" "204"
chkn "refresh after logout is rejected" "$(code -X POST "$B/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$R2\"}")" "200"

echo
echo "=============================================================="
echo " F. DOMAIN EVENTS / OUTBOX"
echo "=============================================================="
BEFORE=$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from shared.domain_events" 2>/dev/null | tr -d '\r')
CR=$(curl -s -X POST "$B/api/creators" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $AURORA" -H 'Content-Type: application/json' -d "{\"platform\":\"instagram\",\"handle\":\"@evt_$(date +%s)\",\"status\":\"active\"}" --max-time 30 | jqv id)
chk "commission accrual emits an event" "$(code -X POST "$B/api/influencer-commissions" -H "Authorization: Bearer $AT" -H "X-Brand-Id: $AURORA" -H 'Content-Type: application/json' -d "{\"creatorId\":\"$CR\",\"grossSale\":250.00,\"commissionAmount\":25.00,\"currency\":\"USD\"}")" "200"
AFTER=$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from shared.domain_events" 2>/dev/null | tr -d '\r')
chk "outbox row count grew" "$([ "$AFTER" -gt "$BEFORE" ] && echo yes || echo no)" "yes"
chk "event carries brand tenancy" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select brand_id is not null from shared.domain_events order by occurred_at desc limit 1" 2>/dev/null | tr -d '\r')" "t"

echo
echo "=============================================================="
echo " G. DATA INTEGRITY"
echo "=============================================================="
chk "25 tables across 9 context schemas" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from pg_tables where schemaname in ('identity','creator','campaign','workflow','attribution','finance','content','mapping','shared')" 2>/dev/null | tr -d '\r')" "25"
chk "no domain tables left in public" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from pg_tables where schemaname='public' and tablename in ('users','creators','campaigns','brands')" 2>/dev/null | tr -d '\r')" "0"
chk "every user resolves to a brand" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from identity.users u where not exists (select 1 from identity.memberships m where m.user_id=u.id)" 2>/dev/null | tr -d '\r')" "0"
chk "no creator row without a brand" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from creator.creators where brand_id is null" 2>/dev/null | tr -d '\r')" "0"

echo
echo "=============================================================="
echo " H. EXTRACTED WORKFLOW SERVICE"
echo "=============================================================="
WF="http://localhost:8444"
if curl -s -o /dev/null --max-time 5 "$WF/workflow-boards" 2>/dev/null; then
  chk "workflow service rejects calls without a service token" "$(curl -s -o /dev/null -w '%{http_code}' "$WF/workflow-boards" --max-time 20)" "401"
  chk "workflow service serves with the token"                 "$(curl -s -o /dev/null -w '%{http_code}' -H "X-Service-Token: $SVC" "$WF/workflow-boards" --max-time 20)" "200"
  # The reason the service exists: it runs as svc_workflow, which the database confines.
  chk "svc_workflow CANNOT write finance tables" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select has_table_privilege('svc_workflow','finance.influencer_payouts','INSERT')" 2>/dev/null | tr -d '
')" "f"
  chk "svc_workflow CANNOT write campaign tables" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select has_table_privilege('svc_workflow','campaign.campaigns','INSERT')" 2>/dev/null | tr -d '
')" "f"
  chk "svc_workflow CAN write its own tables" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select has_table_privilege('svc_workflow','workflow.workflow_boards','INSERT')" 2>/dev/null | tr -d '
')" "t"
  # Dual-run equivalence: the extracted service and the monolith must agree.
  DAO_N=$(curl -sk -H "X-Service-Token: $SVC" "$DAO/workflow-boards" --max-time 20 | python -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null)
  SVC_N=$(curl -s  -H "X-Service-Token: $SVC" "$WF/workflow-boards"  --max-time 20 | python -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null)
  chk "monolith and extracted service return the same rows" "$SVC_N" "$DAO_N"
  chk "cross-context FKs on workflow_cards are gone" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from pg_constraint where conname in ('workflow_cards_campaign_id_fkey','workflow_cards_creator_id_fkey')" 2>/dev/null | tr -d '
')" "0"
  chk "intra-aggregate FKs survived" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from pg_constraint where conname in ('workflow_cards_board_id_fkey','workflow_cards_stage_id_fkey','workflow_board_stages_board_id_fkey')" 2>/dev/null | tr -d '
')" "3"
  chk "orphan-monitoring view is empty" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from workflow.orphaned_cards" 2>/dev/null | tr -d '
')" "0"
else
  echo "  SKIP  workflow service not running on :8444"
fi

echo
echo "=============================================================="
echo " I. ALL EXTRACTED SERVICES"
echo "=============================================================="
# One service per bounded context, each on its own port and its own DB role.
probe_svc() { # probe_svc <name> <port> <path>
  chk "$1 service serves its own data"      "$(curl -s -o /dev/null -w '%{http_code}' -H "X-Service-Token: $SVC" "http://localhost:$2$3" --max-time 15)" "200"
  chk "$1 service rejects unauthenticated"  "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$2$3" --max-time 15)" "401"
}
if curl -s -o /dev/null --max-time 4 "http://localhost:8445/users" 2>/dev/null; then
  probe_svc identity    8445 /users
  probe_svc creator     8446 /creators
  probe_svc campaign    8447 /campaigns
  probe_svc attribution 8448 /influencer-campaign-codes
  probe_svc finance     8449 /influencer-commissions
  probe_svc content     8450 /landing-templates

  # The database is the backstop if code ever drifts across a boundary.
  chk "svc_finance CANNOT write creator tables"  "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select has_table_privilege('svc_finance','creator.creators','INSERT')" 2>/dev/null | tr -d '')" "f"
  chk "svc_creator CANNOT write finance tables"  "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select has_table_privilege('svc_creator','finance.influencer_payouts','INSERT')" 2>/dev/null | tr -d '')" "f"
  chk "svc_content CANNOT write identity tables" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select has_table_privilege('svc_content','identity.users','INSERT')" 2>/dev/null | tr -d '')" "f"

  # Severing left no dangling references behind.
  chk "no orphaned cross-context references" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select (select count(*) from creator.orphaned_references)+(select count(*) from attribution.orphaned_references)+(select count(*) from finance.orphaned_references)+(select count(*) from content.orphaned_references)" 2>/dev/null | tr -d '')" "0"
  chk "cross-context FKs severed (spine kept)" "$(docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -t -A -c "select count(*) from pg_constraint c join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace join pg_class cf on cf.oid=c.confrelid join pg_namespace nf on nf.oid=cf.relnamespace where c.contype='f' and n.nspname<>nf.nspname and n.nspname in ('identity','creator','campaign','workflow','attribution','finance','content','mapping') and not (nf.nspname='identity' and cf.relname in ('brands','users'))" 2>/dev/null | tr -d '')" "0"
else
  echo "  SKIP  extracted services not running on :8445-:8450"
fi

echo
echo "=============================================================="
printf " TOTAL: %d passed, %d failed\n" "$PASS" "$FAIL"
echo "=============================================================="
[ "$FAIL" -eq 0 ] || exit 1
