# Demo video: the collaborative drop

**Status:** production script, revised 2026-08-27. Supersedes the first draft.
**Runtime:** ~85 seconds. 16:9, clippable to 9:16.

**Every beat below was checked against the code before it was written.** The first draft showed a
Share Kit, a diff view, a "say this in my words" button and live revenue — three of which do not
exist and one of which is seeded. Those are cut or replaced here, and §5 lists them with what would
have to be built. A demo that shows something unbuildable is discovered at the worst possible
moment: with two actors booked and a studio paid for.

---

## 1. Why the opening changed

The first draft opened on the product. This one opens on **their spreadsheet**, because that is the
actual competitor. `MARKET-ANALYSIS.md` §6 is blunt about it:

> 80%+ of influencer marketers report using spreadsheets… the competition to beat is Excel, not
> Grin. Any pricing must clear a "why not free" bar.

An empty product does not clear that bar. A product that eats the file already open on their desktop
does, in the first fifteen seconds, before anything has to be explained.

It is also the strongest thing in the codebase that nobody talks about: the importer maps columns
using `agent_service`'s pgvector retrieval over `mapping_examples`, and it **learns** — an approved
mapping is written back as a future example. That is a real differentiator and the current script
never mentioned it.

---

## 2. The arc

| Time | Scene | Beat |
|---|---|---|
| 0:00–0:15 | **The spreadsheet** | Their file, imported, columns mapped |
| 0:15–0:30 | **The brief** | Campaign brief becomes a page |
| 0:30–0:42 | **The handoff** | One click; the board moves with it |
| 0:42–1:00 | **The creator** | Mobile portal, rewrite, send back |
| 1:00–1:10 | **Waiting on you** | Brand reviews and publishes |
| 1:10–1:25 | **The coupon** | Per-creator code, revenue attributed |

---

## 3. Scene by scene

### Scene 0 — The spreadsheet (0:00–0:15)

**Visual:** Sarah's desktop. A real `.xlsx` — sixty rows, inconsistent column names, a "Notes"
column doing four jobs. She drags it into the import screen.

> **Sarah:** *"This is how we ran creator campaigns. Sixty rows, four people editing it, and nobody
> sure which version was current."*
>
> *[The importer reads the file. Column mappings appear, already filled in.]*
>
> *"It reads the columns, works out which are creators and which are campaign details, and shows me
> what it is about to do — before it does it."*

**Built:** `InfluencerUI/src/pages/ImportPage.jsx`; mapping via `agent_service/retrieval_service.py`
(pgvector over `mapping_examples`, `RETRIEVAL_TOP_K=3`, learns from approvals).

**Say "it suggests the mapping", not "it knows".** The review step is the feature — a tool that
silently guessed at somebody's spreadsheet would be worse, and the screen shows the mapping for
confirmation precisely because that is the trustworthy design.

---

### Scene 1 — The brief becomes a page (0:15–0:30)

**Visual:** Campaign brief form → generated draft in the section editor.

> **Sarah:** *"I write the brief once — the goal, the offer, who it is for — and get a page back
> built from sections that cannot be laid out wrong."*

**Built:** `CampaignPageGenerationService`, `SectionEditor`, the eight curated section types.

**Two things to be careful about on camera:**

1. **Generation currently runs the TEMPLATE provider**, not a model —
   `web-experience.landing.generation.provider` defaults to `template` because the Anthropic
   balance is zero. It is a real generator and it is instant, so the scene films fine. But do not
   say "the AI writes it" while the AI is switched off. **"It builds the page from the brief"** is
   true either way.
2. The first draft said **"zero hallucinated claims"**. That is a property of prompt design, not
   something enforced in code. Do not assert it. What IS enforceable and worth saying: the editor
   cannot express a bad layout, because no field in `sectionTypes.js` is a colour, font, size or
   position.

---

### Scene 2 — The handoff, and the board that follows it (0:30–0:42)

**Visual:** Sarah picks Marcus, clicks **Hand over to creator**. Cut to the Kanban board: the card
moves from *Approved* to *With creator* on its own.

> **Sarah:** *"One click. And the board is not a separate tracker I keep in sync — moving the page
> moves the card, and dragging the card moves the page."*

**Built:** `POST /api/landing-pages/{id}/handoff` (one endpoint: grant + stage + turn together),
`InfluencerWorkflowUI`, `LandingStageService`.

**This beat is worth its eight seconds** because most tools have a board that lies. Here content
owns the transition: the board issues a command the page can *refuse*, and the card only moves on an
accepted one, so the card can never show a stage the page does not have.

---

### Scene 3 — The creator (0:42–1:00)

**Visual:** Marcus, phone, in a studio. Notification → magic link → portal. No password screen. He
taps a section, picks a rewrite, edits, taps **Send back to Acme**.

> **Marcus:** *"No account to set up. A link, and the page is there."*
>
> *"I make it sound like me, not like a brand — then send it back."*

**Built:** `InfluencerCreatorPortalUI`, tokenised invite, `SectionEditor` mounted from
`packages/ui`, `POST /api/creator-portal/pages/{id}/hand-back`.

**The rewrite buttons say Shorter / Warmer / Lead with the offer.** There is no "say this in my
words" and no creator-voice prompt — that is the per-actor AI work in
`docs/Creator-Handoff-AI-Per-Actor.md`, designed and unbuilt. Film **Warmer**, and let Marcus say it
in his own words rather than claiming the product does. If the voice work ships before the shoot,
this line gets better and the shot does not change.

---

### Scene 4 — Waiting on you (1:00–1:10)

**Visual:** Sarah's list, "Waiting on you" at the top with Marcus's page in it. She opens it, reads,
publishes.

> **Sarah:** *"It comes back to my list, I read what he changed, and I publish. He cannot publish —
> that stays with us."*

**Built:** the `turn` axis, `CollaboratorPanel`, publish requires `content:publish`.

**There is no diff view.** The first draft showed one; versions are snapshotted so the data exists,
but nothing renders a comparison. "Waiting on you" is the same beat — *I am told, and I can see what
needs me* — and it shows the turn mechanic, which is the genuinely novel part.

---

### Scene 5 — The coupon and the attribution (1:10–1:25)

**Visual:** A code generated for Marcus. Then the revenue view, broken down by creator.

> **Sarah:** *"Marcus gets his own code. Every order that uses it is attributed to him — so we know
> what the partnership was actually worth, per creator, not in aggregate."*

**Built:** `CouponService.generateOne` / `generateBulk` (bulk is per-creator, and a vanity code is
single-only by design), per-creator personalisation with brand approval, and
`AnalyticsService.influencerRevenue(brandId, from, to)`.

**The orders are seeded.** With zero customers there is nothing real to show, and that is fine for a
demo — but do not say "real-time" over invented numbers. **"Every order that uses it is attributed"**
describes the mechanism truthfully without implying the numbers on screen are today's.

---

## 4. End card

> **Sarah:** *"From a spreadsheet to a campaign, with the creator in it."*

No "automated payouts" — see §5.

---

## 5. Cut from the first draft, and why

| Cut | Reason | What it would take |
|---|---|---|
| **Share Kit** (captions, per-platform assets, "copy my Instagram caption") | `PR-45`, not started. Nothing to film. | 6d, and `S3AssetStorage` first — assets currently serve off one EC2 box's local disk |
| **Diff view** | Does not exist. Versions are stored; nothing renders a comparison | Unscoped; the version data is there |
| **"Say this in my words"** | Buttons are Shorter / Warmer / Lead with the offer. No creator-voice prompt | The per-actor AI work; would also push the cacheable prefix past Opus's 512-token minimum |
| **Automated payouts** | `PayoutProvider` is an interface with a registry and **no implementation** — the same pluggable pattern as billing and email, defaulting to doing nothing real. `PayoutService` computes what is owed; nothing pays it | A real provider integration |
| **"Live/real-time revenue"** | Attribution is real; there are no customers, so the numbers are seeded | Customers |
| **"Zero hallucinated claims"** | A property of prompt design, not enforced in code | Not a code change — a claim to stop making |

**"See exactly what each creator has earned"** is true today and nearly as strong as the payout
claim it replaces.

---

## 6. What the shoot needs

A workspace that already looks lived-in: a brand, a campaign, several creators, a page mid-handoff,
a coupon per creator, and enough seeded orders for the revenue view to have a shape.

That is a different thing from the new-signup seed in `PR-02` — one has to look worked-in, the other
has to make sense while empty — but they share most of their machinery. Building the demo seed first
and deriving the signup seed from it is the cheaper order, and it is what unblocks filming the six
scenes above that are all real today.
