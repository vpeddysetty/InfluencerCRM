# KMS key, and every secret the task reads.
#
# HOW SECRETS REACH THE CONTAINERS: Fargate's native injection. The task definition's `secrets`
# block maps a Secrets Manager ARN to an environment variable, and the application already reads
# ${VAR:default} in its properties files. No service calls an AWS SDK for configuration, no new
# dependency, no extra startup failure mode.
#
# THE ONE RULE: anything sensitive goes in `secrets`, never `environment`. Values in `environment`
# are visible in the console and to anyone who can call describe-task-definition.

data "aws_caller_identity" "current" {}

resource "aws_kms_key" "main" {
  description = "influencrm ${var.environment}: encrypts Secrets Manager entries, ECR images, EFS and RDS storage."
  # 30 days rather than the 7-day minimum: deleting this key makes every secret, every EFS file and
  # the database permanently unreadable. The window is the only chance to notice.
  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = { Name = "${local.name_prefix}-kms" }
}

resource "aws_kms_alias" "main" {
  name          = "alias/${local.name_prefix}"
  target_key_id = aws_kms_key.main.key_id
}

# ---------------------------------------------------------------------------
# Generated secrets
# ---------------------------------------------------------------------------
# Terraform generates these so no human ever handles them and nothing is committed. They land in
# state, which is why the S3 backend in versions.tf must be encrypted — state is as sensitive as
# the secrets it holds.

resource "random_password" "db_master" {
  length = 32
  # RDS rejects these outright in a master password.
  override_special = "!#$%&*()-_=+[]{}<>:?"
  special          = true
}

# The DAO's service token and the workload signing keys. Rotating one means tainting it here and
# applying, which restarts the task — acceptable for a token both sides read from the same secret.
resource "random_password" "dao_service_token" {
  length  = 44
  special = false # Travels in an HTTP header; base62 avoids any encoding question.
}

resource "random_password" "workflow_service_token" {
  length  = 44
  special = false
}

resource "random_password" "dps_service_token" {
  length  = 44
  special = false
}

resource "random_password" "workload_signing_key" {
  length  = 64
  special = false
}

resource "random_password" "marketplace_credential_key" {
  length  = 32
  special = false
}

locals {
  # Secrets Terraform can generate itself. Each becomes one Secrets Manager entry.
  generated_secrets = {
    db-password                = random_password.db_master.result
    dao-service-token          = random_password.dao_service_token.result
    workflow-service-token     = random_password.workflow_service_token.result
    dps-service-token          = random_password.dps_service_token.result
    workload-signing-key       = random_password.workload_signing_key.result
    marketplace-credential-key = random_password.marketplace_credential_key.result
  }

  # Secrets Terraform CANNOT generate, because they come from outside: a provider issues them, or
  # they are key material with a specific format. Created EMPTY with a placeholder and must be
  # populated with `aws secretsmanager put-secret-value` before the corresponding feature works.
  #
  # Each notes what happens while it is unset. Several are deliberately fail-closed — the
  # application refuses the feature rather than running it insecurely — which is why an empty secret
  # here is safe but not invisible.
  external_secrets = {
    openai-api-key               = "The agent's OpenAI key. Unset: OpenAIAdvisor reports itself unavailable and mapping falls back to the deterministic matcher. ROTATE THE KEY CURRENTLY IN .env — it was committed to the working tree."
    jwt-signing-key              = "RSA JWK (private) signing access tokens. Unset: the BFF REFUSES TO START. Generate with infrastructure/scripts/generate-jwt-key.sh."
    dao-keystore-b64             = "base64 of the DAO's PKCS12 keystore. Unset: the DAO refuses to start, because server.ssl.key-store points at a file that is not there."
    dao-keystore-password        = "Password for the keystore above."
    google-oauth-client-id       = "Unset: Google sign-in is unavailable; the rest of the platform works."
    google-oauth-client-secret   = "Unset: as above."
    facebook-oauth-client-id     = "Unset: Facebook sign-in is unavailable."
    facebook-oauth-client-secret = "Unset: as above."
    stripe-secret-key            = "Unset: billing falls back to the `manual` provider, which takes NO money and logs at WARN every time it is used."
    billing-webhook-secret       = "Unset: the billing webhook endpoint returns 503. That endpoint cannot be authenticated any other way — a payment provider holds no user token — so the signature IS the authentication."
    ses-access-key-id            = "Unset: email provider stays `log`, which writes messages to the log and SENDS NOTHING."
    ses-secret-access-key        = "Unset: as above."
    youtube-api-key              = "Unset: YouTube subscriber counts stay simulated, recorded as source=mock."
  }
}

resource "aws_secretsmanager_secret" "generated" {
  for_each = local.generated_secrets

  name       = "${local.name_prefix}/${each.key}"
  kms_key_id = aws_kms_key.main.arn
  # 0 = delete immediately on destroy. Non-zero would keep the name reserved for the recovery window,
  # and a re-apply would then fail with "already scheduled for deletion" — which makes tearing an
  # environment down and rebuilding it impossible without waiting days.
  recovery_window_in_days = 0

  tags = { Name = "${local.name_prefix}-${each.key}" }
}

resource "aws_secretsmanager_secret_version" "generated" {
  for_each = local.generated_secrets

  secret_id     = aws_secretsmanager_secret.generated[each.key].id
  secret_string = each.value
}

resource "aws_secretsmanager_secret" "external" {
  for_each = local.external_secrets

  name                    = "${local.name_prefix}/${each.key}"
  description             = each.value
  kms_key_id              = aws_kms_key.main.arn
  recovery_window_in_days = 0

  tags = { Name = "${local.name_prefix}-${each.key}" }
}

# A placeholder version, so the ARN resolves and the task can start. An EMPTY string is used rather
# than a fake-looking value: every consumer treats empty as "not configured" and takes its
# documented fail-closed path, whereas "replace-me" would be passed to Google or Stripe as if it
# were real and fail with a confusing authentication error instead.
resource "aws_secretsmanager_secret_version" "external_placeholder" {
  for_each = local.external_secrets

  secret_id     = aws_secretsmanager_secret.external[each.key].id
  secret_string = ""

  lifecycle {
    # Once a real value is put in — by the CLI, the console, or a rotation — Terraform must not
    # revert it to the placeholder on the next apply. Without this, every `apply` would silently
    # break sign-in and billing.
    ignore_changes = [secret_string]
  }
}

locals {
  # Convenience: one map from short name to ARN, used to build the task definition's `secrets` list.
  secret_arns = merge(
    { for k, v in aws_secretsmanager_secret.generated : k => v.arn },
    { for k, v in aws_secretsmanager_secret.external : k => v.arn },
  )
}
