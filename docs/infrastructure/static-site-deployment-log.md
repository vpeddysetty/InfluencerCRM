# Static site deployment log — 2026-08-05

Every command actually run to build the `www.tejdux.com` static site, in order, with real output.
This is a record of what happened, including two failures and their fixes — not an idealised script.
For a clean re-run, use [static-site-runbook.md](static-site-runbook.md).

Shell: Windows PowerShell 5.1. The AWS CLI was invoked by full path because it was installed after
the shell started and was not yet on `PATH`:

```powershell
$aws = "C:\Program Files\Amazon\AWSCLIV2\aws.exe"
```

All commands use `--profile tejdux`.

---

## 0. Preconditions

```powershell
& $aws --version
# aws-cli/2.36.17 Python/3.14.6 Windows/11 exe/AMD64

& $aws sts get-caller-identity --profile tejdux
# {
#     "UserId": "AIDARORD6NEWFJP2HKJ7U",
#     "Account": "099933382956",
#     "Arn": "arn:aws:iam::099933382956:user/tejdux"
# }
```

> **Failure 1 — no IAM permissions.** The first attempt at every call returned `AccessDenied`
> ("no identity-based policy allows..."). A newly created IAM user has zero permissions by default;
> valid credentials authenticate but authorise nothing. Resolved by attaching the policy in
> [iam-policy.md](iam-policy.md). `sts get-caller-identity` succeeded throughout because it never
> requires permissions — it is not proof of authorisation.

Confirm the hosted zone exists (created automatically when the domain was registered in Route 53):

```powershell
& $aws route53 list-hosted-zones --profile tejdux `
  --query "HostedZones[].{Name:Name,Id:Id}" --output json
# [ { "Name": "tejdux.com.", "Id": "/hostedzone/Z0068206CHFI6QYONX9W" } ]
```

---

## 1. Create the private bucket

```powershell
& $aws s3api create-bucket --bucket tejdux-legal-static --region us-east-1 --profile tejdux
# { "Location": "/tejdux-legal-static",
#   "BucketArn": "arn:aws:s3:::tejdux-legal-static" }

& $aws s3api put-public-access-block --bucket tejdux-legal-static --profile tejdux `
  --public-access-block-configuration `
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"
```

> For any region other than `us-east-1`, add
> `--create-bucket-configuration LocationConstraint=<region>`.

---

## 2. Upload the page

```powershell
& $aws s3 cp "c:\AI\InfluencerCRM\docs\legal\terms-of-service.html" `
  s3://tejdux-legal-static/terms/index.html `
  --content-type "text/html; charset=utf-8" `
  --cache-control "public, max-age=300" `
  --profile tejdux
# upload: docs\legal\terms-of-service.html to s3://tejdux-legal-static/terms/index.html
```

Uploaded as `terms/index.html` so the public path is `/terms/`. `max-age=300` is deliberately short
while the document is a pre-legal-review draft.

---

## 3. TLS certificate

Must be requested in `us-east-1` for CloudFront, regardless of bucket region.

```powershell
& $aws acm request-certificate `
  --domain-name www.tejdux.com `
  --subject-alternative-names tejdux.com `
  --validation-method DNS `
  --region us-east-1 --profile tejdux `
  --query CertificateArn --output text
# arn:aws:acm:us-east-1:099933382956:certificate/d38a2767-198a-491b-892c-3da19aed9ef0
```

Retrieve the DNS validation records:

```powershell
& $aws acm describe-certificate `
  --certificate-arn arn:aws:acm:us-east-1:099933382956:certificate/d38a2767-198a-491b-892c-3da19aed9ef0 `
  --region us-east-1 --profile tejdux `
  --query "Certificate.DomainValidationOptions[].{Domain:DomainName,Name:ResourceRecord.Name,Type:ResourceRecord.Type,Value:ResourceRecord.Value}" `
  --output json
```

| Domain | Record | Value |
|---|---|---|
| www.tejdux.com | `_294ae7c683aa97b08684402f8845c96d.www.tejdux.com.` | `_e65b571cc4c7a4bdef0816ee679184d6.jkddzztszm.acm-validations.aws.` |
| tejdux.com | `_0b0387576a65e42000659d73860b3147.tejdux.com.` | `_95973e67237d7b41ccfefbb606d9de2d.jkddzztszm.acm-validations.aws.` |

Write both into the zone using [config/acm-validation.json](config/acm-validation.json):

```powershell
& $aws route53 change-resource-record-sets `
  --hosted-zone-id Z0068206CHFI6QYONX9W `
  --change-batch "file://config/acm-validation.json" --profile tejdux
# { "Id": "/change/C09440861ZZBMUAQQU1C5", "Status": "PENDING" }

& $aws acm wait certificate-validated `
  --certificate-arn arn:aws:acm:us-east-1:099933382956:certificate/d38a2767-198a-491b-892c-3da19aed9ef0 `
  --region us-east-1 --profile tejdux
# Status: ISSUED
```

> **Leave these CNAMEs in place permanently.** ACM re-checks them to auto-renew. Deleting them
> breaks renewal, and the certificate silently expires.

---

## 4. CloudFront

### 4a. Origin Access Control

```powershell
& $aws cloudfront create-origin-access-control `
  --origin-access-control-config "Name=tejdux-legal-oac,Description=OAC for tejdux legal static,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=s3" `
  --profile tejdux --query "OriginAccessControl.Id" --output text
# E1YGJV27KRKXI1
```

> This call initially failed — the first IAM policy covered S3, ACM and Route 53 but omitted
> CloudFront entirely. Re-run after the policy was widened.

### 4b. Distribution

Config: [config/dist-config.json](config/dist-config.json)

```powershell
& $aws cloudfront create-distribution `
  --distribution-config "file://config/dist-config.json" --profile tejdux `
  --query "Distribution.{Id:Id,Domain:DomainName,Status:Status}" --output json
# { "Id": "ESJ9LTY0C74G0",
#   "Domain": "d2z08jsokinolt.cloudfront.net",
#   "Status": "InProgress" }
```

### 4c. Bucket policy

Now that the distribution ID exists, grant it read access:
[config/bucket-policy.json](config/bucket-policy.json)

```powershell
& $aws s3api put-bucket-policy --bucket tejdux-legal-static `
  --policy "file://config/bucket-policy.json" --profile tejdux
```

---

## 5. DNS

[config/r53-alias.json](config/r53-alias.json) — A-alias records for both hostnames. Alias target
zone `Z2FDTNDATAQYW2` is the fixed, global CloudFront hosted zone ID (identical in every account).

```powershell
& $aws route53 change-resource-record-sets `
  --hosted-zone-id Z0068206CHFI6QYONX9W `
  --change-batch "file://config/r53-alias.json" --profile tejdux
# { "Id": "/change/C011114322NKREN4X0E0R", "Status": "PENDING" }

& $aws cloudfront wait distribution-deployed --id ESJ9LTY0C74G0 --profile tejdux
# Status: Deployed
```

---

## 6. Verification — and the second failure

First check returned **403**:

```powershell
curl.exe -s -o NUL -D - "https://www.tejdux.com/terms/"
# HTTP/1.1 403 Forbidden
# Server: AmazonS3
# X-Cache: Error from cloudfront
```

Diagnosis, in order:

1. Object exists — `s3 ls` showed `terms/index.html`, 27,643 bytes.
2. Bucket policy applied correctly — `get-bucket-policy` matched the intended JSON.
3. OAC attached — `get-distribution-config` confirmed `OriginAccessControlId: E1YGJV27KRKXI1`.
4. Cache invalidated — 403 persisted, so not stale caching.
5. **Explicit path worked.** This isolated it:

```powershell
curl.exe -s -o NUL -D - "https://www.tejdux.com/terms/index.html"   # HTTP 200 ✅
curl.exe -s -o NUL -D - "https://www.tejdux.com/terms/"             # HTTP 403 ❌
```

> **Failure 2 — `DefaultRootObject` does not apply to subdirectories.** It maps only `/`. A request
> for `/terms/` asked S3 for an object named `terms/`, which does not exist; a private bucket returns
> 403 rather than 404 (it will not confirm absence to an unauthorised caller). Fixed with a
> viewer-request CloudFront Function.

### Fix — directory index function

[config/rewrite.js](config/rewrite.js)

```powershell
& $aws cloudfront create-function --name tejdux-dir-index `
  --function-config "Comment=Rewrite directory URIs to index.html,Runtime=cloudfront-js-2.0" `
  --function-code "fileb://config/rewrite.js" --profile tejdux
# Stage: DEVELOPMENT

$etag = & $aws cloudfront describe-function --name tejdux-dir-index `
  --profile tejdux --query "ETag" --output text
& $aws cloudfront publish-function --name tejdux-dir-index --if-match $etag --profile tejdux
# Stage: LIVE
```

Attach to the default cache behaviour. `update-distribution` requires the **full** config plus the
current ETag, so fetch, modify, and re-submit:

```powershell
& $aws cloudfront get-distribution-config --id ESJ9LTY0C74G0 --profile tejdux --output json `
  | Out-File -FilePath current-dist.json -Encoding utf8

$j = Get-Content current-dist.json -Raw | ConvertFrom-Json
$cfg = $j.DistributionConfig
$cfg.DefaultCacheBehavior.FunctionAssociations = [PSCustomObject]@{
  Quantity = 1
  Items = @([PSCustomObject]@{
    FunctionARN = "arn:aws:cloudfront::099933382956:function/tejdux-dir-index"
    EventType   = "viewer-request"
  })
}
$cfg | ConvertTo-Json -Depth 30 | Out-File updated-dist.json -Encoding utf8
```

> **PowerShell gotcha.** `Out-File -Encoding utf8` writes a **UTF-8 BOM** in PowerShell 5.1, and the
> AWS CLI rejects it: `Error parsing parameter '--distribution-config': Expected: '=', received: '﻿'`.
> Strip it explicitly:

```powershell
$content = (Get-Content updated-dist.json -Raw) -replace "^\uFEFF",""
[System.IO.File]::WriteAllText("updated-dist-nobom.json", $content,
  (New-Object System.Text.UTF8Encoding($false)))
```

```powershell
& $aws cloudfront update-distribution --id ESJ9LTY0C74G0 `
  --if-match E23ZP02F085DFQ `
  --distribution-config "file://updated-dist-nobom.json" --profile tejdux
# { "Status": "InProgress", "Funcs": 1 }

& $aws cloudfront wait distribution-deployed --id ESJ9LTY0C74G0 --profile tejdux

$inv = & $aws cloudfront create-invalidation --distribution-id ESJ9LTY0C74G0 `
  --paths "/*" --profile tejdux --query "Invalidation.Id" --output text
& $aws cloudfront wait invalidation-completed --distribution-id ESJ9LTY0C74G0 `
  --id $inv --profile tejdux
```

---

## Final verification

```powershell
curl.exe -s -o NUL -w "HTTP %{http_code} type=%{content_type} bytes=%{size_download}\n" `
  "https://www.tejdux.com/terms/"
# HTTP 200 type=text/html; charset=utf-8 bytes=27643

curl.exe -s -o NUL -w "HTTP %{http_code}\n" "https://tejdux.com/terms/"
# HTTP 200

curl.exe -s -o NUL -w "HTTP %{http_code} redirect=%{redirect_url}\n" "http://www.tejdux.com/terms/"
# HTTP 301 redirect=https://www.tejdux.com/terms/

curl.exe -s -o NUL -w "HTTP %{http_code}\n" `
  "https://tejdux-legal-static.s3.us-east-1.amazonaws.com/terms/index.html"
# HTTP 403   ← bucket is private; only CloudFront can read it

curl.exe -s -o NUL -w "ssl_verify=%{ssl_verify_result}\n" "https://www.tejdux.com/terms/"
# ssl_verify=0   ← valid chain
```

Content check — 19 `<h2>` sections, correct `<title>`, draft banner and the bulk-PII and paid-ads
clauses all present in the served HTML.

| Check | Result |
|---|---|
| `https://www.tejdux.com/terms/` | 200 ✅ |
| `https://tejdux.com/terms/` (apex) | 200 ✅ |
| `http://` → HTTPS | 301 ✅ |
| Direct S3 URL | 403 ✅ (private, as intended) |
| TLS chain | valid ✅ |

---

## Second deployment — Privacy Policy, same day

Adding the second page required **no infrastructure changes**, which validated the routing design:
the `tejdux-dir-index` function already handled the new path.

```powershell
& $aws s3 cp "c:\AI\InfluencerCRM\docs\legal\privacy-policy.html" `
  s3://tejdux-legal-static/privacy/index.html `
  --content-type "text/html; charset=utf-8" `
  --cache-control "public, max-age=300" `
  --profile tejdux
# upload: docs\legal\privacy-policy.html to s3://tejdux-legal-static/privacy/index.html

$inv = & $aws cloudfront create-invalidation --distribution-id ESJ9LTY0C74G0 `
  --paths "/privacy/*" --profile tejdux --query "Invalidation.Id" --output text
& $aws cloudfront wait invalidation-completed --distribution-id ESJ9LTY0C74G0 `
  --id $inv --profile tejdux
# I1ZYXPLOCDYYS5FA6DHQJN4OMX
```

Verified first attempt — no 403 this time:

```
https://www.tejdux.com/privacy/    HTTP 200  23,769 bytes
https://tejdux.com/privacy/        HTTP 200
https://www.tejdux.com/privacy     HTTP 200   ← rewrite function handled it
http://www.tejdux.com/privacy/     HTTP 301 -> https://
```

### Terms redeployed for the cross-link

The Terms referenced a `[Privacy Policy — LINK]` placeholder. With the URL now live, the placeholder
was replaced with a real `<a href="/privacy/">` link, plus a footer link, and the page redeployed:

```powershell
& $aws s3 cp "c:\AI\InfluencerCRM\docs\legal\terms-of-service.html" `
  s3://tejdux-legal-static/terms/index.html `
  --content-type "text/html; charset=utf-8" --cache-control "public, max-age=300" --profile tejdux

$inv = & $aws cloudfront create-invalidation --distribution-id ESJ9LTY0C74G0 `
  --paths "/terms/*" --profile tejdux --query "Invalidation.Id" --output text
& $aws cloudfront wait invalidation-completed --distribution-id ESJ9LTY0C74G0 --id $inv --profile tejdux
# I51UZ3G44L27L6IYH4LCRLB62E
```

Final state:

```powershell
& $aws s3 ls s3://tejdux-legal-static/ --recursive --profile tejdux
# 2026-08-05 20:57:15      23769 privacy/index.html
# 2026-08-05 20:58:34      27678 terms/index.html
```

> **Note on cross-links.** Both pages use root-relative hrefs (`/privacy/`, `/terms/`) rather than
> absolute `https://www.tejdux.com/...`. This keeps links working on the apex domain and on the
> CloudFront domain without redirecting the visitor to a different hostname.

---

## Lessons for the next deployment

1. **A new IAM user has no permissions.** Attach the policy before starting; `get-caller-identity`
   succeeding proves nothing about authorisation.
2. **Grant all four services up front** — S3, ACM, Route 53 *and* CloudFront. Discovering the gap
   halfway through costs a round trip.
3. **`DefaultRootObject` only covers `/`.** Any REST-origin site with subdirectories needs the
   rewrite function from the outset.
4. **Order matters.** The bucket policy needs the distribution ID, so the distribution must exist
   first. This inevitably caches a 403 — invalidate after applying the policy.
5. **PowerShell 5.1 `Out-File -Encoding utf8` emits a BOM** that the AWS CLI rejects. Use
   `[System.IO.File]::WriteAllText` with `UTF8Encoding($false)`.
