# Group 2 Authenticity Signals — Build vs Buy

**Date:** 2026-08-02
**Decides:** [landing-page-builder-roadmap.md](landing-page-builder-roadmap.md) §10.3
**Scope:** audience quality score, fake-follower %, engagement authenticity, audience-type mix

---

## The short version

**Buy.** Not because building is expensive — with Claude, the code is genuinely cheap. Because
**the data required to build it cannot be legitimately obtained.**

That is a different objection from "it would take too long", and it is not one that engineering
velocity solves. The rest of this document shows the numbers anyway, because the cost comparison is
worth having on record and because it changes if the platforms ever change.

---

## 1. The blocker, stated first

Fake-follower detection works by examining **the followers**: their account age, profile
completeness, posting history, follower/following ratio, and engagement behaviour. Every published
methodology does this — Random Forest or SVM over per-follower features, trained on labelled
genuine/fake accounts.

**Instagram does not expose follower lists.** Not gated behind review, not expensive — absent:

> *"The Graph API does not expose follower or following data through standard permissions, and there
> is no official way to retrieve a list of an account's followers programmatically."*
> — [KeyAPI, Instagram Graph API followers](https://www.keyapi.ai/blog/instagram-graph-api-get-followers-list-following/)

TikTok and YouTube are comparable. Only the account owner sees their own follower list.

So a build has three options, and it is worth being blunt about all three:

| Route | Verdict |
|---|---|
| **Official APIs** | The necessary data does not exist in them. Not a budget problem |
| **Scraping / unofficial APIs** | Breaches platform terms. Puts the developer app — which Phases C *and* F depend on — at risk of ban. A vendor doing this absorbs that risk; doing it ourselves means our app is the one revoked |
| **Ask creators to connect their accounts** | Legitimate, and only works for creators who opt in. A creator inflating their numbers is precisely the one who will not connect |

The third is worth noting because it is not useless — for *connected* creators, first-party insights
give real audience demographics. But vetting is most valuable **before** a relationship exists, and
that is exactly when no connection is available.

**There is no fourth option.** This is the whole argument; everything below is supporting detail.

---

## 2. What buying costs

Published pricing, August 2026.

| Vendor | Entry | What it includes | Effective per report |
|---|---|---|---|
| [HypeAuditor](https://hypeauditor.com/pricing/) | **$299/mo** annual (Basic) | 500 creator reports/mo | **~$0.60** |
| HypeAuditor Pro | ~$499/mo annual | Higher volume + competitor/market reports | — |
| [Modash Discovery API](https://www.modash.io/influencer-marketing-api) | **$16,200/yr** (~$1,350/mo) | 3,000 credits/mo; 1 credit = full report | **~$0.45** |
| Modash Raw API | $10,000/yr | 40,000 requests/mo (raw data, not scored) | ~$0.02/req |
| [InsightIQ](https://www.insightiq.ai/pricing) | Custom | Per-report charges on top of subscription | — |

**Modelled cost by volume** (HypeAuditor Basic at 500/mo, then Modash):

| Creator reports / month | Annual cost | Per report |
|---|---|---|
| 100 | $3,600 | $3.00 |
| 500 | $3,600 | $0.60 |
| 3,000 | $16,200 | $0.45 |
| 10,000 | ~$30,000 (negotiated) | ~$0.25 |

**Year one at realistic early volume: $3,600–$16,200.**

A detail that matters for the roadmap: reports are consumed on **vetting** and on **C3 health
refreshes**. Refreshing 1,000 creators monthly is 12,000 reports/year on its own — which is why C3
specifies tiered cadence rather than uniform refresh. That is a cost decision disguised as a
scheduling one.

---

## 3. What building costs — with Claude

Taking the premise seriously: assume Claude writes essentially all of the code, and writes it well.

### 3.1 Where Claude genuinely helps

| Task | Without | With Claude | Saving |
|---|---|---|---|
| Feature extraction pipeline | 2 wks | 2 days | ~80 % |
| Classifier training harness | 2 wks | 3 days | ~70 % |
| Scoring service + API | 1 wk | 1 day | ~80 % |
| Backfill / batch jobs | 1 wk | 1 day | ~80 % |
| Dashboards, alerting | 1 wk | 2 days | ~60 % |
| **Code subtotal** | **7 wks** | **~9 days** | **~75 %** |

That is a real and large saving. If this were a coding problem, build would win comfortably.

### 3.2 Where Claude does not help

| Task | Effort | Why Claude cannot compress it |
|---|---|---|
| **Obtaining follower-level data** | **Blocked** | No API exposes it. Not a code problem |
| **Labelled training data** | 3–6 mo | Someone must label thousands of accounts genuine/fake. Judgement plus platform access, neither of which is code |
| Ongoing label refresh | Continuous | Fraud tactics change; a 2026 model decays through 2027 |
| Accuracy validation | 4–6 wks | Requires ground truth — see above |
| Legal review of acquisition | 2–4 wks | Reviewing whether we may hold scraped profile data |
| False-positive handling | Continuous | Wrongly flagging a legitimate creator is a commercial and reputational cost |

**Claude compresses ~75 % of the code and ~0 % of the data problem.** The data problem is the
project.

### 3.3 If the blocker did not exist

Suppose follower data were freely available. Cost of a Claude-assisted build:

| Item | Cost |
|---|---|
| Engineering (~9 days at a $150k loaded rate) | ~$5,200 |
| Labelling (5,000 accounts, outsourced) | $5,000–15,000 |
| Training compute + experimentation | $2,000–5,000 |
| Validation | ~$6,000 |
| **Year-one build** | **~$18,000–31,000** |
| Annual maintenance (relabel, retrain, drift) | **$15,000–25,000/yr** |

Against $3,600–16,200/yr to buy. **Build loses even in the fantasy where the data is free** —
because maintenance alone exceeds the subscription, permanently.

### 3.4 The number that actually decides it

```
Buy, year one:           $3,600 – $16,200
Build, year one:         $18,000 – $31,000   ← and only if the data existed
Build, ongoing:          $15,000 – $25,000/yr forever
Break-even volume:       ~60,000 reports/year before buying looks expensive
```

At 60,000 reports/year — 5,000 creators vetted or refreshed monthly — this becomes worth revisiting.
That is a large business, and by then the volume itself justifies a negotiated enterprise rate that
moves the line further out.

---

## 4. Where Claude *should* be used instead

Declining to build Group 2 is not declining to build with Claude. Groups 1, 3 and 4 are entirely
within reach, and Claude makes them fast:

| Capability | Data source | Claude's role |
|---|---|---|
| Growth-anomaly detection | Our own C3 snapshots | Statistical logic — days of work |
| Engagement-consistency scoring | Follower count + engagement over time | Straightforward, no external data |
| Comment-quality analysis | Public comments via API | **LLM classification — genuinely a Claude strength.** Research puts comment-quality analysis at ~87 % accuracy for fraud detection on its own |
| Brand-safety classification (Group 4) | Captions, bios, public content | Same — text classification is exactly the job |
| Niche and content classification | Post content | Already the plan (Phase C) |

**Comment-quality analysis is the interesting one.** It is the single strongest fraud signal that
does *not* require follower lists — bot comments are generic, off-topic and repetitive, and an LLM
recognises that reliably. It will not produce a calibrated 0–100 audience quality score, but it
catches the obvious cases.

**Recommendation: build a coarse in-house signal from what we can legitimately see**, and label it
honestly as such:

```
in-house  → "Engagement pattern: unusual"     (a flag, from our own data)
vendor    → "Audience quality: 34/100"        (a score, from follower analysis)
```

The first is defensible from data we own. The second is not something to claim without the data
behind it — publishing a made-up quality score would be worse than publishing none.

---

## 5. Recommendation

**Defer, then buy — unchanged, and now for a firmer reason than before.**

| Phase | Action |
|---|---|
| **Now** | Ship Groups 1, 3, 4. Build the coarse in-house signals with Claude (§4) |
| **C2 rules UI** | Design so a vendor score is an optional input, absent by default. Do not advertise a field that cannot be populated |
| **At ~500 vetted creators/mo** | Trial HypeAuditor Basic — $299/mo, cancellable, real accuracy comparison against our own signals |
| **At ~3,000/mo** | Move to Modash Discovery at a better per-report rate |
| **At ~60,000/yr** | Revisit build, if and only if follower-level data has become legitimately obtainable |

**Integrate behind a port.** `AudienceAuthenticityPort` with a no-op default, our in-house
implementation, and a vendor adapter. The rules engine reads a score and does not know its origin —
which makes trialling a vendor a config change, and swapping one a day's work rather than a
migration.

---

## 6. What would change this

Honest triggers to revisit, so this is a decision with an expiry rather than a permanent verdict:

1. **A platform exposes follower-level data** to vetted partners. Unlikely — the trend is the
   opposite — but it would remove the blocker entirely.
2. **Volume passes ~60,000 reports/year.** Buying stops being obviously cheaper.
3. **Vendor accuracy proves poor** in the §5 trial. If a paid score is no better than our coarse
   signal, stop paying for it — that trial is the point.
4. **A creator-connected model becomes the norm.** If most creators authorise the platform for
   first-party insights, we get audience data legitimately and the calculus flips.

---

## 7. Summary

| | Build (Claude-assisted) | Buy |
|---|---|---|
| Year one | ~$18k–31k *(if data existed)* | $3.6k–16.2k |
| Ongoing | $15k–25k/yr | Subscription, scales with use |
| Time to value | 4–7 months | Days |
| **Feasible today** | **No — data unobtainable** | **Yes** |
| Accuracy | Unknown, unvalidatable without ground truth | Industry-benchmarked |
| Legal exposure | Scraping risks the developer app Phases C and F depend on | Vendor's problem |

Claude makes the build **~75 % cheaper in engineering and 0 % more feasible**, because the
constraint is data access rather than development speed. That is worth stating plainly: a faster
way to write code does not change what data a platform will give you.

**Sources:**
[HypeAuditor pricing](https://hypeauditor.com/pricing/) ·
[Modash API](https://www.modash.io/influencer-marketing-api) ·
[Modash pricing analysis](https://archive.com/blog/modash-pricing) ·
[InsightIQ pricing](https://www.insightiq.ai/pricing) ·
[Instagram Graph API follower access](https://www.keyapi.ai/blog/instagram-graph-api-get-followers-list-following/) ·
[Instagram API 2026 limitations](https://mailerfind.com/instagram-api-in-2026-real-limitations-and-the-api-free-alternative-that-gives-you-all-the-data/) ·
[Fake account detection methodology](https://www.jicce.org/journal/view.html?doi=10.56977/jicce.2025.23.2.94) ·
[ContentGrip fraud detection](https://www.contentgrip.com/influencer-marketing-fraud-detection/)
