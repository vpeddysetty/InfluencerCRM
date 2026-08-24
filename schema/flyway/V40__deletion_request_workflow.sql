-- =====================================================================
-- Deletion request workflow — intake, approval, execution
-- Date: 2026-08-23
-- =====================================================================
--
-- WHY
--
-- V37 created identity.deletion_requests to record what /data-deletion/ promises, and nothing ever
-- wrote to it: the only code that could was DeletionRequestController in InfluencerIdentityService,
-- one of the seven extracted services that receive no traffic. Requests arrived at privacy@, were
-- handled by hand, and left exactly the evidence gap V37's header describes.
--
-- This adds what the automated flow needs on top of that table. The columns V37 already has --
-- requested_at, acknowledged_at, completed_at, refused_at, refused_reason, outcome_note, scope --
-- are unchanged and still mean what they meant.
--
-- THE ONE RULE THIS SCHEMA ENFORCES
--
--   Nothing is deleted until a human approves it.
--
-- Deletion is irreversible and arrives by email, which is trivially forgeable. An automated purge
-- triggered by an inbound message would let anyone destroy anyone else's account by sending mail
-- that claims to be from them. So intake RECORDS and NOTIFIES; a separate, explicit approval
-- authorises. approved_at is the gate, and the purge refuses to run without it.
--
-- WHY THE APPROVAL IS A HASHED TOKEN
--
-- The approval arrives as a link in an email to the operator, because that is where the
-- notification already goes and it needs no console session at 2am. That makes the token a
-- credential: it authorises an irreversible destruction of data. So only its SHA-256 is stored,
-- exactly as identity.member_invitations and identity.email_verifications do, and for the same
-- reason -- a leaked database dump must not contain working ones.
--
-- WHY approved_by IS TEXT AND NOT A FOREIGN KEY
--
-- The approver is an operator, identified by the email address the approval link was sent to. That
-- is not necessarily a row in identity.users -- today it is not -- and a foreign key would make the
-- audit trail depend on the approver still having an account. The record of who authorised a
-- deletion must outlive their employment.
--
-- Idempotent by design (safe to re-run).
--
-- Rollback:
--   alter table identity.deletion_requests
--       drop column if exists approval_token_hash,
--       drop column if exists approval_expires_at,
--       drop column if exists approved_at,
--       drop column if exists approved_by,
--       drop column if exists requester_notified_at,
--       drop column if exists operator_notified_at,
--       drop column if exists raw_message_s3_key,
--       drop column if exists intake_source;
-- =====================================================================

create schema if not exists identity;

-- ---------------------------------------------------------------------
-- Approval: the gate in front of an irreversible act
-- ---------------------------------------------------------------------

-- SHA-256 of the approval token, never the token. See the header.
alter table identity.deletion_requests
    add column if not exists approval_token_hash text;

-- After this, the link stops working and a new one must be issued.
--
-- Bounded because an approval link is a standing authorisation to destroy an account: one that
-- never expires sits in a mailbox indefinitely, and a mailbox compromise a year later would still
-- be able to use it. Long enough that a request arriving on a Friday is still actionable.
alter table identity.deletion_requests
    add column if not exists approval_expires_at timestamptz;

-- The gate itself. NULL means not approved, and the purge refuses to run.
alter table identity.deletion_requests
    add column if not exists approved_at timestamptz;

-- Who authorised it. Text, not a foreign key -- see the header.
alter table identity.deletion_requests
    add column if not exists approved_by text;

-- ---------------------------------------------------------------------
-- Notification: what was sent, and when
-- ---------------------------------------------------------------------
--
-- Recorded rather than inferred from a mail log, because "we told you it was done" is part of what
-- the process promises and a log that rotates cannot answer it six months later. Separate columns
-- for the two audiences: the requester is told their data is gone, the operator is told the request
-- arrived. They are sent at different moments and either can fail on its own.

alter table identity.deletion_requests
    add column if not exists operator_notified_at timestamptz;

alter table identity.deletion_requests
    add column if not exists requester_notified_at timestamptz;

-- ---------------------------------------------------------------------
-- Provenance: where the request came from
-- ---------------------------------------------------------------------

-- Key of the raw MIME message in the intake bucket.
--
-- The bucket expires objects after 90 days, so this key WILL eventually dangle. That is deliberate:
-- the audit trail is this row, and the raw message is working data kept only long enough to
-- investigate a request that was mishandled. A dangling key is a documented state, not corruption.
alter table identity.deletion_requests
    add column if not exists raw_message_s3_key text;

-- How the request reached us: 'email' | 'manual'. Manual covers a request received by another
-- route and entered by hand, which must still be recorded and must be distinguishable from one the
-- system parsed itself.
alter table identity.deletion_requests
    add column if not exists intake_source text not null default 'email';

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'deletion_requests_intake_source_valid'
    ) then
        alter table identity.deletion_requests
            add constraint deletion_requests_intake_source_valid
            check (intake_source in ('email', 'manual'));
    end if;

    -- An approval must name its approver. Enforced rather than trusted: an approved_at with no
    -- approved_by is an audit trail that cannot answer the only question it exists to answer.
    if not exists (
        select 1 from pg_constraint where conname = 'deletion_requests_approver_named'
    ) then
        alter table identity.deletion_requests
            add constraint deletion_requests_approver_named
            check (approved_at is null or approved_by is not null);
    end if;

    -- Completion requires approval. This is the schema-level expression of the rule in the header:
    -- even a bug in the service cannot mark a request complete that nobody authorised.
    if not exists (
        select 1 from pg_constraint where conname = 'deletion_requests_completed_was_approved'
    ) then
        alter table identity.deletion_requests
            add constraint deletion_requests_completed_was_approved
            check (completed_at is null or approved_at is not null);
    end if;
end $$;

comment on column identity.deletion_requests.approval_token_hash is
    'SHA-256 of the approval token. The token authorises an irreversible deletion, so it is a '
    'credential and is never stored in readable form.';
comment on column identity.deletion_requests.approved_at is
    'The gate. NULL means no human has authorised this and the purge must refuse to run.';
comment on column identity.deletion_requests.approved_by is
    'Email address of the operator who approved. Text, not a FK: the record must outlive them.';
comment on column identity.deletion_requests.raw_message_s3_key is
    'Raw MIME message in the intake bucket. Expires after 90 days, so this key eventually dangles '
    'by design -- the audit trail is this row.';

-- ---------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------

-- Redeeming an approval link is a lookup by token hash, on a path that must be fast and must not
-- scan. Unique because two requests sharing a token would make one link approve the wrong one.
create unique index if not exists uq_deletion_requests_approval_token
    on identity.deletion_requests (approval_token_hash)
    where approval_token_hash is not null;

-- "What is waiting for me to approve" -- the operator's queue.
create index if not exists idx_deletion_requests_awaiting_approval
    on identity.deletion_requests (requested_at)
    where approved_at is null and refused_at is null and completed_at is null;

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
--
-- Still no delete, for the reason V37 gives: a service that can erase the record of a purge can
-- erase the evidence the purge was lawful.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'svc_identity') then
        execute 'grant select, insert, update on identity.deletion_requests to svc_identity';
    end if;
end $$;

-- ---------------------------------------------------------------------
-- Verify
-- ---------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from information_schema.columns
                    where table_schema = 'identity' and table_name = 'deletion_requests'
                      and column_name = 'approved_at') then
        raise exception 'deletion_requests.approved_at was not added';
    end if;
    if not exists (select 1 from pg_constraint
                    where conname = 'deletion_requests_completed_was_approved') then
        raise exception 'the completion-requires-approval constraint was not added';
    end if;
end $$;
