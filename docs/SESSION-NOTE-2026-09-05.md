# Session note — 2026-09-05

Written at the end of the session that built §12 (the agency feature set) and deployed `v1.0.60`.
`MASTER-ROADMAP.md` remains the scheduling authority; this is context that does not belong in a
roadmap row — what was learned, what to be careful of, and what the next person would otherwise
have to rediscover.

---

## Where things stand

**Production runs `v1.0.60`, built from `4c30af6` on a clean tree.** `feat/agency-feature-set` was
merged to `master` by fast-forward and everything in §12 is live and verified against the deployed
API, not inferred. §2.0 of the roadmap has the per-row evidence; §2.0b has the known gaps.

The one change that mattered most is `OP-40`. `PR-63` shipped a pricing posture to production in
`v1.0.59` whose central property matched **no account at all** — `identity.accounts.plan` is
`not null default 'free'`, so an "only when unset" rule reached nothing. Every prospect signing up
was hitting the 25-creator wall that row exists to prevent. It is fixed in `v1.0.60` and confirmed
by creating a second brand on a fresh agency signup.

---

## What this session actually taught

Four defects shipped past a green test suite and were found only by running the stack. They share a
shape worth naming, because the next one will look the same.

**A test can be green while exercising none of the code it names.** The worst example: a
cross-tenant leak guard in `SharedCreatorService` was covered by a test that gave the caller only
the brand being viewed, so the method returned early and the test passed with the guard **deleted**.
It was found only because the guard was removed on purpose to check the test bit. Three of the four
had this shape — a stub that mirrored the caller's assumption rather than the collaborator's
contract.

> **The habit that catches it: delete the guard, confirm red, restore.** Cheap, and the only thing
> that distinguishes a test that protects something from a test that merely runs.

**A passing build says the module parses, not that the page works.** `OP-42`: reintroducing an
out-of-scope const gives `vite build` a clean `✓ built in 448ms` and the new render check a
`ReferenceError` with the component and line number. Five remotes still have no such check (§2.0b).

**Field-by-field copies drop columns silently.** `PR-68`'s six new columns were lost in the DAO's
`PUT`, which returns 200 and echoes the request. Found by reading the table, not the response. The
same shape as the projection allow-list, which would have stripped the same fields one layer up:
both are places where a populated column silently stops existing, and neither errors.

**Two correct components can be wrong together.** `AnalyticsService` answers `"0.00"` for a zero-cost
ROI; `PortfolioService` answers `null`. Each is defensible; on one screen a row read `0.00x` directly
under a total reading `—`, for the same situation.

---

## Environment traps that cost time

- **Local Postgres is on port `15432`**, not 5432. A DAO started against 5432 boots fine and 500s on
  the first query.
- **The DAO keystore and the BFF truststore must be a matching pair**, and the failure is a `502`
  with nothing logged. `docs/keystore-rotation.md` now documents the fingerprint comparison that
  identifies it in one command, and why local and production keep separate pairs.
- **Git Bash paths break native binaries.** `$REPO_ROOT` handed to `node.exe` becomes
  `C:\c\AI\...`; `mktemp -d` does the same to Python. `cygpath -w` is the fix, and both bugs had
  already shipped in scripts before this session.
- **A `.node` binary can hold a file lock after its process exits**, breaking `npm ci` with an
  `EPERM` that names no owner. Removing the one directory cleared it.

---

## Judgement calls a later reader might otherwise re-litigate

**Reads come from the transactional store, not a CQRS read model** (§12.2b). Not because CQRS is
wrong, but because at zero subscribers it buys nothing, a read model is eventually consistent and
this is money data, and the projection substrate is the in-process outbox relay CLAUDE.md warns
about. The revisit trigger is measured, not predicted: a real customer's dashboard over ~500ms.

**`csv.js` stayed in the shell rather than moving to `packages/ui`.** It is a presentation utility,
and moving it dragged `analytics.js` with it — shared kernels grow exactly that way, one reasonable
move at a time. `PR-65`'s export went server-side instead, which is also where the domain rules
about what a figure means already live.

**The pricing posture is reversible by two properties**, not by a revert. `WEBE_DEFAULT_PLAN` and
`WEBE_PLATFORM_OWNER_EMAILS` change the commercial stance; every tier, every 402 path and all the
Stripe billing stayed built and tested. Set the first back to `free` and the tiers resume.

**An external product review was recorded with its error rate** (§12.3b). Four of its findings were
real and became `PR-67` and `PR-68`; three of its recommendations were for things that had shipped
weeks earlier, and one advised against the single gap an agency notices first. Both halves are
written down, because a reviewer wrong that often has not earned the flattering conclusion it also
offered.

---

## Two things left undone that are somebody's decision, not an oversight

**The shell's bundled `CreatorsPage` has diverged from the remote** and is now neither a working
fallback nor absent. Copying the code across would settle that question without anyone deciding it.
See §2.0b.

**A test account is in the production database** — `prodcheck.1788626867@example.test`, two brands
and one creator, created to verify the deploy end to end. RDS is not publicly reachable, so it needs
removing from inside the VPC.

---

## What to do next

Nothing on the roadmap is blocked on engineering. `PR-04` (Stripe live keys) and `OP-06` (SES
sandbox) are what stand between this product and revenue, and both are procurement with a lead time.
The five missing render checks (~1 day) are the cheapest real risk reduction available.
