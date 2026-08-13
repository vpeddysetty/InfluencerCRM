# Platform Developer App Registration — Action Required

**To:** peddysetty@gmail.com
**Date:** 2026-08-02
**Why this is urgent:** this is the longest lead time in the landing page roadmap, and none of it
is code. Two phases block on it, and no amount of engineering speed shortens a review queue.

---

## The one-paragraph version

The landing page builder needs creator metrics (follower counts, engagement, audience demographics)
and, later, the ability to publish posts. Both require **approved developer apps** on Instagram,
TikTok, YouTube and Facebook. Meta's review takes **2–4 weeks and resets if a reviewer requests
changes**; TikTok's takes **5–10 business days** with no expedited option. Starting these now means
they land around the time the code needs them. Starting them when the code is ready means a month of
finished work sitting idle.

**If you do one thing this week: create the Meta app and start business verification.** Everything
else can follow.

---

## What blocks on this

| Roadmap phase | Needs | Without it |
|---|---|---|
| **Phase C** — creator onboarding | Read profile metrics | Manual signup only; no verified follower counts, no audience demographics, no automated vetting inputs |
| **Phase C2** — vetting rules | The metrics above | Rules have nothing to evaluate |
| **Phase C3** — health monitoring | Periodic metric refresh | No decline detection |
| **Phase F** — social publishing | Content publishing permissions | No posting at all |

Phases A, B, D, E and G are unaffected — the builder, assets, stage automation, domains and
co-editing all proceed without any platform app.

---

## Priority order

| # | Platform | Review time | Start when |
|---|---|---|---|
| 1 | **Meta** (Instagram + Facebook) | **2–4 weeks**, resets on revision | **This week** |
| 2 | **TikTok** | 5–10 business days | Within 2 weeks |
| 3 | **YouTube** (Google Cloud) | Days, or none for public data | Before Phase C |
| 4 | Pinterest | Varies | Only if wanted |

Instagram and Facebook share one Meta app, so #1 covers both.

---

## 1. Meta — Instagram + Facebook ▸ start first, longest queue

### Before you begin, gather

- A **Facebook Business account**
- A **Business or Creator** Instagram account linked to a Facebook Page
  *(personal Instagram accounts do not work with the Graph API at all)*
- Business verification documents — company registration, address proof
- A **public privacy policy URL** and terms of service URL
- A **screencast** demonstrating each permission in use

### URLs to paste into App Settings → Basic

All three are live and served over HTTPS on both the apex and `www` hostnames:

| Dashboard field | URL |
|---|---|
| Privacy Policy URL | <https://www.tejdux.com/privacy/> |
| Terms of Service URL | <https://www.tejdux.com/terms/> |
| **User Data Deletion** → *Data Deletion Instructions URL* | <https://www.tejdux.com/data-deletion/> |

The **User Data Deletion** field offers two mutually exclusive options: a *Data Deletion Instructions
URL* (a page telling users how to request deletion) or a *Data Deletion Request Callback URL* (an
endpoint Meta POSTs a signed request to when a user removes the app). **Either satisfies the
requirement** — select *Instructions URL* and use the link above.

The callback is the better long-term answer because it deletes automatically rather than by email,
but it needs a signed-request handler in the BFF plus a public status page, and it is not required
to pass review. The groundwork is done: social sign-in now records the provider's subject id in
`identity.federated_identities`, which is what lets a callback resolve a Facebook user id back to a
local account. Before that, no such lookup was possible.

### Steps

1. Go to <https://developers.facebook.com/> → **My Apps** → **Create App**
2. App type: **Business**
3. Add the **Instagram Graph API** and **Facebook Login** products
4. Complete **Business Verification** under App Settings → this is the slowest step, start it
   immediately and in parallel with everything else
5. Request permissions (see below) — **each one is a separate submission with its own screencast**
6. Submit for App Review and wait

### Permissions to request

| Permission | For | Phase |
|---|---|---|
| `instagram_basic` | Read profile and media | C |
| `instagram_manage_insights` | Follower count, reach, audience demographics | C, C3 |
| `instagram_content_publish` | Publish posts | F |
| `pages_show_list`, `pages_read_engagement` | Link the Facebook Page | C |

**Request all four in the initial submission**, even though Phase F is months away. A second review
round later costs another 2–4 weeks, and reviewers do not object to a coherent product asking for a
coherent set.

### Screenshots for the submission

[`snapshots/`](../snapshots/) holds captured PNGs of every screen that touches a Meta API,
with a README mapping each one to the permission it justifies. Regenerate them with
`node snapshots/capture.mjs` after any UI change, so what reviewers see matches the product.

Note what they do **not** show: live Instagram metrics, because the integration is not
approved yet. The README says so explicitly rather than implying an integration we do not
have — a mockup presented as a working feature is its own rejection reason.

### Dashboard fixes still required — verified against the Graph API on 2026-08-13

The live app is **TejDux**, App ID `1532612907951511`. Querying it with an app access token shows
these settings as Meta currently holds them. Each is a review blocker or a review risk, and none can
be fixed from this repo — they are dashboard edits.

| Field | Currently | Must become | Why |
|---|---|---|---|
| Terms of Service URL | `https://www.facebook.com/` | `https://www.tejdux.com/terms/` | 🔴 The app claims Facebook's own homepage as its terms. Almost certainly a paste error; reads to a reviewer as no terms at all |
| Data Deletion | not configured | Instructions URL → `https://www.tejdux.com/data-deletion/` | 🔴 Required. Either option satisfies it — pick *Instructions URL*, per the note above |
| Category | Utilities | Business and Pages | 🟠 Category routes the review. A B2B marketing CRM filed under Utilities invites "what does this app do?" |
| Website / App URL | `http://www.tejdux.com/` | `https://www.tejdux.com/` | 🟠 Works via 301, but registering the http form is sloppy in a field reviewers read |
| App Domains | empty | `tejdux.com` | 🟠 Meta validates redirect URIs against this |
| Valid OAuth Redirect URIs | *(not visible via API)* | must include `https://api.tejdux.com/api/auth/oauth/facebook/callback` | 🔴 A localhost-only URI is itself a rejection reason. This is the exact URI the BFF sends — verified in the 302 above |

**The Graph API cannot show permission statuses, redirect URIs, or business verification state.**
Those must be read off the dashboard by a human; do not assume they are set because the login works.

### The two things that most often cause rejection

- **A screencast that does not clearly show the permission being used in the product.** Record the
  actual flow — a creator pasting a handle, the app fetching metrics, the metrics rendering — not a
  slide deck describing it.
- **A vague data-use explanation.** State plainly: *"We display creator audience metrics to brand
  marketers evaluating partnership fit. Data is stored per brand, is not resold, and is deleted on
  request."*

---

## 2. TikTok ▸ start within two weeks

1. Register at <https://developers.tiktok.com/>
2. Create an app; sandbox access is available within hours for development
3. Request **Display API** (profile and video data — Phase C) and **Content Posting API** (Phase F)
4. Submit for production review with a demo video, privacy policy URL, and a clear description of
   data handling

**Note:** TikTok now reviews for **Data Security Compliance**, so the privacy policy must be current
and accurate before submitting. There is **no expedited track and no way to pay for faster
approval** — the only lever is submitting a complete application first time.

### 2.1 Submission package — prepared 2026-08-07, ready to paste

Deferred by decision, not by blocker. Nothing below needs further work; it is here so that whenever
you choose to start, the queue starts the same day.

**Do the sandbox first, separately.** Sandbox access arrives within hours and needs no review. It
gives the M6 TikTok adapter something to develop against while production review runs, so there is
no reason to couple them.

#### App Settings → Basic

| Field | Value |
|---|---|
| App name | InfluenCRM |
| Category | Business tools / Marketing |
| Website URL | <https://www.tejdux.com/> |
| Privacy Policy URL | <https://www.tejdux.com/privacy/> |
| Terms of Service URL | <https://www.tejdux.com/terms/> |
| Redirect URI | *Set to the deployed BFF callback. Placeholder until deployment lands — do not submit with a localhost URI, it is a rejection reason* |

#### App description

> InfluenCRM is a B2B influencer marketing platform. Brands and agencies use it to evaluate creator
> partnerships, track campaign performance, and attribute sales to individual creators. Brand
> marketers add creators they already work with, review those creators' public profile metrics to
> assess campaign fit, and — with the creator's explicit, revocable consent — publish approved
> content to the creator's account on their behalf.

#### Scopes to request — submit both in one application

| Scope | Product surface | Justification to paste |
|---|---|---|
| `user.info.basic` | Creator profile card | Resolve a creator handle to a profile so a brand can confirm they have added the right person |
| `user.info.profile` | Creator detail panel | Display profile metadata alongside the brand's own notes and negotiated rate |
| `user.info.stats` | Creator list + vetting rules | Follower and engagement counts are the inputs to per-brand vetting rules and health monitoring |
| `video.list` | Content review | Show recent public posts so a brand can assess content fit and brand safety before partnering |
| `video.publish` | Phase F | Publish brand-approved content to the creator's account, only after the creator connects their own account and grants consent they can revoke at any time |

**Request all five in the initial submission.** Same reasoning as Meta: a second round costs another
5–10 business days, and reviewers do not object to a coherent product asking for a coherent set.

#### Data-handling statement

TikTok reviews this under Data Security Compliance. Vagueness is the most common rejection cause:

> We store TikTok profile metrics (follower count, engagement rate, public video metadata) against
> the brand record that added the creator. Data is scoped per brand and is never shared between
> brands, never resold, and never used for advertising targeting. Creators may revoke access at any
> time, which stops all further collection. Stored data is deleted on request via the process
> published at https://www.tejdux.com/data-deletion/. Metrics are refreshed on a tiered cadence —
> weekly for creators on active campaigns, monthly otherwise — rather than continuously polled.

Every clause above is true of the design. The tiered cadence is C3; the per-brand scoping is the
tenancy model verified in ddd-roadmap Phase 2. Do not add claims beyond these.

#### Demo video — shot list

Record the actual product, not a slide deck. That is the other common rejection cause.

1. Brand marketer signs in and opens the creator list.
2. Adds a creator by pasting a TikTok handle — show the handle being typed.
3. The profile resolves; **show the metrics rendering on screen** (`user.info.basic`,
   `user.info.profile`, `user.info.stats`).
4. Open the creator detail panel showing recent public posts (`video.list`).
5. Show the creator-consent screen and the revoke control (`video.publish`).
6. Show the data-deletion page.

**Blocked until:** steps 3–5 need the TikTok adapter (M6.4) running against sandbox credentials.
This is the real reason to take sandbox access early — the video cannot be recorded against mock
data. A mockup presented as a working integration is its own rejection reason.

---

## 3. YouTube ▸ **DONE — 2026-08-07**

1. ~~<https://console.cloud.google.com/> → create a project~~
2. ~~Enable **YouTube Data API v3**~~
3. ~~Create an API key for public data~~ — **channel statistics need no OAuth and no review**
4. OAuth consent screen and verification are needed only for private data or publishing — **not
   done, and not needed** for anything currently on the roadmap

Public channel statistics — subscriber count, view count, video count — are available immediately
with just an API key.

### What this unblocks — read this before scheduling M6

[EXECUTION-ROADMAP.md](../EXECUTION-ROADMAP.md) sizes M6 as a single XL block gated entirely on app
approvals. **That is now only half true.** With a YouTube key in hand, the ungated portion is:

| Roadmap item | Gated on approvals? | Note |
|---|---|---|
| 6.1 Outbound HTTP client | **No** | None exists. `DaoHttpClientFactory` is mTLS-to-DAO only |
| 6.2 Per-platform dispatcher | **No** | `SocialProfileGateway.fetch(platform, handle)` has no per-platform routing today |
| 6.4 YouTube adapter | **No** | API key only |
| 6.5 Rate limiting, caching, quota | **No** | Quota applies to YouTube too |
| 6.3 Creator OAuth token storage | Partly | Not needed for YouTube public stats; needed for Meta/TikTok |
| 6.4 Instagram / TikTok adapters | **Yes** | Meta pending, TikTok not submitted |
| 6.6 Tiered refresh scheduler | **No** | |

So roughly **5 of M6's 15 dev-days are buildable today**, and they are the load-bearing
infrastructure the other two adapters plug into. Building the YouTube slice first turns Meta
approval from "start a 15-day project" into "drop in an adapter" — and it puts at least one real,
non-hash-derived follower count on screen.

**Not scheduled yet by decision (2026-08-07): M0 → M1 comes first.** Recorded here so the option is
not lost.

### API key handling

The key is a credential. Do not commit it — supply it via environment variable to the deployed BFF,
mirroring how the other provider properties are set. See §M0.4 note in the roadmap: the point of
setting provider flags explicitly is that configuration should be a decision, not a default.

---

## What to tell reviewers about our use case

Reused across all three, adjusted for tone. Reviewers reject vagueness far more often than they
reject legitimate use:

> InfluenCRM is a B2B influencer marketing platform. Brands and agencies use it to evaluate and
> manage creator partnerships. We read public profile metrics and audience demographics so a brand
> can assess whether a creator fits a campaign, and — with the creator's explicit, revocable
> consent — publish approved content to the creator's account on their behalf. Creator data is
> stored per brand, never resold, and deleted on request.

That last clause is true of the design (§6.1, Phase F of the roadmap), which is why it can be said
without qualification.

---

## Practical notes

**Rate limits are a design input, not a footnote.** Meta's Business Use Case limits scale with the
connected account's size, which quietly caps small accounts. This is why Phase C3 specifies tiered
refresh cadence — weekly for creators on active campaigns, monthly otherwise — rather than
refreshing everyone nightly.

**Register apps under a company account, not a personal one.** Moving an approved app between
owners later is painful, and business verification is tied to the owning entity.

**Instagram provides no follower-list endpoint**, only aggregate counts and demographics. This is
already accounted for — it is the reason the roadmap recommends buying authenticity signals rather
than building them ([group2-build-vs-buy.md](group2-build-vs-buy.md)). Do not let anyone promise
fake-follower detection on the strength of Graph API access; it is not in there.

---

## Suggested timeline

~~Original plan, superseded 2026-08-07:~~

```
Week 1   Meta app created, business verification submitted   ← the critical action
Week 2   TikTok app + sandbox; YouTube API key
Week 3   Meta screencasts recorded, permissions submitted
Week 4-6 Meta review (allow for one revision round)
Week 5   TikTok production review
```

### Actual state, 2026-08-07

```
DONE     YouTube Data API key          ← unblocks ~5 dev-days of M6 today
RUNNING  Meta access requested          ← 2-4 weeks, resets on reviewer changes
NOT SUBMITTED  TikTok                   ← deferred by decision; package ready in §2.1
```

**The one number worth watching:** TikTok is 5–10 business days *from submission*, and submission is
a form. It is not on the critical path for M0–M5, so deferring it costs nothing **until M6 starts**.
At that point it becomes the gate. The package in §2.1 exists so that day is a copy-paste, and the
sandbox — same-day, no review — can be taken any time to develop the adapter against.

Phase A (the builder) runs in parallel throughout and needs none of this.

---

## Status tracker

| Platform | Owner | Submitted | Approved | Notes |
|---|---|---|---|---|
| Meta — prod Facebook Login credentials | peddysetty | 2026-08-13 | ✅ working | App **TejDux** (`1532612907951511`). Secrets populated in `influencrm-prod/facebook-oauth-client-{id,secret}`; `/api/auth/oauth/facebook/start` now 302s to the Meta dialog. Until 2026-08-13 both secrets held the single-space placeholder, so Facebook sign-in returned `400 facebook.client-id is not configured` **in production** — the top Meta rejection cause |
| Public URLs — privacy, terms, data deletion | peddysetty | — | n/a | Live on tejdux.com since 2026-08-07; **dates and retention periods are still `[PLACEHOLDER]` on the live pages** — blocks review, see §"Dashboard fixes still required" |
| Review screenshots | peddysetty | — | n/a | Captured 2026-08-07 in [`snapshots/`](../snapshots/); regenerate after UI changes |
| Meta — access requested | peddysetty | 2026-08-07 | — | Requested. Expect 2–4 weeks; **resets if a reviewer requests changes**. Confirm below which permissions were included in the submission |
| Meta — business verification | peddysetty | 2026-08-07 | — | Slowest step; runs in parallel with permission review |
| Meta — `instagram_basic` | peddysetty | 2026-08-07 | — | Confirm included in the request |
| Meta — `instagram_manage_insights` | peddysetty | 2026-08-07 | — | Confirm included — this is the one M6 needs for follower counts |
| Meta — `instagram_content_publish` | peddysetty | 2026-08-07 | — | Phase F. Confirm included — a second review round costs another 2–4 weeks |
| TikTok — Display API | peddysetty | — | — | **Deferred by decision 2026-08-07.** Submission package prepared below (§2.1). 5–10 business days once submitted; sandbox same-day |
| TikTok — Content Posting API | peddysetty | — | — | Submit with Display API in one application — see §2.1 |
| YouTube — Data API key | peddysetty | 2026-08-07 | 2026-08-07 | **Obtained.** Public channel statistics need no OAuth and no review. Unblocks the ungated half of M6 — see §4 |

### Reading this tracker

Three states matter and they are not the same:

- **Meta** — clock running, nothing to do but wait. Rejection is information; silence is delay.
- **TikTok** — clock *not* running by choice. Every week deferred is a week added to the end of M6.
  The package in §2.1 exists so that starting it costs minutes.
- **YouTube** — done, and it is the only one that unblocks code today.

---

**Sources:**
[Meta App Review timeline](https://elfsight.com/blog/instagram-graph-api-changes/) ·
[Instagram API integration guide](https://www.getphyllo.com/post/instagram-api-integration-101-for-developers-of-the-creator-economy) ·
[Instagram Graph API developer notes](https://zernio.com/blog/instagram-graph-api) ·
[TikTok developer FAQ](https://developers.tiktok.com/doc/getting-started-faq) ·
[TikTok API access guide](https://www.echotik.live/blog/tiktok-developer-api-2026/) ·
[Instagram follower-list access](https://www.keyapi.ai/blog/instagram-graph-api-get-followers-list-following/)
