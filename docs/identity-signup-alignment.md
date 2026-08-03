# Identity ↔ Signup Alignment

**Date:** 2026-08-02
**Status:** Stage 1 implemented; Stages 2–4 planned
**Related:** [ddd-roadmap.md](ddd-roadmap.md) Phase 2, [E2E-TEST-REPORT-2026-08-02.md](E2E-TEST-REPORT-2026-08-02.md)

---

## The gap

The identity model supports two account types and six roles. The signup path produces exactly one
combination, and the landing page offers no way to ask for anything else.

Two layers independently force the same outcome:

| Layer | What it does |
|---|---|
| `LandingPage.jsx` | Collects `fullName`, `brand`, `email`, `password`. No account-type control. |
| `AuthService.signup` | Takes `(email, password, brandName)`. Passes a literal `"owner"`. |
| `provision_tenancy_for_user` trigger | Hardcodes `account_type='brand'` and `'OWNER'::account_role`. |

Verified rather than inferred — a signup posting `accountType: "agency"` returned `role: "OWNER"`
and wrote `account_type=brand`, with a 200 and no error. The unknown fields were **silently
ignored**, which is the part that makes this a defect rather than only a missing feature: a caller
cannot tell a typo from success.

The seed script is the clearest evidence the application cannot do this. To build the demo agency,
[`schema/seed/test_accounts.sql`](../schema/seed/test_accounts.sql) signs users up through the real
endpoint, then **deletes the solo accounts the trigger created** and re-parents them with raw SQL:

> *"The Phase 1 provisioning trigger gives each new signup its own solo account. Demo agency members
> must instead belong to the shared agency account, so their auto-created solo accounts are removed
> first."*

If the app could create an agency, the seed would not need to undo the signup.

### What is *not* a gap

**Creator is not a login persona.** `creator.creators` rows are CRM records a brand owns — keyed by
`brand_id`, carrying `preferred_rate` and `safety_notes` that are deliberately per-brand
relationship data. Creators have no `users` row, no membership, no credential. `user_role` is only
`owner | marketer`.

So "creator signup" is not misaligned with the identity model; it does not exist in it. Building one
is a new bounded capability (Stage 4), not a defect to fix.

---

## Stage 1 — Account type at signup ✅ DONE

**Goal:** a signup can create an `agency` account, and an invalid request is refused rather than
quietly downgraded.

| Step | Change | File |
|---|---|---|
| 1.1 | `PATCH /tenancy/accounts/{id}` sets `account_type` | `TenancyController.java` (DAO) |
| 1.2 | `promoteAccountType` on the tenancy client | `DaoTenancyClient.java` (BFF) |
| 1.3 | `signup` accepts an account type; agency signups promote after provisioning | `AuthService.java` |
| 1.4 | `accountType` on the signup request, validated | `AuthController.java` |
| 1.5 | Unknown JSON fields rejected on auth payloads | `AuthController.java` records |
| 1.6 | `accountType` forwarded to the BFF | `SessionController.java` (DPS) |
| 1.7 | Account-type selector; label copy follows the choice | `LandingPage.jsx` |
| 1.8 | Selection threaded from the form to the API | `App.jsx` |

### Why promote rather than replace the trigger

The trigger stays authoritative for provisioning and the agency case promotes the account
afterwards, inside the same signup call. Two reasons:

- **The solo path is untouched.** It is the overwhelming majority of signups and already works;
  rewriting provisioning to add a minority case would put the common path at risk for no gain.
- **The trigger is scheduled to move into the application in Phase 2 anyway** (its own description
  says so). Replacing it now would mean doing that migration twice — once here, once properly.

The cost is that an agency signup is two writes rather than one. That is acceptable while the
trigger owns provisioning, and disappears when Stage 2 lands.

### Deliberately still true after Stage 1

A new agency gets **one brand**, named after the workspace, and the creator is its `OWNER` — not
`ADMIN`. This is intentional. `OWNER` is a superset of `ADMIN`, so the account holder can do
everything an admin can plus own billing; handing a founder `ADMIN` at signup would give them
*fewer* rights over their own account. Multi-brand agencies then add brands through the existing
`POST /api/brands` path, which already works and is already permission-checked.

---

## Stage 2 — Move provisioning into the application

**Goal:** retire `provision_tenancy_for_user`, so account shape is decided in one place that can be
tested and reasoned about.

| Step | Change |
|---|---|
| 2.1 | `TenancyProvisioningService` in the BFF: create account → brand → membership in one transaction |
| 2.2 | `AuthService.signup` calls it instead of relying on the trigger |
| 2.3 | Drop the trigger; keep the function one release for rollback |
| 2.4 | Backfill check: every `users` row still resolves to exactly one account |

**Why it matters:** provisioning currently lives in PL/pgSQL, where it cannot be unit-tested, cannot
express "agency with two brands", and is invisible to anyone reading the Java. It also forces
Stage 1's two-write shape.

**Risk:** this is the signup path for every user. Gate on a soak, and keep the function present
(trigger dropped) so a rollback is one `CREATE TRIGGER`.

---

## Stage 3 — Invite members instead of re-parenting by SQL

**Goal:** an agency owner can add a `MANAGER`, `MARKETER`, `ANALYST` or `FINANCE` member from the
UI, and `test_accounts.sql` stops needing raw SQL.

| Step | Change |
|---|---|
| 3.1 | `POST /api/brands/members/invite` — email + `AccountRole` + brand scope |
| 3.2 | Single-use, expiring invitation token (reuse the OAuth handoff pattern) |
| 3.3 | Accept flow: create the user against the **inviting** account, never a new solo one |
| 3.4 | Members screen: list, change role, revoke |
| 3.5 | Rewrite the seed to use the invite endpoint |

`member:invite`, `member:update` and `member:remove` permissions **already exist** and are already
granted to `OWNER`/`ADMIN` — the authorization model is ready; only the endpoint and screen are
missing. Step 3.5 is the acceptance test: when the seed no longer needs `DELETE FROM
identity.accounts`, the capability is genuinely in the product.

---

## Stage 4 — Creator identity (new capability, not a fix)

**Goal:** a creator logs in and sees their own collaborations across brands.

This is a genuine bounded-context addition and should be sized as one. It is listed so it is not
mistaken for a gap Stage 1–3 leave behind.

| Step | Change |
|---|---|
| 4.1 | Decide the model: `creator_identities` linking one login to many `creator` rows |
| 4.2 | Creator-scoped permission set (read own deals, submit content, view own payouts) |
| 4.3 | Separate signup + invite-from-brand flow |
| 4.4 | Creator portal UI — a different application surface, not a role toggle on this one |

**The modelling question to settle first:** a creator exists as N per-brand rows by design, so one
creator login must fan out to many `creator.id` values. That is a new relation and a new tenancy
rule — a creator's tenancy is "the set of brands who have me as a creator", which is the inverse of
every rule the platform enforces today. Do not begin 4.2–4.4 before 4.1 is agreed.

---

## Sequencing

```
Stage 1 ✅ ───► Stage 2 ───► Stage 3
(signup)       (provisioning) (invites)
                                 │
Stage 4 (creator identity) ──────┘  independent; needs its own design round
```

Stages 1–3 are one thread: each removes a reason the previous one needed a workaround. Stage 4 is
independent and should not be bundled in.

---

## Verification

Stage 1 is covered by `tests/e2e_signup_personas.sh`, which asserts:

- a brand signup still yields `account_type=brand`, `role=OWNER` (no regression)
- an agency signup yields `account_type=agency`, `role=OWNER`, one brand
- `accountType: "creator"` is **refused with 400**, not coerced
- an unknown field (`role`, `accountType` typo) is **refused with 400**
- both new accounts are tenancy-isolated from each other and from the demo agency
