# Creator portal — visual and interaction design

Draft, to merge with the workflow's architecture output.

## The design problem, stated honestly

The creator is the only actor who **does not work for the brand**. They have no reason to
tolerate friction, will not be trained, will not read a tooltip, and will almost certainly
open this on a phone, one-handed, from a link in an email or a DM.

Everything below follows from that.

## Why not the generated design system

`ui-ux-pro-max --design-system` proposed *Marketplace/Directory* with a navy/green palette
and Playfair Display body text. Rejected on three counts:

1. **Wrong pattern.** A marketplace optimises for search and browse. This surface has exactly
   one job — review a page someone else wrote and respond to it. There is nothing to browse.
2. **Wrong palette.** The creator is reviewing a page rendered in the brand's terracotta /
   Newsreader system (`SECTION_CSS` in `LandingService.java`). A navy chrome around a terracotta
   page reads as two products stapled together.
3. **Playfair Display for body text** is a display face. At 16px on a phone it is tiring.

The portal chrome should be **quiet** so the page being reviewed is the loudest thing on
screen. That is the opposite of what a marketplace wants.

## The organising idea: one screen, one question

The creator portal is not a smaller version of the brand app. It is a **single-question
surface**: *"Here is your page. Does it work?"*

Everything else — history, other campaigns, settings — is secondary and reachable, but never
competes with that question.

```
┌─────────────────────────────────────┐
│  Linen & Trail                       │   ← who is asking (brand, not us)
│  wants you to review a page          │
├─────────────────────────────────────┤
│                                      │
│   [ the real rendered page,          │   ← the actual server-rendered HTML,
│     full width, scrollable ]          │     same renderer as the public page
│                                      │
├─────────────────────────────────────┤
│  ✓ Looks good      ✎ Suggest a change│   ← two actions, thumb-height, always visible
└─────────────────────────────────────┘
```

**The page is the interface.** Not a form describing the page. The creator scrolls the thing
their followers will see, and responds to it.

## Palette: borrow the page's, quieten it

Reuse the landing page tokens so the chrome and the content belong to each other:

| Token | Value | Use |
|---|---|---|
| `--paper` | `#FAF8F5` | portal background |
| `--ink` | `#2A2724` | primary text |
| `--ink-soft` | `#5C554E` | secondary text |
| `--ink-mute` | `#6E655E` | metadata (verified AA at small sizes) |
| `--accent` | `#A84A32` | the one primary action |
| `--rule` | `#E2DAD1` | dividers |

One deliberate addition, because approval states need a non-terracotta signal:

| `--agree` | `#2F6B4F` | "Looks good" confirmation |

Checked: `#2F6B4F` on `#FAF8F5` = 5.8:1, and white on `#2F6B4F` = 5.4:1. Both clear AA.

**Typography:** Inter throughout the chrome. Newsreader appears only *inside* the previewed
page, where it already lives. The chrome should not compete with the content.

## The two actions, and why only two

- **✓ Looks good** — approves. One tap. No form, no required comment.
- **✎ Suggest a change** — opens a comment sheet, prefilled with nothing.

A third option ("Reject") was considered and dropped. A creator who wants changes has not
rejected anything; they have started a conversation. Naming it "Reject" makes the interaction
adversarial and makes creators reluctant to use it — which is exactly when the brand most needs
to hear from them.

**Section-anchored comments.** Tapping a section of the preview attaches the comment to that
section, so "the second bit is wrong" is never ambiguous. This maps onto the existing
`{type, variant, fields}` section identity — no new addressing scheme needed.

## Edit rights: shown only when granted

`PageCollaborationService` already distinguishes `comment` from `edit`. The portal honours it:

- **comment** — the two actions above.
- **edit** — additionally, tapping a section opens its fields in a sheet, using the *same*
  curated field set as the brand editor. No colour, font, size or position — the constraint is
  the product thesis and does not weaken because the editor is smaller.

A creator with `comment` rights must never see an edit affordance that then fails. Hide, do not
disable.

## Mobile-first specifics

- Actions in a **fixed bottom bar**, 48px minimum, thumb-reachable, above the safe area.
- The preview is a real iframe of server-rendered HTML — identical to the brand's canvas, so
  what the creator approves is what publishes.
- `overscroll-behavior: contain` on the preview, so scrolling the page does not pull-to-refresh
  the portal and lose an in-progress comment.
- No drag interactions anywhere. (Same reasoning as the brand editor's up/down reorder buttons.)
- Session is 12h (`CreatorPortalService.SESSION_TTL`) and **in-memory** — a deploy signs
  creators out. For a creator mid-comment that is data loss, so drafts must persist locally
  (`localStorage`) and restore on re-entry. This is a real consequence of the existing design,
  not a hypothetical.

## Where it lives

A **new Vite project, `InfluencerCreatorPortalUI`**, not a route in `InfluencerUI`.

Reasons: the shell bundles the whole operator app (including GrapesJS today) and a creator
should never download that; the auth model is different (portal session, not operator JWT); and
the DPS `AppRegistry` entitlement model is per-app, so a separate app id is how the creator's
narrower permission set gets enforced rather than assumed.

Cost: the section field schema (`sectionTypes.js`) becomes a **third** copy under the
remote-copy pattern, and must join `contentRemoteCopies.test.mjs`. That is the known tax on
this repo's structure until `@influencer/ui` is extracted.
