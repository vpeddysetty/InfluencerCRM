# Deploy permissions

`arn:aws:iam::099933382956:user/tejdux` **cannot deploy this infrastructure.** It was scoped for the
static site only, and the preflight found five services missing entirely.

Verified 2026-08-09 by calling each API:

| Service | Result |
|---|---|
| ECS | `not authorized to perform: ecs:ListClusters` |
| ELB | `not authorized to perform: elasticloadbalancing:DescribeLoadBalancers` |
| IAM | `not authorized to perform: iam:ListRoles` |
| KMS | `not authorized to perform: kms:ListKeys` |
| CloudWatch Logs | `not authorized to perform: logs:DescribeLogGroups` |
| EC2, ECR, RDS, EFS, Secrets Manager, S3, CloudFront, Route 53 | reads succeed — **writes unproven** |

The user cannot read its own attached policies (`iam:ListAttachedUserPolicies` denied), so the last row
really is unproven rather than merely untested: a successful `Describe` says nothing about `Create`.

## Files

| File | Purpose |
|---|---|
| [deploy-policy.json](deploy-policy.json) | **The annotated source.** Every statement carries a `Comment` explaining why those actions and that scope. Not submittable — IAM rejects unknown keys |
| [deploy-policy-1-platform.json](deploy-policy-1-platform.json) | Generated. EC2/VPC, ECS, ECR, RDS, EFS, ELB, Logs, SSM. 5650 chars |
| [deploy-policy-2-identity-edge.json](deploy-policy-2-identity-edge.json) | Generated. IAM, KMS, Secrets Manager, S3, CloudFront, Route 53, ACM. 4421 chars |

**Two policies, not one, because a managed policy is limited to 6144 characters** (measured excluding
whitespace) and the full set is 10,033. The split is by concern rather than purely by size, so each is
independently reviewable and either can be detached alone.

## Granting it

Run as **an identity that can write IAM** — the root user or an admin. `tejdux` cannot grant itself
permissions, which is the point of the restriction.

```bash
ACCOUNT=099933382956

aws iam create-policy --policy-name InfluencrmDeployPlatform \
  --policy-document file://infrastructure/iam/deploy-policy-1-platform.json
aws iam create-policy --policy-name InfluencrmDeployIdentityEdge \
  --policy-document file://infrastructure/iam/deploy-policy-2-identity-edge.json

aws iam attach-user-policy --user-name tejdux \
  --policy-arn arn:aws:iam::${ACCOUNT}:policy/InfluencrmDeployPlatform
aws iam attach-user-policy --user-name tejdux \
  --policy-arn arn:aws:iam::${ACCOUNT}:policy/InfluencrmDeployIdentityEdge
```

**Attach as separate policies; do not edit the existing one.** The static site's permissions keep
working untouched, and both of these can be removed in two commands when the deployment is done.

Then re-run the preflight — it should now simulate rather than fall back to read probes:

```bash
AWS_PROFILE=tejdux AWS_REGION=us-east-1 ./infrastructure/scripts/preflight.sh
```

## Two things that are narrowed, deliberately

Almost everything else is `Resource: "*"` because Terraform creates resources whose ARNs do not exist
at plan time. These two are the exception, because a wildcard would be an account-wide escalation:

- **`iam:CreateRole` / `PutRolePolicy` / `AttachRolePolicy`** → only `role/influencrm-*`. Unrestricted,
  the holder could mint an administrator role and assume it.
- **`iam:PassRole`** → only `role/influencrm-*`, and only when passed to ECS. PassRole is how a role
  gets handed to a service that then acts with its permissions; it is the escalation path that matters.

**`acm:RequestCertificate` is deliberately absent.** A wildcard `*.tejdux.com` certificate must be
requested and DNS-validated as a deliberate act, not as a side effect of an apply.

**`tejdux-legal-static` is not in the S3 statement.** The S3 scope is `influencrm-*` only, so a mistake
in this configuration cannot touch the hand-built legal site serving live `/terms/` and `/privacy/`
pages.

## Also needed before the first apply

The preflight found these:

```bash
# Fargate needs this and it does not exist in this account yet. Without it the first
# CreateService fails with a message that does not name the cause.
aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com
```

**The only ACM certificate in us-east-1 is `www.tejdux.com`** — not a wildcard. The seven
micro-frontend subdomains (`app.`, `workflow.`, `campaigns.`, `creators.`, `commerce.`, `finance.`,
`content.`) each need coverage, so request a wildcard:

```bash
aws acm request-certificate --region us-east-1 \
  --domain-name '*.tejdux.com' --subject-alternative-names tejdux.com \
  --validation-method DNS
# then add the CNAME it returns to zone Z0068206CHFI6QYONX9W and wait for ISSUED
```

Until that exists, leave `static_site_certificate_arn` empty: each distribution serves on its own
`*.cloudfront.net` name, which is enough to prove the UIs load. The same applies to
`acm_certificate_arn` for the ALB — HTTP-only is fine for a smoke test, and **not** for anything
carrying a session cookie.
