#!/usr/bin/env bash
# Build the seven micro-frontends with the deployed origins baked in, upload each to its own prefix,
# and invalidate its distribution.
#
#   ./infrastructure/scripts/deploy-ui.sh              # all seven
#   ./infrastructure/scripts/deploy-ui.sh workflow     # one
#
# Run `terraform apply` FIRST: this reads the bucket, the distribution ids and the remote origins from
# Terraform outputs, so the infrastructure has to exist before the UIs can be pointed at it.
#
# WHY THE ORIGINS ARE BUILD-TIME. Vite inlines `import.meta.env.VITE_*` at build time, so the shell's
# federation config is FROZEN into its bundle. Changing where a remote lives therefore means
# rebuilding and redeploying the shell — not editing a config file on the server. That is why this
# script writes .env.production from the Terraform outputs rather than expecting anyone to keep a
# checked-in file in sync.
#
# ORDER MATTERS: remotes before the shell. The shell's build resolves each remote's entry over the
# network when federation is enabled; building it before its remotes exist can fail or bake in a stale
# module map.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# RELATIVE, deliberately. On Windows/Git Bash an absolute MSYS path ("/c/AI/...") reaches the
# Windows terraform.exe unconverted and it fails with "chdir /c/...: The system cannot find the path
# specified" - which reads as a missing output and sends you looking for a Terraform problem that
# does not exist. The script cds to REPO_ROOT immediately below, so a relative path is equivalent
# and works on both platforms.
TF_DIR="infrastructure/test/terraform"
cd "$REPO_ROOT"

tf_output() {
    terraform -chdir="$TF_DIR" output -raw "$1" 2>/dev/null || true
}

BUCKET="$(tf_output ui_bucket)"
if [ -z "$BUCKET" ]; then
    echo "ERROR: no ui_bucket output. Run terraform apply first (and check manage_static_site)." >&2
    exit 1
fi

REGION="$(terraform -chdir="$TF_DIR" output -raw ecr_registry 2>/dev/null | sed -E 's/.*\.ecr\.([a-z0-9-]+)\.amazonaws\.com/\1/')"
REGION="${REGION:-${AWS_REGION:-us-east-1}}"

# prefix:project — the same map as local.micro_frontends. The SHELL IS LAST, deliberately (see above).
TARGETS=(
    "workflow:InfluencerWorkflowUI"
    "campaigns:InfluencerCampaignsUI"
    "creators:InfluencerCreatorsUI"
    "commerce:InfluencerCommerceUI"
    "finance:InfluencerFinanceUI"
    "content:InfluencerContentUI"
    # Not a federated remote: a standalone app on its own host with its own sign-in. It is here
    # because it was previously deployed BY HAND, which is a step that gets forgotten -- and did:
    # the invitation fixes shipped in the backend while the portal serving them stayed stale.
    # Its terraform key is `creator-portal`, deliberately not `creators` (that is
    # InfluencerCreatorsUI), and the S3 prefix must match the key static-site.tf uses.
    #
    # It gets NO .env.production, and that is correct rather than an omission: its only variable is
    # VITE_API_BASE_URL, which is meant to be empty. Its own CloudFront distribution routes /api/*
    # to the API origin, so relative URLs reach the BFF from the portal's host. Writing an absolute
    # one here would send the browser cross-origin for no reason and put the calls back through CORS.
    "creator-portal:InfluencerCreatorPortalUI"
    "shell:InfluencerUI"
)

REQUESTED=("$@")
wanted() {
    [ ${#REQUESTED[@]} -eq 0 ] && return 0
    for r in "${REQUESTED[@]}"; do [ "$r" = "$1" ] && return 0; done
    return 1
}

# The shell's build-time environment, straight from Terraform so the two cannot drift.
SHELL_ENV_JSON="$(terraform -chdir="$TF_DIR" output -json shell_build_env 2>/dev/null || echo '{}')"
DIST_IDS_JSON="$(terraform -chdir="$TF_DIR" output -json ui_distribution_ids 2>/dev/null || echo '{}')"

echo "==> bucket   ${BUCKET}"
echo "==> region   ${REGION}"
echo

for entry in "${TARGETS[@]}"; do
    prefix="${entry%%:*}"
    project="${entry#*:}"
    wanted "$prefix" || continue

    echo "=============================================================="
    echo "  ${project}  ->  s3://${BUCKET}/${prefix}/"
    echo "=============================================================="

    cd "${REPO_ROOT}/${project}"

    # Only the shell needs the remote ORIGINS; a remote's own build takes no VITE_MF_* input, and
    # writing the whole file unconditionally would leave a stale .env.production in six projects
    # that ignore it.
    #
    # The content remote is the exception, and for a reason worth stating: it renders
    # CollaboratorPanel, which builds the creator's invitation link. Vite inlines
    # `import.meta.env.VITE_*` at BUILD time, so a variable the shell has and the remote does not
    # is simply undefined there -- and production serves the REMOTE. The panel then fell back to
    # window.location.origin and produced https://<brand-host>/invite?token=..., which is not a
    # route: the SPA falls through to the marketing page and the invitation is never redeemed.
    # Every link copied off that screen was dead while looking perfectly valid.
    if [ "$prefix" = "shell" ]; then
        python - "$SHELL_ENV_JSON" > .env.production <<'PY'
import json, sys
print("# GENERATED by infrastructure/scripts/deploy-ui.sh from terraform outputs. Do not edit.")
for key, value in json.loads(sys.argv[1]).items():
    print(f"{key}={value}")
PY
        echo "--- .env.production ---"
        cat .env.production
        echo "-----------------------"
    elif [ "$prefix" = "content" ]; then
        python - "$SHELL_ENV_JSON" > .env.production <<'PY'
import json, sys
print("# GENERATED by infrastructure/scripts/deploy-ui.sh from terraform outputs. Do not edit.")
# Only what this remote actually reads. Copying the federation origins here would imply the
# remote loads other remotes, which it does not.
wanted = {"VITE_BFF_URL", "VITE_DPS_URL", "VITE_CREATOR_PORTAL_URL"}
for key, value in json.loads(sys.argv[1]).items():
    if key in wanted:
        print(f"{key}={value}")
PY
        echo "--- .env.production ---"
        cat .env.production
        echo "-----------------------"
    fi

    npm ci --silent
    npm run build

    if [ ! -d dist ]; then
        echo "ERROR: ${project} produced no dist/" >&2
        exit 1
    fi

    # Two passes, because the caching rules differ and getting this wrong is the classic stale-deploy.
    #
    # 1) Hashed assets: immutable, cached for a year. Their filenames change when the content changes,
    #    so a long TTL is free correctness.
    aws s3 sync dist/ "s3://${BUCKET}/${prefix}/" \
        --region "$REGION" \
        --delete \
        --exclude "index.html" \
        --exclude "remoteEntry.js" \
        --cache-control "public,max-age=31536000,immutable"

    # 2) The two entry points, which keep their NAMES across releases and must therefore never be
    #    cached: index.html for the shell, remoteEntry.js for a federated remote. Cached, they would
    #    keep pointing browsers at the previous release's hashed assets — which --delete has just
    #    removed, so the app fails to load rather than merely looking stale.
    for entrypoint in index.html remoteEntry.js; do
        if [ -f "dist/${entrypoint}" ]; then
            aws s3 cp "dist/${entrypoint}" "s3://${BUCKET}/${prefix}/${entrypoint}" \
                --region "$REGION" \
                --cache-control "no-cache,must-revalidate" \
                --content-type "$([ "$entrypoint" = "index.html" ] && echo text/html || echo application/javascript)"
        fi
    done

    # Invalidate only the uncacheable entry points. A blanket /* invalidation is billed per path beyond
    # the free tier and is pointless for content-hashed assets, which can never be stale.
    dist_id="$(python -c "import json,sys; print(json.loads(sys.argv[1]).get(sys.argv[2],''))" "$DIST_IDS_JSON" "$prefix")"
    if [ -n "$dist_id" ]; then
        # MSYS_NO_PATHCONV=1 is REQUIRED under Git Bash on Windows, which rewrites any argument that
        # looks like a Unix path into a Windows one before the process sees it. "/index.html" arrives at
        # the AWS CLI as "C:/Program Files/Git/index.html", and CloudFront rejects the request with
        # `InvalidArgument: Your request contains one or more invalid invalidation paths` — an error that
        # names the paths but not the cause, and which does not reproduce under WSL or Linux CI.
        #
        # Harmless everywhere else: the variable is simply ignored off MSYS.
        MSYS_NO_PATHCONV=1 aws cloudfront create-invalidation \
            --distribution-id "$dist_id" \
            --paths "/index.html" "/remoteEntry.js" "/" \
            --region "$REGION" \
            --query 'Invalidation.Id' --output text >/dev/null
        echo "    invalidated ${dist_id}"
    fi

    cd "$REPO_ROOT"
    echo
done

# ---------------------------------------------------------------------------
# Smoke check: does the thing that just deployed actually RUN?
# ---------------------------------------------------------------------------
# Everything above proves the FILES are in place -- S3 accepted them, CloudFront was invalidated,
# and curl gets 200 with a real index.html. None of that says the app inside them starts.
#
# On 2026-09-01 the GrapesJS removal left three calls to a `setEditorMode` that no longer existed.
# Vite compiles a call to an undefined identifier without complaint, so the bundle built clean,
# uploaded clean, and this script printed "Done." over a Content page that threw on mount and
# rendered COMPLETELY BLANK in production. It stayed that way for about fifteen minutes, and what
# eventually noticed was an end-to-end journey failing on a selector three steps later.
#
# So the deploy is not done when the upload finishes. It is done when a browser loads each host and
# no JS error is thrown. That takes seconds and is the cheapest possible guard against shipping a
# blank page.
#
# NON-FATAL BY DESIGN. A failure here means the deploy already happened -- the files are live and
# exiting non-zero would neither undo that nor tell anyone anything the output does not already say.
# It is loud instead, because the whole point is that this failure is otherwise silent. Playwright
# lives in tests/e2e; if it is not installed the check says so and is skipped rather than failing a
# deploy over a missing devDependency.
echo "==> smoke check"
if node "${REPO_ROOT}/infrastructure/scripts/smoke-ui.mjs" "${REQUESTED[@]}"; then
    :
else
    echo
    echo "  !! The upload succeeded and the deployed UI does not run. See above."
    echo "     The previous release is already gone; fix forward rather than expecting a rollback."
fi
echo

echo "Done."
terraform -chdir="$TF_DIR" output ui_urls
