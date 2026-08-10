# Postgres in a container, on an EBS volume that outlives the instance.
#
# Replaces RDS to save ~$25/month. THIS IS THE RISKIEST CHOICE IN THIS CONFIGURATION and the trade is
# worth stating in full, because "cheaper" is only half of it:
#
#   WHAT YOU KEEP
#     Data survives Spot reclamation. The volume is separate from the instance, so a reclaimed instance
#     is replaced and the new one re-attaches the same volume with the same data.
#     Nightly pg_dump to S3 with 30-day retention (see the backup section below).
#
#   WHAT YOU LOSE, versus RDS
#     Point-in-time recovery. RDS can restore to any second in the retention window; here the recovery
#     granularity is "last night's dump".
#     Automated failover, automated minor-version patching, and the storage-level durability guarantees
#     of a managed service.
#     A delete of this volume is UNRECOVERABLE beyond the last dump. `prevent_destroy` is set for that
#     reason and is not a formality.
#
#   WHEN TO GO BACK TO RDS
#     The moment there is a customer whose data you would be embarrassed to lose. Set
#     use_rds = true and the RDS instance in data.tf takes over; it was left in place for exactly that.

variable "use_rds" {
  description = <<-EOT
    TRUE runs Postgres on RDS (managed, ~$25/month, automated backups and PITR).
    FALSE runs it as a container on the EBS volume defined here (~$3/month for 30GB gp3).

    Switching from false to true is NOT a data migration — Terraform will create an empty RDS instance
    and the services will point at it. Move the data with pg_dump first.
  EOT
  type        = bool
  default     = false
}

locals {
  pg_container_enabled = !var.use_rds
  # Where every service points. One place, so the twelve consumers cannot disagree about which database
  # they are using.
  #
  # In the container case this is `localhost`: Postgres is another container in the same task, sharing
  # one network namespace, exactly like every other service-to-service hop here.
  db_host = var.use_rds ? aws_db_instance.main[0].address : "localhost"
}

# ---------------------------------------------------------------------------
# The volume
# ---------------------------------------------------------------------------

resource "aws_ebs_volume" "postgres" {
  count = local.pg_container_enabled ? 1 : 0

  # Must be in the same AZ as the instance — EBS is an AZ-scoped resource, and an instance in another AZ
  # simply cannot attach it. This is the hidden cost of the one-AZ decision: the ASG can only replace
  # the instance in THIS AZ, so a genuine AZ outage is unrecoverable until it ends, rather than merely
  # a restart.
  availability_zone = local.task_az
  size              = 30
  type              = "gp3"
  encrypted         = true
  kms_key_id        = aws_kms_key.main.arn

  tags = { Name = "${local.name_prefix}-postgres-data" }

  lifecycle {
    # THE DATABASE. Without this, a `terraform destroy` — or any change that forces replacement, such as
    # editing availability_zone — silently deletes it. There is no undo and no snapshot unless one was
    # taken. Removing this line should be a deliberate, reviewed act.
    prevent_destroy = true
  }
}

# Attachment is done by the instance at BOOT, not by Terraform.
#
# `aws_volume_attachment` would bind the volume to one specific instance id — and with Spot, that
# instance is replaced without Terraform involvement, leaving the attachment pointing at an instance
# that no longer exists and requiring an apply to recover. The user-data script below finds and attaches
# the volume by TAG instead, so a replacement instance recovers on its own.

data "aws_iam_policy_document" "instance_ebs" {
  count = local.pg_container_enabled ? 1 : 0

  statement {
    sid    = "AttachDataVolume"
    effect = "Allow"
    actions = [
      "ec2:AttachVolume",
      "ec2:DescribeVolumes",
      "ec2:DescribeVolumeStatus",
      "ec2:DescribeTags",
    ]
    # DescribeVolumes cannot be scoped to a volume (it is a list operation), so the narrowing is on the
    # attach: only this volume, and only to an instance carrying the project tag.
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "instance_ebs" {
  count = local.pg_container_enabled ? 1 : 0

  name   = "attach-postgres-volume"
  role   = aws_iam_role.instance[0].id
  policy = data.aws_iam_policy_document.instance_ebs[0].json
}

# ---------------------------------------------------------------------------
# Backups — because losing RDS's backups is not acceptable, only cheaper
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "backups" {
  count = local.pg_container_enabled ? 1 : 0

  bucket = "${local.name_prefix}-db-backups-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "${local.name_prefix}-db-backups" }
}

resource "aws_s3_bucket_public_access_block" "backups" {
  count = local.pg_container_enabled ? 1 : 0

  bucket                  = aws_s3_bucket.backups[0].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  count = local.pg_container_enabled ? 1 : 0

  bucket = aws_s3_bucket.backups[0].id
  rule {
    apply_server_side_encryption_by_default {
      # KMS here, unlike the UI bucket: these dumps contain every row of customer data, so the extra
      # per-object decryption cost is trivially worth it.
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.main.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "backups" {
  count = local.pg_container_enabled ? 1 : 0

  bucket = aws_s3_bucket.backups[0].id
  versioning_configuration {
    # A dump overwritten by a corrupt one is still recoverable.
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  count = local.pg_container_enabled ? 1 : 0

  bucket = aws_s3_bucket.backups[0].id

  rule {
    id     = "retain-30-days"
    status = "Enabled"
    filter {}

    expiration {
      # 30 days of nightly dumps. Longer costs almost nothing at this data size, but 30 days is the
      # window in which anyone would actually notice they need a restore.
      days = 30
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }
}

data "aws_iam_policy_document" "instance_backups" {
  count = local.pg_container_enabled ? 1 : 0

  statement {
    sid       = "WriteBackups"
    effect    = "Allow"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket"]
    resources = [aws_s3_bucket.backups[0].arn, "${aws_s3_bucket.backups[0].arn}/*"]
  }

  statement {
    sid    = "EncryptBackups"
    effect = "Allow"
    # PutObject against a KMS-encrypted bucket needs GenerateDataKey, not just PutObject. Omitting it is
    # a common and confusing failure: the upload is refused with an access-denied that names S3.
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.main.arn]
  }

  statement {
    sid       = "ReadDbPassword"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [local.secret_arns["db-password"]]
  }
}

resource "aws_iam_role_policy" "instance_backups" {
  count = local.pg_container_enabled ? 1 : 0

  name   = "postgres-backups"
  role   = aws_iam_role.instance[0].id
  policy = data.aws_iam_policy_document.instance_backups[0].json
}
