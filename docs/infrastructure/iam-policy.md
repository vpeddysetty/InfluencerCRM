# IAM policy for the deploy identity

Identity: `arn:aws:iam::099933382956:user/tejdux`

## Background

The user was created with programmatic access but **no permissions policy**, which is the AWS
default. Credentials authenticated successfully — `sts get-caller-identity` returned the correct ARN
— while every substantive call failed with `AccessDenied ... no identity-based policy allows`.

A valid identity is not an authorised one. `get-caller-identity` never requires permissions, so it
is not a useful check that a deploy identity is ready.

The policy was then attached in two rounds: S3 + ACM + Route 53 first, CloudFront only after
`CreateOriginAccessControl` failed. The consolidated policy below is what should have been attached
once.

## Policy

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "StaticSiteBucket",
      "Effect": "Allow",
      "Action": [
        "s3:CreateBucket",
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject",
        "s3:ListBucket",
        "s3:PutBucketPolicy",
        "s3:GetBucketPolicy",
        "s3:PutPublicAccessBlock",
        "s3:GetPublicAccessBlock",
        "s3:GetBucketLocation"
      ],
      "Resource": [
        "arn:aws:s3:::tejdux-legal-static",
        "arn:aws:s3:::tejdux-legal-static/*"
      ]
    },
    {
      "Sid": "CloudFrontDistribution",
      "Effect": "Allow",
      "Action": [
        "cloudfront:CreateDistribution",
        "cloudfront:GetDistribution",
        "cloudfront:GetDistributionConfig",
        "cloudfront:UpdateDistribution",
        "cloudfront:ListDistributions",
        "cloudfront:CreateOriginAccessControl",
        "cloudfront:GetOriginAccessControl",
        "cloudfront:ListOriginAccessControls",
        "cloudfront:CreateFunction",
        "cloudfront:DescribeFunction",
        "cloudfront:PublishFunction",
        "cloudfront:UpdateFunction",
        "cloudfront:CreateInvalidation",
        "cloudfront:TagResource"
      ],
      "Resource": "*"
    },
    {
      "Sid": "Certificates",
      "Effect": "Allow",
      "Action": [
        "acm:RequestCertificate",
        "acm:DescribeCertificate",
        "acm:ListCertificates"
      ],
      "Resource": "*"
    },
    {
      "Sid": "Dns",
      "Effect": "Allow",
      "Action": [
        "route53:ListHostedZones",
        "route53:ListResourceRecordSets",
        "route53:ChangeResourceRecordSets",
        "route53:GetChange"
      ],
      "Resource": "*"
    }
  ]
}
```

## Scoping notes

**S3 is scoped to the one bucket.** Both ARN forms are required: the bare bucket ARN for
bucket-level actions (`ListBucket`, `PutBucketPolicy`), and the `/*` form for object actions
(`PutObject`, `GetObject`).

**CloudFront and ACM use `Resource: "*"`** because neither supports meaningful resource-level
constraints on the create actions — you cannot scope `CreateDistribution` to a distribution that does
not exist yet.

**Route 53 could be tightened.** `ChangeResourceRecordSets` accepts a zone-scoped resource:
`arn:aws:route53:::hostedzone/Z0068206CHFI6QYONX9W`. `ListHostedZones` cannot be scoped and must stay
`*`. Worth doing if this identity is ever used more broadly.

**No IAM permissions are granted**, deliberately — this identity cannot escalate its own privileges.
A consequence is that it cannot read its own policy (`iam:ListAttachedUserPolicies` is denied), so
verifying what it has requires a separate admin identity or the console.

## Credential handling

The access key lives only in `~/.aws/credentials` under profile `tejdux`, created with
`aws configure --profile tejdux`. It is not in this repo and must not be committed.

Recommended improvements for anything beyond initial setup:

- **Rotate the key**, and rotate on a schedule. Long-lived access keys on a workstation are the
  weakest link here.
- **Prefer IAM Identity Center (SSO)** over a long-lived key — short-lived credentials, no static
  secret at rest.
- **Move deployment to CI** with an OIDC-federated role, removing workstation credentials from the
  path entirely. This is the right end state once the site is updated more than occasionally.

Note also the existing security findings recorded for this project (bypassable auth, IDOR,
trust-all TLS, a committed keystore) — those concern the application tiers, not this static site, but
the same caution about committed credentials applies.
