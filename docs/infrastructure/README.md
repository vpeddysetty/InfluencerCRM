# Infrastructure

AWS infrastructure for InfluencerCRM.

| Document | Covers |
|---|---|
| [static-site-architecture.md](static-site-architecture.md) | Deployed architecture for the public static site (`www.tejdux.com`) — components, request flow, security model, cost |
| [static-site-deployment-log.md](static-site-deployment-log.md) | Every command actually run to build it, in order, including the two failures and their fixes |
| [static-site-runbook.md](static-site-runbook.md) | Day-to-day operations — publishing updates, adding pages, rotating content, teardown |
| [iam-policy.md](iam-policy.md) | The IAM policy the deploy identity requires |
| [config/](config/) | The exact JSON/JS payloads applied to AWS |

## Scope

These documents cover **only** the static marketing/legal site. The application tiers — React UI,
Spring BFF, Spring domain services, the Python agent service, and Postgres — are not yet deployed to
AWS and are not described here. When they are, add a sibling document rather than extending these.

## Current deployed state

| Resource | Identifier |
|---|---|
| AWS account | `099933382956` |
| Deploy identity | `arn:aws:iam::099933382956:user/tejdux` |
| S3 bucket | `tejdux-legal-static` (us-east-1, private) |
| CloudFront distribution | `ESJ9LTY0C74G0` → `d2z08jsokinolt.cloudfront.net` |
| Origin Access Control | `E1YGJV27KRKXI1` |
| CloudFront Function | `tejdux-dir-index` (viewer-request) |
| ACM certificate | `arn:aws:acm:us-east-1:099933382956:certificate/d38a2767-198a-491b-892c-3da19aed9ef0` |
| Route 53 hosted zone | `Z0068206CHFI6QYONX9W` (tejdux.com) |

## Published pages

| URL | S3 key | Source |
|---|---|---|
| https://www.tejdux.com/terms/ | `terms/index.html` | [../legal/terms-of-service.html](../legal/terms-of-service.html) |
| https://www.tejdux.com/privacy/ | `privacy/index.html` | [../legal/privacy-policy.html](../legal/privacy-policy.html) |

Both resolve on the apex (`tejdux.com`) as well, and both cross-link to each other.
The site root `/` is currently unpublished and returns 403 — see the runbook to add a landing page.

Deployed 2026-08-05.
