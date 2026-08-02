# Extraction Runbook
## Turning a bounded context into its own service and frontend

**Date:** 2026-08-02
**Audience:** whoever performs the first extraction — possibly you plus Claude agents, possibly a
team you hire later.

---

## Why this document exists

Phases 0–6 built the foundation but deliberately stopped short of splitting the runtime. That was a
cost decision, not an architectural one: the seams are cut, and this runbook is what makes acting on
them a checklist rather than a project.

Read [contracts/README.md](contracts/README.md) first — it is the authoritative list of what each
context owns.

---

## What is already in place

| Prerequisite | Status | Where |
|---|---|---|
| Tenancy is a first-class key (`brand_id`) | ✅ | All 18 domain tables |
| Authorization is stateless and verifiable anywhere | ✅ | RS256 JWT with `perms` claim |
| Contexts are packaged separately (DAO) | ✅ | 8 contexts × 4 layers |
| Contexts are packaged separately (BFF) | ✅ | 7 contexts × 3 layers |
| Boundaries enforced at build time | ✅ | 23 ArchUnit rules (12 DAO + 11 BFF) |
| Cross-context calls go through ports | ✅ | `CreatorProvisioningPort`, `BrandLookupPort`, `TokenVerifier` |
| Tables separated per context | ✅ | 9 Postgres schemas |
| Per-context DB credentials | ✅ | 8 `svc_*` roles, schema-scoped |
| Asynchronous communication | ✅ | Transactional outbox + relay + handlers |
| Contracts published | ✅ | [contracts/README.md](contracts/README.md) |
| UI API surface split | ✅ | `api/core.js` + 7 slices |
| UI routes declared as data | ✅ | `shell/routeManifest.js` |
| Shell/remote boundary defined | ✅ | `shell/SessionContext.jsx` |

**Not in place, by design:** separate deployables, Module Federation remotes, a message broker.
Each is a config-and-wiring step now, not a redesign.

---

## Recommended extraction order

Unchanged from the migration plan, and the reasoning still holds:

1. **Identity & Access** — everything depends on it, so it must be a real service before any other
   can validate tokens independently.
2. **Collaboration Workflow** — the pilot. Three tables, no money, no inbound port dependencies.
   Cheapest place to learn the deployment and observability pattern.
3. **Creator Relationship** — high read volume, clean aggregate.
4. **Campaign Management** — depends on Creator's port.
5. **Attribution & Commerce** — highest write volume; benefits most from independent scaling.
6. **Payouts & Finance** — money. Extract only once events and audit logging are proven.
7. **Content & Landing** — different caching profile (public pages).

**Do not start with Finance**, however tempting its isolation looks. It is the context where a
mistake costs real money, and it should inherit a pattern that is already working.

---

## Per-context extraction checklist

Work through this once per context. Steps are ordered so the system stays shippable throughout.

### 1. Confirm the context is actually clean

```bash
# Both must pass before you start. A violation here means the boundary is not real yet.
cd InfluencerDAO           && mvn test -Dtest=ContextBoundaryTest
cd InfluencerWebExperience && mvn test -Dtest=BffContextBoundaryTest
```

Then read the context's section of [contracts/README.md](contracts/README.md) and confirm nothing
outside it is listed as a dependency.

### 2. Sever cross-context foreign keys

*Added after the Workflow pilot — this was missing and it is step zero.*

A service with its own database cannot enforce an FK to a table it cannot see. Inventory them first:

```sql
select c.conrelid::regclass::text as tbl, c.conname, c.confrelid::regclass::text as refs
from pg_constraint c
join pg_class t on t.oid = c.conrelid
join pg_namespace n on n.oid = t.relnamespace
where c.contype = 'f' and n.nspname = '<context>';
```

- Drop only the FKs pointing **outside** the context. Keep the ones internal to the aggregate —
  they stay enforceable after extraction.
- Count would-be orphans **before** dropping, so a pre-existing integrity problem is not silently
  inherited by the new service.
- Add a monitoring view replacing the dropped guarantee (`workflow.orphaned_cards` is the pattern).
  It should always be empty and is safe to alert on.
- Grant the service read-only SELECT on the referenced tables, or it cannot query its own
  monitoring view.

See `schema/migrations/2026_08_02_phase5_workflow_extraction.sql` for a worked example.

### 3. Stand up the service

- New repo (or module) containing only `<context>/{domain,application,infrastructure,api}`.
- Connect as the context's own role — `svc_<context>` — **not** `influencercrm_user`. Set a real
  password first:
  ```sql
  alter role svc_workflow with password '<from your secret store>';
  ```
- Verify isolation actually bites before writing any code:
  ```sql
  -- Must be false. If it is true, the grants are wrong and the boundary is decorative.
  select has_table_privilege('svc_workflow', 'finance.influencer_payouts', 'INSERT');
  ```

### 4. Route traffic behind a feature flag

- Point the gateway at the new service for that context's endpoints.
- **Keep the monolith path live.** The flag is what makes this reversible in seconds rather than a
  redeploy.
- Dual-run in staging and diff responses. Contract tests are cheaper than a rollback.

### 5. Cut over

- Flip the flag in production, watch error rate and latency, then delete the monolith code path.
- Deleting it is not optional: two live paths diverge, and the dead one is the source of the next
  confusing bug.

### 6. Replace synchronous calls with events

Where the extracted context previously called another in-process:

- Emit a domain event through `DomainEvents.publish(...)`.
- Implement `DomainEventHandler` in the consuming context.
- **Handlers must be idempotent** — the outbox delivers at-least-once, and redelivery is normal.

> An earlier version of this runbook said to drop cross-schema FKs at this step. The Workflow pilot
> showed that is too late: the service cannot start against its own database while they exist. They
> now go in step 2, with a monitoring view replacing the guarantee.

### 7. Extract the frontend

- Add a Vite Module Federation remote exposing the context's pages.
- In `shell/routeManifest.js`, change one line:
  ```js
  // from
  const WorkflowPage = lazy(() => import('../pages/WorkflowPage'))
  // to
  const WorkflowPage = lazy(() => import('mf_workflow/WorkflowPage'))
  ```
- The remote consumes `useSession()` for auth, active brand and permissions, and imports **only**
  its own `api/<context>.js` slice.
- Everything else in the shell is unchanged. That is the whole point of the manifest.

---

## Known work that must happen before or during the first extraction

Stated plainly so none of it is discovered late.

| Item | Why it matters | When |
|---|---|---|
| **Rotate the TLS keystore** | The old private key is in git history and must be treated as compromised | **Before any deployment** |
| **Set a persistent JWT signing key** | An ephemeral key means services cannot verify each other's tokens | Before service #2 |
| **Move `RefreshTokenStore` to Postgres** | In-memory today; a second instance cannot see the first's tokens | With Identity extraction |
| **Move BFF domain logic into contexts** | `AttributionService`, `CouponService`, `PayoutService`, `LandingService` still hold logic that belongs in the owning context | Per context, at extraction |
| **Distributed tracing** | Once there are 3+ services, "which hop failed?" stops being answerable from logs | Before service #3 |
| **Contract tests** | ArchUnit stops compile-time coupling; only contract tests stop runtime drift between services | With service #2 |
| **Member invitation UI** | Endpoints and permissions exist; the flow does not | Product decision |

---

## What NOT to do

- **Do not extract without the feature flag.** A cutover you cannot reverse in seconds is not a
  cutover, it is an outage waiting for a bad afternoon.
- **Do not drop cross-schema FKs without a replacement.** Dropping one trades an enforced guarantee
  for an unenforced assumption; add the orphan-monitoring view in the same migration.
- **Do not share entity classes between services.** A shared DTO library re-creates the coupling the
  ports removed, with extra steps.
- **Do not extract two contexts at once.** The first one teaches you what the runbook got wrong.
- **Do not skip the ArchUnit check in step 1.** It takes seconds and is the only thing that will tell
  you the boundary drifted since it was drawn.

---

## Rough effort estimate

Per context, assuming the checklist is followed and Claude agents do the mechanical work:

| Step | Effort |
|---|---|
| Service scaffolding + DB role | hours |
| Gateway routing + feature flag | hours |
| Dual-run and diff | 1–2 days of soak |
| Cutover + delete old path | hours |
| Event replacement for sync calls | 1–2 days |
| Frontend remote | 1 day |

The first context will take considerably longer than this — that is what makes Workflow the right
pilot rather than Identity, despite Identity being first in dependency order. Consider extracting
Workflow first purely as a rehearsal, then Identity properly.

---

## Lessons from the first extraction (Workflow, 2026-08-02)

The pilot ran end-to-end. Everything below is a correction to the checklist above, learned by doing
it rather than by planning it.

### What the runbook got wrong

**1. Cross-context foreign keys were not mentioned, and they are step zero.**

`workflow_cards` held FKs to `campaign.campaigns` and `creator.creators`. A service with its own
database cannot enforce those — the referenced rows are somewhere it cannot see. The checklist jumped
straight to scaffolding.

*Correction:* before scaffolding, run the FK inventory and drop the cross-context ones:

```sql
select c.conrelid::regclass::text as tbl, c.conname, c.confrelid::regclass::text as refs
from pg_constraint c
join pg_class t on t.oid = c.conrelid
join pg_namespace n on n.oid = t.relnamespace
where c.contype = 'f' and n.nspname = '<context>';
```

Drop only the ones pointing *outside* the context. FKs internal to the aggregate
(cards → boards, stages → boards) stay enforceable after extraction and must be kept.

**2. Dropping an FK needs a replacement, not just a comment.**

Removing the constraint trades an enforced guarantee for an unenforced assumption. Add a monitoring
view in the same migration — `workflow.orphaned_cards` is the pattern — so the gap is observable and
alertable rather than silent.

**3. The extracted service needs cross-context SELECT for its own monitoring.**

`svc_workflow` could not read `campaign.campaigns`, so the orphan view it owns was unqueryable by the
service itself. Grant read-only on the referenced tables when you drop the FK. Writes stay owned.

**4. "Same package" imports break silently when a context is copied out.**

Classes that referenced each other without imports (same package in the monolith) fail to compile in
the new service. Expect a pass adding explicit imports — and beware auto-import tooling that matches
class names inside javadoc, which produces unused imports that then fail the build.

**5. Log the routing decision at startup, not per request.**

During a cutover the first question is "which target am I actually hitting?". A single startup line
answers it. Per-request logging in a hot path does not survive contact with production.

### What the runbook got right

- **Workflow as the pilot.** Three tables, no money, no inbound ports. Every problem above was cheap
  to fix here and would have been expensive in Identity or Finance.
- **The feature flag.** Cutover and rollback were both a property change, verified in both directions.
  Doing this via redeploy would have made the rollback rehearsal too expensive to bother with.
- **Dual-run diffing before cutover.** All three collections came back byte-identical, and the write
  path returned the same response shape. That is what made the cutover boring.
- **Per-context DB roles first.** `svc_workflow` was already scoped, so the service could not
  accidentally reach another context's tables even while running inside the same database.

### Actual effort

| Step | Estimated | Actual |
|---|---|---|
| FK inventory + drop + orphan view | *not in the runbook* | ~30 min |
| Service scaffolding + DB role | hours | ~45 min |
| Feature-flagged routing | hours | ~30 min |
| Dual-run and diff | 1–2 days of soak | minutes (local); the soak is still right in production |
| Cutover + rollback rehearsal | hours | ~15 min |

The mechanical work was faster than estimated. The *thinking* — which FKs to drop, what replaces the
guarantee, what the service may still read — was the real content, and is now captured above so the
second extraction skips it.

### Still deliberately not done for Workflow

- **The monolith path is not deleted.** The runbook says delete it after cutover, and that is right —
  but only after a production soak. The flag currently defaults to `false` (monolith), so the
  committed state is the safe one; flipping it is a deliberate act.
- **No separate database.** Workflow runs against its own schema with its own role, which is what
  makes the boundary enforceable. Moving to a separate Postgres instance is a deployment change that
  buys availability isolation, not correctness, and can happen whenever it is warranted.
- **No events yet.** Workflow neither emits nor consumes. When card movement needs to notify another
  context, that is the moment to add it — not before.
