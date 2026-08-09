# Recorded end-to-end journeys

Nine journeys across four personas, run against the real stack and recorded to video.

## Run them

The stack must be up: UI on `:5173`, DPS on `:8090`, BFF on `:8081`, DAO on `:8443`.

```bash
cd tests/e2e
npm install          # first time only
npx playwright test  # runs the journeys, records one .webm each
node build-video.mjs # stitches them into ../../influencrm-e2e-journeys.mp4
```

## What they cover

| Persona | Journey |
|---|---|
| **Prospect** (signed out) | Sees the free tier honestly; reaches sign-up and sign-in |
| **Solo brand owner** | Signs up; walks Creators/Campaigns/Board/Revenue; adds a creator; sees that roles are paid |
| **Agency owner** | Signs up as an agency; reaches billing |
| **Any user** | A hard reload does not strand them in a tokenless workspace |

## Notes for whoever maintains these

**They write to the real database.** Every signup uses a timestamped address
(`solo-owner-<stamp>@e2e.example`) because a fixed one collides with the previous run and fails on
"email already registered" — a failure that looks like a product bug and is not. That prefix is
also how you find and clean up the rows a run created.

**One worker, not parallel.** Parallel videos interleave into something nobody can follow, and
these journeys share one backend, so concurrent runs create and delete each other's rows.

**`beat()` is presentation, never synchronisation.** It holds the frame so a viewer can register
what happened. Every assertion still waits on a real condition — if a journey only passes because
of a `beat`, that is a bug in the journey.

**ffmpeg comes from `ffmpeg-static`, not from Playwright.** Playwright's bundled ffmpeg is cut down
to what recording needs: VP8 only, with no libx264, no drawtext, and no lavfi input. It cannot
produce mp4 or title cards.
