# Static site runbook

Day-to-day operations for `www.tejdux.com`. Architecture is in
[static-site-architecture.md](static-site-architecture.md); the original build is in
[static-site-deployment-log.md](static-site-deployment-log.md).

The AWS CLI may not be on `PATH` in a fresh shell. Either reopen the terminal after installation or
call it by full path:

```powershell
$aws = "C:\Program Files\Amazon\AWSCLIV2\aws.exe"
```

Quick reference:

| | |
|---|---|
| Profile | `tejdux` |
| Bucket | `tejdux-legal-static` |
| Distribution | `ESJ9LTY0C74G0` |
| Hosted zone | `Z0068206CHFI6QYONX9W` |

---

## Publish an updated Terms of Service

The most common operation — e.g. after legal review fills in the placeholders.

```powershell
& $aws s3 cp "c:\AI\InfluencerCRM\docs\legal\terms-of-service.html" `
  s3://tejdux-legal-static/terms/index.html `
  --content-type "text/html; charset=utf-8" `
  --cache-control "public, max-age=300" `
  --profile tejdux

& $aws cloudfront create-invalidation --distribution-id ESJ9LTY0C74G0 `
  --paths "/terms/*" --profile tejdux
```

Invalidation takes 1–3 minutes. Verify:

```powershell
curl.exe -s -o NUL -w "HTTP %{http_code} bytes=%{size_download}\n" "https://www.tejdux.com/terms/"
```

> Always pass `--content-type` explicitly. Without it S3 guesses, and a wrong type makes the browser
> download the file rather than render it.

Once the document is final, consider raising `--cache-control` to `max-age=3600` or higher — the
short TTL exists only because the current version is a draft.

---

## Publish an updated Privacy Policy

```powershell
& $aws s3 cp "c:\AI\InfluencerCRM\docs\legal\privacy-policy.html" `
  s3://tejdux-legal-static/privacy/index.html `
  --content-type "text/html; charset=utf-8" `
  --cache-control "public, max-age=300" `
  --profile tejdux

& $aws cloudfront create-invalidation --distribution-id ESJ9LTY0C74G0 `
  --paths "/privacy/*" --profile tejdux
```

---

## Add a new page

No infrastructure changes needed. The `tejdux-dir-index` function maps any `<name>/index.html` to
`/<name>/` automatically — this was confirmed when the Privacy Policy was added and worked first
attempt.

```powershell
& $aws s3 cp "<source>.html" s3://tejdux-legal-static/<name>/index.html `
  --content-type "text/html; charset=utf-8" `
  --cache-control "public, max-age=300" `
  --profile tejdux

& $aws cloudfront create-invalidation --distribution-id ESJ9LTY0C74G0 `
  --paths "/<name>/*" --profile tejdux
```

Then live at `https://www.tejdux.com/<name>/`.

> **Use root-relative links between pages** — `href="/terms/"`, not the full
> `https://www.tejdux.com/terms/`. Absolute links break on the apex domain and force an unnecessary
> hostname change.

Still outstanding: a **Data Processing Addendum** and a **sub-processor list**, both referenced by
the Terms and the Privacy Policy but not yet written.

---

## Add a root landing page

Currently `https://www.tejdux.com/` returns 403 — nothing is published at the root. `DefaultRootObject`
is already set to `index.html`, so publishing one is enough:

```powershell
& $aws s3 cp index.html s3://tejdux-legal-static/index.html `
  --content-type "text/html; charset=utf-8" --profile tejdux

& $aws cloudfront create-invalidation --distribution-id ESJ9LTY0C74G0 --paths "/" --profile tejdux
```

---

## Inspect current state

```powershell
# Distribution status and settings
& $aws cloudfront get-distribution --id ESJ9LTY0C74G0 --profile tejdux `
  --query "Distribution.{Status:Status,Domain:DomainName,Aliases:DistributionConfig.Aliases.Items}"

# What is published
& $aws s3 ls s3://tejdux-legal-static/ --recursive --profile tejdux

# Certificate status and expiry
& $aws acm describe-certificate `
  --certificate-arn arn:aws:acm:us-east-1:099933382956:certificate/d38a2767-198a-491b-892c-3da19aed9ef0 `
  --region us-east-1 --profile tejdux `
  --query "Certificate.{Status:Status,NotAfter:NotAfter,Renewal:RenewalEligibility}"

# DNS records
& $aws route53 list-resource-record-sets --hosted-zone-id Z0068206CHFI6QYONX9W --profile tejdux `
  --query "ResourceRecordSets[].{Name:Name,Type:Type}" --output table
```

---

## Health check

```powershell
curl.exe -s -o NUL -w "terms www:    HTTP %{http_code}\n" "https://www.tejdux.com/terms/"
curl.exe -s -o NUL -w "terms apex:   HTTP %{http_code}\n" "https://tejdux.com/terms/"
curl.exe -s -o NUL -w "privacy www:  HTTP %{http_code}\n" "https://www.tejdux.com/privacy/"
curl.exe -s -o NUL -w "privacy apex: HTTP %{http_code}\n" "https://tejdux.com/privacy/"
curl.exe -s -o NUL -w "http->https:  HTTP %{http_code} -> %{redirect_url}\n" "http://www.tejdux.com/terms/"
curl.exe -s -o NUL -w "s3 direct:    HTTP %{http_code} (expect 403)\n" `
  "https://tejdux-legal-static.s3.us-east-1.amazonaws.com/terms/index.html"
curl.exe -s -o NUL -w "tls:          verify=%{ssl_verify_result} (expect 0)\n" "https://www.tejdux.com/terms/"
```

Expected: 200, 200, 200, 200, 301, 403, 0.

---

## Troubleshooting

### 403 on a directory path like `/newpage/`

The rewrite function should prevent this. Check the object is at `newpage/index.html`, not
`newpage.html`, and that the function is still attached:

```powershell
& $aws cloudfront get-distribution-config --id ESJ9LTY0C74G0 --profile tejdux `
  --query "DistributionConfig.DefaultCacheBehavior.FunctionAssociations"
```

### 403 on every path

Check the bucket policy still names the current distribution ID:

```powershell
& $aws s3api get-bucket-policy --bucket tejdux-legal-static --profile tejdux --query Policy --output text
```

The `AWS:SourceArn` condition must contain `ESJ9LTY0C74G0`. A mismatch — for instance after the
distribution was recreated — denies every origin fetch.

### Stale content after upload

CloudFront caches. Invalidate the path; `X-Cache: Hit from cloudfront` in the response headers
confirms a cached copy was served.

### Certificate approaching expiry

ACM auto-renews DNS-validated certificates, but **only while the validation CNAMEs remain in the
hosted zone**. Both `_…acm-validations.aws` records must stay. If renewal stalls, confirm they are
still present.

### AWS CLI parse error mentioning an odd leading character

A UTF-8 BOM in a JSON file written by PowerShell. See the note in
[config/README.md](config/README.md).

---

## Teardown

In order — CloudFront must be disabled and fully deployed before it can be deleted, which takes
15–20 minutes.

```powershell
# 1. Disable the distribution (fetch config, set Enabled=false, update), then:
& $aws cloudfront wait distribution-deployed --id ESJ9LTY0C74G0 --profile tejdux
& $aws cloudfront delete-distribution --id ESJ9LTY0C74G0 --if-match <etag> --profile tejdux

# 2. Route 53 alias records — DELETE via change-resource-record-sets

# 3. Bucket
& $aws s3 rm s3://tejdux-legal-static --recursive --profile tejdux
& $aws s3api delete-bucket --bucket tejdux-legal-static --profile tejdux

# 4. Function and OAC
& $aws cloudfront delete-function --name tejdux-dir-index --if-match <etag> --profile tejdux
& $aws cloudfront delete-origin-access-control --id E1YGJV27KRKXI1 --if-match <etag> --profile tejdux
```

Leave the ACM certificate and hosted zone unless the domain itself is being retired — the
certificate is free, and deleting the zone breaks all DNS for `tejdux.com`.

> Do not run teardown while the Terms or Privacy URLs are submitted to a platform app review. Meta
> and TikTok re-check both URLs during review, and a dead link fails the submission.
