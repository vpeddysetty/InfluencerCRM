# The creator handoff — design and decision record

**Status:** design, scheduled as `PR-40`..`PR-44` and `OP-18` in `MASTER-ROADMAP.md`, which remains
the scheduling authority. Written 2026-08-27 from an 11-agent design workflow; every defect in §1 was
re-verified directly against the code before being recorded here.

**Question asked:** brand and creator collaborate on authoring a landing page, each with their own
login, with a lifecycle between them, an acknowledgement email on publish, and the ability for the
creator to publish to their own handle.

**Related:** `docs/Creator-Portal-UI-Design.md` (the creator UI), `docs/Creator-Handoff-AI-Per-Actor.md`
(prompt design per actor), `docs/Landing-Editor-Framework-Evaluation.md` (why the editor is
section-shaped), `docs/platform-app-registration.md` (the Meta dossier §6 depends on).

**Prompt caching is deliberately NOT part of this plan.** The measured cacheable prefix is ~437
tokens against `claude-opus-5`'s 512-token minimum, so caching today is a silent no-op — the API
accepts `cache_control`, caches nothing, and `cache_read_input_tokens` stays 0. Adding the per-actor
voice prompts in §5 would take the prefix to ~700 and make it engage, but at present volume the
saving is under a dollar a month. Deferred by decision 2026-08-27; revisit only if generation volume
and prompt size both grow.

---

# The Creator Handoff: A Decision-Ready Plan

## 1. THE SHAPE

**The handoff is not a new subsystem. It is a collaborator grant and a stage transition fired together, plus one tokenised email that breaks the bootstrap circularity.** The collaboration engine, the stage machine, the AI ports, and the email transport all already exist. What is missing is a creator-facing UI, an invitation with a token, and a `turn` — a nullable column answering "whose move is it?" beside the existing stage answering "how far along is this?"

But **you cannot build any of it on the current code, because the foundation has five live defects**, four of which I verified directly and none of which are visible from reading the happy path:

| Defect | Verified | Consequence |
|---|---|---|
| `saveAsCollaborator` drops `sections` | `PageCollaborationService.java:167-182` handles only `document`/`blocks` | Creator's edit returns 200 and vanishes |
| `saveAsCollaborator` drops `scheduledPublishAt` | `LandingService.java:85-86` carries it forward; the collaborator path never sets it | **Creator saves once → the brand's scheduled launch is silently cancelled** |
| `decide()` has no brand check | `CreatorIdentityController.java:128` loads the link by id alone | Any brand confirms any other brand's pending claim → cross-tenant access |
| `pagesForCreator`/`requireEditRights` trust `grant.brandId` | Neither compares it to the fetched page's `brandId` | A malformed grant row = cross-brand read+write |
| No `@Version` on `LandingTemplate` | zero hits in the domain class | Concurrent brand+creator edits silently overwrite, unrecoverably |

The third and fourth are **live cross-tenant defects today**. The second is the worst of the set — a missed campaign launch with no error anywhere. Increment 0 is therefore three days, not half a day, and it ships real value on its own: it fixes bugs that exist in production right now regardless of whether you ever build a creator portal.

**Three external dependencies are procurement, not engineering, and two of the three designs got them backwards.** SES is in sandbox with zero verified identities (`MASTER-ROADMAP.md:188`) — every "cheap" email increment fails silently until you fix that. Anthropic's balance is zero (`PR-35`), so the generation provider defaults to `template`. And Meta review is **not** an unwinnable wall — `PR-27b` says "~2 days, deliberately not on the critical path," with a completed submission dossier in `docs/platform-app-registration.md`. Start all three today; they run on calendar time, in parallel with everything else.

---

## 2. THE FOUR ACTORS

**Brand owner** (`OWNER`, account-scoped, `impliesAllBrands`). Authors pages, invites creators, is the only actor who publishes. Sees the collaborator panel, the pending-claims queue, and a "Waiting on you" filter driven by `turn`. Holds `ACCOUNT_BILLING`.

**Agency owner** (`OWNER`/`ADMIN` on an account with several brands). Structurally identical to brand owner — the multi-brand case is already handled by `AccountRole.impliesAllBrands()`. No new code. The one thing they gain: `BriefEnricher` already accepts `brandTone`/`brandName`, so per-brand voice at volume works today.

**Marketer** (`MARKETER`, brand-scoped, needs an explicit grant). Does the day-to-day authoring and initiates the handoff. Has `CONTENT_WRITE` and `CREATOR_WRITE` but **not** `content:publish` — so a marketer can hand off and hand back but cannot publish. This is the existing separation and it stays.

**Creator** (not a role at all — authenticates through `CreatorPortalService` with `X-Creator-Token`). Edits page *content* and nothing else. Cannot publish, cannot change slug/status/stage, cannot see any other brand's pages. Deliberately holds no `accountId`, no `brandId`, no `account_role`.

---

## 3. THE LIFECYCLE

Two orthogonal axes. **Stage** (existing, 8 values in `LandingStageMachine`) = how far along. **Turn** (new, nullable: `brand`|`creator`|`null`) = whose move. They change for different reasons — a page sits at `content_needed` while the turn bounces three times.

```
                        turn=brand
  draft ──► review ──► approved
                          │
                          │  ◄─── THE HANDOFF (one button, one endpoint)
                          │       POST /api/landing-pages/{id}/handoff
                          │       = invite(rights=edit) + changeStage(creator_assigned) + turn=creator
                          ▼
                  creator_assigned ──────────────► content_needed
                        turn=creator                  turn=creator
                          │                               │
                          │  "Take it back"               │  "Send back to Acme"
                          │  (existing reverse edge)      │  POST /creator-portal/pages/{id}/hand-back
                          ▼                               ▼
                      approved                      content_needed, turn=brand
                                                          │
                                                          │  brand reviews, accepts
                                                          ▼
                                                   ready_to_publish
                                                     turn=brand
                                                          │
                                                          │  ONLY content:publish
                                                          ▼
                                                      published ──► performance_tracking
                                                     turn=null          turn=null
```

| # | State | Actor | Action | Notifies |
|---|---|---|---|---|
| 1 | `draft`/`review`/`approved`, turn=brand | Owner/marketer | Authors in `SectionEditor`, optionally seeded by `/api/campaign-pages/generate` | Nobody — default state |
| 2 | **`creator_assigned`, turn=creator — THE HANDOFF** | Owner/marketer (`CONTENT_WRITE`+`CREATOR_WRITE`) | One button. Mints invite token if needed, calls `PageCollaborationService.invite(rights='edit')`, `changeStage(creator_assigned)`, sets turn | **Creator**: `CreatorHandoffEmail` + magic link |
| 3 | `content_needed`, turn=creator | Creator | Edits in the same `SectionEditor`, saves via `PUT /api/creator-portal/pages/{id}` | Nobody on save — saving is not an event |
| 4 | `content_needed`, **turn=brand — HANDOFF BACK** | Creator | "Send back to Acme". Moves **turn only, not stage** — the creator asserts done, the brand decides `ready_to_publish` | **Granting user**: `CreatorSubmittedEmail` |
| 5 | `ready_to_publish`, turn=brand | Owner/marketer | Reviews diff against the version snapshot. Accepts, or bounces back to step 2 with a note | On bounce: creator, with the note |
| 6 | **`published`** | Owner **only** (`content:publish`) | `LandingStageService.publishNow` walks the shortest legal path | **Creator: `CreatorPublishNotificationEmail` — the acknowledgement asked for.** Keyed on `first_published_at` |
| 7 | `published`, turn=creator | Creator | Opens the Share Kit. Assisted-manual — see §6 | On "I posted this": the brand |
| 8 | **Abandonment** (no design handled this) | Scheduler | `turn_changed_at` sweep: reminder day 3, brand nudge day 7 | Creator, then brand |
| 9 | **Unpublish** (no design handled this) | Owner | Retracting a page any creator was emailed about | **Creator: retraction email.** Warn the brand first if the creator reported posting |
| 10 | **Revocation mid-edit** | Brand | `revoke()` — server-side already correct | Creator gets `{"code":"access_revoked"}`, sees "Acme ended your access" + **Download your draft** |

Rows 8, 9 and 10 are the unhappy paths. Ghosting is the *modal* outcome in real creator marketing, and all three candidate designs assumed forward motion. The reverse edges `CREATOR_ASSIGNED → APPROVED` and `CONTENT_NEEDED → CREATOR_ASSIGNED` **already exist in the machine** (lines 53-54) — nobody wires them. Wiring them is free.

**One rule, written in a comment, to stop turn/stage drift:** the collaborator row is authoritative for "is a creator involved"; the stage is display. `revoke()` moves the stage back to `approved` when it removes the last active grant. A single command path owns both columns; never a direct column write.

---

## 4. THE CREATOR EXPERIENCE

**A new Vite project, `InfluencerCreatorPortalUI/`, on its own CloudFront distribution at `creators.tejdux.com`.** Not a route in `InfluencerUI` — the shell's `App.jsx` assumes an operator bearer token, an `accountId` and a brand switcher, and every one of those would grow an "unless creator" branch.

**It talks to the BFF directly, not through the DPS.** Adding a `CREATOR_PORTAL` constant to `AppRegistry` is not merely insufficient, it is structurally wrong: `scope()` intersects against `account_role` permissions a creator provably lacks, so it returns empty for every creator; `UiSession` is a record with mandatory `userId`/`accountId`/`brandId`/`role`; and `ApiProxyController` attaches `session.accessToken()` + `X-Brand-Id` when a creator needs `X-Creator-Token`. The cost of going direct — the token lives in JS, not an httpOnly cookie — is a real, documented trade, not an oversight.

**The security posture must be inverted first.** `CREATOR_PORTAL_PATHS` is `permitAll()` at the filter chain (line 165), with *all* authentication in hand-rolled controller code. You are about to add six to eight endpoints to that surface. One forgotten `requireCreator(token)` is a fully unauthenticated endpoint serving unpublished pages. **Write a `CreatorTokenAuthenticationFilter` and change those matchers to `authenticated()`** so a forgotten check fails closed. One day, and it is the single highest-leverage security change in the plan.

**Four screens, mobile-first.** `SectionEditor`'s own Javadoc says reordering uses up/down buttons rather than drag because "dragging is the interaction that breaks on a phone, which is where a creator asked to tweak their page will be." That is exactly this surface.

1. **`/invite/{token}`** — the entire cold start, no login wall. Shows brand name, campaign, and a **redacted teaser** — brand name, campaign name, one line. **Not the rendered page.** A GET that renders stored unpublished content is fetched automatically by email scanners, Slack/WhatsApp unfurlers, and link-prewarmers; one forwarded invite leaks an unreleased campaign. Acceptance is a POST. Then password + consent (`ConsentService.recordSignupConsent(SUBJECT_CREATOR_IDENTITY, …)` already exists), below the fold, after they see why.
2. **`/pages`** — "Waiting on you" (turn=creator) first, then "In progress", then "Live". This *is* the whose-turn answer. Backed by the existing `GET /api/creator-portal/pages`, which already projects `sections` correctly.
3. **`/pages/{id}`** — mounts `SectionEditor.jsx` **unchanged**. Verified pure presentational: its only import is `../shell/sectionTypes`, zero fetch, zero session, zero `brandId`. Creator-scoped callbacks: `onSave` → `PUT /api/creator-portal/pages/{id}`; `onPreview` → new creator preview endpoint; `onRewrite` → new creator rewrite endpoint. Pass `savedTemplates={[]}` and **omit `onSaveAsTemplate`** — the gallery is metered against the brand's `PlanPolicy.Resource.SAVED_TEMPLATE` quota. Mobile specifics: accordion (one section open at a time), panel-first with canvas as a "Preview" tab, autosave on blur *and* `visibilitychange`, 16px minimum inputs (or iOS zooms), camera-capture upload with client-side downscale.
4. **`/pages/{id}/share`** — post-publish Share Kit.

**What a creator cannot do, and why:** publish (structurally — `saveAsCollaborator` reads `status`/`stage` off the stored row, and `ck_collaborators_rights` has no `publish` value; the constraint *is* the policy); own a page; see another brand's work; or set the stage to anything but the hand-back. Enforce that last one **once, centrally** — an `assertCreatorStageTransition(from, to)` allowlist defaulting to deny, with `PUBLISHED` unreachable unconditionally — not restated at each new endpoint.

**Session:** keep the opaque server-side token (not a JWT) for the reason the Javadoc gives — re-reading the DB every call means revocation is immediate. But move it out of the `ConcurrentHashMap` to a `creator_portal_sessions` table **before the first real creator uses it**, because an ASG roll is the live step of every deploy. Keep the access TTL at ≤24h with a **refresh token** for the 30-day feel; a 30-day bearer for an identity spanning many brands, with no server-side "revoke all sessions" and no invalidation on password change, is a lateral-read credential across every brand that creator works with.

**`rights: 'comment'` has no possible client.** Restrict the invite UI to `edit` for v1 and record the gap. Do not ship a grant no client can honour.

---

## 5. AI, PER ACTOR

Everything reuses the two shipped ports — `PageGenerationPort.generate` and `rewriteSection`. **No new AI capability is genuinely needed.**

| Actor | Where | Endpoint |
|---|---|---|
| Brand/agency owner | Brief → 2-3 full drafts before the creator ever sees it. This matters most for the *creator's* first impression: the invite must show something worth looking at | Existing `POST /api/campaign-pages/generate` |
| Marketer | Per-section rewrite, plus an AI-drafted handoff note ("here's what I'd like you to bring"), always editable, never auto-sent | Existing `POST /api/campaign-pages/sections/rewrite` |
| **Creator** | **The highest-value new use.** "Rewrite in my voice" per section — framed as helping *them* sound like themselves, not helping the brand. Creators are not copywriters; this is what makes co-authoring work instead of producing a blank box they abandon | New `POST /api/creator-portal/pages/{id}/sections/rewrite` — the same service with `requireEditRights` swapping the JWT check |
| Everyone | Share Kit captions, per platform, from the page's own sections — the best AI input in the product because it is already structured and already written | Existing `rewriteSection` |

**Two invariants.** AI never crosses the curated-editor line: it returns typed sections validated against `SECTION_TYPES`, and cannot emit a colour, font, size or position because those fields do not exist in `sectionTypes.js`. Write that down in the file — it is the obvious thing a future change breaks. And AI never changes the turn or the stage: generation is a content operation, a model does not decide a page is ready.

**The blocker, plainly:** `web-experience.landing.generation.provider` defaults to `template` because the Anthropic balance is zero. The template generator is a real generator, so everything above works today — just deterministically. Purchasing credit is a one-variable cutover, no rebuild.

---

## 6. SOCIAL PUBLISHING — THE HONEST POSITION

**Nothing in this platform posts to any handle today.** `SocialPlatformAdapter`'s entire surface is `platform()`, `isConfigured()`, `fetch(handle)`. There is no publish method anywhere.

**But "Meta review is unwinnable for a pre-revenue product" is wrong, and two of the three designs asserted it.** `MASTER-ROADMAP.md:110` says `PR-27b | Meta app review | ~2 days | deliberately not on the critical path`. `docs/platform-app-registration.md` is a mostly-complete dossier: privacy/terms/deletion URLs live, two apps correctly separated by type, per-permission screencast stills with a README. Open items are a category change and business verification. **The doc's own guidance, which every design missed:** request the full permission set in the *initial* submission, because "a second review round later costs another 2–4 weeks, and reviewers do not object to a coherent product asking for a coherent set."

**What each platform actually permits:**

- **Instagram** — publishing needs `instagram_content_publish`, a Business/Creator account linked to a Facebook Page, and App Review with a screencast. Personal accounts cannot be posted to at any tier. **The honest catch nobody flagged: a `content_publish` screencast requires a working publish flow to record, which does not exist.** So either record against a staging implementation or accept that this one permission slips to round two.
- **TikTok** — sandbox access arrives in hours. Critically, `direct_post` (public) needs audit, but **`share/upload` delivers the asset and caption into the creator's drafts, where they tap Post.** That is not the same as "useless SELF_ONLY posting" — it removes the two worst steps (download 8MB on mobile data, re-upload). Spend half a day registering and determining empirically which is reachable unaudited. This is the highest-value seam in the social half and all three designs collapsed it into a binary.
- **Brand's own handle** — higher ceiling but not the small follow-on two designs assumed. The current integration reads via `business_discovery` from *our own* single connected account. Publishing to a *customer's* handle needs per-brand Business Login using `config_id` (not `scope` — the Business app ignores `scope`, learned the slow way per the doc), a per-tenant token store, refresh, and a Page-selection screen. None exists.

**The interim — the Share Kit — and it is genuinely good, not a consolation prize.** Per platform: an AI-drafted caption from the page's own sections, correctly-sized assets, the tracked link, required `#ad` disclosure (non-removable, an FTC obligation), a QR for desktop-to-phone, `navigator.share()` on mobile, and "I posted this" closing the loop back to the brand.

**Three hard prerequisites the designs treated as details:**

1. **There is no `S3AssetStorage` class in the repo.** `FilesystemAssetStorage` is `matchIfMissing=true` and `assets.provider` is set nowhere, so prod serves assets off one EC2 box's local disk — the adapter's own header says "not intended for production." **Meta's Content Publishing API fetches your asset URL server-side**, so object storage is a *precondition of the API path*, not share-kit polish.
2. **There is no image resizing anywhere** — zero hits for `Graphics2D`/`getScaledInstance`. `AssetService` uses `ImageIO` only to measure. "Per-platform aspect ratio" is unwritten code, not an existing capability. Defer per-platform sizing from v1 or cost it honestly.
3. **Instagram captions are not clickable.** The share kit's central artifact — a tracked URL — is inert plain text on the dominant platform. Lead with **the coupon code** (already the attribution primitive, and it survives being read off a screen) plus bio-link guidance and a Stories link-sticker asset. Also verify `/s/{slug}/{creator}` returns 200 before showing it — that route **404s when no coupon matches**, so a creator on a coupon-less page gets a dead link.

**And the token nobody mentioned:** the Instagram Page token **expires every 60 days with nothing refreshing it** (`secrets.tf:230`), and `OP-02`, the alarm for it, is unshipped. Worse, `SocialPlatformRegistry.find()` returns empty on `!isConfigured()` and falls through to **fabricated metrics by design**. That graceful degradation is correct for read-only vetting and becomes a *liar* the moment a publish path shares the credential. **Rule: a failed publish must surface as a failure; only a failed read may degrade to simulation.**

**Model the port with three outcomes, not two:** `posted`, `staged_for_user_confirmation` (TikTok inbox / IG draft), `manual` (share kit). A binary real-or-manual result forecloses the inbox path that is reachable *now*.

**Roadmap honesty:** social publishing is recorded as `Declined in all four predecessors` (`MASTER-ROADMAP.md:231`), trigger column empty. You are reopening a four-times-made decision, so write the reversal down with its reason, and keep the share kit and the API adapter under **distinct IDs** so the declined thing stays visibly declined. Status is `PARTIAL` with the external blocker named, per your own rule.

---

## 7. BUILD ORDER

| # | Increment | Days | Ships what |
|---|---|---|---|
| **0** | **Foundation repair.** (a) `saveAsCollaborator` carries `sections` **and** `scheduledPublishAt`/`hostingExpiresAt`/`firstPublishedAt` — extract one shared carry-forward helper, since this has now been got wrong twice, and fix `changeStage` too. (b) `X-Creator-Token` into `setAllowedHeaders`. (c) `decide()` takes and checks `brandId` at **both** BFF and DAO. (d) `pagesForCreator`/`requireEditRights` assert `page.brandId == grant.brandId`. (e) `@Version` on `LandingTemplate` + 409-with-both-versions; `snapshotVersion` captures `before`, not `saved`. (f) Symmetric status on backward stage transitions. **First unit tests either service has ever had.** | **3** | Fixes five live production bugs — two cross-tenant, two silent data loss. Valuable even if you build nothing else. |
| **0b** | **Start the three procurement clocks, today, in parallel.** SES domain verification + sandbox exit (`OP-06`, `docs/ses-setup.md`); Anthropic credit; Meta submission with the **full** permission set including `content_publish`; TikTok sandbox registration. | 0.5 + wait | Unblocks increments 2, 5, 6. Calendar time you cannot compress later. |
| **1** | **Creator auth filter + turn axis.** `CreatorTokenAuthenticationFilter`, `CREATOR_PORTAL_PATHS` → `authenticated()`. `V44`: `turn`, `turn_changed_at`, `page_handoffs` audit table, `creator_portal_sessions`. `HandoffMachine` shaped like `LandingStageMachine`. `assertCreatorStageTransition` allowlist. Per-occurrence idempotency keys — **not** `templateId:from->to`, since work legitimately loops twice. | 5 | The spine, provable by `tests/e2e_handoff.sh` before any UI exists. |
| **2** | **Tokenised invite + first creator email.** `creator_invites` (SHA-256 hash at rest, single-use, 7-day expiry, ≥128-bit entropy, rate-limited redemption). Follow `MemberInvitationService` exactly. Redemption creates identity + confirmed link atomically. Expired links land on "ask Acme for a new one", not a 404. Add `findById`→email to `DaoCreatorIdentityClient` projecting **only** id/email/displayName — `GET /creator-identities/{id}` returns `passwordHash`. | 4 | **Breaks the bootstrap circularity** — today the only route to `confirmed` is out-of-band UUID exchange. Needs SES from 0b. |
| **3** | **Brand-side collaborator panel + handoff button.** Invite/list/revoke, pending-claims queue (now brand-scoped), "Waiting on you" filter, "Take it back". Built in **`InfluencerContentUI`** — production serves the remote. Add to `contentRemoteCopies.test.mjs`. | 4 | Brands get the feature whose backend has been dark since Phase G. |
| **4** | **Creator portal: invite screen + page list.** New Vite project, own CloudFront distribution, terraform output (not `.env.production` — it is generated). Redacted teaser only. Rate limiting on `/auth/login` **before** the BCrypt call. | 4 | First increment a real creator can touch. |
| **5** | **Creator editor + return-leg emails.** Mount `SectionEditor` with creator callbacks; new creator preview + rewrite endpoints. `/hand-back`. Revocation UX: `{"code":"access_revoked"}` + Download-your-draft; snapshot the draft on revoke so the brand keeps the work. `CreatorSubmittedEmail`, `CreatorPublishNotificationEmail` keyed on **`first_published_at`** (not stage arrival — publish→unpublish→republish would otherwise re-send), retraction email. Abandonment sweep on `turn_changed_at`. **Extract `@influencer/ui`** — a third copy of `SectionEditor` is where duplication stops being tolerable, and CLAUDE.md already names this as the intended repair. | 6 | The core of the ask, including the acknowledgement email. |
| **6** | **S3 asset storage**, then **Share Kit**. `S3AssetStorage` + flip the provider (precondition of any future API path). `SocialPublishPort` with three outcomes and a `manual` default. Captions, coupon-first links, disclosure, "I posted this". No platform adapter. | 6 | The honest answer to "creator publishes to their handle". |

**Total ≈ 32 engineer-days.** Increments 0, 1, 2, 5 (~18d) deliver the user's literal ask. If it slips, cut increment 6 first and increment 3's pending-claims queue second.

---

## 8. THE HONEST ARGUMENT AGAINST

**This plan is wrong if the bottleneck is demand, not workflow.** The project is pre-revenue with zero subscribers. This spends ~32 days building a two-sided collaboration product — and two-sided products have a two-sided cold-start problem. You would be building creator-side UX for creators who do not exist yet, for brands who are not paying yet. Every day here is a day not spent on the thing `project-state-pre-revenue` says is actually missing: *the gap is commercial, not technical.*

**It is also wrong if the real ask is a demo.** If this is for a pitch or a design partner conversation, increments 0 + 3 alone (7 days) give you a brand-side collaborator panel over a backend that already works, and you can narrate the creator half.

**The cheaper alternative, if the read is wrong — roughly 6 days:**

1. **Increment 0 regardless.** Three days. Those are live bugs — cross-tenant confirmation and a silently cancelled campaign launch — and they are worth fixing whether or not a creator ever logs in.
2. **Skip the portal entirely. Send the creator a tokenised magic link to a single-page editor** — no account, no password, no session table, no eighth CloudFront distribution. The token *is* the auth, scoped to one page, expiring in 7 days. Mount `SectionEditor` on a route inside the existing `InfluencerContentUI`. Three days.
3. **One email at publish.** Half a day once SES is out of sandbox.

That gets you the handoff, the co-authoring, and the acknowledgement — the user's literal ask — for a fifth of the cost. What you lose: creators cannot see their history across brands, cannot manage a profile, and get a new link per page. **If a real creator ever asks "where do I log in to see all my work?", that is your signal to build increment 4.** Until someone asks, the portal is speculative.

**The one thing I would not cut either way:** increment 0. Everything else is a judgement call about where the product is. Those five defects are wrong today, in production, on code that is already shipped.