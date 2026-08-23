-- =====================================================================
-- Consent evidence — the URL, the document text, and version history
-- Date: 2026-08-23
-- =====================================================================
--
-- WHY
--
-- V36 records that someone accepted version "2026-08-11" of the privacy policy. That is enough to
-- answer WHEN and WHICH VERSION, and not enough to answer the question that actually gets asked in
-- a dispute: WHAT DID IT SAY?
--
-- A version string is a label, not evidence. The published page at /privacy/ is a mutable S3 object;
-- republishing it overwrites the bytes and leaves the label pointing at text nobody kept. So the
-- record would attest to a document that no longer exists in the form it was accepted in. GDPR
-- Article 7(1) asks the controller to demonstrate consent, and "they accepted a version whose text
-- we cannot produce" is a weak demonstration.
--
-- Two things are added, for two different questions:
--
--   1. consent_records.document_url + document_sha256 + evidence_s3_key
--      Per-acceptance. WHAT WAS THIS PERSON SHOWN, and where is the proof.
--
--   2. identity.consent_document_versions
--      Per-document, INDEPENDENT OF ANY USER. WHAT VERSIONS HAVE EVER BEEN PUBLISHED. This exists
--      as its own table rather than being derived with SELECT DISTINCT over consent_records because
--      a version nobody accepted still has to be provable -- a policy published for a week during
--      which nobody signed up would otherwise leave no trace at all. It is also the join target
--      that lets a subject-access request answer "here is the exact text" without storing the
--      document body once per acceptance.
--
-- WHY THE HASH AND NOT THE TEXT
--
-- The document body lives in S3 (see infrastructure/test/terraform/consent-evidence.tf), written
-- once per version under Object Lock. Postgres holds the SHA-256 and the key. Storing 24KB of HTML
-- per consent row would multiply the same bytes by every signup; storing it once per version and
-- referencing it keeps the DB answering "which" and lets the object store answer "what".
--
-- The hash is the load-bearing part: it is what makes the S3 copy checkable. Without it, an object
-- store copy is just another mutable file and proves nothing more than the live page did.
--
-- WHY THE NEW COLUMNS ARE NULLABLE
--
-- Every consent_records row written before this migration has no URL and no snapshot, and no
-- backfill can honestly invent one -- we do not know what those users were shown beyond the version
-- label, which is precisely the gap being closed. NULL means "captured before evidence capture
-- existed". A default would fabricate evidence, which is worse than having none: it would make old
-- rows indistinguishable from new ones while being unverifiable.
--
-- This is the same reasoning as V38's email_verified_at, and it is deliberate that both read the
-- same way: an absent value marks a real historical gap rather than asserting a fact.
--
-- Idempotent by design (safe to re-run).
--
-- Rollback:
--   alter table identity.consent_records
--       drop column if exists document_url,
--       drop column if exists document_sha256,
--       drop column if exists evidence_s3_key;
--   drop table if exists identity.consent_document_versions;
-- =====================================================================

create schema if not exists identity;

-- ---------------------------------------------------------------------
-- 1. Per-acceptance evidence on the existing record
-- ---------------------------------------------------------------------

-- The absolute URL the person was shown, e.g. https://www.tejdux.com/privacy/.
--
-- Recorded per row rather than derived from consent_type because the address can change -- a policy
-- could move to a versioned path, or a regional variant could be served -- and the record must say
-- where THIS person read it, not where the document lives today.
alter table identity.consent_records
    add column if not exists document_url text;

-- SHA-256 (lowercase hex) of the exact document bytes at the moment of acceptance.
--
-- 64 hex characters. Checked rather than assumed, because a truncated or uppercase value would
-- silently fail every later comparison against a recomputed digest and look like tampering.
alter table identity.consent_records
    add column if not exists document_sha256 text;

-- Where the snapshot lives, e.g. documents/privacy_policy/2026-08-11/document.html.
alter table identity.consent_records
    add column if not exists evidence_s3_key text;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'consent_records_document_sha256_format'
    ) then
        alter table identity.consent_records
            add constraint consent_records_document_sha256_format
            check (document_sha256 is null or document_sha256 ~ '^[0-9a-f]{64}$');
    end if;
end $$;

comment on column identity.consent_records.document_url is
    'Absolute URL the subject was shown. NULL for rows captured before evidence capture existed.';
comment on column identity.consent_records.document_sha256 is
    'SHA-256 of the document bytes as accepted. Makes the S3 snapshot checkable; without it the '
    'snapshot is just another mutable file.';
comment on column identity.consent_records.evidence_s3_key is
    'Key of the immutable snapshot in the consent-evidence bucket.';

-- ---------------------------------------------------------------------
-- 2. Version history, independent of any user
-- ---------------------------------------------------------------------

create table if not exists identity.consent_document_versions (
    id                uuid primary key default gen_random_uuid(),

    -- Matches consent_records.consent_type. Same vocabulary on purpose: a join between the two is
    -- the query that answers "show me the text this person accepted".
    consent_type      text        not null,

    -- The published "Last updated" string, e.g. '2026-08-11'. Text for the same reason V36 gives:
    -- comparison is string equality, and a date type would invite arithmetic that means nothing.
    version           text        not null,

    -- Where it was published and what it said.
    url               text        not null,
    content_sha256    text        not null,
    s3_key            text        not null,

    -- Bytes, as a cheap corroboration of the hash. A mismatch in either is a signal the snapshot
    -- is not what it claims to be.
    content_bytes     integer     not null check (content_bytes > 0),

    -- When this version went live, and when it stopped being current. superseded_at is NULL for the
    -- version in force now; exactly one row per consent_type should have it NULL, but that is not
    -- enforced by a constraint because the transition is two statements and a brief overlap during
    -- a republish is not corruption.
    published_at      timestamptz not null default now(),
    superseded_at     timestamptz,

    -- How the row got here: 'startup_snapshot' when the app uploaded it on boot, 'manual' if
    -- backfilled by hand. Distinguishes evidence the system captured from evidence a human asserted.
    captured_by       text        not null default 'startup_snapshot',

    created_at        timestamptz not null default now(),

    constraint consent_document_versions_type_check
        check (consent_type in ('terms_of_service', 'privacy_policy')),
    constraint consent_document_versions_sha256_format
        check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint consent_document_versions_dates_ordered
        check (superseded_at is null or superseded_at >= published_at)
);

-- One row per published version. This is the idempotency rule: the app re-uploads its snapshot on
-- every boot, and a restart must not create a second row for a document that has not changed.
create unique index if not exists uq_consent_document_versions
    on identity.consent_document_versions (consent_type, version);

-- "What is in force now" -- the lookup on the signup path, so it is indexed rather than scanned.
create index if not exists idx_consent_document_versions_current
    on identity.consent_document_versions (consent_type)
    where superseded_at is null;

-- Reverse lookup from a consent record's hash back to the version that produced it, for the
-- subject-access-request join.
create index if not exists idx_consent_document_versions_sha
    on identity.consent_document_versions (content_sha256);

comment on table identity.consent_document_versions is
    'Every published version of the terms and privacy policy, independent of who accepted them. '
    'Exists separately from consent_records so a version nobody accepted is still provable, and so '
    'the document text is stored once per version rather than once per acceptance.';

comment on column identity.consent_document_versions.superseded_at is
    'NULL for the version currently in force. Set when a newer version is published.';

-- ---------------------------------------------------------------------
-- 3. Grants
-- ---------------------------------------------------------------------
--
-- Insert and update only. No delete, for the same reason V37 withholds it from deletion_requests:
-- a service that can erase the record of what was published can erase the evidence that the record
-- exists to provide.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'svc_identity') then
        execute 'grant select, insert, update on identity.consent_document_versions to svc_identity';
    end if;
end $$;

-- ---------------------------------------------------------------------
-- 4. Verify
-- ---------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from information_schema.tables
                    where table_schema = 'identity' and table_name = 'consent_document_versions') then
        raise exception 'consent_document_versions was not created';
    end if;
    if not exists (select 1 from information_schema.columns
                    where table_schema = 'identity' and table_name = 'consent_records'
                      and column_name = 'document_sha256') then
        raise exception 'consent_records.document_sha256 was not added';
    end if;
end $$;
