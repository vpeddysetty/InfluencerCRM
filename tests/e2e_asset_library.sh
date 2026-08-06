#!/usr/bin/env bash
# Asset library — roadmap Phase B (docs/landing-page-builder-roadmap.md §5).
#
# An upload endpoint accepts attacker-controlled bytes and later serves them from the
# platform's own origin. That combination is what most of this suite is about: B4-B7 cover
# what may be stored, B8-B11 cover who may reach it. The happy path (B1-B3) is the small
# part.
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

# ---- fixtures -------------------------------------------------------------
# Generated rather than committed: a few dozen bytes of PNG is clearer as code than as a
# binary blob in the repo, and it keeps the hostile fixtures obviously inert.
python - "$SP" <<'PY'
import sys, struct, zlib
sp = sys.argv[1]

def chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xffffffff)

def png(w, h):
    raw = b''.join(b'\x00' + b'\xff\x00\x00' * w for _ in range(h))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw))
            + chunk(b'IEND', b''))

open(sp + "/lb_real.png", "wb").write(png(4, 2))
# HTML that claims to be a PNG by name and header. If content sniffing is skipped this
# gets stored and later served from our origin as an XSS payload.
open(sp + "/lb_fake.png", "wb").write(b'<html><script>alert(1)</script></html>')
# SVG is a real image format and a script carrier. Excluded on purpose.
open(sp + "/lb_evil.svg", "wb").write(
    b'<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
PY

up() { # up <token> <file> <declared-type> -> body, status in $SP/.ucode
  curl -s -m 60 -X POST "$BFF/api/assets" -H "Authorization: Bearer $1" \
    -F "file=@$2;type=$3" -o "$SP/.ubody" -w '%{http_code}' > "$SP/.ucode"
  cat "$SP/.ubody"
}
ust() { cat "$SP/.ucode" 2>/dev/null; }

api() { # api <method> <path> <token>
  curl -s -m 30 -X "$1" "$BFF$2" -H "Authorization: Bearer $3" -o "$SP/.abody" -w '%{http_code}' > "$SP/.acode"
  cat "$SP/.abody"
}
ast() { cat "$SP/.acode" 2>/dev/null; }

EMAIL="ab.brand.$STAMP@example.test"
OTHER_EMAIL="ab.other.$STAMP@example.test"

echo "################ setup ################"
TOKEN=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"AB Brand\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
OTHER=$(curl -s -m 30 -X POST "$BFF/api/auth/signup" -H "Content-Type: application/json" \
  -d "{\"email\":\"$OTHER_EMAIL\",\"password\":\"DemoPass123!\",\"brandName\":\"AB Other\",\"accountType\":\"brand\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
rec SETUP nonempty "$([[ -n "$TOKEN" && -n "$OTHER" ]] && echo nonempty || echo empty)" "two brands"

echo "################ B1: upload an image ################"
ASSET=$(up "$TOKEN" "$SP/lb_real.png" "image/png")
rec B1 200 "$(ust)" "PNG accepted"
ASSET_ID=$(jqv "$ASSET" "['id']")
ASSET_URL=$(jqv "$ASSET" "['url']")
KEY=$(jqv "$ASSET" "['storageKey']")
BRAND=$(jqv "$ASSET" "['brandId']")
rec B1b nonempty "$([[ -n "$ASSET_ID" && -n "$ASSET_URL" ]] && echo nonempty || echo empty)" "id + url returned"

echo "################ B2: metadata is derived, not trusted ################"
rec B2  4 "$(jqv "$ASSET" "['width']")"  "width probed from the bytes"
rec B2b 2 "$(jqv "$ASSET" "['height']")" "height probed from the bytes"
rec B2c image/png "$(jqv "$ASSET" "['contentType']")" "content type from magic bytes"

echo "################ B3: the key is generated and brand-prefixed ################"
# A caller-supplied key would be a path-traversal and a cross-tenant overwrite in one.
rec B3 true "$([[ "$KEY" == "$BRAND/"* ]] && echo true || echo false)" "key sits under the owning brand"
rec B3b false "$([[ "$KEY" == *"lb_real"* ]] && echo true || echo false)" \
    "key is NOT the uploaded filename — two files named the same must not collide"

echo "################ B4-B7: what may be stored ################"
up "$TOKEN" "$SP/lb_fake.png" "image/png" > /dev/null
rec B4 415 "$(ust)" "HTML named .png with an image content-type is refused (sniffed, not trusted)"

up "$TOKEN" "$SP/lb_evil.svg" "image/svg+xml" > /dev/null
rec B5 415 "$(ust)" "SVG refused — it is an XML document that can carry script"

# A correct declared type with the wrong bytes must still fail: sniffing is the check.
up "$TOKEN" "$SP/lb_fake.png" "text/html" > /dev/null
rec B6 415 "$(ust)" "text/html refused outright"

STORED=$($PG -c "select count(*) from content.assets where brand_id='$BRAND';" | tr -d '\r')
rec B7 1 "$STORED" "only the one legitimate image was stored — no rejected upload left a row"

echo "################ B8: bytes serve back unchanged, anonymously ################"
# Anonymous on purpose: a public landing page's images must load for visitors who have no
# token. Safe because keys are random UUIDs under a brand prefix.
curl -s -m 30 "$BFF$ASSET_URL" -o "$SP/.served" -w '%{http_code}' > "$SP/.scode"
rec B8 200 "$(cat "$SP/.scode")" "asset serves without a token"
rec B8b identical "$(cmp -s "$SP/lb_real.png" "$SP/.served" && echo identical || echo differs)" \
    "served bytes are byte-for-byte the uploaded file"

CT=$(curl -s -m 30 -o /dev/null -w '%{content_type}' "$BFF$ASSET_URL")
rec B8c image/png "$CT" "served with an image content type"
NOSNIFF=$(curl -s -m 30 -D - -o /dev/null "$BFF$ASSET_URL" | grep -ci "X-Content-Type-Options: nosniff")
rec B8d 1 "$NOSNIFF" "nosniff set — the browser must not re-interpret the type"

echo "################ B9: path traversal ################"
# get()/delete() take a key that came from a database row, and a row is not a trust boundary.
for probe in "..%2f..%2f..%2fetc%2fpasswd" "..%2F..%2Fpom.xml"; do
  CODE=$(curl -s -m 20 -o /dev/null -w '%{http_code}' "$BFF/assets/$BRAND/$probe")
  rec B9 400,404 "$CODE" "traversal probe rejected: $probe"
done

echo "################ B10: cross-tenant reads ################"
OTHER_LIST=$(api GET /api/assets "$OTHER")
rec B10 0 "$(echo "$OTHER_LIST" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo err)" \
    "another brand's library is empty — assets are never shared"

MY_LIST=$(api GET /api/assets "$TOKEN")
rec B10b 1 "$(echo "$MY_LIST" | python -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null || echo err)" \
    "the owning brand sees its own asset"

echo "################ B11: cross-tenant delete ################"
api DELETE "/api/assets/$ASSET_ID" "$OTHER" > /dev/null
rec B11 404 "$(ast)" "another brand cannot delete by id"
STILL=$($PG -c "select count(*) from content.assets where id='$ASSET_ID';" | tr -d '\r')
rec B11b 1 "$STILL" "the asset survived the cross-tenant delete attempt"

echo "################ B12: the owner can delete ################"
api DELETE "/api/assets/$ASSET_ID" "$TOKEN" > /dev/null
rec B12 200,204 "$(ast)" "owner deletes their own asset"
GONE=$($PG -c "select count(*) from content.assets where id='$ASSET_ID';" | tr -d '\r')
rec B12b 0 "$GONE" "row removed"
AFTER=$(curl -s -m 20 -o /dev/null -w '%{http_code}' "$BFF$ASSET_URL")
rec B12c 404 "$AFTER" "bytes no longer served"

echo
echo "################ RESULT ################"
echo "PASS=$PASS FAIL=$FAIL"
if [[ $FAIL -gt 0 ]]; then
  printf '%s\n' "${FAILED[@]}"
  exit 1
fi
echo "Test data: brand=$EMAIL other=$OTHER_EMAIL asset_key=$KEY"
