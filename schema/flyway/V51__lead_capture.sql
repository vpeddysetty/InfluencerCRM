-- Lead capture on a public landing page (roadmap PR-61).
--
-- THIS DELIBERATELY REMOVES A GUARD, and the guard was right until now. Three places in this
-- codebase refuse to put a form on a landing page, all for the same stated reason: a form there
-- "would collect personal data on an anonymous public page with nowhere to POST it"
-- (LandingService.renderSection), the `signup` section is named "Closing call" precisely so the
-- picker does not promise a field that is not there (sectionTypes.js), and
-- LandingDocumentSanitizer strips <form> even from the builder path. Those three were consistent
-- and correct. What changes is the "nowhere to POST it" half: this table is the somewhere, and the
-- consent machinery PR-31/PR-36 built is what makes collecting it defensible rather than merely
-- possible.
--
-- WHAT THIS IS NOT. It is not a creator row. A visitor who asks a brand to get in touch has not
-- joined that brand's creator roster, has no handle, and must not appear in a vetting queue --
-- conflating the two would put a member of the public into a workflow designed for commercial
-- partners. `creator.creators` already carries lead_source for creators who applied; this is a
-- different kind of lead and lives apart from it.
--
-- EMAIL IS THE SUBJECT KEY, and that is the whole reason consent matters here. There is no account
-- to attach the record to: a person hands a brand their address on a page served to anonymous
-- visitors, and the platform processes it as a third party. That is exactly the case with the
-- weakest paper trail and the one an erasure request is most likely to arrive about, which is why
-- consent is recorded through ConsentService rather than as a boolean column here -- the evidence
-- (version, document URL, immutable snapshot) belongs in the system built for it.

begin;

create table if not exists content.page_leads (
    id                    uuid primary key default gen_random_uuid(),
    brand_id              uuid        not null,
    landing_template_id   uuid        not null,
    -- Which creator's personalised page it came from, when it came from one. Nullable because the
    -- brand's own page has no coupon, and a lead from there is still a lead.
    campaign_code_id      uuid,

    -- The only three fields collected. Deliberately no phone, no address, no company: every extra
    -- field is more personal data to defend on a page with no account behind it, and a brand that
    -- needs more can ask for it in the reply.
    email                 text        not null,
    name                  text,
    message               text,

    -- Set by the server from the request, never from the form: an IP a client could name is
    -- evidence of nothing, and this is the record an erasure or a dispute is answered from.
    ip_address            text,
    user_agent            text,

    created_at            timestamptz not null default now()
);

comment on table content.page_leads is
    'A visitor asking a brand to get in touch, from a public landing page. NOT a creator: no handle, '
    'no vetting, and it must never reach the creator roster. Consent is recorded separately through '
    'ConsentService, keyed by email, because there is no account to attach it to.';

-- The query a brand actually runs: what came in for this page, newest first.
create index if not exists idx_page_leads_template
    on content.page_leads (landing_template_id, created_at desc);

-- And the one an erasure request runs. Case-insensitive because a person asking to be forgotten
-- will not type their address the way the form recorded it, and a lower(email) predicate without
-- this index is a sequential scan over every brand's leads.
create index if not exists idx_page_leads_email
    on content.page_leads (lower(email));

-- NOT unique on (template, email). A visitor who asks twice has asked twice -- possibly because the
-- first message went unanswered -- and silently discarding the second would hide exactly the signal
-- a brand needs. Rate limiting belongs in front of the endpoint, not in a constraint here.

grant select, insert, delete on content.page_leads to influencercrm_user;

commit;
