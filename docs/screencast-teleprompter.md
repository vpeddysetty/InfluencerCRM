# Read-aloud script — Meta App Review

Everything you say is in **bold**. Everything you do is in plain text.
Nothing else on this page needs reading while you record.

Target 4:00. Running to 4:30 is fine. **Rushing is worse than long.**

---

## 1 · Open (0:00 – 0:20)

Start on `https://www.tejdux.com/`, signed out.

> **"This is Tejdux, a B2B influencer marketing CRM operated by KMPS Global Corporation.**
>
> **Brands use it to manage partnerships with social media creators.**
>
> **I'll show where each requested permission is used."**

---

## 2 · Facebook sign-in (0:20 – 1:15)

> **"Signing in with Facebook. This is where we request email and public profile."**

Click **Continue with Facebook**.

⏸ **STOP TALKING. Count three seconds.** Let the consent dialog sit on screen, fully visible.

> **"The dialog lists exactly what we ask for — public profile, and email address."**

Approve. Wait for the app to load. Don't talk over the redirect.

> **"We use the email as the account identifier, and for transactional notices — partnership
> updates and payout notifications.**
>
> **The public profile gives us the display name shown in the workspace.**
>
> **We request nothing else, and we never post to anyone's account."**

Click **Settings** in the left nav. Show the Sign-in methods panel.

> **"Users can see which sign-in methods are connected here, and disconnect at any time."**

---

## 3 · Why we need creator data (1:15 – 1:35)

Click **Creators** in the left nav.

> **"This is a brand's creator list.**
>
> **To decide who to partner with — and to check that an audience is real — a brand needs the
> creator's follower count and engagement.**
>
> **That's what the Instagram permissions are for."**

---

## 4 · The Instagram call (1:35 – 3:05)

Click **New creator**.

> **"Adding a creator. Rather than typing an audience size by hand, we read it from Instagram."**

Type `tejduxtest` into **Handle**. Click **Look up**.

⏸ Wait for it. Don't fill the silence.

> **"That's a live call to the Instagram Graph API.**
>
> **It travels through our own connected Instagram Business account — that's what pages_show_list
> and pages_read_engagement are for. They identify the Page our Business account is linked to, and
> let the call read through it. instagram_basic is what returns the profile itself."**

Point the cursor at the follower count.

> **"Follower count and engagement come straight from the API.**
>
> **To be precise about what's on screen: this is our own connected Business account. The
> business_discovery endpoint that reads other creators needs Advanced Access on instagram_basic —
> which is what this submission requests.**
>
> **Once approved, a brand types any public Business handle here, and this panel fills the same
> way."**

Hover the **Platform verified** badge so the tooltip appears.

> **"This badge says 'Platform verified' — the platform answered.**
>
> **If the numbers were simulated, because we had no credentials, it would say 'Simulated'
> instead.**
>
> **Every metric records where it came from, so nobody can mistake an estimate for measured
> data."**

Click **Add creator**. Open the saved record from the list.

> **"Saved with the creator, with the same provenance label.**
>
> **That's the whole use — a brand sees a real audience size before committing to a
> partnership."**

---

## 5 · Deletion and privacy (3:05 – 3:40)

Open `https://www.tejdux.com/data-deletion/` in a new tab.

> **"Our data deletion instructions, linked in the app dashboard.**
>
> **It explains what we hold, how to disconnect Facebook sign-in, and how to request erasure by
> email."**

Scroll to the Facebook section. Pause on it.

> **"Disconnecting Facebook stops it being a sign-in method, and removes the provider identifiers
> we hold. Deleting the account removes everything else."**

Open `https://www.tejdux.com/privacy/`.

> **"The privacy policy states exactly what we receive from Facebook — name, email, and the
> provider account ID — and that we never post on a user's behalf."**

---

## 6 · Close (3:40 – 4:00)

Go back to the creator record.

> **"So: email and public profile, for sign-in and notices.**
>
> **instagram_basic, pages_show_list and pages_read_engagement, to read a creator's public audience
> metrics through our connected Business account — so a brand can evaluate a partnership.**
>
> **That's every permission, and where each one is used."**

Stop recording. **No outro. Don't say thank you or goodbye.**

---

## If something goes wrong mid-take

**Stumble on a word** — pause two seconds, start that sentence again. Keep rolling.

**Badge says "Simulated"** — stop recording. Do not carry on. Those numbers are generated, and
filming them as real audience data is the thing App Review exists to catch.

**A notification pops up** — stop and re-record. It may show personal data.

**The lookup fails** — stop. `tejduxtest` is the only handle that resolves before approval; any
other will say "could not be resolved".

---

## Practising

Read it aloud twice before recording anything.

The second read is where you find the words that don't sit right in your mouth — change them.
It's your script; nothing here has to be said exactly as written, as long as the meaning holds.

Two sentences that should keep their meaning precisely, because they are what the review turns on:

- the one naming what `pages_show_list` and `pages_read_engagement` do
- the one saying `business_discovery` needs the Advanced Access you're requesting

Time your second read. If you land near 3:15 of pure speech, the finished take will sit close to
4:00 once clicks and page loads are in.
