# Identity ↔ Signup Alignment

**Date:** 2026-08-02
**Status:** Stages 1–4 implemented
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

So "creator signup" was not misaligned with the identity model; it did not exist in it. Building one
was a new bounded capability, not a defect to fix — which is what Stage 4 did, as a separate thread
with its own design round. A creator still has no `users` row and no membership; they authenticate
through the portal instead, and the per-brand `creator.creators` rows are unchanged.

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

### Why this shipped as promote-then-provision, and no longer works that way

Stage 1 originally left the trigger authoritative and promoted the account to `agency` immediately
afterwards, inside the same signup call — deliberately, so the solo path (the overwhelming majority
of signups) was untouched, and so the trigger migration was not done twice.

**Stage 2 has since removed that.** Provisioning now decides the account type at creation, so the
second write is gone and `promoteAccountType` is no longer on the signup path. The steps above
describe what was built; the shape they describe is superseded by Stage 2 below.

### Deliberately still true after Stage 1

A new agency gets **one brand**, named after the workspace, and the creator is its `OWNER` — not
`ADMIN`. This is intentional. `OWNER` is a superset of `ADMIN`, so the account holder can do
everything an admin can plus own billing; handing a founder `ADMIN` at signup would give them
*fewer* rights over their own account. Multi-brand agencies then add brands through the existing
`POST /api/brands` path, which already works and is already permission-checked.

---

## Stage 2 — Move provisioning into the application ✅ DONE

**Goal:** retire `provision_tenancy_for_user`, so account shape is decided in one place that can be
tested and reasoned about.

| Step | Change | Where |
|---|---|---|
| 2.1 | `POST /tenancy/provision` — account + brand + membership in one transaction | `TenancyController` (DAO) |
| 2.2 | `AuthService.signup` calls it instead of relying on the trigger | `AuthService` |
| 2.3 | Trigger dropped; function retained for rollback | `2026_08_02_stage2_app_owned_provisioning.sql` |
| 2.4 | Federated sign-up provisions too | `AuthService.signupWithSocial` |

**One endpoint, not three calls.** The three writes share a DAO transaction: a user with an account
but no membership is a tenant nothing can serve, and splitting the writes across HTTP would make
that state reachable on any partial failure. Idempotent on `legacy_user_id`, so a retried signup
returns the existing workspace rather than creating a second one.

**The social path needed the same treatment.** `signupWithSocial` also creates users and was relying
on the trigger implicitly. Without provisioning there too, dropping the trigger would have left
every Google sign-up with a user and no account — caught before the trigger was removed, not after.

**Verified while the trigger was still live.** With both active the trigger won the race and the
application call returned the existing workspace, confirming the two cannot coexist as a steady
state. The trigger was dropped only after that. Post-migration: zero users without a membership,
zero accounts without a brand.

**Why it mattered:** provisioning lived in PL/pgSQL, where it could not be unit-tested, could not
express "agency with two brands", and was invisible to anyone reading the Java. It also forced
Stage 1's two-write shape.

**Rollback:** the function is retained, so restoring the old behaviour is one `CREATE TRIGGER` —
the statement is in the migration's footer. Do not run both: application provisioning is idempotent
on `legacy_user_id`, so the trigger would win every race and agency signups would silently become
brand accounts again.

---

## Stage 3 — Invite members instead of re-parenting by SQL ✅ DONE

**Goal:** an agency owner can add a `MANAGER`, `MARKETER`, `ANALYST` or `FINANCE` member from the
UI, and `test_accounts.sql` stops needing raw SQL.

| Step | Change | Where |
|---|---|---|
| 3.1 | `member_invitations` table, hash-only token storage | `2026_08_02_stage3_member_invitations.sql` |
| 3.2 | Invite / accept / revoke / list, role + member management | `MemberInvitationService`, `BrandsController` |
| 3.3 | Accepting adds a membership on the **inviting** account | `TenancyController.acceptInvitation` |
| 3.4 | Members screen: list, invite, change role, revoke | `MembersPage.jsx` |
| 3.5 | Seed rebuilt on the invite endpoints | `tests/seed_demo_accounts.sh` |

**Only a SHA-256 hash of the token is stored.** An invitation token grants access to an account, so
a database dump must not contain usable invitations — the same standard already applied to passwords
and refresh tokens. The token is returned once, at creation, and cannot be read again.

**An invitation is addressed, not bearer-only.** Redemption checks the invitation was issued to the
accepting user's email; otherwise a forwarded token would let whoever holds it join the account.

**`OWNER` cannot be granted by invitation.** It carries billing and the right to delete the account,
so transferring it should be a deliberate, separately-confirmed act rather than a dropdown selection.

**A gap found while testing: brand-scoped roles need `brand_access` rows.** `MANAGER`, `MARKETER`
and `ANALYST` reach brands *only* through that table — `OWNER`, `ADMIN` and `FINANCE` are
account-wide. Without granting it on acceptance, an invited marketer held a membership and still saw
zero brands, which reads as a broken account rather than a permissions decision. Acceptance now
grants the invitation's brand, or every current brand if unscoped.

**Step 3.5 is the acceptance test, and it passes.** `tests/seed_demo_accounts.sh` builds the whole
Northstar agency — owner plus five roles with correct brand scoping — through signup, invite and
accept. The old seed had to `DELETE FROM identity.accounts` and re-parent users; this one touches no
table directly.

`member:invite`, `member:update` and `member:remove` permissions **already exist** and are already
granted to `OWNER`/`ADMIN` — the authorization model is ready; only the endpoint and screen are
missing. Step 3.5 is the acceptance test: when the seed no longer needs `DELETE FROM
identity.accounts`, the capability is genuinely in the product.

---

## Stage 4 — Creator identity (new capability, not a fix) ✅ DONE

**Goal:** a creator logs in and sees their own collaborations across brands.

This is a genuine bounded-context addition and should be sized as one. It is listed so it is not
mistaken for a gap Stage 1–3 leave behind.

| Step | Change | Where |
|---|---|---|
| 4.1 | `creator_identities` + `creator_identity_links` | `2026_08_02_stage4_creator_identity.sql` |
| 4.2 | Portal session, separate from the operator JWT | `CreatorPortalService` |
| 4.3 | Creator signup/login, claim, and brand-side approve/invite | `CreatorPortalController` |
| 4.4 | Collaborations view across every confirmed brand | `CreatorPortalController.collaborations` |

### 4.1 — the decision that gated the rest

**A creator login is not an `identity.users` row.** A users row resolves through memberships to an
account and carries `account_role` permissions. A creator has neither. Sharing the table would mean
every membership query had to remember to exclude creators — a rule that eventually gets forgotten
in one place and leaks access. `creator_identities` is a separate table for that reason.

**Tenancy runs backwards here.** Every other rule in the platform is "this brand owns these rows".
A creator's is "these rows have been confirmed as me" — the inverse. That is why this is not another
`account_role`: a creator must never inherit brand-scoped permissions.

**Links are claimed explicitly, never matched on email or handle.** The data decides this: only 48
of 210 creator rows carry an email at all, and handles repeat across brands — `@solo_demo` appears
under 23. Auto-linking would be guesswork, and guessing wrong hands one creator another's negotiated
rate.

**A claim grants nothing until a brand confirms it.** `claimed` is an assertion; only `confirmed`
appears in the portal. A brand inviting a creator creates the link as confirmed directly, because
requiring the brand to approve its own invitation would be ceremony rather than a control.

**Portal sessions are opaque and server-side**, not JWTs. A creator session carries no claims worth
signing, and re-reading links per call means a brand revoking one takes effect immediately rather
than at token expiry. They are held in memory: the portal has no multi-instance deployment yet, and
a shared store before there is a second instance would be infrastructure ahead of need. A restart
signs creators out — acceptable for a portal, and noted here rather than left to be discovered.

---

## Sequencing

```
Stage 1 ✅ ───► Stage 2 ✅ ───► Stage 3 ✅
(signup)       (provisioning)  (invites)

Stage 4 ✅ (creator identity)   independent thread
```

Stages 1–3 were one thread: each removed a reason the previous one needed a workaround. Stage 1
promoted an account because the trigger could only make brands; Stage 2 removed the trigger, so the
promotion went away. Stage 3 then had somewhere coherent to attach a member. Stage 4 is independent
and was designed separately.

---

## Verification

| Suite | Covers | Result |
|---|---|---|
| `tests/e2e_signup_personas.sh` | Stages 1–2 — both account types, refusals, provisioning | 17/17 |
| `tests/e2e_member_invitations.sh` | Stage 3 — invite, accept, revoke, roles, authorization | 25/25 |
| `tests/e2e_creator_portal.sh` | Stage 4 — claim, approve, fan-out, separation | 22/22 |
| `tests/seed_demo_accounts.sh` | Stage 3 acceptance — the whole demo agency, no raw SQL | builds cleanly |

What the assertions are actually defending:

- a brand signup still yields `account_type=brand`, `role=OWNER` — no regression on the common path
- an agency signup yields `account_type=agency` in the database, not just in the response
- `accountType: "creator"`, a misspelling, and an unknown field are each **refused with 400**
- an invitation token exists in the database **only as a hash**
- a forwarded invitation cannot be redeemed by a different email
- an invited `MARKETER` receives `brand_access`, so the brand is actually reachable
- an **unconfirmed creator claim exposes nothing** — the check that makes the claim/approve split
  meaningful rather than decorative
- one brand approving a creator does **not** confirm another brand's record for them
- an operator JWT is not a creator session, and a creator token cannot reach the brand API
