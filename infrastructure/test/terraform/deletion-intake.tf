# ---------------------------------------------------------------------------
# Deletion request intake by email
# ---------------------------------------------------------------------------
#
# WHAT THIS IS FOR
#
# /data-deletion/ tells people to email privacy@tejdux.com, and V37 created a table to record what
# arrives. Nothing connected the two: requests landed in a mailbox, were handled by hand, and left
# no audit trail -- which the V37 header names as the exact failure it was built to prevent.
#
# WHY A SUBDOMAIN AND NOT privacy@tejdux.com DIRECTLY
#
# SES receiving requires the MX record of the receiving domain to point at SES. The apex MX points
# at Google Workspace and carries all real business mail; repointing it would break every mailbox to
# automate one. So SES receives on inbox.tejdux.com, which has no mail today, and a Gmail filter
# forwards privacy@ to deletion@inbox.tejdux.com.
#
# The apex MX is NOT MANAGED BY THIS CONFIGURATION and must stay that way. A resource here for
# tejdux.com's MX would take ownership of Google's mail routing, and a later destroy or a defaulted
# variable could remove it.
#
# WHY THE FORWARD IS SET UP BY HAND
#
# Gmail forwarding cannot be configured from Terraform without Workspace admin API credentials,
# which would be a new long-lived secret with broad mailbox scope to save one manual step. The
# forward is documented in docs/ses-setup.md instead.
#
# RULE SETS ARE ACCOUNT-GLOBAL
#
# Only one receipt rule set is active per account per region, and activating one DEACTIVATES any
# other. Checked before writing this: the account has no rule sets at all, so there is nothing to
# displace. If that ever changes, rules belong in the EXISTING active set rather than in a new one.

locals {
  # The subdomain SES receives on. Deliberately not a variable: it is referenced in a Gmail filter
  # and in docs/ses-setup.md, and a value that can drift between those places and here is a way to
  # silently stop receiving mail.
  deletion_intake_domain = "inbox.${var.root_domain}"
  deletion_intake_address = "deletion@inbox.${var.root_domain}"
}

# ---------------------------------------------------------------------------
# The receiving domain
# ---------------------------------------------------------------------------

resource "aws_sesv2_email_identity" "deletion_intake" {
  email_identity = local.deletion_intake_domain
}

# SES receiving needs the MX of the RECEIVING domain only. inbox.tejdux.com has no other mail, so
# this record is the whole of its mail routing and cannot affect the apex.
resource "aws_route53_record" "deletion_intake_mx" {
  count = var.root_domain == "" ? 0 : 1

  zone_id = data.aws_route53_zone.root[0].zone_id
  name    = local.deletion_intake_domain
  type    = "MX"
  ttl     = 300

  # Priority 10, and the inbound endpoint is regional: it must match the region the rule set lives
  # in or mail is accepted nowhere.
  records = ["10 inbound-smtp.${var.aws_region}.amazonaws.com"]
}

# ---------------------------------------------------------------------------
# Where raw messages land
# ---------------------------------------------------------------------------
#
# SES writes the whole MIME message here and the BFF reads it. Not the consent-evidence bucket:
# that one is under Object Lock for seven years, and an inbound mailbox is exactly the wrong thing
# to make undeletable -- it receives whatever anyone sends it, including spam and mail sent to the
# address by mistake.

resource "aws_s3_bucket" "deletion_intake" {
  bucket = "${local.name_prefix}-deletion-intake-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "${local.name_prefix}-deletion-intake" }
}

resource "aws_s3_bucket_public_access_block" "deletion_intake" {
  bucket = aws_s3_bucket.deletion_intake.id

  # A deletion request contains the requester's email address and whatever they chose to write.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Deliberately SSE-S3 rather than the customer-managed KMS key used elsewhere.
#
# SES must be able to encrypt the object as it writes it. With a KMS key that means granting the SES
# service principal kms:GenerateDataKey on a key that also wraps EBS volumes, Secrets Manager and
# the log group -- widening access to all of it so that inbound mail can be stored. The message is
# already in transit over the public internet before it arrives; SSE-S3 is the proportionate answer.
resource "aws_s3_bucket_server_side_encryption_configuration" "deletion_intake" {
  bucket = aws_s3_bucket.deletion_intake.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Raw messages are working data, not evidence. The audit trail is identity.deletion_requests, and
# the request's substance is copied into that row when it is parsed. 90 days is long enough to
# investigate a request that was mishandled and short enough not to accumulate a mail archive
# nobody manages.
resource "aws_s3_bucket_lifecycle_configuration" "deletion_intake" {
  bucket = aws_s3_bucket.deletion_intake.id

  rule {
    id     = "expire-raw-messages"
    status = "Enabled"

    filter {}

    expiration {
      days = 90
    }
  }
}

# SES writes here. The SourceAccount condition is the confused-deputy guard: without it any AWS
# account's SES could be pointed at this bucket.
data "aws_iam_policy_document" "deletion_intake" {
  statement {
    sid    = "AllowSesPut"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }

    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.deletion_intake.arn}/*"]

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_s3_bucket_policy" "deletion_intake" {
  bucket = aws_s3_bucket.deletion_intake.id
  policy = data.aws_iam_policy_document.deletion_intake.json
}

# ---------------------------------------------------------------------------
# Notification that a message arrived
# ---------------------------------------------------------------------------
#
# A separate topic from the SES event destination and from the alerts topic, for the reason recorded
# in monitoring.tf: the alerts topic uses the AWS-managed alias/aws/sns key whose policy cannot be
# edited, so a service principal cannot be granted publish on it.

resource "aws_sns_topic" "deletion_intake" {
  name = "${local.name_prefix}-deletion-intake"
  tags = { Name = "${local.name_prefix}-deletion-intake" }
}

data "aws_iam_policy_document" "deletion_intake_topic" {
  statement {
    sid    = "AllowSesPublish"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }

    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.deletion_intake.arn]

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "deletion_intake" {
  arn    = aws_sns_topic.deletion_intake.arn
  policy = data.aws_iam_policy_document.deletion_intake_topic.json
}

# So a request is visible even if the parser is broken or the instance is down. The operator email
# is the same one the alerts go to.
resource "aws_sns_topic_subscription" "deletion_intake_email" {
  count = var.alert_email == "" ? 0 : 1

  topic_arn = aws_sns_topic.deletion_intake.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# ---------------------------------------------------------------------------
# The receipt rule
# ---------------------------------------------------------------------------

resource "aws_ses_receipt_rule_set" "main" {
  rule_set_name = "${local.name_prefix}-inbound"
}

resource "aws_ses_receipt_rule" "deletion" {
  name          = "${local.name_prefix}-deletion-request"
  rule_set_name = aws_ses_receipt_rule_set.main.rule_set_name
  enabled       = true

  # Scoped to one address. A rule with no recipients matches EVERY address SES receives for, which
  # would make this the catch-all for the whole account.
  recipients = [local.deletion_intake_address]

  # Mail arriving over TLS is not required: a deletion request from a webmail client that does not
  # negotiate TLS to SES is still a request the law obliges us to honour. Refusing it would drop a
  # rights request on a technicality.
  tls_policy = "Optional"

  s3_action {
    bucket_name       = aws_s3_bucket.deletion_intake.id
    object_key_prefix = "inbound/"
    position          = 1
  }

  sns_action {
    topic_arn = aws_sns_topic.deletion_intake.arn
    position  = 2
    # The notification carries headers and metadata, not the body. The body can exceed the SNS
    # size limit, and the object in S3 is the copy that matters.
    encoding = "UTF-8"
  }

  depends_on = [aws_s3_bucket_policy.deletion_intake]
}

# Only one rule set is active per account per region, and this activates ours. Verified before
# writing: the account had no rule sets, so nothing is displaced.
resource "aws_ses_active_receipt_rule_set" "main" {
  rule_set_name = aws_ses_receipt_rule_set.main.rule_set_name
}

# ---------------------------------------------------------------------------
# Application access
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "deletion_intake_app" {
  statement {
    sid       = "ReadInboundMessages"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.deletion_intake.arn}/*"]
  }

  statement {
    sid       = "ListInboundMessages"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.deletion_intake.arn]
  }
}

resource "aws_iam_role_policy" "deletion_intake_app" {
  name   = "${local.name_prefix}-deletion-intake"
  role   = aws_iam_role.compose_instance.id
  policy = data.aws_iam_policy_document.deletion_intake_app.json
}

output "deletion_intake_address" {
  description = "Forward privacy@ mail here so deletion requests are recorded. See docs/ses-setup.md."
  value       = local.deletion_intake_address
}

output "deletion_intake_bucket" {
  description = "Raw inbound messages, expiring after 90 days."
  value       = aws_s3_bucket.deletion_intake.bucket
}
