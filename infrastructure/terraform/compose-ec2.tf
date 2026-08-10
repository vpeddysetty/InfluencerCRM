# The platform on a single EC2 Spot instance, run by Docker Compose. This file REPLACES ecs.tf,
# ecs-ec2-spot.tf and ecs-containers-extra.tf.
#
# WHY THE CHANGE. The ECS deployment deadlocked and never ran a task. The capacity provider's managed
# scaling wanted two instances; the boot script assumed exactly one owner of a single EBS volume holding
# Postgres. Instance A held the volume and failed device discovery; instance B waited for a volume that
# would never be released. Zero container instances ever registered, and the service reported
# "EMPTY CAPACITY PROVIDER" every thirty minutes.
#
# The fix is not to tune the capacity provider. It is to delete the component that wants to scale, since
# this deployment can only ever have one instance:
#   - DPS holds sessions in-heap (no Redis), so a second instance would log users out at random.
#   - One Elastic IP is the DNS target, and it attaches to one instance.
#
# WHAT THIS BUYS, beyond the deadlock:
#   - Postgres moves to RDS (use_rds = true). The instance becomes STATELESS, which is the entire point
#     of running on Spot: nothing on it needs to survive, so a reclamation costs availability, not data.
#     Automated backups and PITR replace the pg_dump cron.
#   - Shared state moves to EFS, including Caddy's certificates. EFS needs no attach and no detach, so
#     the volume-sequencing failure class is gone rather than merely less likely.
#   - The ten-container ECS limit is gone, so the seven extracted context services are expressible again
#     (behind a compose profile — see the template).
#
# WHAT IT COSTS, stated plainly:
#   - No rolling deploy. `compose up` restarts in place, so a deploy is ~3-5 minutes of downtime. ECS at
#     desired_count = 1 on one instance had the same gap, so this is not a regression.
#   - No task-level replacement by a control plane. `restart: unless-stopped` plus health checks covers
#     a crashed container; it does not cover a wedged one. This is a real reduction in robustness.
#   - Secrets are written to a root-only file on tmpfs rather than injected by an execution role.
#   - Containers share a bridge network instead of one namespace, so service-to-service calls go by
#     service name and the DAO certificate needs a `dao` SAN. See dao_tls_verification below.
#   - RDS db.t4g.micro adds ~$13/month against the Postgres container it replaces.

# ---------------------------------------------------------------------------
# Instance IAM role
# ---------------------------------------------------------------------------
# One role now, not three. Without ECS there is no execution role and no task role: the instance itself
# pulls the images, reads the secrets and mounts EFS, so those permissions belong here.

data "aws_iam_policy_document" "compose_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "compose_instance" {
  name               = "${local.name_prefix}-compose-instance"
  assume_role_policy = data.aws_iam_policy_document.compose_assume_role.json
  tags               = { Name = "${local.name_prefix}-compose-instance" }
}

resource "aws_iam_role_policy_attachment" "compose_ssm" {
  role = aws_iam_role.compose_instance.name
  # Session Manager. This is how you get a shell on a Spot instance with no SSH key, no bastion and no
  # inbound port 22 — and it is the only way in, since the security group opens nothing but 80/443.
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "compose_instance" {
  # ECR. GetAuthorizationToken is account-wide by API design (it takes no resource), which is why it is
  # a separate statement from the per-repository pulls rather than folded in with a wildcard.
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPull"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = [for r in aws_ecr_repository.service : r.arn]
  }

  # Secrets. Scoped to the twenty-odd secrets this platform owns rather than to Secrets Manager at
  # large, so a compromised instance cannot read another application's credentials.
  statement {
    sid       = "ReadSecrets"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = values(local.secret_arns)
  }

  # The secrets are encrypted with the customer-managed key, so reading them needs Decrypt as well.
  # Without this the fetch fails with AccessDenied on KMS, which reads like a Secrets Manager problem.
  statement {
    sid       = "DecryptSecrets"
    effect    = "Allow"
    actions   = ["kms:Decrypt", "kms:DescribeKey"]
    resources = [aws_kms_key.main.arn]
  }

  # EFS. IAM authorization is enabled on the mounts, so this is what actually permits them — the
  # security group only permits the packets.
  statement {
    sid    = "MountEfs"
    effect = "Allow"
    actions = [
      "elasticfilesystem:ClientMount",
      "elasticfilesystem:ClientWrite",
      "elasticfilesystem:DescribeMountTargets",
    ]
    resources = [aws_efs_file_system.main.arn]

    condition {
      test     = "StringEquals"
      variable = "elasticfilesystem:AccessPointArn"
      values = [
        aws_efs_access_point.logs.arn,
        aws_efs_access_point.assets.arn,
        aws_efs_access_point.caddy.arn,
      ]
    }
  }

  # The compose file. GetObject on the one key, not the bucket: the instance has no reason to read
  # anything else that lands there later.
  statement {
    sid       = "ReadComposeFile"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.config.arn}/docker-compose.yml"]
  }

  # The Elastic IP the instance claims for itself at boot. DescribeAddresses cannot be resource-scoped
  # (it is a list operation), AssociateAddress can be but the instance id is not known until it exists.
  statement {
    sid    = "ClaimEip"
    effect = "Allow"
    actions = [
      "ec2:DescribeAddresses",
      "ec2:AssociateAddress",
    ]
    resources = ["*"]
  }

  # SES, for the transactional email the BFF sends. The BFF still reads explicit access keys from
  # configuration (SesEmailSender does not use the default credentials provider), so this permission is
  # currently unused — it is here so that fixing the adapter is a code change alone.
  statement {
    sid       = "SendEmail"
    effect    = "Allow"
    actions   = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = ["*"]
  }

  # CloudWatch Logs, for the awslogs docker driver. Without ECS nothing else creates the streams.
  statement {
    sid    = "WriteLogs"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:CreateLogGroup",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]
    resources = ["${aws_cloudwatch_log_group.platform.arn}:*"]
  }
}

resource "aws_iam_role_policy" "compose_instance" {
  name   = "${local.name_prefix}-compose-instance"
  role   = aws_iam_role.compose_instance.id
  policy = data.aws_iam_policy_document.compose_instance.json
}

resource "aws_iam_instance_profile" "compose_instance" {
  name = "${local.name_prefix}-compose-instance"
  role = aws_iam_role.compose_instance.name
}

# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------
# Replaces the /ecs/ group. The awslogs docker driver writes here directly.

resource "aws_cloudwatch_log_group" "platform" {
  name              = "/influencrm/${var.environment}"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.main.arn

  tags = { Name = "${local.name_prefix}-logs" }
}

# ---------------------------------------------------------------------------
# Caddy's certificate store, on EFS
# ---------------------------------------------------------------------------
# A THIRD access point, and the reason it is separate from logs and assets is ownership: Caddy runs as
# ROOT in its official image, while every application image runs as uid 1001. An access point forces its
# posix_user onto every file, so sharing one with the application would make Caddy's certificate writes
# fail at runtime — after the container had started and reported healthy.
#
# This is what moves the certificates off the instance. Losing them means re-requesting, and Let's
# Encrypt allows only 5 per domain per week; on EFS they survive a Spot replacement with no volume to
# attach and no sequencing to get wrong.

resource "aws_efs_access_point" "caddy" {
  file_system_id = aws_efs_file_system.main.id

  posix_user {
    uid = 0
    gid = 0
  }

  root_directory {
    path = "/caddy"
    creation_info {
      owner_uid = 0
      owner_gid = 0
      # 0700: private keys. Nothing but the process that owns them has any business reading them.
      permissions = "0700"
    }
  }

  tags = { Name = "${local.name_prefix}-efs-caddy" }
}

# ---------------------------------------------------------------------------
# Security group
# ---------------------------------------------------------------------------
# Replaces both the task and the ECS-instance groups. With no ALB, the instance IS the edge: Caddy
# terminates TLS on it, so 80 and 443 are open to the internet and nothing else is.
#
# There is deliberately NO port 22 rule. Shell access is via SSM Session Manager, which needs no inbound
# rule at all — an open 22, even key-only, is a login surface that does not need to exist.
#
# The nine service ports (8081, 8090, 8443, 8000, 8444-8450) are NOT here. They are reachable only on the
# compose bridge network, which is internal to the instance and has no route from outside.

resource "aws_security_group" "compose_instance" {
  name_prefix = "${local.name_prefix}-compose-"
  description = "Platform instance. Public 80/443 for Caddy; no SSH."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-compose-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "compose_http" {
  for_each = toset(var.alb_ingress_cidrs)

  security_group_id = aws_security_group.compose_instance.id
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  # 80 must stay open even once HTTPS works: it is how Let's Encrypt completes the HTTP-01 challenge,
  # and closing it breaks renewal ninety days later rather than immediately.
  description = "HTTP. Caddy redirects to HTTPS and serves the ACME challenge here."
}

resource "aws_vpc_security_group_ingress_rule" "compose_https" {
  for_each = toset(var.alb_ingress_cidrs)

  security_group_id = aws_security_group.compose_instance.id
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "HTTPS terminated by Caddy."
}

resource "aws_vpc_security_group_egress_rule" "compose_all" {
  security_group_id = aws_security_group.compose_instance.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  # No apostrophe: EC2 rejects a rule description containing one. Unrestricted egress is genuinely
  # needed - ECR and Secrets Manager before the app starts, then RDS, EFS, OAuth, OpenAI and SES.
  description = "ECR pull, Secrets Manager, RDS, EFS, OAuth providers, OpenAI and SES."
}

# The instance must reach EFS and RDS. Both groups live in network.tf and admit the ECS task's group;
# these add the compose instance alongside, so the old rules can be removed with the ECS resources.
resource "aws_vpc_security_group_ingress_rule" "efs_from_compose" {
  security_group_id            = aws_security_group.efs.id
  referenced_security_group_id = aws_security_group.compose_instance.id
  from_port                    = 2049
  to_port                      = 2049
  ip_protocol                  = "tcp"
  description                  = "NFS from the platform instance."
}

resource "aws_vpc_security_group_ingress_rule" "database_from_compose" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.compose_instance.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Postgres from the platform instance - also how a manual psql runs, via SSM."
}

# ---------------------------------------------------------------------------
# The rendered compose file
# ---------------------------------------------------------------------------

locals {
  # The registry host, derived from any one repository URL. Every repository in an account shares it.
  ecr_registry = split("/", aws_ecr_repository.service["dao"].repository_url)[0]

  # Which secrets the boot script fetches, and the environment variable each becomes. The compose file
  # references these by $${NAME}; this map is what makes them exist.
  #
  # ONE ENTRY PER DISTINCT SECRET, not per use: WORKLOAD_SIGNING_KEY is read by the DAO, the BFF, the DPS
  # and all seven context services, and they must all see the same value.
  compose_secret_env = {
    DB_PASSWORD                  = local.secret_arns["db-password"]
    DAO_SERVICE_TOKEN            = local.secret_arns["dao-service-token"]
    DAO_KEYSTORE_B64             = local.secret_arns["dao-keystore-b64"]
    DAO_KEYSTORE_PASSWORD        = local.secret_arns["dao-keystore-password"]
    WORKLOAD_SIGNING_KEY         = local.secret_arns["workload-signing-key"]
    JWT_SIGNING_KEY              = local.secret_arns["jwt-signing-key"]
    MARKETPLACE_CREDENTIAL_KEY   = local.secret_arns["marketplace-credential-key"]
    WORKFLOW_SERVICE_TOKEN       = local.secret_arns["workflow-service-token"]
    DPS_SERVICE_TOKEN            = local.secret_arns["dps-service-token"]
    GOOGLE_OAUTH_CLIENT_ID       = local.secret_arns["google-oauth-client-id"]
    GOOGLE_OAUTH_CLIENT_SECRET   = local.secret_arns["google-oauth-client-secret"]
    FACEBOOK_OAUTH_CLIENT_ID     = local.secret_arns["facebook-oauth-client-id"]
    FACEBOOK_OAUTH_CLIENT_SECRET = local.secret_arns["facebook-oauth-client-secret"]
    STRIPE_SECRET_KEY            = local.secret_arns["stripe-secret-key"]
    BILLING_WEBHOOK_SECRET       = local.secret_arns["billing-webhook-secret"]
    YOUTUBE_API_KEY              = local.secret_arns["youtube-api-key"]
    OPENAI_API_KEY               = local.secret_arns["openai-api-key"]
    SES_ACCESS_KEY_ID            = local.secret_arns["ses-access-key-id"]
    SES_SECRET_ACCESS_KEY        = local.secret_arns["ses-secret-access-key"]
  }

  # The seven extracted services, with the memory figures measured for the ECS deployment. Unused while
  # the `contexts` profile is off, and kept so re-enabling one does not require re-deriving its size.
  compose_context_services = {
    workflow    = { port = 8444, env_prefix = "WORKFLOW", memory = 384 }
    identity    = { port = 8445, env_prefix = "IDENTITY", memory = 384 }
    creator     = { port = 8446, env_prefix = "CREATOR", memory = 384 }
    campaign    = { port = 8447, env_prefix = "CAMPAIGN", memory = 384 }
    attribution = { port = 8448, env_prefix = "ATTRIBUTION", memory = 384 }
    finance     = { port = 8449, env_prefix = "FINANCE", memory = 384 }
    content     = { port = 8450, env_prefix = "CONTENT", memory = 384 }
  }

  # Where a browser reaches the platform. Caddy serves on the Elastic IP until api_domain resolves to it.
  #
  # An IP-based URL is a smoke-test address only: OAuth providers reject an IP as a redirect URI, and
  # Let's Encrypt cannot issue a certificate for one — so sign-in needs api_domain either way.
  public_base_url = (
    var.public_base_url != "" ? var.public_base_url :
    var.api_domain != "" ? "https://${var.api_domain}" :
    "http://${aws_eip.app.public_ip}"
  )
  ui_base_url = var.ui_base_url != "" ? var.ui_base_url : local.public_base_url

  # Whether the BFF verifies the DAO's certificate. TRUE requires the certificate to carry `dao` as a
  # SAN, because the containers no longer share a namespace and the URL is https://dao:8443 rather than
  # https://localhost:8443.
  #
  # The committed keystore carries `localhost` only, so this defaults to FALSE and must be flipped once
  # the certificate is reissued. Leaving it true against the current keystore fails every DAO call with
  # a hostname-verification error at runtime.
  compose_dao_tls_verification = var.dao_certificate_has_service_san

  compose_rendered = templatefile("${path.module}/templates/docker-compose.yml.tftpl", {
    region       = var.aws_region
    ecr_registry = local.ecr_registry
    image_tag    = var.image_tag
    log_group    = aws_cloudwatch_log_group.platform.name

    db_host = var.use_rds ? aws_db_instance.main[0].address : "localhost"
    db_name = var.db_name
    db_user = var.db_master_username

    public_base_url = local.public_base_url
    ui_base_url     = local.ui_base_url
    site_address    = var.api_domain != "" ? var.api_domain : ":80"
    acme_email      = var.acme_email

    ses_from_address         = var.ses_from_address
    workflow_service_enabled = tostring(var.workflow_service_enabled)
    dao_tls_verification     = tostring(local.compose_dao_tls_verification)

    # Secure cookies over HTTPS only, and SameSite=None only alongside Secure. With Caddy the
    # certificate exists as soon as api_domain does, so that is the condition rather than an ACM arn.
    cookie_secure    = var.api_domain != "" ? "true" : "false"
    cookie_same_site = var.api_domain != "" ? "None" : "Lax"

    context_services = local.compose_context_services
  })
}

# ---------------------------------------------------------------------------
# The compose file, in S3
# ---------------------------------------------------------------------------
# NOT in user data, and this is a hard AWS limit rather than a preference: user data is capped at 16384
# bytes and the rendered compose file is ~23KB on its own (~31KB base64-encoded). Embedding it failed the
# apply outright with "InvalidUserData.Malformed: User data is limited to 16384 bytes".
#
# So the boot script fetches it instead. That is better anyway: the instance role already needs S3 for
# nothing else, the object is versioned, and a compose change no longer forces a new launch template
# version — though it DOES still need an instance refresh to take effect, because nothing re-reads the
# file on a running instance.

resource "aws_s3_bucket" "config" {
  bucket = "${local.name_prefix}-config-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "${local.name_prefix}-config" }
}

resource "aws_s3_bucket_public_access_block" "config" {
  bucket = aws_s3_bucket.config.id

  # The compose file names every secret ENV VAR (not its value) and every internal port. Not a
  # credential, but not something to serve to the internet either.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "config" {
  bucket = aws_s3_bucket.config.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.main.arn
    }
  }
}

resource "aws_s3_bucket_versioning" "config" {
  bucket = aws_s3_bucket.config.id

  versioning_configuration {
    # A bad compose file is recoverable by rolling the object back, without a Terraform run.
    status = "Enabled"
  }
}

resource "aws_s3_object" "compose" {
  bucket  = aws_s3_bucket.config.id
  key     = "docker-compose.yml"
  content = local.compose_rendered

  # So a changed compose file is visible in the plan as a content change rather than only as a new etag.
  etag = md5(local.compose_rendered)

  tags = { Name = "${local.name_prefix}-compose" }
}

# ---------------------------------------------------------------------------
# Launch template
# ---------------------------------------------------------------------------

data "aws_ssm_parameter" "compose_ami" {
  # AL2023 rather than the ECS-optimized AMI: without an agent to run, the ECS image's preinstalled
  # agent is dead weight. Docker is installed by the boot script.
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64"
}

resource "aws_launch_template" "compose" {
  name_prefix = "${local.name_prefix}-compose-"
  image_id    = data.aws_ssm_parameter.compose_ami.value

  # No instance_type here on purpose: the mixed-instances policy in the ASG supplies the list, which is
  # what lets one request accept five types.

  iam_instance_profile {
    arn = aws_iam_instance_profile.compose_instance.arn
  }

  # The instance NEEDS a public IP — there is no NAT gateway, so this is its only route to ECR, Secrets
  # Manager and the OAuth/OpenAI endpoints the platform calls. It is also the Caddy edge.
  network_interfaces {
    associate_public_ip_address = !var.enable_nat_gateway
    security_groups             = [aws_security_group.compose_instance.id]
    delete_on_termination       = true
  }

  # GZIPPED, then base64-encoded. user_data is capped at 16384 bytes AFTER encoding, and this script is
  # ~12.5KB raw — which base64 inflates by a third to just over the limit. cloud-init detects gzip magic
  # bytes and decompresses before executing, so this is transparent at boot and buys ~4x headroom.
  #
  # Without it, adding a few lines of comment to the boot script fails the apply with
  # "InvalidUserData.Malformed: User data is limited to 16384 bytes" — which reads like a syntax problem.
  user_data = base64gzip(templatefile("${path.module}/templates/compose-boot.sh.tftpl", {
    region = var.aws_region

    efs_id        = aws_efs_file_system.main.id
    efs_ap_logs   = aws_efs_access_point.logs.id
    efs_ap_assets = aws_efs_access_point.assets.id
    efs_ap_caddy  = aws_efs_access_point.caddy.id

    eip_enabled  = "true"
    ecr_registry = local.ecr_registry

    secret_env = local.compose_secret_env

    # The compose file is FETCHED, not embedded: at ~23KB it does not fit in user data's 16KB budget.
    # The etag is passed so a changed file forces a new launch template version, which is what makes an
    # instance refresh pick it up — without it, a compose-only change would leave user data byte-identical
    # and the refresh would have nothing to notice.
    compose_bucket = aws_s3_bucket.config.id
    compose_key    = aws_s3_object.compose.key
    compose_etag   = aws_s3_object.compose.etag
  }))

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      # 30GB: twelve images at ~500-600MB each is ~7GB, and Docker needs room for layers and writable
      # layers on top. The default 8GB fills during the first pull and the stack fails with
      # "no space left on device".
      volume_size           = 30
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }

  metadata_options {
    # IMDSv2 required. v1 is reachable by any process on the instance, including anything that gets a
    # foothold in a container, and it hands out the instance role's credentials.
    http_tokens                 = "required"
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 2 # 2, not 1: a containerised process is one network hop away.
  }

  monitoring {
    enabled = false
  }

  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${local.name_prefix}-platform" }
  }

  lifecycle {
    create_before_destroy = true
  }
}

# ---------------------------------------------------------------------------
# Autoscaling group
# ---------------------------------------------------------------------------
# THE NUMBERS ARE THE FIX. max_size = 1, not 2.
#
# The ECS deployment ran min 1 / desired 2 / max 2, because the capacity provider's managed scaling
# raised desired to place a task it could not place. Two instances then contended for one EBS volume and
# neither ever started. With Postgres on RDS there is no volume to contend for — but a second instance
# would still be wrong, because DPS holds sessions in-heap and one Elastic IP points at one instance.
#
# max_size = 1 makes that structural rather than a matter of configuration: the ASG cannot produce a
# second instance even if something asks it to. The cost is that a replacement is sequential — the old
# instance terminates, then the new one launches and boots for ~3-5 minutes.

resource "aws_autoscaling_group" "compose" {
  name_prefix = "${local.name_prefix}-compose-"
  # Public subnet: no NAT gateway, so the instance needs a route to the internet gateway to pull images
  # at all, and it is the public edge in any case.
  vpc_zone_identifier = local.task_subnet_ids

  min_size         = 1
  max_size         = 1
  desired_capacity = 1

  # CAPACITY REBALANCE: AWS proactively replaces an instance it predicts will be interrupted, before the
  # two-minute notice. With max_size = 1 this cannot launch the replacement first, but it still gives the
  # spot watcher its cue to stop the stack cleanly.
  capacity_rebalance = true

  health_check_type = "EC2"
  # Ten JVMs is a slow boot. A shorter grace period gets the instance killed as unhealthy while it is
  # still starting — a loop that looks exactly like broken infrastructure.
  health_check_grace_period = 600

  mixed_instances_policy {
    instances_distribution {
      # 100% Spot. on_demand_base_capacity = 1 would guarantee uptime at ~$60/month, defeating the
      # purpose; that is the line to change if reclamation becomes intolerable.
      on_demand_base_capacity                  = 0
      on_demand_percentage_above_base_capacity = 0
      # Cheapest across the pools rather than lowest-price-first, which spreads the risk of one pool
      # running dry.
      spot_allocation_strategy = "price-capacity-optimized"
      spot_max_price           = var.spot_max_price
    }

    launch_template {
      launch_template_specification {
        launch_template_id = aws_launch_template.compose.id
        version            = "$Latest"
      }

      # Five types, one request. Spot capacity is per-type-per-AZ, so this is what stops a single
      # exhausted pool from leaving the platform with nowhere to run.
      dynamic "override" {
        for_each = var.spot_instance_types
        content {
          instance_type = override.value
        }
      }
    }
  }

  # Replace instances when the launch template changes — which is how a new image tag or a changed
  # compose file is deployed, since both are baked into user data.
  instance_refresh {
    strategy = "Rolling"
    preferences {
      # 0, and it must be: with max_size = 1 there is no spare capacity to keep a healthy instance
      # alive during the refresh. This accepts the ~3-5 minute gap rather than deadlocking on capacity
      # that cannot exist — the mistake the ECS deployment made in a different form.
      min_healthy_percentage = 0
      instance_warmup        = 600
    }
  }

  tag {
    key                 = "Name"
    value               = "${local.name_prefix}-platform"
    propagate_at_launch = true
  }

  depends_on = [
    # A missing mount target makes every EFS mount hang until it times out.
    aws_efs_mount_target.main,
    # IAM propagation: the instance fails to pull or to read secrets if the policy is not yet in effect.
    aws_iam_role_policy.compose_instance,
  ]
}
