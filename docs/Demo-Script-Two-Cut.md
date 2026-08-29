# Demo video: two cuts, actor-led

**Status:** production script, 2026-08-27. Replaces `Demo-Script-Collaborative-Drop.md` as the
primary script; that one remains the record of what was cut from the first draft and why.

**Runtime:** ~3:15 across two cuts, or either cut standalone (~1:40 each).
**Format:** actor on camera for the framing, screen capture with voiceover for the walkthroughs.

**Every claim below was checked against the code.** §6 lists what this script deliberately does not
say, with the reason and the true alternative. Read it before briefing an actor.

---

## 1. What changed, and why

The earlier script opened on the product. This opens on **a person describing a problem**, because
that is what the audience recognises — and it is the audience's own problem, not a hypothetical.
`MARKET-ANALYSIS.md` §6 is blunt: 80%+ of influencer marketers run this on spreadsheets, and *"the
competition to beat is Excel, not Grin."*

**Instagram publishing is not in this script.** It was in the brief and it is not built: the
`SocialPlatformAdapter` interface has `platform()`, `isConfigured()` and `fetch(handle)` and no
publish method anywhere. The live Meta token is real and non-expiring but carries `instagram_basic`
and `instagram_manage_comments` — **not `instagram_content_publish`**, which only App Review grants.
Implementing it would produce correct code and a permission error on every call.

What replaces it is true and demonstrable: **the creator gets their own tracked link and coupon
code, and shares it themselves.** The API path belongs in the closing "what's coming" beat, which is
where the actor is going anyway.

---

## 2. Cut 1 — "The spreadsheet problem" (~1:40)

For the brand owner or marketer who is already doing this work by hand.

### Beat 1.1 — The actor, on camera (~25s)

No product. One person, talking.

> "If you work with creators, you know this spreadsheet. One tab per campaign. A column for who's
> agreed what, a column for whether they've posted, a column somebody added called 'notes' that's
> doing four different jobs.
>
> And the bit nobody solves — when a sale comes in, you genuinely cannot say which creator earned
> it. So at the end of the month you're guessing at what to pay people who did real work for you."

**This is the whole pitch.** The attribution gap is the pain that spreadsheets cannot fix at any
level of discipline, and it is the one the product actually closes.

### Beat 1.2 — Sign up, and the file goes in (~25s, screen + VO)

> "Signing up takes a minute, and the free tier is a real tier — one brand, twenty-five creators,
> one seat. Enough to run a campaign, not a trial that expires.
>
> Then drop the spreadsheet in. It works out which columns are creators and which are campaign
> details, and shows you the mapping before it commits to anything."

**Built:** signup; `ImportPage.jsx`; mapping via `agent_service`'s pgvector retrieval over
`mapping_examples`, which learns from approved mappings. Free tier limits verified in
`PlanPolicy.FREE` — 1 brand, 25 creators, 1 seat.

### Beat 1.3 — The board that doesn't lie (~20s)

> "Every campaign gets a board. But this one isn't a tracker you keep in sync by hand — move the
> page and the card moves; drag the card and the page moves. There's one truth, and both views read
> from it."

**Built:** `InfluencerWorkflowUI`, `LandingStageService`. Content owns the transition: the board
issues a command the page can refuse, and the card only moves on an accepted one — so the card can
never show a stage the page does not have.

### Beat 1.4 — The coupon closes the gap (~30s)

> "Here's the part the spreadsheet couldn't do. Each creator gets their own code. Every order placed
> with it is attributed to them — automatically, at the moment it happens.
>
> So when you ask what a partnership was worth, there's an answer. Per creator, over whatever window
> you pick. And what you owe them follows from the same number: approve the commission, record the
> payment, and it's closed off against the work."

**Built:** `CouponService.generateOne`/`generateBulk`; `AnalyticsService.influencerRevenue(brandId,
from, to)`; `PayoutService.approveCommission` and `createPayout`, with `ManualPayoutProvider`.

**Say "record the payment", not "automated payouts".** The workflow is real — approve, create, mark
paid — but `ManualPayoutProvider` deliberately moves no money: it records that the operator paid
out-of-band. Its own header says most small brands start exactly there, which is a strength worth
stating plainly rather than a gap to talk around.

---

## 3. Cut 2 — "Working with creators" (~1:35)

For the DTC brand that has creators but no process.

### Beat 2.1 — The actor, on camera (~20s)

> "The other half of this is the creators themselves. You send them a brief, they send back
> something that doesn't sound like them, you rewrite it, they rewrite it back. Three days gone on a
> page that goes live for a week.
>
> There are two ways through that, and which one you want depends on how much the creator's voice
> matters to the campaign."

### Beat 2.2 — Path one: you write it (~25s)

> "If you just need it out, write it yourself. Describe the campaign — the goal, the offer, who it's
> for — and you get a page back built from sections that can't be laid out wrong. Change every word
> if you like; you can't break the design, because nothing in here sets a colour or a position.
>
> Add the coupon, publish, and the page is live with tracking already wired in."

**Built:** `CampaignPageGenerationService`, `SectionEditor`, `sectionTypes.js` (no field is a colour,
font, size or position — that is what makes "cannot look wrong" true rather than aspirational).

**Do not say "the AI writes it".** `web-experience.landing.generation.provider` is `template`
because the Anthropic balance is zero. The template generator is real and instant, so the beat films
fine — "it builds the page from the brief" is accurate either way.

### Beat 2.3 — Path two: they write it (~35s)

> "Or hand it to the creator. One click — and they get a link, not an account to set up. It opens on
> their phone with the product already in it.
>
> They rework the copy so it sounds like them, and send it back. It lands in your list under
> 'waiting on you'. You read what changed and publish. They can't publish; that stays with you.
>
> Then they get their own version of the link and their own code. They share it with their audience
> — their post, their words — and every sale that comes through it is theirs."

**Built:** `POST /api/landing-pages/{id}/handoff`, `InfluencerCreatorPortalUI`, the tokenised invite,
the `turn` axis, `POST /api/creator-portal/pages/{id}/hand-back`, per-creator coupons and links.

**"They share it" — not "it publishes to their handle".** See §1 and §6.

### Beat 2.4 — The numbers (~15s)

> "Both paths end in the same place. Revenue by creator, what each one is owed, and a record of what
> you've paid."

**Mocked data**, seeded by `tests/e2e/seed-demo-workspace.mjs` — three creators with deliberately
uneven order counts, because identical revenue reads as fabricated and hides the comparison the view
exists to make.

---

## 4. Closing — the actor, on camera (~25s)

> "Everything you've just seen runs on the free tier. One brand, twenty-five creators, one seat — no
> card, no expiry.
>
> Paid is for when it grows: more creators, your whole team in there, and several brands if you're
> an agency. And two things we're building next — publishing straight to a creator's Instagram
> rather than handing them a link, and a share kit that writes the caption for them, sized for each
> platform, with the disclosure already in it.
>
> The link and the code work today. The posting is what's coming."

**Verified limits:** free = 1 brand / 25 creators / 1 seat; pro = 1 brand / 250 creators / 10 seats;
agency = unlimited. Prices are on `tejdux.com/pricing/` and are **not** spoken — a price read aloud
is a price that goes stale in an edit.

**The last line matters.** It states plainly what works now versus what is coming, which is the
difference between a roadmap and an overclaim. A prospect who finds out for themselves stops
believing the rest.

---

## 5. Shot list

| # | Beat | Shot | Source |
|---|---|---|---|
| 1.1 | The problem | Actor, on camera | — |
| 1.2 | Signup + import | Screen + VO | seeded workspace |
| 1.3 | The board | Screen + VO | `InfluencerWorkflowUI` |
| 1.4 | Coupon + payout | Screen + VO | seeded orders |
| 2.1 | Two paths | Actor, on camera | — |
| 2.2 | Brand authors | Screen + VO | section editor |
| 2.3 | Creator authors | Screen + VO, incl. phone | portal |
| 2.4 | The numbers | Screen + VO | revenue view |
| 4 | Free and paid | Actor, on camera | — |

Three on-camera pieces, six screen segments. The actor bookends each cut; the product carries the
middle.

---

## 6. Things not to say

| Don't | Why | Do |
|---|---|---|
| "Publishes to their Instagram" | No publish method exists. The live token has `instagram_basic` and `instagram_manage_comments`, **not** `instagram_content_publish` | "They get their own link and code, and share it" |
| "Automated payouts" | `ManualPayoutProvider` records a payment made out-of-band; no money moves | "Approve what's owed and record paying it" |
| "The AI writes your page" | Generation provider is `template`; the Anthropic balance is zero | "It builds the page from the brief" |
| "Rewrites it in your voice" | Rewrite options are Shorter / Warmer / Lead with the offer | The creator edits it themselves |
| "Live revenue" | Orders are seeded; there are no customers yet | "Every sale with that code is attributed to them" |
| Any spoken price | Prices change and a video does not | "It's on the pricing page" |

**Two of these are worth understanding rather than just avoiding.** Instagram publishing is gated on
Meta App Review, and the dossier's own advice is to request the full permission set in the *initial*
submission because a second round costs 2–4 weeks. And the manual payout provider is not a
placeholder — its header records that most small brands genuinely start there, which is a fair thing
to say out loud.
