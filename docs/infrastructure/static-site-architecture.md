# Static site deployment architecture — www.tejdux.com

**Deployed:** 2026-08-05
**Account:** `099933382956`
**Region:** us-east-1 (ACM certificates for CloudFront must live here)
**Live:** https://www.tejdux.com/terms/ · https://www.tejdux.com/privacy/ ·
https://www.tejdux.com/data-deletion/

Serves the public legal and marketing pages for InfluencerCRM. The Terms of Service and Privacy
Policy are published — both are required as public URLs by the Meta and TikTok developer app reviews
described in [../platform-app-registration.md](../platform-app-registration.md).

## Published content

| Path | S3 key | Source file | Published |
|---|---|---|---|
| `/terms/` | `terms/index.html` | [../legal/terms-of-service.html](../legal/terms-of-service.html) | 2026-08-05 |
| `/privacy/` | `privacy/index.html` | [../legal/privacy-policy.html](../legal/privacy-policy.html) | 2026-08-05 |
| `/data-deletion/` | `data-deletion/index.html` | [../legal/data-deletion.html](../legal/data-deletion.html) | 2026-08-07 |

All three are served on `www.tejdux.com` and the apex `tejdux.com`, with or without a trailing
slash. The root `/` is unpublished and returns 403.

`/data-deletion/` is the **Data Deletion Instructions URL** Meta requires of any app that accesses
user data. Meta accepts either that or a deletion *callback* endpoint; the instructions page was
published first because it unblocks app review without waiting on the callback, which needs a
signed-request handler in the BFF and a public status page.

Adding a page requires no infrastructure change — upload to `<name>/index.html` and invalidate. The
directory-index function described below handles the routing.

---

## Components

| Component | Identifier | Purpose |
|---|---|---|
| S3 bucket | `tejdux-legal-static` | Origin storage. Private — no public access, no website hosting enabled. |
| Origin Access Control | `E1YGJV27KRKXI1` | SigV4-signs CloudFront's origin requests so S3 can authorise them. |
| CloudFront distribution | `ESJ9LTY0C74G0` | Public edge. Terminates TLS, caches, serves both hostnames. |
| CloudFront Function | `tejdux-dir-index` | Viewer-request rewrite: appends `index.html` to directory paths. |
| ACM certificate | `…certificate/d38a2767-198a-491b-892c-3da19aed9ef0` | TLS for `www.tejdux.com` + `tejdux.com`. DNS-validated, auto-renewing. |
| Route 53 hosted zone | `Z0068206CHFI6QYONX9W` | DNS for `tejdux.com`. A-alias records point both names at CloudFront. |

---

## Request flow

```
                    ┌──────────────────────────────────────────┐
   Browser          │  https://www.tejdux.com/terms/           │
      │             └──────────────────────────────────────────┘
      │
      ▼
┌─────────────────┐   A-alias (www + apex)
│   Route 53      │──────────────────────────────► d2z08jsokinolt.cloudfront.net
│  Z0068206…      │   zone Z2FDTNDATAQYW2 (fixed CloudFront zone)
└─────────────────┘
      │
      ▼
┌──────────────────────────────────────────────────────────────┐
│  CloudFront  ESJ9LTY0C74G0                                   │
│                                                              │
│  1. TLS terminate — ACM cert, min TLSv1.2_2021               │
│  2. HTTP → HTTPS  — 301 (redirect-to-https)                  │
│  3. Viewer-request function  tejdux-dir-index                │
│         /terms/  ──rewrite──►  /terms/index.html             │
│  4. Cache lookup — CachingOptimized (658327ea-…)             │
│  5. Miss → sign origin request with OAC E1YGJV27KRKXI1       │
└──────────────────────────────────────────────────────────────┘
      │  SigV4-signed GetObject
      ▼
┌──────────────────────────────────────────────────────────────┐
│  S3  tejdux-legal-static  (PRIVATE)                          │
│                                                              │
│  Bucket policy allows s3:GetObject ONLY for                  │
│  principal cloudfront.amazonaws.com AND                      │
│  AWS:SourceArn = …distribution/ESJ9LTY0C74G0                 │
│                                                              │
│  terms/index.html                                            │
└──────────────────────────────────────────────────────────────┘
```

---

## Distribution configuration

| Setting | Value | Why |
|---|---|---|
| Aliases | `www.tejdux.com`, `tejdux.com` | Both resolve; neither redirects to the other. |
| Viewer protocol policy | `redirect-to-https` | Plain HTTP 301s to HTTPS. |
| Minimum TLS | `TLSv1.2_2021` | Rejects TLS 1.0/1.1. |
| Default root object | `index.html` | Applies to `/` only — see the gotcha below. |
| Cache policy | `658327ea-f89d-4fab-a63d-7e88639e58f6` | AWS managed *CachingOptimized*. |
| Compression | Enabled | Gzip/Brotli; the 27 KB HTML compresses well. |
| HTTP version | `http2and3` | HTTP/3 supported. |
| IPv6 | Enabled | |
| Price class | `PriceClass_100` | North America + Europe edges only; cheapest tier. |
| Origin type | REST endpoint (`…s3.us-east-1.amazonaws.com`) | Required for OAC. The *website* endpoint cannot be private. |

---

## Security model

**The bucket is never public.** All four S3 Block Public Access flags are on
(`BlockPublicAcls`, `IgnorePublicAcls`, `BlockPublicPolicy`, `RestrictPublicBuckets`), and the bucket
policy grants `s3:GetObject` only to the CloudFront service principal, further constrained by
`AWS:SourceArn` to this one distribution. A different CloudFront distribution in the same account
cannot read it.

Verified: `https://tejdux-legal-static.s3.us-east-1.amazonaws.com/terms/index.html` returns **403**,
while the same object through CloudFront returns **200**.

**TLS** is terminated at the edge with an ACM certificate covering both hostnames. Because it was
DNS-validated and the validation CNAMEs remain in the hosted zone, ACM renews it automatically —
do not delete those two `_…acm-validations.aws` records.

**Content is public by design.** These are legal pages meant to be read by anyone, including
platform reviewers. Nothing here is authenticated, and nothing non-public should ever be placed in
this bucket.

---

## The `/terms/` 403 — why the CloudFront Function exists

`DefaultRootObject` maps only the root path `/` to `index.html`. It does **not** apply to
subdirectories. With a REST origin, a request for `/terms/` therefore asks S3 for an object literally
named `terms/`, which does not exist — and because the bucket is private, S3 returns **403** rather
than 404.

The fix is a viewer-request CloudFront Function ([config/rewrite.js](config/rewrite.js)):

```js
if (uri.endsWith('/'))        request.uri = uri + 'index.html';
else if (!uri.includes('.'))  request.uri = uri + '/index.html';
```

This makes `/terms/`, `/terms`, and `/terms/index.html` all resolve. Any future page added as
`<name>/index.html` works with no further change.

> The alternative — an S3 *website* endpoint, which handles index documents natively — was rejected
> because website endpoints are HTTP-only and cannot be made private. That would have meant a public
> bucket and no HTTPS on the custom domain.

---

## Cost

Effectively negligible at current volume:

- **S3** — 27 KB stored; storage and request charges round to zero.
- **CloudFront** — the perpetual free tier covers 1 TB egress and 10M requests per month.
  `PriceClass_100` also limits edge locations to the cheapest regions.
- **CloudFront Functions** — free tier covers 2M invocations/month.
- **ACM** — public certificates are free.
- **Route 53** — $0.50/month per hosted zone, already incurred by the domain registration.

Expect roughly **$0.50/month**, essentially all hosted-zone cost. The main way this changes is
traffic far beyond the free tier, or adding distributions.

---

## What is *not* here

This covers the static site only. Not deployed to AWS:

- `InfluencerUI` and the per-domain React UIs
- `InfluencerWebExperience` (BFF) and the Spring domain services
- `agent_service` (Python/FastAPI)
- Postgres

Those still run locally via [../../docker-compose.yml](../../docker-compose.yml). The landing page
builder in [../LandingPageBuildPRD.md](../LandingPageBuildPRD.md) will need real application hosting
plus per-tenant domain provisioning — a substantially different architecture from this, and it
should get its own document rather than an extension of this one.

---

## Related

- [static-site-deployment-log.md](static-site-deployment-log.md) — the commands that built this
- [static-site-runbook.md](static-site-runbook.md) — how to update it
- [iam-policy.md](iam-policy.md) — permissions the deploy identity needs
- [../platform-app-registration.md](../platform-app-registration.md) — why the public ToS URL was needed
