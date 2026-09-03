-- "I posted this" — the creator closing the loop back to the brand (roadmap PR-45).
--
-- WHY A ROW AND NOT A FLAG ON THE PAGE. A boolean would answer "did anyone post this" and nothing
-- else. The questions a brand actually has are which CREATOR posted, to which platform, and when --
-- and one page is shared by many creators, each with their own code. A column could only ever hold
-- the last answer, which on a page with six creators is the wrong one five times.
--
-- IT IS THE CREATOR'S CLAIM, NOT A MEASUREMENT, and the table name says so rather than implying
-- verification. Nothing in this product can see a creator's feed: `business_discovery` is gated
-- behind Meta Advanced Access (PR-27b), and even approved it reads metrics rather than confirming a
-- specific post. Storing this as `posted_at` on the page would let a later reader mistake a
-- self-report for a fact. When PR-46 lands a real adapter, a verified post is a DIFFERENT row with
-- a platform id in it, and the two must stay distinguishable.
--
-- APPEND-ONLY, like page_handoffs. A creator who posts, deletes and reposts has done two things,
-- and a brand asking "what happened here" is better served by both than by the latest overwriting
-- the earlier. It is also the honest shape for something nobody can verify: an edit history of
-- claims is evidence; a mutable single value is not.

begin;

create table if not exists content.share_posts (
    id                    uuid primary key default gen_random_uuid(),
    brand_id              uuid        not null,
    landing_template_id   uuid        not null,
    -- Which creator's code was shared. The kit is per-coupon because the link and the code both
    -- are, so this is what ties a claim to one creator rather than to the page in general.
    campaign_code_id      uuid        not null,
    -- Nullable: a brand may record a post on a creator's behalf, and a creator posting through the
    -- portal has no user row at all -- they authenticate as a creator identity.
    creator_identity_id   uuid,
    reported_by_user_id   uuid,
    -- Free text rather than a check constraint. A closed set would need a migration every time a
    -- platform is added, and this column is a label on a self-report, not something a rule runs on.
    platform              text,
    created_at            timestamptz not null default now()
);

comment on table content.share_posts is
    'A creator''s own claim that they posted a page. NOT a verified post: nothing here can see a '
    'creator''s feed. A platform-confirmed post is a different row, added by PR-46.';

-- The only query this serves today: what has been reported for this page, newest first, so a brand
-- looking at a page sees who says they have posted it.
create index if not exists idx_share_posts_template
    on content.share_posts (landing_template_id, created_at desc);

-- Read and written by the BFF through the DAO's role, like every other content table.
grant select, insert on content.share_posts to influencercrm_user;

commit;
