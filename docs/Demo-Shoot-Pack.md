# Demo shoot pack — actor-facing

**Companion to** `docs/Demo-Script-Collaborative-Drop.md`, which is the production script and the
record of what is and is not built. **This is the version you hand to two people in a studio.**

**Why it differs from the script.** That one is written for text-to-speech: tight, every word
load-bearing, no air. A person reading it aloud sounds like a person reading. This one gives beats
instead of verbatim lines, because the point of casting real people is that they sound like
themselves — and 85 seconds of TTS becomes roughly 100 when a human says it, so the timings here are
looser too.

**Read §4 before you roll.** Three claims in this product are not true yet, and an actor
improvising past them is the one mistake that cannot be fixed in the edit.

---

## 1. The two people

**Sarah — brand growth lead.** Desktop, office. She is the one who inherited the spreadsheet. Play
her as competent and slightly done with the old way — not amazed by software. The demo works because
she is unimpressed by things that do not save her time.

**Marcus — creator.** Phone, studio, natural light. His whole value on camera is sounding
unrehearsed. **Give him the beats, not the lines.** If he says it in his own words it proves the
product's argument better than any script can — a page written in a creator's voice is the thing
being sold.

---

## 2. Beat sheet

Each block is what has to land, not what has to be said. The suggested phrasing is a floor, not a
target — if the actor finds a better line for the same beat, use theirs.

### Beat 0 — The spreadsheet (~18s, Sarah)

**Land:** this is how it works today, and it is a mess. Then: the file goes in, and the tool works
out the columns.

- Have a **real, ugly spreadsheet** on screen. Inconsistent headers, a Notes column doing four jobs,
  someone's initials in a cell. A tidy spreadsheet undercuts the whole scene.
- She drags it in. The mapping appears **already filled in**, and she reviews it.

> *Roughly:* "Sixty rows. Four of us editing it. Nobody sure which copy was current."
> … "It figures out the columns. And it shows me before it commits to anything."

**Do not say "it knows".** The review step is the feature — say *suggests*, or *works out*, and let
her glance over it. A tool that silently guessed at somebody's spreadsheet would be worse, and the
confirmation screen is the trustworthy design rather than an extra step.

### Beat 1 — Brief to page (~15s, Sarah)

**Land:** she describes the campaign once and gets a page back she cannot lay out wrong.

> *Roughly:* "I write the brief once — the goal, the offer, who it's for." … "And I get a page. I
> can change the words. I can't break the layout."

**See §4 on the word "AI".**

### Beat 2 — The handoff (~12s, Sarah)

**Land:** one click sends it, and the board moves by itself.

- Frame so the **board is visible when the card moves**. That shot is the beat.

> *Roughly:* "One click, and it's his." … "And the board isn't something I keep in sync. The page
> moved, so the card moved."

### Beat 3 — The creator (~20s, Marcus)

**Land:** no account, the page is just there, he makes it sound like him, he sends it back.

**Give Marcus these four beats and let him talk:**
- a link arrived, and there was nothing to sign up for
- the page opened on his phone and it already had the product in it
- he changed the words so they sound like him rather than like a brand
- he sent it back

> *If he wants a line:* "There's no account. It's just… there." … "That's not how I'd say it." …
> *[edits]* … "That is."

**One instruction, and it matters:** he changes the copy **himself**, and says so. Do not let him
say the AI wrote it in his voice — see §4.

### Beat 4 — Waiting on you (~12s, Sarah)

**Land:** it comes back to her list, she reads it, she publishes. He could not have.

> *Roughly:* "It's back on my list — that's the bit I actually look at." … "I read what he changed,
> and I publish. He can't. That stays with us."

### Beat 5 — The coupon (~18s, both)

**Land:** each creator has their own code, and the revenue view says which partnership was worth
what.

> *Sarah, roughly:* "Marcus has his own code. Anything bought with it is his." … "So I know what
> the partnership was worth. Per creator — not one number for the whole campaign."

**See §4 on "real-time".**

### End (~5s)

> *Sarah:* "From a spreadsheet to a campaign, with the creator actually in it."

---

## 3. What has to be on screen

The workspace is seeded by `tests/e2e/seed-demo-workspace.mjs` — brand, three creators, two
campaigns, a coupon each, a published page, and orders spread across three weeks so the revenue view
has a shape. Run it before the shoot and note the credentials it prints.

Bring your own **ugly spreadsheet** for Beat 0. The seed does not make one, and a convincing mess is
not something to improvise on the day.

---

## 4. Things not to say

**Brief both actors on this page.** These are not stylistic preferences — each is a claim the
product cannot currently back, and an ad-lib past one is a false statement on camera.

### "The AI writes your page"

`web-experience.landing.generation.provider` is set to `template`, because the Anthropic account
balance is zero. The template generator is real and instant, so the scene films perfectly — but no
model is involved right now.

- ✅ "It builds the page from the brief." / "I describe the campaign and get a page back."
- ❌ "The AI writes it." / "AI-generated copy."

*Flip `page_generation_provider` to `anthropic` once there is credit and this restriction lifts —
one variable, no rebuild.*

### "It rewrites it in your voice"

The rewrite is real, but the options are **Shorter**, **Warmer** and **Lead with the offer**. There
is no creator-voice option and no first-person-with-disclosure prompt — that work is designed
(`docs/Creator-Handoff-AI-Per-Actor.md`) and unbuilt.

- ✅ Marcus edits the copy himself and says so. He can use **Warmer** on camera; that button exists.
- ❌ "I tap 'say this in my words' and it sounds like me."

### "Real-time revenue" / "watch the orders come in"

Attribution is real and the mechanism works. The orders on screen are **seeded** — there are no
customers yet.

- ✅ "Anything bought with his code is attributed to him."
- ❌ "These are live sales." / "Watch it tick up in real time."

### Two more, briefly

- **"Zero hallucinated claims"** — a property of how the prompt is written, not something enforced
  in code. Do not assert it. What IS true and worth saying: no field in the editor is a colour,
  font, size or position, so a brand *cannot* produce a broken layout.
- **"Automated payouts"** — `PayoutProvider` is an interface with a registry and no implementation.
  The system computes what is owed; nothing pays it. Say **"see exactly what each creator has
  earned"** instead.

---

## 5. Two notes for the edit

**Sarah's lines will run long.** Everything in §2 is a floor. If a take runs to 100 seconds, that is
the expected outcome of using a person rather than TTS — cut in the edit rather than rushing her,
because a brand lead who sounds hurried undercuts the "this saves me time" argument.

**Marcus's best take will not be the scripted one.** Shoot his beats several times and keep the one
where he stops performing. The product's claim is that a creator's own voice converts better than a
brand's; the demo should demonstrate that rather than assert it.
