# AI, per actor — prompt design

Draft, to merge with the workflow's architecture output.

## The gap in what exists today

`AnthropicPageGenerator.systemPrompt()` is genuinely good. It is specific about the reader
("read on a phone by someone who arrived from a creator's post and has not heard of the brand"),
it forbids invention ("do not invent statistics, testimonials, certifications"), and it demands
structural variety between drafts rather than reworded sameness.

But it has **no notion of who is writing**. It writes brand copy, always. That is correct today,
because only the brand can author. The moment a creator can author, it is wrong:

> A brand writes *about* a product. A creator writes *about their own experience of* a product.

Those are different voices, different claims, and different legal exposure. One prompt cannot
serve both, and pretending it can produces the worst outcome — a creator's page in brand-speak,
which their followers instantly detect as an ad and which is precisely what the creator was
hired to avoid.

## The change: voice is a parameter of the brief

`PageGenerationPort.Brief` gains a `voice` field. Not a new port, not a new provider — the
existing registry, timeout, fallback and validation all still apply.

```java
/**
 * Who is writing, which changes what may be claimed and in whose person.
 *
 * <p>A brand writes about a product; a creator writes about their experience of it. Same page,
 * incompatible voices — and the difference is not stylistic. First-person experience claims are
 * only honest from someone who had the experience, so the generator must know which it is
 * writing before it writes a sentence.
 */
public enum Voice { BRAND, CREATOR }
```

## Three system prompts, one shared spine

The invention prohibition and the mobile-reader framing stay identical across all three — they
are true regardless of who writes. Only the voice section differs.

### Shared spine (unchanged from today)

- The reader arrived from a creator's post, is on a phone, and has not heard of the brand.
- State the offer plainly; give the page one clear action.
- **Invent nothing**: no statistics, testimonials, certifications, endorsements, product claims.
- Where the brief gives nothing for a section, write the section without that material.

### BRAND voice (today's behaviour, made explicit)

```
You are writing as the brand. Use "we" for the brand and "you" for the reader.

Do not write in the creator's person. You may say the creator is involved; you may not put
words in their mouth or describe experiences they have not described to you. A quote you
compose for a creator is a fabricated endorsement, and it publishes under their name.
```

### CREATOR voice (new)

```
You are drafting for the CREATOR to review and edit. They will publish it in their own name
to their own audience, who follow them personally and can tell when a post was written by a
marketing team.

Write in the first person, from the creator's point of view, using only what the brief and the
creator's own notes actually contain.

You do not know what they thought of the product. Where the draft needs an opinion or an
experience you have not been given, leave the sentence for them to complete rather than
inventing a reaction. An invented opinion is the one failure that costs the creator their
audience's trust, and it is not recoverable by editing.

Disclosure is not optional and not a footnote: this is a paid partnership and the copy must
say so in the creator's own plain words, not in legal boilerplate.
```

That third paragraph is the important one. The usual failure mode of AI-drafted creator content
is **confident fabricated enthusiasm** — "I've been wearing this every day for a month" written
about a product the creator has not opened. Leaving a gap is strictly better than filling it,
because the creator can fill a gap in seconds and cannot un-publish a lie.

## Per-actor surfaces

| Actor | Surface | Voice | What AI does |
|---|---|---|---|
| Brand owner / marketer | brief → drafts | BRAND | Generates 2–3 structurally distinct drafts (exists today) |
| Brand owner / marketer | per-section rewrite | BRAND | Shorter / Warmer / Lead with the offer (exists today) |
| Agency owner | same as brand, per client brand | BRAND | Same; the brand context differs per workspace |
| Creator | their section of the page | CREATOR | Drafts *their* quote/section in first person, with gaps left |
| Creator | "say this in my words" | CREATOR | Rewrites brand-written copy into their voice — the highest-value action for them |

The creator's rewrite reuses `/api/campaign-pages/sections/rewrite` unchanged. It already takes
a free-text instruction; the voice comes from the brief, not from a new endpoint.

## Social captions — one new capability, deliberately scoped

Posting to a handle needs a **caption**, which is not a landing-page section. This is the one
place a genuinely new AI capability is justified.

It should be a method on the existing `PageGenerationPort`, not a new port:

```java
/**
 * A caption for a social post linking to the page.
 *
 * <p>Default is honest refusal, matching rewriteSection: a generator with no caption capability
 * returns empty rather than echoing the page's headline back. A headline is not a caption —
 * captions carry the disclosure, the platform's conventions, and the creator's voice.
 */
default CaptionResult caption(Brief brief, Voice voice, String platform) {
    return CaptionResult.unavailable(key(), "this generator cannot write captions");
}
```

Platform matters: an Instagram caption, a TikTok caption and an X post have different lengths,
different conventions, and different disclosure placement rules. The `platform` parameter is not
decoration.

**Disclosure reuses what exists.** `Brief.disclosure` is already a first-class field, and
`BriefEnricher` already treats FTC/ASA disclosure as "a legal requirement on the page, not a
stylistic choice". `AnthropicPageGenerator` already passes it through with a comment warning that
rewording it could weaken the statement that makes the page compliant.

The caption path must inherit that, not reinvent it: the same disclosure text, placed in the
caption body rather than appended by the UI afterwards, so a creator editing the caption sees it
and keeps it. If the model omits it, the service adds it — the same "safe by construction"
reasoning as the section renderer's escaping.

## What I would NOT do

- **No new AI provider or port.** The registry, the `template` fallback and the `anthropic`
  implementation all still apply. Voice is a parameter, not an architecture.
- **No autonomous posting.** AI drafts a caption; a human presses post. An AI that publishes to
  a creator's handle unsupervised is a reputational risk with no upside.
- **No "AI approves the page".** The whole point of the handoff is that a human creator agrees
  to put their name on it.
