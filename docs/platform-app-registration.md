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

---

## 3. YouTube ▸ easiest, do it when convenient

1. <https://console.cloud.google.com/> → create a project
2. Enable **YouTube Data API v3**
3. Create an API key for public data — **channel statistics need no OAuth and no review**
4. OAuth consent screen and verification are needed only for private data or publishing

Public channel statistics — subscriber count, view count, video count — are available immediately
with just an API key. This is the fastest platform to get useful data from, and worth doing first
if you want to see Phase C working end-to-end before Meta approval lands.

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

```
Week 1   Meta app created, business verification submitted   ← the critical action
Week 2   TikTok app + sandbox; YouTube API key
Week 3   Meta screencasts recorded, permissions submitted
Week 4-6 Meta review (allow for one revision round)
Week 5   TikTok production review
```

Phase A (the builder) runs in parallel throughout and needs none of this.

---

## Status tracker

| Platform | Owner | Submitted | Approved | Notes |
|---|---|---|---|---|
| Meta — business verification | | | | |
| Meta — `instagram_basic` | | | | |
| Meta — `instagram_manage_insights` | | | | |
| Meta — `instagram_content_publish` | | | | |
| TikTok — Display API | | | | |
| TikTok — Content Posting API | | | | |
| YouTube — Data API key | | | | |

---

**Sources:**
[Meta App Review timeline](https://elfsight.com/blog/instagram-graph-api-changes/) ·
[Instagram API integration guide](https://www.getphyllo.com/post/instagram-api-integration-101-for-developers-of-the-creator-economy) ·
[Instagram Graph API developer notes](https://zernio.com/blog/instagram-graph-api) ·
[TikTok developer FAQ](https://developers.tiktok.com/doc/getting-started-faq) ·
[TikTok API access guide](https://www.echotik.live/blog/tiktok-developer-api-2026/) ·
[Instagram follower-list access](https://www.keyapi.ai/blog/instagram-graph-api-get-followers-list-following/)
