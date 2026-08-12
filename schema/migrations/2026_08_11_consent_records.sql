-- =====================================================================
-- Consent capture — terms of service and privacy policy acceptance
-- Date: 2026-08-11
-- =====================================================================
--
-- WHY
--
-- Nothing in the platform recorded that anyone agreed to anything. The signup form said "By
-- continuing you agree to our Terms of Service and Privacy Policy" and linked to two pages that were
-- returning AccessDenied, so the only evidence of consent was a sentence next to a broken link.
--
-- Under GDPR Article 7(1) the controller must be able to DEMONSTRATE that the data subject consented.
-- "The form said so" is not a demonstration: it cannot show WHO agreed, WHEN, or to WHICH VERSION of
-- a document that will be revised. This table is that evidence.
--
-- WHAT IS AND IS NOT CONSENT
--
-- Only some of this is consent in the Article 6(1)(a) sense. Accepting the Terms of Service is
-- contractual necessity, 6(1)(b). Recording both in one table is deliberate: the evidentiary question
-- ("what did this person agree to, and when") is identical, and splitting them would mean two tables
-- with the same shape and two places to look during a subject access request. `consent_type`
-- distinguishes them.
--
-- SHAPE — FOLLOWING billing_events
--
-- Append-only, like identity.billing_events: uuid pk, jsonb payload, created_at default now(), a
-- unique index expressing the idempotency rule, and no update path. A consent record is a statement
-- about a moment. Revocation or re-acceptance is a NEW ROW, never an edit — an audit trail that can be
-- rewritten is not an audit trail. That is also why there is no updated_at.
--
-- WHY subject_type/subject_id RATHER THAN A FOREIGN KEY
--
-- The four surfaces that capture consent do not share a table:
--
--   user        -> identity.users            brand owner, agency owner, invited teammate
--   creator_identity -> identity.creator_identities   creator portal account
--   lead        -> (no account at all)       public landing-page capture; PII with no login
--
-- A single nullable-FK-per-type design would mean three mostly-null columns and a check constraint to
-- keep them exclusive. The polymorphic pair is honest about there being no single parent table. The
-- cost is no referential integrity, which is ACCEPTABLE HERE AND WOULD NOT BE ELSEWHERE: a consent
-- record must OUTLIVE the account it describes. If a user is deleted, the record that they once
-- consented is precisely what proves the deletion was lawful, so an ON DELETE CASCADE would destroy
-- the evidence at the exact moment it becomes relevant.
--
-- THE LEAD CASE
--
-- `subject_id` is null for a lead captured on a public landing page: there is no account row to point
-- at. `subject_email` carries the identity instead, which is why it is stored on every row rather
-- than being looked up through the subject.
--
-- Rollback:
--   drop table if exists identity.consent_records;

create schema if not exists identity;

create table if not exists identity.consent_records (
    id                uuid primary key default gen_random_uuid(),

    -- Who. See the header for why this is a polymorphic pair and not a foreign key.
    subject_type      text        not null,
    subject_id        uuid,
    -- Denormalised on purpose: the only identifier a lead has, and it must survive the subject row
    -- being deleted. citext so it matches identity.users.email's comparison semantics.
    subject_email     citext      not null,

    -- What was agreed to.
    consent_type      text        not null,
    -- The version of the document. Text, not a date: the policy is versioned by its published
    -- "Last updated" string, and comparing what someone accepted against what is live now is a string
    -- equality test. A date type would invite arithmetic that has no meaning here.
    document_version  text        not null,

    -- Whether this row grants or withdraws. A withdrawal is a new row with granted=false, so the
    -- current state is the LATEST row per (subject, consent_type) — never an update of an old one.
    granted           boolean     not null default true,

    -- Where it happened: the endpoint that captured it, e.g. 'brand_signup', 'creator_portal_signup'.
    -- Article 7(1) asks what the subject was shown; the surface is how that is reconstructed.
    source            text        not null,

    -- Evidence of the act itself. Retained because a bare boolean is weak evidence if ever disputed.
    -- These are personal data in their own right and inherit the subject's retention period.
    ip_address        inet,
    user_agent        text,

    -- Anything surface-specific: the brand name at signup, the landing page slug, the invitation id.
    metadata          jsonb       not null default '{}'::jsonb,

    created_at        timestamptz not null default now(),

    constraint consent_records_subject_type_check
        check (subject_type in ('user', 'creator_identity', 'lead')),
    constraint consent_records_consent_type_check
        check (consent_type in ('terms_of_service', 'privacy_policy')),
    -- A lead has no account row; everyone else must have one. Catches a wiring mistake that would
    -- otherwise produce an orphan record indistinguishable from a lead.
    constraint consent_records_subject_id_present
        check (subject_type = 'lead' or subject_id is not null)
);

-- The subject-access-request query: everything one person ever agreed to. Email rather than id
-- because a request arrives as an email address, and because it is the only handle a lead has.
create index if not exists idx_consent_records_subject_email
    on identity.consent_records (subject_email, created_at desc);

-- "What is this account's current consent state" — the latest row per subject and type.
create index if not exists idx_consent_records_subject
    on identity.consent_records (subject_type, subject_id, consent_type, created_at desc);

-- Which users are still on a superseded policy version, for a re-consent campaign after a revision.
create index if not exists idx_consent_records_version
    on identity.consent_records (consent_type, document_version);

-- IDEMPOTENCY, mirroring idx_billing_events_provider_event.
--
-- Signup is retried: a double-clicked button or a client retry after a timeout can deliver the same
-- signup twice. Without this, one acceptance becomes two rows with different timestamps, and the
-- question "when did they agree" acquires two answers.
--
-- Scoped to granted rows so that a later withdrawal — and a re-acceptance after it — are not blocked
-- by the original grant. A partial index is what makes "one grant per version" and "revocation is a
-- new row" coexist.
create unique index if not exists idx_consent_records_unique_grant
    on identity.consent_records (subject_type, subject_id, consent_type, document_version)
    where granted = true and subject_id is not null;
