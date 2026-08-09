# Hosting Topology Decision — the SNI constraint

**Date:** 2026-08-07
**Decides:** [EXECUTION-ROADMAP.md](../../EXECUTION-ROADMAP.md) M0 — *"The SNI story. M7 needs TLS
termination for names you do not own. That constrains the hosting topology chosen in 0.3. Deciding
it in M7 means redoing 0.3."*
**Status:** recommendation for the deployment owner. Nothing here is built.

---

## The question, stated precisely

M5 serves landing pages on `<brand>.pages.tejdux.com` — subdomains of a domain **you own**. One
wildcard certificate covers all of them.

M7 serves those same pages on `shop.acmebrand.com` — a domain **the brand owns**. To terminate TLS
for a name you do not own, the edge must:

1. Present a certificate valid for `shop.acmebrand.com`, selected **per-connection by SNI**;
2. Have obtained that certificate, which requires proving control of a domain you do not control
   (the brand delegates this via CNAME or by pointing DNS at you);
3. Renew it every ~90 days, unattended, for every customer domain.

**The trap:** M5 needs none of this, and a topology that serves M5 perfectly can make M7
impossible without a migration. That is why the roadmap forces the decision now.

---

## Constraint: you are already on CloudFront

From [README.md](README.md), `www.tejdux.com` runs S3 + CloudFront + ACM + Route 53. That is not a
neutral starting point — it pushes hard toward one of the options below and rules out another.

**CloudFront's hard limit: 100 alternate domain names (CNAMEs) per distribution** by default.
Raising it requires a support request, and it is a per-distribution quota, not per-account.

This single number decides the architecture. It is fine for M5 (one wildcard = one CNAME entry).
It is a wall at customer #100 for M7 if every customer domain is an alias on one distribution.

---

## The three options

### Option A — CloudFront, one distribution, aliases per customer

Add each customer domain as an alternate domain name on the existing distribution; issue an ACM
certificate per domain (ACM does SNI automatically).

| | |
|---|---|
| **M5 cost** | Near zero. You already run this |
| **M7 cost** | Low per customer, until the wall |
| **Ceiling** | **100 domains.** Then a support ticket, then a shard-management problem you did not plan for |
| **Cert automation** | ACM + DNS validation. Renews itself, but each new domain needs an API call and a customer DNS record |
| **Verdict** | **Correct for M5. A trap for M7 at scale, survivable below ~100 customers** |

### Option B — CloudFront + Lambda@Edge / dynamic SNI via a cert-management layer

Same edge, but certificates managed outside ACM's per-distribution alias model.

**Not recommended.** CloudFront does not offer arbitrary-SNI termination the way a self-managed
proxy does; working around the alias limit means many distributions and a routing layer to pick
between them. You would be building a CDN control plane. That is not a landing-page feature.

### Option C — ALB or self-managed proxy (Caddy / nginx / Traefik) with on-demand TLS

An origin that terminates TLS itself, issuing certificates on first request via ACME.

| | |
|---|---|
| **M5 cost** | Moderate. New component to run, patch, and monitor |
| **M7 cost** | **Near zero per customer.** On-demand TLS is one config block in Caddy |
| **Ceiling** | Thousands of domains; the limit becomes Let's Encrypt rate limits (50 certs/week/registered domain — not a constraint here, since each cert is a *different* registered domain) |
| **Cert automation** | ACME with on-demand issuance and automatic renewal. This is the problem Caddy was built for |
| **Verdict** | **Higher M5 cost, near-zero M7 cost. The only option where M7 is a config change** |

---

## Recommendation

**Option A now, with two constraints that keep Option C reachable.** Do not build Option C yet.

The reasoning is the roadmap's own: **M7 is gated on evidence that may never arrive.** M5's
validation signal is "does anyone click *connect your own domain*?" and the roadmap explicitly says
that if nobody does, M7 never gets built and 20 dev-days are banked. Building on-demand-TLS
infrastructure now spends the M7 budget *before* the gate that decides whether M7 happens.

But Option A must not be built in a way that makes Option C a rewrite. Two constraints:

### Constraint 1 — the Host → page lookup lives in the application, not the edge

M5.3 needs a `Host` header → brand → page resolver. **Put it in the BFF**, reading
`X-Forwarded-Host`. Do not implement it as CloudFront Functions, distribution-per-brand, or S3 key
prefixes.

*Why:* if resolution lives in the application, swapping CloudFront for Caddy changes what sits in
front of the app and nothing else. If it lives in edge config, that config is the migration.

Per [PRODUCT-GAPS.md](../../PRODUCT-GAPS.md) §2.2, no such resolver exists today: there is no
`Host`-header read, no `X-Forwarded-Host`, and no hostname → `brand_domains` lookup anywhere in the
repo. It is being written from scratch either way — so write it in the place that survives.

### Constraint 2 — no certificate material in the database until M7 decides the issuer

`Certificate` is currently `record Certificate(boolean issued, String provider, String detail)`,
and there is **no column for a certificate or private key**. Leave it that way through M5.

*Why:* ACM (Option A) never gives you the private key — AWS holds it. Caddy (Option C) manages keys
on its own disk. **Both correct answers involve storing no key material in Postgres.** A schema
designed now for keys you cannot obtain would be wrong for both.

---

## What this means for the M5 deployment

| Decision | Value | Reason |
|---|---|---|
| Edge | CloudFront (existing distribution or a sibling) | Already run, already understood |
| M5 certificate | One ACM wildcard, `*.pages.tejdux.com` | M5.2 — one cert, no per-domain work |
| `hosting-target` | `pages.tejdux.com` | Replaces `pages.influencrm.example`, which **cannot resolve** (RFC 2606). Set `WEBE_HOSTING_TARGET` |
| Host resolution | **In the BFF**, from `X-Forwarded-Host` | Constraint 1 |
| Cert storage | **None** | Constraint 2 |
| App tier | Anything that can sit behind CloudFront | Not constrained by this decision |

**One wildcard-certificate caveat worth knowing now:** `*.pages.tejdux.com` covers
`acme.pages.tejdux.com` but **not** `a.b.pages.tejdux.com` — wildcards match one label. Brand
subdomains must be single-label. If a brand slug can contain a dot, sanitise it, or the certificate
silently fails for that one customer.

---

## The M7 trigger — write this down now

Revisit this decision when **either** is true:

1. **Customer domains approach 60** (60% of the CloudFront alias limit). Migrating under quota
   pressure is worse than migrating on schedule.
2. **The per-domain onboarding cost becomes the complaint.** Option A needs an ACM request plus a
   validation record per customer. At ten customers that is fine; at eighty it is a support queue.

Neither trigger is reachable before M7's own gate opens, which is the point: **this decision cannot
be wrong before you have the evidence to make it right.**

---

## What was explicitly not decided

- **Where the app tier runs** (ECS / EC2 / Lightsail). Unconstrained by SNI. Choose on operational
  preference.
- **Whether M7 happens at all.** That is M5's validation signal, and it is the roadmap's decision
  to leave open.
- **The registrar adapter.** Cloudflare vs Route 53 is an M7.1 choice behind `DomainRegistrarPort`,
  which is correctly a port already. It does not constrain topology.

**Decision #9 in the roadmap stands: no domain reselling.** The brand buys on their own registrar
account. That is what makes Option A viable at all — you never hold a customer's registrar
credentials, only a delegated validation record.
