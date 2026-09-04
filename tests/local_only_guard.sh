# Refuse to run a local-stack suite against a remote deployment (roadmap OP-33).
#
# WHAT THIS PREVENTS. Fourteen of the sixteen shell suites reach the database directly with
# `docker exec influencercrm-postgres psql`. That container is on THIS machine. Point the suite at
# a deployed API and it still queries the local database, so every assertion that reads a row back
# compares a live API against an empty local table and fails. The output is a wall of red that
# reads exactly like a broken product.
#
# It has already misled once, on 2026-09-02: `e2e_asset_library` B11b reported "the asset survived
# the cross-tenant delete attempt" while B11 -- the actual API refusal, the part that matters --
# had returned 404 correctly. The product was fine. The test was asking the wrong database.
#
# WHY REFUSING RATHER THAN SKIPPING. A suite that silently skips its database assertions still
# prints a green summary, and a green summary is a claim: it says these behaviours were verified.
# Against production it would be verifying roughly half of what its name implies, and the reader
# has no way to tell which half. Refusing outright is the honest answer, and it makes the mistake
# impossible rather than merely documented -- a header comment saying "local only" is only read by
# someone who already suspected there was a problem.
#
# These suites remain the real coverage for local work. Nothing about them is deprecated; they are
# simply answerable only where their database lives. For judging a deployment, use the Playwright
# suite in tests/e2e/, which asserts through the API and has no such assumption.
#
# USAGE. Source this after the target variable is set, and pass it:
#
#     . "$(dirname "$0")/local_only_guard.sh"
#     require_local_target "$BFF"
#
# Override with E2E_ALLOW_REMOTE=1 if you genuinely want the API-level checks and accept that the
# database ones are meaningless -- deliberately awkward, so it cannot happen by accident.

require_local_target() {
    _target="${1:-}"

    if [ "${E2E_ALLOW_REMOTE:-0}" = "1" ]; then
        echo "WARNING: E2E_ALLOW_REMOTE=1 -- running against '${_target}' while asserting against the" >&2
        echo "         LOCAL database. Every database assertion below is meaningless. Read only the" >&2
        echo "         API-level results, and do not treat a summary line from this run as coverage." >&2
        return 0
    fi

    case "$_target" in
        # Empty counts as local: a suite that hardcodes its URL has nothing to redirect, and
        # failing those would refuse a run that was always going to be local anyway.
        ""|*localhost*|*127.0.0.1*|*host.docker.internal*|*0.0.0.0*)
            return 0
            ;;
    esac

    echo "REFUSING TO RUN: this suite is local-stack only." >&2
    echo "" >&2
    echo "  target:   $_target" >&2
    echo "  database: docker exec influencercrm-postgres (on THIS machine)" >&2
    echo "" >&2
    echo "Its assertions read rows back from a local Postgres container. Against a remote API they" >&2
    echo "would compare live responses to an empty local database, fail nearly everywhere, and look" >&2
    echo "like product defects. That has happened before and cost an afternoon." >&2
    echo "" >&2
    echo "To judge a deployment, use the Playwright suite in tests/e2e/ instead." >&2
    echo "To run anyway and read only the API-level checks: E2E_ALLOW_REMOTE=1 $0" >&2
    exit 2
}
