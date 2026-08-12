#!/bin/sh
# Apply the schema to Postgres with Flyway, then exit.
#
# EXIT CODE IS THE CONTRACT. Zero means the schema is present and correct. Non-zero means it is not,
# and the deploy must not proceed — every service in docker-compose waits on this container's
# successful exit, so a service never starts against a half-migrated database.
#
# WHY FLYWAY, replacing a psql loop over zzz_apply_migrations.sql (2026-08-11).
#
# The old runner re-executed EVERY migration on EVERY deploy and relied on each one being written
# `if not exists`. That held until a migration needed to touch DATA. `landing_builder_reset` cleared
# pre-GrapesJS fixtures and aborted if a template belonged to a real account — correct once, by hand.
# But it kept running, and the day a real brand published a page it began failing every deploy. The
# migrate container exited non-zero and NOTHING started: an unrelated deploy took the whole platform
# down, reporting an error about landing templates.
#
# A ledger fixes the class, not the instance. Flyway records what it has applied in
# `public.flyway_schema_history` and never runs it twice, so a migration's correctness no longer
# depends on it being safely repeatable forever.
#
# WHAT IS KEPT from the old script, because none of it was the problem: the readiness wait, creating
# extensions as their own step, and the post-run verification.
set -eu

: "${PGHOST:?PGHOST is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required — it comes from Secrets Manager via the task definition}"

export PGCONNECT_TIMEOUT=10

echo "==> target ${PGUSER}@${PGHOST}/${PGDATABASE}"

# ---------------------------------------------------------------------------
# Wait for the database to accept connections
# ---------------------------------------------------------------------------
# RDS reports "available" before it finishes its first startup, and this task can be scheduled the
# moment the instance exists. Without this the first connection fails on a database that would have
# been ready ten seconds later, and the whole deploy fails for a race.
echo "==> waiting for ${PGHOST}"
i=0
until pg_isready -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -q; do
    i=$((i + 1))
    if [ "$i" -ge 60 ]; then
        echo "FATAL: ${PGHOST} did not accept connections after 5 minutes." >&2
        echo "  Check: the task's security group is allowed on 5432, and the instance is 'available'." >&2
        exit 1
    fi
    sleep 5
done
echo "    ready after $((i * 5))s"

# ---------------------------------------------------------------------------
# Extensions first
# ---------------------------------------------------------------------------
# Separate from the migrations that use them so the failure is unambiguous: "cannot create extension"
# is a permissions problem, whereas the same failure inside a migration reads like a syntax error.
# On RDS this needs rds_superuser, which the master user has.
echo "==> extensions"
psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q \
     -c "create extension if not exists vector;" \
     -c "create extension if not exists pg_stat_statements;" \
     -c "create extension if not exists citext;" \
     -c "create extension if not exists pgcrypto;" \
    || { echo "FATAL: could not create extensions. Does ${PGUSER} have rds_superuser?" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Is this an existing database, or an empty one?
# ---------------------------------------------------------------------------
# THE ADOPTION PROBLEM. Production already has all 36 migrations applied, by the old runner, with no
# record of the fact. Pointing Flyway at it with an empty history would make it replay all 36 from
# V1 — most are `if not exists` no-ops, but some are not, and "mostly a no-op" is not a property to
# bet a production database on.
#
# So: a database that already has our tables is BASELINED — Flyway writes a history row saying
# "everything up to V${BASELINE_VERSION} is already here" and applies only what comes after. An empty
# database has no such row and gets every migration from V1, which is what a fresh environment needs.
#
# The probe is `identity.users`, chosen because it exists in the very first schema file and in every
# environment that has ever been deployed. `flyway_schema_history` itself is not a usable probe: it
# is absent both on a fresh database AND on the pre-Flyway production one, which are exactly the two
# cases that must be told apart.
BASELINE_VERSION=36

HAS_SCHEMA="$(psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc \
    "select count(*) from information_schema.tables where table_name = 'users' and table_schema in ('identity','public');")"

HAS_HISTORY="$(psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc \
    "select count(*) from information_schema.tables where table_name = 'flyway_schema_history';")"

# SEARCH PATH, and why it is set explicitly here.
#
# V15 moves every table out of `public` into a per-context schema and makes unqualified names keep
# working with `alter role ... set search_path = identity, creator, ...`. That takes effect on the
# NEXT connection, which was fine for the old runner: it invoked psql once per file, so every file
# after V15 got a fresh connection carrying the new path.
#
# Flyway holds ONE connection for the whole run. Without this, migrations after V15 execute with the
# path the connection opened with — and V35 fails with `relation "influencer_sale_attributions" does
# not exist` because the table is now in `attribution`. Setting it on the JDBC connection makes every
# migration see what V15 intended, on a fresh database and an existing one alike.
#
# `-schemas=public` keeps the history table where the baseline probe expects it; without it Flyway
# would put flyway_schema_history in the first schema on the path.
SEARCH_PATH="identity,creator,campaign,workflow,attribution,finance,content,mapping,shared,public"

FLYWAY_ARGS="-url=jdbc:postgresql://${PGHOST}:5432/${PGDATABASE}?options=-c%20search_path%3D${SEARCH_PATH} \
             -user=${PGUSER} \
             -password=${PGPASSWORD} \
             -locations=filesystem:/schema/flyway \
             -schemas=public \
             -defaultSchema=public \
             -connectRetries=3"

# ---------------------------------------------------------------------------
# The base schema, on an empty database only
# ---------------------------------------------------------------------------
# influencer_crm_schema.sql and mapping_examples_vector.sql are the pre-migration starting point, not
# migrations themselves — they have no version and are not tracked. On an existing database they have
# long since been applied and re-running them is pointless; on an empty one nothing else works
# without them.
#
# ORDER MATTERS, and getting it wrong is caught by nothing but a run: this must happen BEFORE the
# baseline decision below is acted on, but the decision itself must be made from the probe taken
# ABOVE. Applying the base schema fills `public`, so a probe taken afterwards would see a non-empty
# database and baseline a fresh one at V36 — skipping all 36 migrations and leaving a database that
# looks migrated and is missing every table after the base schema.
if [ "$HAS_SCHEMA" -eq 0 ]; then
    for f in influencer_crm_schema.sql mapping_examples_vector.sql; do
        echo "==> ${f} (base schema)"
        psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q -1 -f "/schema/${f}" \
            || { echo "FATAL: ${f} failed. The schema is INCOMPLETE; do not start the services." >&2; exit 1; }
    done
fi

# ---------------------------------------------------------------------------
# Migrate
# ---------------------------------------------------------------------------
# Flyway applies only what its history says is missing, in version order, each in its own
# transaction. A failure leaves that migration unapplied and marked, and exits non-zero.
if [ "$HAS_HISTORY" -eq 0 ] && [ "$HAS_SCHEMA" -gt 0 ]; then
    # An EXISTING database, migrated by the old runner, with no record of it. Mark everything up to
    # V${BASELINE_VERSION} as already present and apply only what comes after.
    echo "==> existing schema, no Flyway history: baselining at V${BASELINE_VERSION}"
    echo "    (migrations up to V${BASELINE_VERSION} were applied by the pre-Flyway runner)"
    # shellcheck disable=SC2086
    flyway $FLYWAY_ARGS \
        -baselineVersion="${BASELINE_VERSION}" \
        -baselineDescription="adopted from zzz_apply_migrations.sql" \
        baseline \
        || { echo "FATAL: baseline failed." >&2; exit 1; }
elif [ "$HAS_HISTORY" -eq 0 ]; then
    # A FRESH database. The base schema above has just filled `public`, so Flyway would refuse to
    # migrate a non-empty schema with no history ("Found non-empty schema(s) but no schema history
    # table"). baselineOnMigrate writes the history table and then applies EVERY migration, because
    # baselineVersion is left at its default of 1 rather than 36 — which is the distinction between
    # this branch and the one above, and the whole reason they are separate.
    echo "==> empty database: every migration will be applied from V1"
    FLYWAY_ARGS="${FLYWAY_ARGS} -baselineOnMigrate=true -baselineVersion=0"
fi

echo "==> flyway migrate"
# shellcheck disable=SC2086
flyway $FLYWAY_ARGS migrate \
    || { echo "FATAL: migration failed. The schema is INCOMPLETE; do not start the services." >&2; exit 1; }

# ---------------------------------------------------------------------------
# Verify, rather than assume
# ---------------------------------------------------------------------------
# A successful exit says the statements ran; it does not say the schema is what the application
# expects. These assert the things that were actually missing at some point in this repo's history,
# so a regression is caught here rather than by a 500 in production.
echo "==> verifying"

check_count() {
    label="$1"; sql="$2"; expected="$3"
    actual="$(psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc "$sql")"
    if [ "$actual" -lt "$expected" ]; then
        echo "FATAL: ${label}: found ${actual}, expected at least ${expected}" >&2
        return 1
    fi
    echo "    ${label}: ${actual}"
}

FAILED=0

# The eight context schemas the extracted services connect into. Fewer than eight means
# phase5_schema_per_context did not apply, and seven services will fail on their first query.
check_count "context schemas" \
    "select count(*) from information_schema.schemata where schema_name in ('identity','creator','campaign','workflow','attribution','finance','content','shared');" \
    8 || FAILED=1

# The svc_* roles. These are what make a cross-context query fail at the database rather than at
# review; without them every extracted service fails to authenticate.
check_count "svc_* roles" \
    "select count(*) from pg_roles where rolname like 'svc_%';" \
    8 || FAILED=1

# Representative tables from the eleven migrations that were once missing from the init list. If
# these exist, the whole tail of the migration list ran.
check_count "late-migration tables" \
    "select count(*) from information_schema.tables where table_name in ('landing_template_versions','assets','landing_page_collaborators','subscriptions','brand_domains','creator_platform_tokens');" \
    6 || FAILED=1

# The order-idempotency indexes: the one that stops a replayed Shopify webhook double-counting a sale.
check_count "order idempotency indexes" \
    "select count(*) from pg_indexes where tablename='influencer_sale_attributions' and indexname like 'uq_isa%';" \
    2 || FAILED=1

# Consent capture — the evidence that a signup was lawful. Its absence is a compliance problem that
# would otherwise surface as a silently unrecorded consent rather than an error.
check_count "consent_records" \
    "select count(*) from information_schema.tables where table_schema='identity' and table_name='consent_records';" \
    1 || FAILED=1

# The ledger itself has to be non-empty, or Flyway ran against the wrong database and every check
# above passed for the wrong reason.
check_count "flyway history rows" \
    "select count(*) from public.flyway_schema_history where success;" \
    1 || FAILED=1

if [ "$FAILED" -ne 0 ]; then
    echo "FATAL: schema verification failed. The services must not be started against this database." >&2
    exit 1
fi

echo
echo "==> applied versions"
psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc \
    "select '    V' || version || ' ' || description from public.flyway_schema_history where success and version is not null order by installed_rank desc limit 5;"

echo
echo "Schema applied and verified. No data was written — this task creates structure only."
