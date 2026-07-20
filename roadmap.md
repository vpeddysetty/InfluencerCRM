MVP roadmap
Build order for the influencer CRM. The organizing bet: prove small brands will abandon their spreadsheet. Phase 1 exists only to validate that. Everything after earns and defends the subscription.

See CLAUDE.md for product context and influencer_crm_schema.sql for the data model.


Current UI implementation milestone (mock, no backend integration)
Status: completed as a React mock in InfluencerUI, branded for tejdux.io.

What is included:
- Home/auth entry view for brands and solo startup owners (sign up / login UX)
- Spreadsheet import UI accepting CSV/XLS/XLSX with preview support
- Campaign creation UI
- Creator creation UI
- Creator-to-campaign tying workflow
- Kanban-style campaign_creator relationship board (outreach -> agreed -> shipped -> posted -> paid)

Scope note:
- This milestone is intentionally mock-only and does not integrate with agent_service or DAO APIs yet.


Phase 1 — MVP: the spreadsheet replacement
Goal: a brand imports their existing creator sheet and, within their first session, is running campaigns out of the app instead of the sheet. This is the only thing Phase 1 needs to prove.

Ship, in rough dependency order:

Auth + workspace — email/password sign-up and login. One user = one workspace that owns all data. No teams yet.
One-click spreadsheet import — upload CSV/XLSX, map columns to fields, preview, confirm. Creates creators (and optionally campaign_creators) tied to an import_batch. This is the wedge — invest here.
Import undo / re-map — delete a batch to cleanly unlink the rows it created. Reversibility is what makes users trust the import.
Creator list — search, filter by tag, sort. The view that replaces the sheet.
Creator detail — profile, campaign history, and interaction log (notes / emails / DMs). The relationship memory.
Campaign pipeline (Kanban) — five stages (outreach → agreed → shipped → posted → paid), drag to move. Each card is a campaign_creators row. The core daily view.
Manual entry — add a creator or a campaign_creator by hand (codes, links, fee) without importing.

Explicitly not in Phase 1: team roles, live Shopify/TikTok sync, outreach automation, UGC library, auto-enriched profiles.

Definition of done: a new user can sign up, import a real sheet, and manage at least one campaign end-to-end (outreach → paid) without touching a spreadsheet. Every query is scoped by user_id.

Validation signal: do imported brands come back in week two and move cards? If yes, the bet holds and Phase 2 is justified.


Phase 2 — attribution + automation
Goal: make the CRM earn its keep by showing ROI and saving time on follow-up. Start once Phase 1 retention is real.

Shopify integration — auto-generate unique discount codes + affiliate links per creator; pull sales/attribution back into each campaign_creators row (replaces the manual entry from Phase 1).
ROI dashboard — revenue and conversions per creator and per campaign.
Outreach templates + auto follow-up — canned first messages and automatic nudges when a creator stalls in a stage (e.g. sitting in shipped too long).
Time-in-stage tracking — surface who's stuck and needs a nudge; feeds the reminders above.


Phase 3 — the relationship moat
Goal: make the product hard to leave by owning content and creator intelligence. This is where you pull decisively ahead of free tools like Shopify Collabs.

UGC / content library — store every post and reel a creator made for you, with an "approved for paid ads" rights flag.
Auto-enriched creator profiles — paste a handle, auto-fetch follower count, engagement rate, recent posts, contact email.
Repeat-partnership prompts — surface past top performers when planning a new campaign; relationship scoring.
Additional channels — TikTok Shop, Amazon, affiliate networks beyond Shopify.


Sequencing principles
Don't start a phase until the previous one's core bet is validated by real usage.
Prefer additive, reversible changes; never break the user_id tenant filter.
Keep the schema ahead of the UI where it's cheap (e.g. role, multi-user), but don't build UI for it until needed.

