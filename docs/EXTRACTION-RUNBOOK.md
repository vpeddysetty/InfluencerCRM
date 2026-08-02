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

### 2. Stand up the service

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

### 3. Route traffic behind a feature flag

- Point the gateway at the new service for that context's endpoints.
- **Keep the monolith path live.** The flag is what makes this reversible in seconds rather than a
  redeploy.
- Dual-run in staging and diff responses. Contract tests are cheaper than a rollback.

### 4. Cut over

- Flip the flag in production, watch error rate and latency, then delete the monolith code path.
- Deleting it is not optional: two live paths diverge, and the dead one is the source of the next
  confusing bug.

### 5. Replace synchronous calls with events

Where the extracted context previously called another in-process:

- Emit a domain event through `DomainEvents.publish(...)`.
- Implement `DomainEventHandler` in the consuming context.
- **Handlers must be idempotent** — the outbox delivers at-least-once, and redelivery is normal.
- Drop the cross-schema foreign key **only now**, once the event path is proven. Doing it earlier
  removes referential integrity months before anything needs it.

### 6. Extract the frontend

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
- **Do not drop cross-schema FKs early.** They are the safety net while this is still one database.
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
