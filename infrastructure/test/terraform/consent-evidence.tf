# ---------------------------------------------------------------------------
# Consent evidence store
# ---------------------------------------------------------------------------
#
# WHAT THIS HOLDS
#
#   documents/<consent_type>/<version>/document.html   the exact bytes a user was shown
#   documents/<consent_type>/<version>/manifest.json   sha256, url, byte count, captured_at
#   receipts/<yyyy>/<mm>/<dd>/<consent-id>.json        one per acceptance
#
# WHY AN OBJECT STORE AND NOT POSTGRES
#
# Postgres answers "which version did this person accept". It should not answer "what did that
# version say": storing 24KB of HTML on every consent row multiplies identical bytes by every
# signup. The document is written once per version here, and identity.consent_document_versions
# (V39) holds the key and the hash that make this copy checkable.
#
# WHY OBJECT LOCK, AND WHY THIS IS A ONE-WAY DOOR
#
# The point of the snapshot is that it cannot be revised after the fact. A mutable copy of a
# policy is worth no more as evidence than the live page it was copied from -- both can be
# rewritten, and neither can then be shown to be the text somebody agreed to.
#
# COMPLIANCE mode, not GOVERNANCE: under GOVERNANCE a principal holding
# s3:BypassGovernanceRetention can delete anyway, which makes the guarantee only as strong as the
# IAM policy of the moment. Under COMPLIANCE nobody can shorten the retention or delete the object
# before it expires -- not an administrator, not the account root, not AWS support.
#
# READ THAT AGAIN BEFORE APPLYING. It means a bug that writes wrong bytes writes them for seven
# years and there is no cleanup path. Two things mitigate it: the writer validates and hashes the
# payload before the PUT, and a superseded document is corrected by publishing a NEW version rather
# than editing the old one, which is the same discipline the append-only consent table already
# imposes.
#
# Object Lock also CANNOT BE ENABLED ON AN EXISTING BUCKET. It is set at creation and only at
# creation, so this bucket cannot be added to later -- it has to be created with the flag or
# replaced, and replacing a bucket that holds legal evidence is not an operation that should exist.
#
# WHY SEVEN YEARS
#
# Matches the retention the privacy policy already publishes for "billing and tax records"
# (typically 6-7 years). A consent record is the evidence that the processing behind those records
# was lawful, so retiring it earlier would leave the longer-lived record unsupported. It is
# deliberately NOT tied to the account lifetime: the evidence matters most after an account is
# gone, which is the same reason V36's table has no cascading foreign key.
#
# WHY THE APP CANNOT DELETE
#
# The instance role gets PutObject and GetObject and no delete of any kind. Object Lock already
# refuses the delete, so this is belt and braces -- but it means an attempted delete fails as an
# authorization error at the call site, which is a clearer signal in a log than a retention
# rejection, and it keeps the intent visible in the policy rather than only in bucket state.
# Same reasoning as V37 withholding a delete grant on deletion_requests.

resource "aws_s3_bucket" "consent_evidence" {
  bucket = "${local.name_prefix}-consent-evidence-${data.aws_caller_identity.current.account_id}"

  # Set at creation, impossible to add later. See the header.
  object_lock_enabled = true

  tags = { Name = "${local.name_prefix}-consent-evidence" }

  lifecycle {
    # Destroying this bucket destroys the evidence that the platform's consent capture was lawful.
    # Object Lock would refuse to empty it anyway; this makes Terraform refuse first, with a
    # message that says why, rather than failing partway through a destroy.
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "consent_evidence" {
  bucket = aws_s3_bucket.consent_evidence.id

  # Consent receipts carry an email address, an IP and a user agent. This is personal data about
  # identifiable people and must never be reachable without credentials.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Required by Object Lock, and independently wanted: a new version per PUT means an overwrite adds
# a version rather than replacing bytes.
resource "aws_s3_bucket_versioning" "consent_evidence" {
  bucket = aws_s3_bucket.consent_evidence.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "consent_evidence" {
  bucket = aws_s3_bucket.consent_evidence.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.main.arn
    }
    # Cuts KMS calls on a bucket written on every signup: one data key is reused per S3-managed
    # window instead of one GenerateDataKey per object.
    bucket_key_enabled = true
  }
}

# The default retention every object inherits unless the PUT overrides it.
resource "aws_s3_bucket_object_lock_configuration" "consent_evidence" {
  bucket = aws_s3_bucket.consent_evidence.id

  rule {
    default_retention {
      mode = "COMPLIANCE"
      # Years rather than days so the intent survives a reader who does not divide by 365.
      years = 7
    }
  }
}

# ---------------------------------------------------------------------------
# Access for the application
# ---------------------------------------------------------------------------
#
# Attached to the same instance role the app already runs under, so the BFF uses IMDSv2 instance
# credentials (see InstanceRoleCredentials) rather than a long-lived key in configuration.

data "aws_iam_policy_document" "consent_evidence" {
  statement {
    sid    = "WriteConsentEvidence"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      # Object Lock is set per PUT via headers; PutObjectRetention is what a future backfill of an
      # object written before the default applied would need. Granted narrowly for that case.
      "s3:PutObjectRetention",
    ]
    resources = ["${aws_s3_bucket.consent_evidence.arn}/*"]
  }

  statement {
    sid    = "ReadConsentEvidence"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
    ]
    resources = ["${aws_s3_bucket.consent_evidence.arn}/*"]
  }

  statement {
    sid    = "ListConsentEvidence"
    effect = "Allow"
    # Scoped to this bucket. Needed to check whether a version's snapshot already exists before
    # re-uploading it on boot.
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.consent_evidence.arn]
  }

  # Writing an SSE-KMS object needs GenerateDataKey, which the existing instance policy does not
  # grant -- it has Decrypt and DescribeKey only, which is enough to READ an encrypted object and
  # not to write one. Without this every PUT fails with an AccessDenied that names KMS, not S3,
  # and reads as a bucket-policy problem.
  statement {
    sid       = "EncryptConsentEvidence"
    effect    = "Allow"
    actions   = ["kms:GenerateDataKey"]
    resources = [aws_kms_key.main.arn]
  }
}

resource "aws_iam_role_policy" "consent_evidence" {
  name   = "${local.name_prefix}-consent-evidence"
  role   = aws_iam_role.compose_instance.id
  policy = data.aws_iam_policy_document.consent_evidence.json
}

output "consent_evidence_bucket" {
  description = "Bucket holding immutable consent document snapshots and per-acceptance receipts."
  value       = aws_s3_bucket.consent_evidence.bucket
}
