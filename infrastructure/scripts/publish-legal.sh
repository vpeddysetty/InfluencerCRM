#!/usr/bin/env bash
# Publish the legal pages to the static site, stripping internal notes on the way out.
#
#   ./infrastructure/scripts/publish-legal.sh            # publish every page
#   ./infrastructure/scripts/publish-legal.sh subprocessors   # publish one
#
# WHY THIS SCRIPT EXISTS AT ALL. These pages were previously uploaded by hand, and the result was
# `subprocessors/index.html` sitting eleven days behind its own source while `privacy` and `dpa`
# were current -- the sub-processor list contradicting the two documents that point AT it. A copy
# loop nobody has to remember the arguments for is the fix.
#
# WHY THE COMMENTS ARE STRIPPED HERE rather than deleted from the source. The pages carry
# `<!-- INTERNAL, not rendered: [COUNSEL: ...] -->` notes recording what still needs legal review
# and why a clause is written as it is. "Not rendered" was true of DISPLAY and false of DELIVERY:
# they were being served to the public and were readable with view-source. Among them, in a
# document presented to customers as a contract:
#
#   "this document has not been reviewed by counsel"
#   "Section 13 is deliberately incomplete"
#   "no independent certification (SOC 2, ISO 27001) is held"
#
# Those are true, and they belong in the repo where the next editor will see them. Handing them to
# a reader of the published contract is a different act. Stripping at publish keeps both: the notes
# stay authoritative in git, and the artifact carries only what it means to say.
set -euo pipefail

BUCKET="${LEGAL_BUCKET:-tejdux-legal-static}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

# source basename -> published key prefix. The two differ (privacy-policy.html -> privacy/) and
# guessing one from the other is how the wrong file lands at the right URL.
declare -A PAGES=(
  [privacy-policy]=privacy
  [terms-of-service]=terms
  [dpa]=dpa
  [subprocessors]=subprocessors
  [data-deletion]=data-deletion
  [pricing]=pricing
  [government-requests]=government-requests
)

WANTED=("$@")
if [ ${#WANTED[@]} -eq 0 ]; then WANTED=("${!PAGES[@]}"); fi

# A repo-local temp dir, not `mktemp -d`: under Git Bash on Windows that returns a POSIX path
# (/tmp/tmp.XXXX) which the native python.exe doing the stripping cannot resolve, and the script
# dies on the first write.
TMP="${REPO_ROOT}/.legal-publish-tmp"
rm -rf "$TMP"; mkdir -p "$TMP"
# ...and hand python a NATIVE path. Git Bash gives $REPO_ROOT as /c/AI/... which python.exe reads
# as a relative path off the current drive root and cannot open. cygpath -w is the translation;
# on a real POSIX host it is absent and the path is already correct, hence the fallback.
TMP_NATIVE="$(cygpath -w "$TMP" 2>/dev/null || echo "$TMP")"
trap 'rm -rf "$TMP"' EXIT
PUBLISHED=()

for name in "${WANTED[@]}"; do
    src="docs/legal/${name}.html"
    key="${PAGES[$name]:-}"
    if [ -z "$key" ]; then echo "ERROR: unknown page '$name'" >&2; exit 1; fi
    if [ ! -f "$src" ]; then echo "ERROR: $src not found" >&2; exit 1; fi

    # Strip only `<!-- INTERNAL ... -->`. A blanket comment strip would also remove conditional
    # comments and any structural markers a template relies on.
    python -c "
import re, io, sys
src, out = sys.argv[1], sys.argv[2]
h = io.open(src, encoding='utf-8').read()
cleaned, n = re.subn(r'[ \t]*<!--\s*INTERNAL.*?-->[ \t]*\n?', '', h, flags=re.S)
if '<!-- INTERNAL' in cleaned:
    raise SystemExit('FATAL: an INTERNAL comment survived the strip in ' + src)
io.open(out, 'w', encoding='utf-8', newline='').write(cleaned)
print('  stripped %d internal note(s)' % n)
" "$src" "$TMP_NATIVE/$name.html"

    echo "==> $src -> s3://$BUCKET/$key/index.html"
    aws s3 cp "$TMP_NATIVE/$name.html" "s3://$BUCKET/$key/index.html" \
        --content-type "text/html; charset=utf-8" \
        --cache-control "public, max-age=300" \
        --only-show-errors
    PUBLISHED+=("/$key/*")
done

# Invalidate, so a correction is live in seconds rather than after the 300s TTL. Worth doing
# explicitly: a legal page is exactly the kind someone is told to go and look at right now.
DIST="${LEGAL_DISTRIBUTION_ID:-}"
if [ -z "$DIST" ]; then
    DIST="$(aws cloudfront list-distributions \
        --query "DistributionList.Items[?contains(to_string(Origins.Items[].DomainName), '${BUCKET}')].Id | [0]" \
        --output text 2>/dev/null || true)"
fi

if [ -n "$DIST" ] && [ "$DIST" != "None" ]; then
    echo "==> invalidating ${#PUBLISHED[@]} path(s) on $DIST"
    aws cloudfront create-invalidation --distribution-id "$DIST" \
        --paths "${PUBLISHED[@]}" --query 'Invalidation.Id' --output text
else
    echo "WARNING: no CloudFront distribution found for $BUCKET." >&2
    echo "         Uploads are live at the origin; the CDN clears within its 300s TTL." >&2
fi

echo "Done."
