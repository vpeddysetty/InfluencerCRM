# Two roles, and they are not interchangeable.
#
#   EXECUTION role — assumed by the ECS AGENT, before any container starts. It pulls images and
#                    resolves secrets. The application never has these permissions.
#   TASK role      — assumed by the APPLICATION code. What the running services can do to AWS.
#
# Conflating them is the common mistake and it is a real privilege escalation: the application would
# inherit the ability to read every secret ARN, not just have its own values injected.

data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    # Confused-deputy guard: without these, the trust policy allows ANY account's ECS task to assume
    # this role. They scope it to tasks in this account and this cluster.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*"]
    }
  }
}

# ---------------------------------------------------------------------------
# Execution role
# ---------------------------------------------------------------------------

resource "aws_iam_role" "execution" {
  name               = "${local.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
  tags               = { Name = "${local.name_prefix}-ecs-execution" }
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role = aws_iam_role.execution.name
  # AWS-managed: ECR pull and CloudWatch Logs write. Deliberately used rather than hand-written —
  # AWS updates it when the agent needs a new action, and a hand-rolled copy silently goes stale.
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  # Reading the secret values injected into the task's environment. Scoped to this environment's
  # secrets by ARN, so the role cannot read another environment's.
  statement {
    sid       = "ReadInjectedSecrets"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = values(local.secret_arns)
  }

  # THE PERMISSION EVERYONE FORGETS. The secrets are encrypted with a customer-managed KMS key, and
  # GetSecretValue alone cannot decrypt them. Without this the task fails at START with
  # "AccessDeniedException ... kms:Decrypt", before a single container runs — and the error surfaces
  # as a stopped task with no application logs at all, because the application never began.
  statement {
    sid       = "DecryptSecrets"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.main.arn]
    condition {
      # Only via Secrets Manager — this role has no business decrypting anything else with the key.
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "secrets-access"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# ---------------------------------------------------------------------------
# Task role — what the application itself may do
# ---------------------------------------------------------------------------

resource "aws_iam_role" "task" {
  name               = "${local.name_prefix}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
  tags               = { Name = "${local.name_prefix}-ecs-task" }
}

data "aws_iam_policy_document" "task" {
  # EFS. The task role — not the execution role — is what the EFS mount authorizes against when IAM
  # authorization is enabled on the volume.
  statement {
    sid    = "MountEfs"
    effect = "Allow"
    actions = [
      "elasticfilesystem:ClientMount",
      "elasticfilesystem:ClientWrite",
    ]
    resources = [aws_efs_file_system.main.arn]
    condition {
      # Only through the two access points, which is what pins the uid/gid and the subdirectory. A
      # mount without this condition could reach the filesystem root as any user the task chose.
      test     = "StringEquals"
      variable = "elasticfilesystem:AccessPointArn"
      values = [
        aws_efs_access_point.logs.arn,
        aws_efs_access_point.assets.arn,
      ]
    }
  }

  # SES, for the BFF's transactional email. Narrow deliberately: these credentials can send mail AS
  # your domain, so nothing else should be reachable with them.
  #
  # This replaces the IAM USER whose access keys the properties files currently expect
  # (AWS_SES_ACCESS_KEY_ID / AWS_SES_SECRET_ACCESS_KEY). Long-lived keys in a secret are strictly
  # worse than a role the task already has — see the note in ecs.tf about which the app uses today.
  statement {
    sid    = "SendEmail"
    effect = "Allow"
    actions = [
      "ses:SendEmail",
      "ses:SendRawEmail",
    ]
    resources = ["*"]
    condition {
      # Only as a verified identity in this account. Without this the role could send as any identity
      # the account happens to verify later.
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [var.ses_from_address]
    }
  }

  # ECS Exec: a shell into a running container, which is the only practical way to debug a Fargate
  # task that has no host to log into. Requires no inbound access and is fully audited in CloudTrail.
  statement {
    sid    = "EcsExec"
    effect = "Allow"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"] # These actions do not support resource-level permissions.
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "app-permissions"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}
