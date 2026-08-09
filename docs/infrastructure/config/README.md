# Deployment config payloads

The exact JSON/JS applied to AWS on 2026-08-05. Kept so the stack can be rebuilt or audited without
reverse-engineering it from the console.

| File | Applied with |
|---|---|
| [dist-config.json](dist-config.json) | `cloudfront create-distribution --distribution-config` |
| [bucket-policy.json](bucket-policy.json) | `s3api put-bucket-policy --policy` |
| [acm-validation.json](acm-validation.json) | `route53 change-resource-record-sets --change-batch` |
| [r53-alias.json](r53-alias.json) | `route53 change-resource-record-sets --change-batch` |
| [rewrite.js](rewrite.js) | `cloudfront create-function --function-code fileb://` |

## Notes

**`dist-config.json`** here includes the `FunctionAssociations` block. The version originally posted
to `create-distribution` did not — the function was written later, in response to the `/terms/` 403,
and attached via `update-distribution`. This file reflects the **current deployed state**, which is
what you would want when rebuilding.

**`bucket-policy.json`** hardcodes distribution `ESJ9LTY0C74G0` in the `AWS:SourceArn` condition.
Rebuilding from scratch produces a new distribution ID — update this file before applying it, or the
origin will 403.

**Hardcoded identifiers** throughout: account `099933382956`, bucket `tejdux-legal-static`, OAC
`E1YGJV27KRKXI1`, certificate ARN, hosted zone `Z0068206CHFI6QYONX9W`. These are environment-specific.
The one value that is *not* environment-specific is `Z2FDTNDATAQYW2` in `r53-alias.json` — that is
the global CloudFront alias-target zone ID, identical in every AWS account.

**Not secrets.** Resource IDs and ARNs are safe to commit. No credentials appear in these files, and
none should be added — the deploy identity is configured locally via `aws configure --profile tejdux`
and lives in `~/.aws/credentials`, outside this repo.

**BOM warning.** If you regenerate any of these from PowerShell, do not use `Out-File -Encoding utf8`
— PowerShell 5.1 writes a UTF-8 BOM that the AWS CLI rejects with a parse error. Use
`[System.IO.File]::WriteAllText(path, content, (New-Object System.Text.UTF8Encoding($false)))`.
