#!/usr/bin/env bash
# Build all eleven images and push them to ECR under one tag.
#
#   ./infrastructure/scripts/build-and-push.sh v1.0.0
#   ./infrastructure/scripts/build-and-push.sh v1.0.0 dao web-experience   # a subset
#
# Then deploy that exact tag:
#   cd infrastructure/terraform && terraform apply -var image_tag=v1.0.0
#
# WHY THE TAG IS AN ARGUMENT AND NOT `latest`. ECR repositories here are IMMUTABLE, so pushing the
# same tag twice is rejected by the registry. That is deliberate: if a tag could be overwritten,
# "deploy v1.0.0" would not identify any particular code and a rollback would have nothing specific
# to roll back to. The Terraform variable rejects `latest` for the same reason.
#
# The tag is also stamped into each image as an OCI label alongside the git SHA, so `docker inspect`
# on a running image answers "what commit is this?" without consulting a deployment log.
set -euo pipefail

TAG="${1:-}"
if [ -z "$TAG" ]; then
    echo "usage: $0 <tag> [service ...]" >&2
    echo "  e.g. $0 v1.0.0" >&2
    exit 1
fi
shift || true

if [ "$TAG" = "latest" ]; then
    echo "ERROR: refusing to push 'latest'. An immutable tag is what makes a rollback expressible." >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

REGION="${AWS_REGION:-us-east-1}"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

# service:module — an empty module means it builds from Dockerfile.agent instead. Must match
# local.services in infrastructure/terraform/ecr.tf; a service here with no repository there fails at
# push with "repository does not exist", which is a clearer error than a missing container at runtime.
SERVICES=(
    "dao:InfluencerDAO"
    "web-experience:InfluencerWebExperience"
    "dps:InfluencerPresentationService"
    "workflow:InfluencerWorkflowService"
    "identity:InfluencerIdentityService"
    "creator:InfluencerCreatorService"
    "campaign:InfluencerCampaignService"
    "attribution:InfluencerAttributionService"
    "finance:InfluencerFinanceService"
    "content:InfluencerContentService"
    "agent:"
)

# A subset may be requested; default is everything.
if [ $# -gt 0 ]; then
    REQUESTED=("$@")
else
    REQUESTED=()
    for entry in "${SERVICES[@]}"; do REQUESTED+=("${entry%%:*}"); done
fi

# Provenance. --dirty so an image built from uncommitted changes says so rather than claiming to be
# a clean commit — the label is worthless if it can lie.
GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
if ! git diff-index --quiet HEAD -- 2>/dev/null; then
    GIT_SHA="${GIT_SHA}-dirty"
    echo "WARNING: working tree has uncommitted changes; images will be labelled ${GIT_SHA}" >&2
fi
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "==> registry ${REGISTRY}"
echo "==> tag      ${TAG}  (git ${GIT_SHA})"
echo

# One login for the whole registry; the credential is good for 12 hours.
aws ecr get-login-password --region "$REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
echo "==> logged in"

build_one() {
    local name="$1" module="$2"
    local image="${REGISTRY}/influencrm/${name}:${TAG}"

    echo
    echo "=============================================================="
    echo "  ${name}  ->  ${image}"
    echo "=============================================================="

    local -a args=(
        --file docker/Dockerfile.service
        --build-arg "MODULE=${module}"
    )
    if [ -z "$module" ]; then
        # The agent has its own Dockerfile and takes no MODULE.
        args=(--file docker/Dockerfile.agent)
    fi

    docker build \
        "${args[@]}" \
        --label "org.opencontainers.image.version=${TAG}" \
        --label "org.opencontainers.image.revision=${GIT_SHA}" \
        --label "org.opencontainers.image.created=${BUILD_DATE}" \
        --label "org.opencontainers.image.source=https://github.com/peddysetty/InfluencerCRM" \
        --tag "$image" \
        .

    docker push "$image"
}

FAILED=()
for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    module="${entry#*:}"

    # Skip anything not requested.
    wanted=false
    for r in "${REQUESTED[@]}"; do
        [ "$r" = "$name" ] && wanted=true && break
    done
    $wanted || continue

    # Keep going on failure and report at the end: with eleven images, stopping on the first means
    # discovering the second problem only after a ten-minute retry.
    if ! build_one "$name" "$module"; then
        FAILED+=("$name")
    fi
done

echo
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "FAILED: ${FAILED[*]}" >&2
    exit 1
fi

echo "All images pushed at tag ${TAG}."
echo
echo "Deploy:"
echo "  cd infrastructure/terraform && terraform apply -var image_tag=${TAG}"
