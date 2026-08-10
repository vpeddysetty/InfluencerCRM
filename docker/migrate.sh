#!/bin/sh
# Apply the schema to RDS, in order, then exit.
#
# Run as a one-off ECS task inside the VPC. Reads PGHOST/PGDATABASE/PGUSER from the task definition and
# PGPASSWORD from Secrets Manager — the same secret the DAO uses, so there is no second copy of the
# credential and no chance of the two disagreeing.
#
# EXIT CODE IS THE CONTRACT. Zero means the schema is present and correct. Non-zero means it is not, and
# the deploy must not proceed — a service that starts against a half-migrated database fails later, on a
# query, in a way that looks like an application bug.
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
# moment the instance exists. Without this the first psql fails on a database that would have been ready
# ten seconds later, and the whole deploy fails for a race.
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
# pgvector, needed by mapping_examples_vector.sql for the embedding column and its ivfflat index.
#
# On RDS this requires rds_superuser, which the master user has. It is done as a separate step from the
# .sql file that uses it so the failure is unambiguous: "cannot create extension" is a permissions
# problem, whereas the same failure inside a 20KB script reads like a syntax error.
echo "==> extensions"
psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q \
     -c "create extension if not exists vector;" \
     -c "create extension if not exists pg_stat_statements;" \
    || { echo "FATAL: could not create extensions. Does ${PGUSER} have rds_superuser?" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Schema, in dependency order
# ---------------------------------------------------------------------------
# The order is the same one the postgres image's init hook applies alphabetically in local development —
# base schema, then the vector table, then every migration via zzz_apply_migrations.sql. Stated
# explicitly here rather than relying on a glob, because the ordering is a correctness requirement:
# phase_a creates the landing tables that phase_b and m5_6 later alter.
#
# ON_ERROR_STOP=1 on every invocation. Without it psql reports the error and CARRIES ON, so a failed
# statement produces a zero exit code and a silently incomplete schema — the exact failure this whole
# task exists to prevent.
#
# -1 wraps each file in a single transaction, so a file either applies completely or not at all.
for f in influencer_crm_schema.sql mapping_examples_vector.sql zzz_apply_migrations.sql; do
    echo "==> ${f}"
    psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 -q -1 -f "/schema/${f}" \
        || { echo "FATAL: ${f} failed. The schema is INCOMPLETE; do not start the services." >&2; exit 1; }
done

# ---------------------------------------------------------------------------
# Verify, rather than assume
# ---------------------------------------------------------------------------
# A successful psql exit says the statements ran; it does not say the schema is what the application
# expects. These checks assert the things that were actually missing at some point in this repo's
# history, so a regression is caught here rather than by a 500 in production.
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

# The svc_* roles. These are what make a cross-context query fail at the database rather than at review;
# without them every extracted service fails to authenticate.
check_count "svc_* roles" \
    "select count(*) from pg_roles where rolname like 'svc_%';" \
    8 || FAILED=1

# Representative tables from the eleven migrations that were once missing from the init list. If these
# exist, the whole tail of the migration list ran.
check_count "late-migration tables" \
    "select count(*) from information_schema.tables where table_name in ('landing_template_versions','assets','landing_page_collaborators','subscriptions','brand_domains','creator_platform_tokens');" \
    6 || FAILED=1

# The order-idempotency indexes: the newest migration, and the one that stops a replayed Shopify webhook
# from double-counting a sale.
check_count "order idempotency indexes" \
    "select count(*) from pg_indexes where tablename='influencer_sale_attributions' and indexname like 'uq_isa%';" \
    2 || FAILED=1

if [ "$FAILED" -ne 0 ]; then
    echo "FATAL: schema verification failed. The services must not be started against this database." >&2
    exit 1
fi

echo
echo "Schema applied and verified. No data was written — this task creates structure only."
