-- =============================================================
-- Migration: identity.deletion_requests
-- Date: 2026-08-11
-- Purpose:
--   Give the deletion process published at /data-deletion/ a record it can be held to.
--
--   The published page promises three things this table has to make true:
--     1) "We acknowledge requests within 5 business days"      -> acknowledged_at
--     2) "...and complete them within 30 days"                 -> completed_at, and a due date
--        derived from requested_at that a report can sort on.
--     3) "We will confirm what will be deleted before we act"  -> scope, recorded before execution.
--
--   Deletion arrives by EMAIL, not through a self-service button. That is a deliberate product
--   choice (see /data-deletion/ section 2), and it is why this table exists at all: without it the
--   only evidence a request was honoured is a support mailbox, which is not an audit trail.
--
-- WHY THIS SURVIVES THE USER IT DESCRIBES
--
--   subject_user_id is ON DELETE SET NULL, never CASCADE. The same reasoning as
--   identity.consent_records (V36): the record that a deletion was requested and completed is
--   precisely what proves the deletion was lawful, so cascading it would destroy the evidence at
--   the exact moment it becomes relevant. subject_email carries the identity forward once the
--   user row is gone -- it is stored on every row rather than looked up through the subject.
--
--   That means this table intentionally retains an email address after the account is deleted.
--   It is the minimum needed to answer "did you honour that request?" and is itself covered by
--   the billing/legal-records retention basis, not the account-data basis.
--
-- WHY subject_email IS NOT UNIQUE
--
--   Someone may delete an account, sign up again later, and delete again. Each is a distinct
--   request with its own clock. A unique constraint would make the second request unrecordable.
--
-- Rollback:
--   drop table if exists identity.deletion_requests;
-- =============================================================

create schema if not exists identity;

create table if not exists identity.deletion_requests (
    id                uuid primary key default gen_random_uuid(),

    -- Null once the user row is purged. Present while the request is in flight, which is when it
    -- is needed to find what to delete.
    subject_user_id   uuid references identity.users(id) on delete set null,

    -- The identity that outlives the user row. Always populated.
    subject_email     text        not null,

    -- account  -> the whole account and the data it owns
    -- provider -> only the data obtained from one federated provider (Google/Facebook), leaving
    --             the account intact. /data-deletion/ section 3.2 promises this separately, and
    --             Meta's reviewers test it.
    scope             text        not null,

    -- Set only when scope = 'provider'. 'google' | 'facebook'.
    provider          text,

    requested_at      timestamptz not null default now(),
    acknowledged_at   timestamptz,
    completed_at      timestamptz,

    -- Populated when a request is refused, e.g. it could not be attributed to an account, or the
    -- requester owns a workspace and has not yet transferred ownership. A refusal is an outcome,
    -- not an error: the page tells people we must refuse what we cannot attribute.
    refused_at        timestamptz,
    refused_reason    text,

    -- Free-text note of what was actually removed, written by the purge service.
    outcome_note      text,

    created_at        timestamptz not null default now(),

    constraint deletion_requests_scope_valid
        check (scope in ('account', 'provider')),

    -- A provider-scoped request without a provider is not actionable.
    constraint deletion_requests_provider_present
        check (scope <> 'provider' or provider is not null),

    -- A request cannot be both completed and refused.
    constraint deletion_requests_terminal_state
        check (completed_at is null or refused_at is null)
);

comment on table identity.deletion_requests is
    'Audit trail for data-deletion requests received by email, per /data-deletion/. Outlives the '
    'user it describes (subject_user_id is SET NULL, not CASCADE) because it is the evidence the '
    'deletion was performed. Retained under the legal-records basis, not the account-data basis.';

comment on column identity.deletion_requests.subject_email is
    'Retained after the user row is gone; this is what makes the record answerable.';

-- Finding in-flight work: the operator view is "what is due".
create index if not exists idx_deletion_requests_open
    on identity.deletion_requests (requested_at)
    where completed_at is null and refused_at is null;

create index if not exists idx_deletion_requests_email
    on identity.deletion_requests (lower(subject_email));

create index if not exists idx_deletion_requests_subject
    on identity.deletion_requests (subject_user_id);

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'svc_identity') then
        execute 'grant select, insert, update on identity.deletion_requests to svc_identity';
    end if;
end $$;

-- Deliberately no delete grant: purging the audit trail of a purge is not an operation the
-- service should be able to perform.

do $$
begin
    if not exists (select 1 from information_schema.tables
                    where table_schema = 'identity' and table_name = 'deletion_requests') then
        raise exception 'deletion_requests was not created';
    end if;
end $$;
