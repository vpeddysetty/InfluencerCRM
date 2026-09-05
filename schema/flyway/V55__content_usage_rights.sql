-- Content usage rights on a campaign–creator engagement (roadmap PR-68).
--
-- WHAT THIS IS FOR. Paying a creator for a post buys two separate things: the content being MADE,
-- and permission to USE it. The second is what gets brands sued. The default assumption in most
-- influencer deals is that the creator keeps copyright and the brand gets an implied licence to the
-- post existing on the creator's own feed -- nothing more. Running that same photo as a paid ad, on
-- a homepage, or on packaging without an explicit grant is copyright infringement, plus a likeness
-- claim where the creator appears in it. It is a routine source of demand letters in this industry.
--
-- WHY AN AGENCY CARES MORE THAN A BRAND. The agency negotiated the terms and runs the ads, so when
-- a creator's lawyer writes, it writes to them. Eight clients times twenty creators times several
-- campaigns is a few hundred overlapping grants with different expiry dates, currently living in a
-- spreadsheet or nowhere.
--
-- WHY HERE. `campaign_creators` is already the engagement: one row per (campaign, creator), already
-- carrying `contract_signed_at`. Rights are a property of THAT engagement, not of the creator --
-- the same person can grant a brand paid-ad rights on one campaign and organic-only on the next,
-- and hanging this off the creator would force one answer for both.
--
-- WHAT THIS DELIBERATELY IS NOT. It records the TERMS, not the agreement. No document is stored, no
-- signature is captured, and nothing here is evidence of anything -- the contract lives wherever the
-- agency signs contracts. That boundary is what keeps this a small feature instead of `PR-53`'s
-- e-signature work, and it is the same line `PR-49` draws by recording that a W-9 arrived without
-- holding the form.
--
-- NULL MEANS NOT RECORDED, NEVER "GRANTED". Every column here is nullable and unset by default, and
-- readers must render an unset grant as unknown rather than as permission. Failing open on a legal
-- question is worse than an empty field: an empty field prompts someone to ask, and a green tick
-- stops them.

begin;

alter table creator.campaign_creators
    -- What the brand may do with the content. A TEXT ARRAY rather than a set of booleans or an
    -- enum: the vocabulary here is genuinely open (whitelisting, dark posts, in-store, packaging,
    -- OOH), and a check constraint would need a migration the first time an agency negotiates
    -- something ordinary that nobody listed. Suggested values -- organic, paid_amplification,
    -- brand_channels, web, email, print -- are enforced in the application, where widening them
    -- costs nothing.
    add column if not exists usage_scopes text[],
    -- Where the grant applies. Separate from scope because they vary independently: a brand can
    -- hold paid-amplification rights on Instagram and organic-only on TikTok, and a single field
    -- would force the narrower answer onto both.
    add column if not exists usage_platforms text[],
    -- The licence term. Both nullable because "perpetual" is a real answer and is expressed as a
    -- start with no end -- distinct from "not recorded", which is both unset.
    add column if not exists rights_start_at timestamptz,
    add column if not exists rights_end_at timestamptz,
    -- How long the creator may not work with a competitor, counted from the campaign. Days rather
    -- than a date because that is how it is negotiated ("30 days exclusivity"), and deriving a date
    -- from a campaign whose end can move would silently change the term.
    add column if not exists exclusivity_days integer,
    -- Whatever the agreement actually says. There is always a clause that does not fit a column,
    -- and the alternative to a note is that it is not recorded at all.
    add column if not exists usage_rights_note text;

comment on column creator.campaign_creators.usage_scopes is
    'What the brand may do with the content. NULL means NOT RECORDED -- never treat it as granted.';

comment on column creator.campaign_creators.rights_end_at is
    'When the licence lapses. NULL with a start set means perpetual; both NULL means not recorded.';

-- The query the expiry view runs: what lapses soon, for this brand.
--
-- Partial, on rows that HAVE an end date. A perpetual grant and an unrecorded one both have none
-- and neither can expire, so indexing them would be indexing the rows the query exists to skip.
create index if not exists idx_campaign_creators_rights_expiry
    on creator.campaign_creators (brand_id, rights_end_at)
    where rights_end_at is not null;

commit;
