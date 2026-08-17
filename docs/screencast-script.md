# Meta App Review screencast — shot script

**App:** TejDux (Business, `1532612907951511`) · **Runtime:** ~4 min · **Recorded:** 1920×1080 @ 30fps, voice-over, no cuts

---

## What this video is for, and what it is not

Meta reviewers are answering one question per permission: *does a real user, in the live app,
visibly trigger this permission, and is the returned data used for the stated purpose?* They are not
assessing the product. So this script demonstrates permissions in the order they are exercised and
spends no time on features that do not touch the Graph API.

**This is not a product tour.** A marketing walkthrough — value proposition, KPIs, closing
call-to-action — submitted as App Review footage is a common rejection cause. Everything below earns
its place by showing a permission being used.

### The constraint that shapes the whole video

`business_discovery` is gated on **Advanced Access for `instagram_basic`** — the permission this
submission requests. Until it is granted, that endpoint answers
`(#10) Application does not have permission` for **every** handle, including our own. Verified
against the live Graph API on 2026-08-16.

So the only Instagram lookup that returns data today is of **our own connected Business account**,
through the ungated `/{ig-user-id}` endpoint. That is what the video shows, and the narration says
so in as many words. Implying we can already read arbitrary creators would be a claim the reviewer
can disprove in one API call.

### Do not

- No cuts, speed-ups or transitions through the OAuth consent dialog — reviewers reject edited flows
- No music, no intro animation, no webcam or presenter
- No DevTools, no terminal, no localhost — this is the live deployment
- Do not look up a creator we cannot resolve and talk over the failure

---

## Before recording

| | |
|---|---|
| Safe Browsing | Warning must be **cleared**. A red interstitial mid-OAuth is its own rejection |
| Browser | Fresh private window, signed out, bookmarks bar hidden |
| Notifications | Windows Focus Assist ON — a toast on screen means re-recording |
| Display | Primary 1920×1080 monitor only. Keep the browser there |
| Account | `tejduxtest` should have several posts and more than a handful of followers if possible |
| Test credentials | Must match what is entered on the submission form |
| Dry run | Walk the whole flow once without recording. Confirm the badge reads **Platform verified** |

**OBS:** profile and scene collection `TejduxAppReview` load on launch. Output goes to
`C:\AI\InfluencerCRM\recordings`. Check the mic meter moves before you start.

---

## The script

**Target 4:00.** The narration is ~450 words, which is about 3¼ minutes at a deliberate 140 wpm;
the rest is page loads, the API call, and the pause on the consent dialog. Glance at the OBS timer
at each section boundary:

| By the end of | Clock should read |
|---|---|
| §1 what the app is | 0:20 |
| §2 sign-in permissions | 1:15 |
| §3 why creator data | 1:35 |
| §4 Instagram lookup | 3:05 |
| §5 deletion and privacy | 3:40 |
| §6 close | 4:00 |

Running long is not a failure — App Review has no time limit, and a rushed consent dialog is far
worse than a video that runs to 4:30. If you are behind, cut §3 rather than hurrying §2 or §4.

Speak slower than feels natural.

### 1 · What the app is (0:00 – 0:25)

**Screen:** `https://www.tejdux.com/` — the signed-out landing page.

> "This is Tejdux, a B2B influencer marketing CRM operated by KMPS Global Corporation. Brands use it
> to manage partnerships with social media creators. I'll show where each requested permission is
> used."

*Do not scroll the marketing page. One sentence of context, then move.*

---

### 2 · `email` and `public_profile` (0:25 – 1:20)

**Screen:** Click **Continue with Facebook**.

> "Signing in with Facebook. This is where we request `email` and `public_profile`."

**The consent dialog must be fully visible, unedited, with the permission list readable. Pause here
— three full seconds of silence on screen.** This frame is the single most important one in the
video.

> "The dialog lists exactly what we ask for: public profile and email address."

Approve. Land in the app.

> "We use the email as the account identifier and for transactional notices — partnership updates
> and payout notifications. The public profile supplies the display name shown in the workspace. We
> request nothing else, and we never post to anyone's account."

**Screen:** Go to **Settings → Sign-in methods**, showing Facebook connected.

> "Users can see which sign-in methods are connected here, and disconnect at any time."

---

### 3 · Why a brand needs creator data (1:20 – 1:45)

**Screen:** **Creators** in the left nav — the creator directory.

> "This is a brand's creator list. To decide who to partner with, and to check that an audience is
> real, a brand needs the creator's follower count and engagement. That's what the Instagram
> permissions are for."

*Stating the purpose BEFORE the call. Reviewers are matching the use against the justification on
the form.*

---

### 4 · `instagram_basic`, `pages_show_list`, `pages_read_engagement` (1:45 – 3:10)

**Screen:** **New creator** → the drawer opens.

> "Adding a creator. Rather than typing an audience size by hand, we read it from Instagram."

Type `tejduxtest` in **Handle**. Click **Look up**. **Do not cut the wait.**

> "That's a live call to the Instagram Graph API. It travels through our own connected Instagram
> Business account, which is what `pages_show_list` and `pages_read_engagement` are for — they
> identify the Page our Business account is linked to and let the call read through it.
> `instagram_basic` is what returns the profile itself."

**Screen:** The audience panel — follower count, engagement, the badge.

> "Follower count and engagement come straight from the API.
>
> To be precise about what's on screen: this is our own connected Business account. The
> `business_discovery` endpoint that reads other creators needs Advanced Access on
> `instagram_basic` — which is what this submission requests. Once approved, a brand types any
> public Business handle here and this panel fills the same way."

**Point at the badge. Hover it so the tooltip shows.**

> "This badge says 'Platform verified' — the platform answered. If the numbers were simulated,
> because we had no credentials, it would say 'Simulated' instead. Every metric records where it
> came from, so nobody can mistake an estimate for measured data."

Click **Add creator**. Open the saved record.

> "Saved with the creator, with the same provenance label. That's the whole use: a brand sees a
> real audience size before committing to a partnership."

---

### 5 · Deletion and control (3:10 – 3:45)

**Screen:** `https://www.tejdux.com/data-deletion/`

> "Our data deletion instructions, linked in the app dashboard. It explains what we hold, how to
> disconnect Facebook sign-in, and how to request erasure by email."

Scroll to the Facebook section. Pause on it.

> "Disconnecting Facebook stops it being a sign-in method and removes the provider identifiers we
> hold. Deleting the account removes everything else."

**Screen:** briefly, `https://www.tejdux.com/privacy/`

> "The privacy policy states exactly what we receive from Facebook — name, email, and the provider
> account ID — and that we never post on a user's behalf."

---

### 6 · Close (3:45 – 4:00)

**Screen:** back on the creator record.

> "So: `email` and `public_profile` for sign-in and notices. `instagram_basic`, `pages_show_list`
> and `pages_read_engagement` to read a creator's public audience metrics through our connected
> Business account, so a brand can evaluate a partnership. That's every permission and where it's
> used."

Stop recording. Do not add an outro.

---

## After recording

1. Watch it back in full. Confirm the consent dialog is legible and uncut.
2. Confirm the badge reads **Platform verified** on camera, not Simulated.
3. Confirm no notification, tooltip or personal data is visible.
4. Under 5 minutes, under 500 MB.
5. Upload directly to the App Review form. Meta accepts mp4 — do not put it on YouTube.

## If the badge says Simulated

Stop. Do not submit. It means the adapter fell back to generated numbers, and filming those as real
audience data is precisely what App Review exists to catch. Run
`infrastructure\scripts\verify-instagram.bat` and fix it first.
