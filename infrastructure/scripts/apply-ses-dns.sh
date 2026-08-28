#!/usr/bin/env bash
#
# OP-06: authorise SES in the apex SPF record, and publish DKIM CNAMEs for inbox.tejdux.com.
#
# WHY THIS SCRIPT EXISTS
#
#   Two DNS gaps, both additive, both on the live domain:
#
#     1. The apex SPF record authorises Google but not SES. Mail sent through SES therefore fails
#        SPF alignment -- a weak signal when AWS reviews a sandbox-exit request, and a
#        deliverability problem afterwards.
#     2. inbox.tejdux.com's three DKIM CNAMEs were never published, so AWS gave up verifying after
#        three days and emailed about it. That domain RECEIVES (GDPR deletion requests forwarded
#        from privacy@) and never sends, so nothing is broken -- but a failed verification sitting
#        in the account is exactly the sort of untidiness a reviewer sees.
#
# WHY THE SPF CHANGE IS AN EDIT AND NOT AN ADDITION
#
#   Two `v=spf1` TXT records at the same name is a PERMANENT ERROR that breaks BOTH senders. There
#   can only ever be one, so authorising SES means rewriting the existing record with an extra
#   include -- never adding a second one. The batch therefore restates the two
#   google-site-verification strings byte-for-byte alongside it; dropping them would break Google
#   Search Console and Workspace domain ownership.
#
# WHAT THIS DELIBERATELY DOES NOT TOUCH
#
#   The apex MX (`1 smtp.google.com`) carries all real business mail through Google Workspace.
#   It is not in the change batch and must never be -- repointing it to automate one address would
#   break every mailbox. The preflight below refuses to run if the batch mentions MX at all.
#
# SAFETY
#
#   Every change is UPSERT: one modify (the TXT) and three creates (new CNAME names that overwrite
#   nothing). There are no deletes and no replaces. The script prints the diff and waits for
#   confirmation before writing, then blocks until Route 53 reports the change INSYNC.
#
# USAGE
#
#   bash infrastructure/scripts/apply-ses-dns.sh            # prompts before applying
#   bash infrastructure/scripts/apply-ses-dns.sh --dry-run  # prints the diff and exits
#
set -euo pipefail

ZONE_ID="Z0068206CHFI6QYONX9W"
DOMAIN="tejdux.com"
RECEIVE_DOMAIN="inbox.tejdux.com"
PROFILE="${AWS_PROFILE:-tejdux}"
REGION="${AWS_REGION:-us-east-1}"
DRY_RUN="no"

if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN="yes"
fi

# Work directory. Deliberately NOT mktemp -d: on Git Bash for Windows that returns a POSIX path
# (/tmp/...) which the Windows python.exe this script calls cannot open. A directory beside the
# script is visible to both, and `cygpath` normalises it when one is available.
WORK="$(dirname "$0")/.ses-dns-work"
mkdir -p "$WORK"
if command -v cygpath >/dev/null 2>&1; then
  WORK="$(cygpath -m "$(cd "$WORK" && pwd)")"
else
  WORK="$(cd "$WORK" && pwd)"
fi
trap 'rm -rf "$WORK"' EXIT

aws() { command aws --profile "$PROFILE" --region "$REGION" "$@"; }

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

# ---------------------------------------------------------------------------
# 1. Read the live zone. Every decision below is made against THIS, never against
#    an assumption about what the zone contains.
# ---------------------------------------------------------------------------
say "Reading the live zone..."
aws route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID" \
  --output json > "$WORK/zone.json"

CURRENT_SPF="$(python -c "
import json
zone = json.load(open(r'$WORK/zone.json'))['ResourceRecordSets']
spf = [v['Value'] for r in zone
       if r['Type'] == 'TXT' and r['Name'] == '$DOMAIN.'
       for v in r['ResourceRecords'] if 'v=spf1' in v['Value']]
print(spf[0] if len(spf) == 1 else 'ERROR:%d' % len(spf))
")"

case "$CURRENT_SPF" in
  ERROR:0)
    echo "STOP: no v=spf1 record at the apex. This script edits an existing record;" >&2
    echo "      creating one from nothing needs a human decision about policy." >&2
    exit 1 ;;
  ERROR:*)
    echo "STOP: more than one v=spf1 record at the apex. That is already a permanent" >&2
    echo "      error and must be reduced to one BY HAND before anything is automated." >&2
    exit 1 ;;
esac

if printf '%s' "$CURRENT_SPF" | grep -q 'amazonses.com'; then
  echo "SES is already authorised in SPF; nothing to change there."
  SPF_NEEDED="no"
else
  SPF_NEEDED="yes"
fi

# ---------------------------------------------------------------------------
# 2. Ask SES what it currently wants. The tokens are NOT hardcoded: re-creating an
#    identity issues new ones, and publishing stale tokens would verify nothing
#    while looking correct.
# ---------------------------------------------------------------------------
say "Asking SES for $RECEIVE_DOMAIN's DKIM tokens..."
aws sesv2 get-email-identity --email-identity "$RECEIVE_DOMAIN" \
  --query 'DkimAttributes.Tokens' --output text | tr '\t' '\n' | grep . > "$WORK/tokens.txt"

TOKEN_COUNT="$(wc -l < "$WORK/tokens.txt" | tr -d ' ')"
if [ "$TOKEN_COUNT" != "3" ]; then
  echo "STOP: expected 3 DKIM tokens, got $TOKEN_COUNT." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 3. Build the change batch from what was just read.
# ---------------------------------------------------------------------------
say "Building the change batch..."
python - "$WORK/zone.json" "$WORK/tokens.txt" "$SPF_NEEDED" "$WORK/batch.json" <<'PYEOF'
import json, sys

zone_path, tokens_path, spf_needed, out_path = sys.argv[1:5]
zone = json.load(open(zone_path))['ResourceRecordSets']
tokens = [t.strip() for t in open(tokens_path) if t.strip()]

changes = []

if spf_needed == 'yes':
    apex = next(r for r in zone if r['Type'] == 'TXT' and r['Name'] == 'tejdux.com.')
    values = []
    for record in apex['ResourceRecords']:
        value = record['Value']
        if 'v=spf1' in value:
            # Insert the include BEFORE the ~all qualifier, which must stay last.
            value = value.replace('include:_spf.google.com',
                                  'include:_spf.google.com include:amazonses.com')
        values.append({'Value': value})
    changes.append({
        'Action': 'UPSERT',
        'ResourceRecordSet': {
            'Name': 'tejdux.com.', 'Type': 'TXT',
            'TTL': apex.get('TTL', 300), 'ResourceRecords': values,
        },
    })

for token in tokens:
    changes.append({
        'Action': 'UPSERT',
        'ResourceRecordSet': {
            'Name': '%s._domainkey.inbox.tejdux.com.' % token,
            'Type': 'CNAME', 'TTL': 300,
            'ResourceRecords': [{'Value': '%s.dkim.amazonses.com' % token}],
        },
    })

json.dump({'Comment': 'OP-06: SES SPF include and inbox DKIM CNAMEs', 'Changes': changes},
          open(out_path, 'w'), indent=2)
PYEOF

# ---------------------------------------------------------------------------
# 4. Preflight. The MX check is the one that matters: it is the record whose loss
#    would take down all business mail, so the batch is refused if it appears at all.
# ---------------------------------------------------------------------------
if grep -q '"MX"' "$WORK/batch.json"; then
  echo "STOP: the batch references an MX record. It must not." >&2
  exit 1
fi
if grep -qE '"Action": *"DELETE"' "$WORK/batch.json"; then
  echo "STOP: the batch contains a DELETE. Every change here must be additive." >&2
  exit 1
fi

CHANGE_COUNT="$(python -c "import json;print(len(json.load(open(r'$WORK/batch.json'))['Changes']))")"
if [ "$CHANGE_COUNT" = "0" ]; then
  say "Nothing to do — SPF already authorises SES and no DKIM records are needed."
  exit 0
fi

say "The change ($CHANGE_COUNT records, all UPSERT, no deletes, no MX):"
if [ "$SPF_NEEDED" = "yes" ]; then
  echo "  apex TXT (SPF)"
  echo "    - $CURRENT_SPF"
  echo "    + $(printf '%s' "$CURRENT_SPF" | sed 's/include:_spf.google.com/include:_spf.google.com include:amazonses.com/')"
fi
while read -r token; do
  echo "  + ${token}._domainkey.$RECEIVE_DOMAIN CNAME ${token}.dkim.amazonses.com"
done < "$WORK/tokens.txt"

echo
echo "  UNCHANGED: $DOMAIN MX -> 1 smtp.google.com   (Google Workspace; all business mail)"

if [ "$DRY_RUN" = "yes" ]; then
  say "Dry run — nothing applied."
  exit 0
fi

echo
printf 'Apply this to the LIVE zone? [y/N] '
read -r reply
case "$reply" in
  y|Y) ;;
  *) echo "Aborted. Nothing changed."; exit 0 ;;
esac

# ---------------------------------------------------------------------------
# 5. Apply, then wait for propagation rather than assuming it.
# ---------------------------------------------------------------------------
say "Applying..."
CHANGE_ID="$(aws route53 change-resource-record-sets \
  --hosted-zone-id "$ZONE_ID" \
  --change-batch "file://$WORK/batch.json" \
  --query 'ChangeInfo.Id' --output text)"

echo "Change $CHANGE_ID submitted. Waiting for INSYNC..."
aws route53 wait resource-record-sets-changed --id "$CHANGE_ID"

say "Applied and INSYNC. Verifying..."
echo "SPF now:"
aws route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID" \
  --query "ResourceRecordSets[?Type=='TXT' && Name=='$DOMAIN.'].ResourceRecords[].Value" \
  --output text | tr '\t' '\n' | grep 'v=spf1' || true

echo
echo "MX (must be unchanged):"
aws route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID" \
  --query "ResourceRecordSets[?Type=='MX' && Name=='$DOMAIN.'].ResourceRecords[].Value" \
  --output text

cat <<'NOTE'

Next:
  - DKIM verification is SES's own poll; it usually completes within minutes. Check with:
      aws sesv2 get-email-identity --email-identity inbox.tejdux.com \
        --query 'DkimAttributes.Status' --profile tejdux

  - NEITHER of these unblocks sending. The account is still in the SES SANDBOX, which is a
    separate review (case 178750875200560, previously DENIED). That request is missing its
    UseCaseDescription, which is almost certainly why it was refused.
NOTE
