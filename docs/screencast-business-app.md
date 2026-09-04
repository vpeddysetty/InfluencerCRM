# Business-app screencast — the token chain

> **SUPERSEDED 2026-09-03 — DO NOT FOLLOW THIS AS WRITTEN.**
>
> The consent segment below was never recorded, by decision. It required trimming the Business
> app's granted permissions to three, and three attempts failed to move them off fifteen:
>
> 1. **Trimmed a Business Login Configuration** — none existed, so there was nothing to trim.
> 2. **Created configuration `2021219268570991`** with exactly the three permissions, and changed
>    `instagram-token.py` to send `config_id` instead of the `scope` a Business app ignores.
>    Verified in the emitted URL. The re-minted token still carried fifteen.
> 3. **Untested hypothesis:** the grant is attached to the authorising Facebook user from an
>    earlier authorisation (2026-08-16), and Meta reissues an existing grant rather than
>    re-prompting. Removing the app at facebook.com/settings?tab=business_tools and re-minting
>    would test it. Not attempted.
>
> **The decision was to record narration-only instead** — the approach `screencast-teleprompter.md`
> already describes, which never films a Business-app consent dialog and so never exposes the
> fifteen scopes. That is a legitimate submission: `pages_show_list` has no user-visible surface,
> and reviewers assess whether a permission is justified and used, not whether its grant was filmed.
>
> **What remains true and useful here:** the failure modes at the end, the note on the test account,
> and the diagnosis above — which is the record of what was actually tried, so nobody repeats it.
> The `config_id` change to the script is correct on its own merits and was kept.
>
> Revisit only if a reviewer asks for proof of the token chain.


**Prepared 2026-09-03.** A companion to [`screencast-teleprompter.md`](screencast-teleprompter.md),
not a replacement. That script covers the Consumer app (sign-in) and the creator lookup. This one
adds the segment it is missing: **visible proof that the app obtains a Page access token**, which is
what `pages_show_list` and `pages_read_engagement` are actually for.

## Why this exists

The existing scripts narrate those two permissions over a creator-lookup screen — the viewer hears
what they do and sees a follower count. Accurate, but nothing on screen demonstrates `/me/accounts`
being called; it happens server-side during token minting, weeks before a user opens the product.
Meta reviewers assess whether a permission is justified and used, and an asserted permission is
weaker evidence than a shown one. Verified 2026-09-03: the stored token is type `PAGE` and carries
both scopes, so the chain is real and filmable.

---

## BEFORE YOU RECORD — the blocker

**Trim the Business Login Configuration first.** The Business app (`1532612907951511`) currently
grants **15 scopes**, verified by `debug_token` on 2026-09-03:

```
manage_fundraisers, publish_video, catalog_management, pages_show_list,
ads_management, ads_read, business_management, pages_messaging,
instagram_basic, instagram_manage_comments, leads_retrieval,
pages_read_engagement, pages_manage_metadata, pages_manage_ads, public_profile
```

`instagram-token.py` requests only three, and its comment says asking for more than the adapter uses
is a review risk. The script is right and is being overruled: a Business app ignores `scope` and
takes its permissions from the dashboard's Business Login Configuration, so that config — not the
constant in the script — is what the consent screen will display.

**Filming a consent dialog showing `ads_management` and `business_management`, for a submission that
requests neither, hands the reviewer the "where is this used?" question on camera.** It is the same
objection that withdrew `instagram_manage_insights` on 2026-08-16, in a harder-to-answer form.

Dashboard, App `1532612907951511` → **Facebook Login for Business** → **Configurations** → edit the
configuration → reduce permissions to exactly:

- `instagram_basic`
- `pages_show_list`
- `pages_read_engagement`

Then re-run `python infrastructure/scripts/instagram-token.py` and confirm the printed scope list
shows only those three. **Do not record until it does.**

Two consequences worth knowing before you start:

- **Re-minting replaces the stored token.** That is the script's normal job and the running app is
  unaffected — it reads the secret per call — but it is a real write to Secrets Manager.
- **The old token keeps working** until overwritten, so a failed trim is recoverable.

---

## Recording setup

Same as the main script. In addition:

- **A second browser profile, signed out of Facebook**, or the consent dialog may skip straight to
  "you have already authorised this app" and show nothing.
- If it does skip: App `1532612907951511` → **App Roles** → remove your own authorisation, or use
  <https://www.facebook.com/settings?tab=business_tools> to remove Tejdux, then retry.
- **Close every unrelated tab.** The consent dialog shows your real Facebook identity; a visible
  notification or a second account is a re-record.

---

## The segment

Record this as its own take. Splice it into the main video between section 3 (why we need creator
data) and section 4 (the Instagram call) — the point where the narration currently claims what
`pages_show_list` does without showing it.

Target 0:45. It is the shortest segment in the video and the only one that shows a permission being
granted rather than used.

---

### Take 1 — consent

Start on the Business app's login URL, signed out of Facebook.

> **"Before the creator lookup, this is where the Instagram permissions are granted.**
>
> **This is our own operational account connecting to our own app — it happens once, when we set
> the integration up. Our customers never see this screen."**

Click through to the consent dialog.

⏸ **STOP TALKING. Count three seconds.** Let the dialog sit fully visible. The permission list is
the single most important frame in this video.

> **"The dialog lists exactly three permissions.**
>
> **instagram_basic, to read a creator's public profile.**
>
> **pages_show_list, to find which of our Facebook Pages carries our Instagram Business account.**
>
> **And pages_read_engagement, to read that Page and resolve the Instagram account itself."**

Approve.

> **"That gives us a Page access token. Every Instagram call the product makes travels through it."**

---

### Take 2 — the token (optional but strong)

Only if you are comfortable showing a terminal. Skip it if not; the consent screen is the load-bearing
frame and this is corroboration.

Run the status check. Show the output.

> **"Confirming what we hold: a Page token, carrying those three permissions and nothing else.**
>
> **It is stored server-side. It never reaches a browser."**

**If the scope list shows anything beyond the three, stop recording.** The trim did not take, and
this frame becomes evidence against the submission rather than for it.

---

## Then continue with the main script

Cut to section 4 of [`screencast-teleprompter.md`](screencast-teleprompter.md) — the creator lookup —
unchanged. Its narration still works, and now describes something the viewer has watched happen.

---

## On the test account

`@tejduxtest` has **2 followers and 1 post** (verified 2026-09-03), and it is being filmed as-is by
decision rather than oversight. That is defensible: the reviewer is checking that the API answered
and that the number on screen is the number the platform returned, not that the account is large.
The provenance badge is what carries the segment, and the teleprompter already points the cursor at
it.

Say nothing apologetic about the size. Explaining it invites attention to it; the honest frame is
that this is a test account and the mechanism is what is being demonstrated.

---

## Failure modes specific to this segment

**The consent dialog skips.** You are still authorised. Remove the app from your Facebook Business
Tools settings and retry — do not film a "you have already connected" screen, it shows no
permissions.

**The dialog shows more than three permissions.** Stop. The configuration was not trimmed, or was
trimmed on the wrong app — check the app id in the URL is `1532612907951511` and not the Consumer
app `2214744426037953`.

**The lookup afterwards says "Simulated".** The re-mint failed and the adapter fell back. The
teleprompter's rule applies unchanged: stop recording. Filming simulated numbers as platform data is
precisely what App Review exists to catch.

**A `(#10)` error on `tejduxtest`.** Expected on `business_discovery` and not on the direct read.
If the product's lookup fails, the adapter is taking the discovery path for a handle it should
recognise as its own — check `web-experience.creators.instagram-own-username` (env `INSTAGRAM_OWN_USERNAME`)
matches the connected account.
