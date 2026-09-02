# SES setup — verification, deliverability, sandbox exit (`OP-06`)

**Status as of 2026-08-19:** SES is in the **sandbox**, and **no identity is verified**. Nothing can
send. This blocks invitations, expiry warnings, dunning, and both scheduled agents.

**Owner:** you — every step needs console access or a DNS change.
**Lead time:** DNS propagation is minutes; the sandbox-exit review is **~24 hours**. Start step 4 today.

---

## What is already true

Checked against the live account (`099933382956`) and hosted zone `Z0068206CHFI6QYONX9W`:

| Fact | Value | Consequence |
|---|---|---|
| `SendingEnabled` | `true` | The account is not suspended |
| `ProductionAccessEnabled` | **`false`** | **Sandbox.** Mail only to *verified* addresses; 200/day, 1/sec |
| Verified identities | **none** | `SesEmailSender` would fail every send |
| MX | `1 smtp.google.com` | **Google Workspace handles inbound mail.** Do not touch |
| DKIM | `google._domainkey` published | Google's signing key, for mail *Google* sends |
| DMARC | `p=none; rua=mailto:privacy@tejdux.com; fo=1` | Present, in monitoring mode |
| SPF | **absent** | **Gap.** No `v=spf1` record at the apex |

The application side is ready: `OP-01` fixed the duplicated `web-experience.email.from`, so the
sender resolves to `no-reply@tejdux.com` and `SesEmailSender.isConfigured()` can return true once
credentials exist.

---

## The two traps

**1. `vijay.peddysetty@kmpsglobal.com` is not a `tejdux.com` address.** In the sandbox, SES rejects
mail to any unverified address. The daily digest agents send *there*, so until production access is
granted they fail silently — the send is rejected, not queued. Either verify that address as an
identity (step 1b) or finish step 4 before relying on the digests.

**2. Adding SES must not break Google's inbound mail.** The MX record belongs to Google Workspace.
SES verification adds **CNAME** records for DKIM and, optionally, a *separate* MAIL FROM subdomain.
Neither touches MX. **Do not add a second MX record**, and when you add SPF, publish **one** record
authorising both senders — multiple `v=spf1` TXT records at the same name are a permanent error.

---

## Steps

### 1. Verify the domain identity (Easy DKIM)

Console → SES → Identities → Create identity → Domain → `tejdux.com`, Easy DKIM, RSA_2048, and leave
"Publish DNS records to Route 53" **checked** (the zone is in the same account, so SES writes the
three CNAMEs itself).

Or by CLI:

```bash
export AWS_PROFILE=tejdux AWS_REGION=us-east-1
aws sesv2 create-email-identity --email-identity tejdux.com \
  --dkim-signing-attributes NextSigningKeyLength=RSA_2048

aws sesv2 get-email-identity --email-identity tejdux.com \
  --query 'DkimAttributes.Tokens' --output text
```

Each token becomes `<token>._domainkey.tejdux.com CNAME <token>.dkim.amazonses.com`.

These sit alongside `google._domainkey` — DKIM selectors are namespaced, so both coexist. Verification
usually completes within minutes.

### 1b. (Sandbox only) verify the digest recipient

```bash
aws sesv2 create-email-identity --email-identity vijay.peddysetty@kmpsglobal.com
```

Confirm the link in that inbox. **Delete this identity once step 4 is granted** — it is a sandbox
workaround, not part of the design.

### 2. Publish SPF — one record, both senders

There is no SPF record today. Google needs one regardless, and SES needs one to align. Publish a
single TXT at the apex:

```
tejdux.com.  TXT  "v=spf1 include:_spf.google.com include:amazonses.com ~all"
```

`~all` (softfail) rather than `-all` while you confirm nothing legitimate is missing. Tighten to
`-all` after a week of clean DMARC reports.

**Check first that no `v=spf1` TXT exists** — a second one invalidates both:

```bash
aws route53 list-resource-record-sets --hosted-zone-id Z0068206CHFI6QYONX9W \
  --query "ResourceRecordSets[?Type=='TXT']"
```

### 3. Leave DMARC at `p=none` for now

`_dmarc.tejdux.com` already reads `v=DMARC1; p=none; rua=mailto:privacy@tejdux.com; fo=1`. That is the
right setting *today*: it collects reports without quarantining anything while a new sender (SES) is
introduced. Move to `p=quarantine` only after reports show SES mail aligning.

Note `rua` points at `privacy@tejdux.com` — one of the four inboxes the legal pages commit to, and one
the `AG-02` digest will read. Aggregate reports will land there daily.

### 4. Request production access — do this first, it has the longest clock

Console → SES → Account dashboard → Request production access. Or by CLI, supplying a use-case
description that names the mail as transactional, states that recipients opted in at signup, and
mentions bounce/complaint monitoring — those three points are what the review looks for.

```bash
aws sesv2 put-account-details \
  --production-access-enabled \
  --mail-type TRANSACTIONAL \
  --website-url https://tejdux.com \
  --contact-language EN \
  --use-case-description "Transactional email for an influencer-marketing SaaS: team invitations, hosting-expiry warnings, subscription and payment notifications, and internal operational digests. Recipients are account holders who signed up and colleagues they invited. No marketing or bulk mail. Bounces and complaints are monitored through a configuration set."
```

Review is typically ~24 hours. Approval lifts the 200/day cap and the verified-recipient restriction.

#### The first request was DENIED (2026-09-01, case `178750875200560`)

`get-account` shows why, or as close as AWS will let anyone get: `Details` carries `MailType`,
`WebsiteURL` and `ContactLanguage` and **no `UseCaseDescription` field at all.** The free-text answer
the review actually reads was never submitted, so there was nothing to evaluate. The denial letter is
boilerplate and names no cause, which is normal — AWS does not disclose criteria.

Nothing in the account's own signals suggests reputation: `EnforcementStatus: HEALTHY`, 42 sends /
1 bounce / 0 complaints over 30 days, 0 suppressed destinations, and the `influencrm-prod`
configuration set live with an enabled BOUNCE/COMPLAINT/REJECT/RENDERING_FAILURE destination.

**The CLI cannot resubmit.** `put-account-details` returns `ConflictException` while a DENIED case is
open. Use **Console → SES → Account dashboard → Request production access**, or reply to case
`178750875200560`. The `--website-url` also wanted correcting from `https://www.tejdux.com` to the
apex, which the console form asks for anyway.

Text to paste, with every figure verified against the account rather than asserted:

> InfluenCRM (tejdux.com) is an influencer-marketing CRM for small brands. All mail is transactional
> and sent only to people who created an account or were invited by an account holder: team
> invitations, landing-page hosting-expiry warnings (30/7/1 days), subscription and payment
> notifications, password and sign-in mail, and a creator-collaboration acknowledgement. There is no
> marketing, newsletter or bulk mail of any kind, and no purchased or imported recipient lists.
>
> Consent is captured and enforced server-side at signup and recorded with an immutable snapshot of
> the exact terms text the user accepted, retained under S3 Object Lock. Users can request deletion
> of their data through a published address, which is received by SES and processed as a tracked
> request.
>
> Bounces and complaints are monitored through the SES configuration set 'influencrm-prod', which has
> an enabled event destination for BOUNCE, COMPLAINT, REJECT and RENDERING_FAILURE. Over the last 30
> days the account sent 42 messages with 1 bounce, 0 complaints and 0 suppressed destinations, and
> enforcement status is HEALTHY. Bounced addresses are removed from further sending and complaints
> are treated as an immediate opt-out.
>
> Privacy policy: https://tejdux.com/privacy/ — sub-processors: https://tejdux.com/subprocessors/ —
> DPA: https://tejdux.com/dpa/

All four URLs return 200, checked 2026-09-02. Refresh the send/bounce figures before submitting if
much time has passed — a stale number a reviewer can contradict is worse than no number.

#### `inbox.tejdux.com` shows `dkim: FAILED`. Do NOT delete it to tidy that up.

It is the deletion-request intake (`PR-37`), managed by `deletion-intake.tf`, with a live MX to
`inbound-smtp.us-east-1.amazonaws.com` and the active `influencrm-prod-inbound` rule set. The flag is
cosmetic: all three DKIM CNAMEs resolve correctly to `dkim.amazonses.com`, and DKIM signs OUTBOUND
mail while this domain only ever receives. SES marked it failed once and stopped retrying;
re-enabling signing does not clear it. Removing the identity would break a GDPR obligation to
silence a warning that costs nothing.

### 5. Create a configuration set before the first real send

Without one, bounces and complaints are invisible, and repeated sends to a dead address damage the
sending reputation until SES throttles the account.

```bash
aws sesv2 create-configuration-set --configuration-set-name influencrm-transactional
```

Then add a CloudWatch event destination for `BOUNCE`, `COMPLAINT`, `REJECT` and `DELIVERY`, and set
`AWS_SES_CONFIGURATION_SET=influencrm-transactional`.

### 6. Point the application at SES

The credentials are already provisioned as Secrets Manager entries (`ses-access-key-id`,
`ses-secret-access-key`) and reach the container via `/run/influencrm/platform.env`. Set:

```
WEBE_EMAIL_PROVIDER=ses
WEBE_EMAIL_FROM=no-reply@tejdux.com
AWS_SES_REGION=us-east-1
AWS_SES_CONFIGURATION_SET=influencrm-transactional
```

**The IAM user behind those keys should be restricted to `ses:SendEmail` on the one verified
identity.** These credentials can send mail as your domain; nothing else should be reachable with
them.

---

## Verification

1. **Identity verified:**
   ```bash
   aws sesv2 get-email-identity --email-identity tejdux.com \
     --query '{Verified:VerifiedForSendingStatus,DkimStatus:DkimAttributes.Status}'
   ```
   Expect `Verified: true`, `DkimStatus: SUCCESS`.

2. **Production access granted:**
   ```bash
   aws sesv2 get-account --query '{Prod:ProductionAccessEnabled,Quota:SendQuota}'
   ```
   Expect `Prod: true` and a raised `Max24HourSend`.

3. **Startup is clean:** restart the BFF and confirm the log does **not** contain
   `NO MAIL WILL BE SENT`. That line means `isConfigured()` returned false — the exact symptom
   `OP-01` fixed.

4. **A real send lands, and authenticates.** Send an invitation to an external address you control,
   then check the received headers show `spf=pass`, `dkim=pass` and `dmarc=pass` (in Gmail: ⋮ → Show
   original). **Delivery alone is not success** — mail that lands in spam is a failure this check
   catches and a user never reports.

5. **Bounce handling works:** send to `bounce@simulator.amazonses.com` and confirm the event appears
   in the configuration set's CloudWatch metrics.

---

## Why this is `P0`

It fails **silently**. Every other component reports its own breakage — the DAO refuses to start
without a keystore, the billing webhook 503s without a signing secret, a marketplace provider refuses
to connect without a KEK. Email is the exception: `EmailPort.send()` is contractually forbidden from
throwing, so a misconfigured sender returns `Result(sent=false, ...)` and the caller carries on. The
invitation flow then tells a user their colleague was invited when nothing was sent.

That is why `OP-01` added a test rather than only a fix, and why step 4 above checks the *headers*
rather than merely that a message arrived.

---

## Receiving: the deletion-request intake (`B1`)

Everything above is about **sending**. This section is about the one address the platform
**receives** on, and the single manual step the automated deletion workflow depends on.

### Why a subdomain

SES receiving requires the **MX record of the receiving domain** to point at SES. `tejdux.com`'s MX
points at Google Workspace and carries all real business mail — repointing it would break every
mailbox in order to automate one address.

So SES receives on **`inbox.tejdux.com`**, which had no mail before this, and the apex is left
alone. `deletion-intake.tf` deliberately does not manage the apex MX at all: a resource for it would
take ownership of Google's mail routing, and a later destroy could remove it.

### The manual step

**Terraform cannot do this part.** Gmail forwarding needs Workspace admin API credentials — a new
long-lived secret with broad mailbox scope — to save one manual step, which is not a good trade.

In Google Workspace, as an admin:

1. Open Gmail settings for `privacy@tejdux.com` → **Forwarding and POP/IMAP**
2. Add a forwarding address: **`deletion@inbox.tejdux.com`**
3. Google sends a confirmation code to that address. It lands in the intake bucket, not a mailbox:

   ```bash
   aws s3 ls s3://influencrm-prod-deletion-intake-099933382956/inbound/ --profile tejdux
   aws s3 cp s3://influencrm-prod-deletion-intake-099933382956/inbound/<key> - --profile tejdux \
     | grep -i -A3 "confirmation code"
   ```

4. Enter the code in Gmail, then create a filter: mail to `privacy@tejdux.com` → **Forward to**
   `deletion@inbox.tejdux.com`. Keep a copy in the inbox — the forward is an addition, not a
   replacement, and `privacy@` remains the published address.

Until this is done, the workflow is live but receives nothing except what is sent directly to
`deletion@inbox.tejdux.com`.

### What happens then

```
privacy@tejdux.com  (Gmail)
   │  filter: forward, keep a copy
   ▼
deletion@inbox.tejdux.com  ──►  SES receipt rule
   │
   ├─► S3  influencrm-prod-deletion-intake-*  (raw MIME, expires after 90 days)
   └─► SNS influencrm-prod-deletion-intake
          ├─► email to the operator      (fallback: works even if the platform is down)
          └─► POST /api/deletion-requests (records the row, emails an approval link)
```

**Nothing is deleted until the operator clicks the approval link.** See
`DeletionRequestPolicy` for why, and V40 for the `CHECK` constraint that enforces it even if the
service is wrong.

### Verification

```bash
# 1. The apex MX must be untouched — this is the check that matters most
nslookup -type=MX tejdux.com 8.8.8.8          # expect: 1 smtp.google.com
nslookup -type=MX inbox.tejdux.com 8.8.8.8    # expect: 10 inbound-smtp.us-east-1.amazonaws.com

# 2. Only one receipt rule set is active per account per region
aws ses describe-active-receipt-rule-set --region us-east-1 --profile tejdux

# 3. The app subscription must be Confirmed, not Pending. It confirms itself on the first
#    SNS retry against a running endpoint, so Pending immediately after a first apply is normal.
aws sns list-subscriptions-by-topic --profile tejdux --region us-east-1 \
  --topic-arn arn:aws:sns:us-east-1:099933382956:influencrm-prod-deletion-intake \
  --query "Subscriptions[].{proto:Protocol,arn:SubscriptionArn}" --output table

# 4. End to end
aws sesv2 send-email --region us-east-1 --profile tejdux \
  --from-email-address no-reply@tejdux.com \
  --destination ToAddresses=deletion@inbox.tejdux.com \
  --content '{"Simple":{"Subject":{"Data":"Delete my account"},"Body":{"Text":{"Data":"Please delete my data."}}}}'
```

### While SES is in the sandbox

Notifications **to the operator** work today — `vijay.peddysetty@kmpsglobal.com` is a verified
identity. Notifications **to an arbitrary requester** will not send until production access is
granted; the deletion still happens and `requester_notified_at` stays null, which is the honest
record that they were not told. Those need re-sending once the sandbox is left.
