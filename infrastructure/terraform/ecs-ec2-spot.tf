# ECS on EC2 Spot, instead of Fargate.
#
# WHAT THIS CHANGES AND WHAT IT DOES NOT. The cluster, the task definition's containers, the ALB, RDS,
# EFS, ECR, the secrets and both IAM roles are all unchanged — this swaps only WHERE the task runs.
# That is the whole point of the capacity-provider abstraction: same task, different compute.
#
# WHY: Fargate has no free tier and no spot discount at this size — ~$72/month for 2 vCPU / 8GB running
# continuously. The same capacity as an EC2 Spot t3a.large is ~$22/month, a saving of ~$50.
#
# WHAT SPOT COSTS YOU, stated plainly because it is not a free lunch:
#   - The instance can be reclaimed with TWO MINUTES of notice, at any time. The platform is down until
#     the autoscaling group replaces it and ten JVMs boot again — call it 3-5 minutes.
#   - DPS sessions live in the JVM heap while Redis is out of scope, so every signed-in user is logged
#     out by a reclamation, not just delayed.
#   - Postgres is on RDS, NOT on this instance, so no DATA is at risk from a reclamation. That was the
#     deciding reason to keep RDS rather than containerising it for another $25.
#
# Interruption is handled as well as it can be: the ASG uses capacity-rebalance, the capacity provider
# drains the instance on termination, and the ALB deregisters before the container dies.
#
# ARCHITECTURE NOTE: x86_64, not arm64. The eleven images were built on x86 and pushed as such;
# ARM would be ~10% cheaper again but needs all eleven rebuilt under buildx. Not worth the churn for
# $2/month, and a mismatch fails at task start with an exec-format error that reads like a corrupt
# image.

variable "use_ec2_spot" {
  description = <<-EOT
    Run the task on EC2 Spot instead of Fargate.

    TRUE creates the launch template, autoscaling group and capacity provider below, and the service
    uses the capacity provider strategy rather than launch_type = FARGATE.

    FALSE leaves all of this uncreated and the service runs on Fargate exactly as originally validated.
    Flipping it back is a supported rollback — the task definition is compatible with both, because it
    declares FARGATE and EC2 in requires_compatibilities.
  EOT
  type        = bool
  default     = true
}

variable "spot_instance_types" {
  description = <<-EOT
    Instance types the Spot request may use, cheapest first. ALL must have at least 8GB, because the
    measured footprint of the full stack is ~4.7GB and a 4GB type would be reclaimed by the OOM killer
    rather than by AWS.

    Several types rather than one is the single most effective thing for availability: Spot capacity is
    per-type-per-AZ, so a request that accepts five types is far less likely to go unfulfilled than one
    that insists on t3a.large. Prices observed in us-east-1 on 2026-08-09:
      t3a.large $0.0303/hr  t3.large $0.0309/hr  m5a.large $0.0412/hr  m5.large $0.0458/hr
  EOT
  type        = list(string)
  default     = ["t3a.large", "t3.large", "m5a.large", "m5.large", "m6a.large"]
}

variable "spot_max_price" {
  description = <<-EOT
    Maximum hourly price. EMPTY means "up to the on-demand price", which is the right default and is
    what AWS recommends: a capped bid does not save money (you pay the market rate regardless), it only
    makes the request fail to be fulfilled when the market moves above the cap.

    Set it only if a hard ceiling matters more than staying up.
  EOT
  type        = string
  default     = ""
}

locals {
  spot_enabled = var.use_ec2_spot
}

# ---------------------------------------------------------------------------
# The AMI
# ---------------------------------------------------------------------------
# Resolved from SSM rather than hardcoded, so a new instance launched after an AMI update gets the
# patched image. The trade is that `terraform plan` can show a launch-template change on any day AWS
# publishes a new AMI — which is honest: the desired state genuinely did change.

data "aws_ssm_parameter" "ecs_ami" {
  count = local.spot_enabled ? 1 : 0
  # ECS-optimized AL2023: the ECS agent, Docker and the SSM agent are preinstalled and the agent is
  # configured to join a cluster from /etc/ecs/ecs.config. A plain AL2023 AMI would need all of that
  # installed in user data, which is a slower boot and one more thing to maintain.
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id"
}

# ---------------------------------------------------------------------------
# Instance IAM role
# ---------------------------------------------------------------------------
# A THIRD role, distinct from the task and execution roles in iam.tf. This one belongs to the EC2
# instance: it lets the ECS agent register with the cluster and report task state. The application
# never uses it — that is the task role's job.

data "aws_iam_policy_document" "ec2_assume_role" {
  count = local.spot_enabled ? 1 : 0

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  count = local.spot_enabled ? 1 : 0

  name               = "${local.name_prefix}-ecs-instance"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role[0].json
  tags               = { Name = "${local.name_prefix}-ecs-instance" }
}

resource "aws_iam_role_policy_attachment" "instance_ecs" {
  count = local.spot_enabled ? 1 : 0

  role = aws_iam_role.instance[0].name
  # AWS-managed. Lets the agent register the instance, poll for work and report task state.
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

resource "aws_iam_role_policy_attachment" "instance_ssm" {
  count = local.spot_enabled ? 1 : 0

  role = aws_iam_role.instance[0].name
  # Session Manager. This is how you get a shell on a Spot instance with no SSH key, no bastion and no
  # inbound port 22 — and it is also what makes the manual RDS schema step possible from inside the VPC.
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "instance" {
  count = local.spot_enabled ? 1 : 0

  name = "${local.name_prefix}-ecs-instance"
  role = aws_iam_role.instance[0].name
}

# ---------------------------------------------------------------------------
# Launch template
# ---------------------------------------------------------------------------

resource "aws_launch_template" "spot" {
  count = local.spot_enabled ? 1 : 0

  name_prefix = "${local.name_prefix}-ecs-"
  image_id    = data.aws_ssm_parameter.ecs_ami[0].value

  # No instance_type here on purpose: the mixed-instances policy in the ASG supplies the list, which is
  # what lets one request accept five types.

  iam_instance_profile {
    arn = aws_iam_instance_profile.instance[0].arn
  }

  # associate_public_ip_address, not vpc_security_group_ids at the top level: setting both forms is an
  # API conflict. The instance NEEDS a public IP — there is no NAT gateway, so this is its only route to
  # ECR, Secrets Manager and the OAuth/OpenAI endpoints the platform calls.
  network_interfaces {
    associate_public_ip_address = !var.enable_nat_gateway
    security_groups             = [aws_security_group.instance[0].id]
    delete_on_termination       = true
  }

  # The ECS agent reads this at boot to find its cluster. Without ECS_CLUSTER the instance registers
  # with a cluster named "default" — which exists nowhere — and the task stays PENDING forever with no
  # error that names the cause.
  #
  # ECS_ENABLE_SPOT_INSTANCE_DRAINING is the line that makes Spot survivable: on receiving the
  # two-minute interruption notice the agent puts the instance in DRAINING, which deregisters the task
  # from the ALB and lets Spring shut down cleanly instead of being killed mid-request.
  # The boot script does what Terraform cannot: attach and mount the EBS data volume, claim the Elastic
  # IP, install the backup cron, and only THEN join the cluster — because a task placed before the volume
  # is mounted would write the database to ephemeral instance storage.
  #
  # Templated rather than inline so it can be read, and so the conditionals are Terraform's rather than
  # shell tests against a variable that may not exist.
  user_data = base64encode(templatefile("${path.module}/templates/instance-boot.sh.tftpl", {
    region       = var.aws_region
    cluster_name = aws_ecs_cluster.main.name
    # tostring: templatefile passes only strings/numbers/lists reliably, and the template
    # compares against "true" rather than relying on bool coercion.
    pg_enabled    = tostring(local.pg_container_enabled)
    eip_enabled   = tostring(local.caddy_enabled)
    volume_id     = local.pg_container_enabled ? aws_ebs_volume.postgres[0].id : ""
    backup_bucket = local.pg_container_enabled ? aws_s3_bucket.backups[0].id : ""
    db_name       = var.db_name
    db_user       = var.db_master_username
    db_secret_arn = local.secret_arns["db-password"]
  }))

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      # 30GB: eleven images at ~500-600MB each is ~6GB, and Docker needs room for layers, writable
      # layers and the ECS agent's own state. The default 8GB fills during the first image pull and the
      # task fails with "no space left on device".
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
    # Basic (5-minute) metrics. Detailed monitoring costs money and adds nothing at one instance.
    enabled = false
  }

  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${local.name_prefix}-ecs-spot" }
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "instance" {
  count = local.spot_enabled ? 1 : 0

  name_prefix = "${local.name_prefix}-instance-"
  description = "ECS EC2 instance. Inbound from the ALB only; no SSH."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-instance-sg" }
}

# The ALB reaches the task through the instance. awsvpc network mode gives the TASK its own ENI with
# its own security group (aws_security_group.task), so these rules cover the instance itself.
#
# There is deliberately NO port 22 rule. Shell access is via SSM Session Manager, which needs no
# inbound rule at all — an open 22, even key-only, is a login surface that does not need to exist.
resource "aws_vpc_security_group_ingress_rule" "instance_from_alb" {
  # Requires BOTH: a spot instance to attach to, and an ALB to reference. With Caddy there is no load
  # balancer, and the instance is reached directly on 80/443 by the rules in alb-toggle.tf.
  count = local.spot_enabled && var.use_alb ? 1 : 0

  security_group_id            = aws_security_group.instance[0].id
  referenced_security_group_id = aws_security_group.alb[0].id
  # The ephemeral range: with awsvpc the ALB targets the task's ENI, but host-level health and
  # bridge-mode fallbacks land here. Narrower than "all", wider than two ports.
  from_port   = 32768
  to_port     = 65535
  ip_protocol = "tcp"
  description = "ALB to container ports on the instance."
}

resource "aws_vpc_security_group_egress_rule" "instance_all" {
  count = local.spot_enabled ? 1 : 0

  security_group_id = aws_security_group.instance[0].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  # No apostrophe: EC2 rejects a security-group rule description containing one, with
  # "InvalidParameterValue: Invalid rule description". The permitted set is a-zA-Z0-9._-:/()#,@[]+=&;{}!$*
  description = "ECR pull, Secrets Manager, SSM, EFS mount, and the platform outbound calls."
}

# The instance must be allowed to mount EFS, which is a separate security group from the task's.
resource "aws_vpc_security_group_ingress_rule" "efs_from_instance" {
  count = local.spot_enabled ? 1 : 0

  security_group_id            = aws_security_group.efs.id
  referenced_security_group_id = aws_security_group.instance[0].id
  from_port                    = 2049
  to_port                      = 2049
  ip_protocol                  = "tcp"
  description                  = "NFS from the ECS instance."
}

# Postgres from the instance, for the same reason.
resource "aws_vpc_security_group_ingress_rule" "database_from_instance" {
  count = local.spot_enabled ? 1 : 0

  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.instance[0].id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Postgres from the ECS instance - also how the manual schema step connects, via SSM."
}

# ---------------------------------------------------------------------------
# Autoscaling group
# ---------------------------------------------------------------------------

resource "aws_autoscaling_group" "spot" {
  count = local.spot_enabled ? 1 : 0

  name_prefix = "${local.name_prefix}-ecs-"
  # Public subnet, matching the Fargate configuration and for the same reason: no NAT gateway, so the
  # instance needs a route to the internet gateway to pull images at all.
  vpc_zone_identifier = local.task_subnet_ids

  min_size = 1
  # 2, not 1, so a reclaimed instance can be replaced before the old one is gone — and so the capacity
  # provider has somewhere to place the replacement task during a deploy. It normally runs at 1.
  max_size         = 2
  desired_capacity = 1

  # CAPACITY REBALANCE: AWS proactively replaces an instance it predicts will be interrupted, before
  # the two-minute notice. This is the difference between a planned replacement and an outage, and it
  # is the single most valuable Spot setting.
  capacity_rebalance = true

  # The ECS capacity provider manages scaling, so the ASG must not fight it.
  protect_from_scale_in = true

  health_check_type = "EC2"
  # Ten JVMs is a slow boot. A shorter grace period gets the instance killed as unhealthy while it is
  # still starting — a loop that looks like broken infrastructure.
  health_check_grace_period = 300

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
        launch_template_id = aws_launch_template.spot[0].id
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

  # ManagedDraining on the capacity provider needs this tag to find its instances.
  tag {
    key                 = "AmazonECSManaged"
    value               = "true"
    propagate_at_launch = true
  }

  tag {
    key                 = "Name"
    value               = "${local.name_prefix}-ecs-spot"
    propagate_at_launch = true
  }

  lifecycle {
    create_before_destroy = true
    # The capacity provider owns this number once it is running.
    ignore_changes = [desired_capacity]
  }
}

# ---------------------------------------------------------------------------
# Capacity provider
# ---------------------------------------------------------------------------

resource "aws_ecs_capacity_provider" "spot" {
  count = local.spot_enabled ? 1 : 0

  name = "${local.name_prefix}-spot"

  auto_scaling_group_provider {
    auto_scaling_group_arn = aws_autoscaling_group.spot[0].arn
    # ENABLED is what drains a task off an instance being terminated instead of killing it. Combined
    # with ECS_ENABLE_SPOT_INSTANCE_DRAINING in user data, an interruption becomes a graceful stop.
    managed_draining = "ENABLED"
    # ECS manages scale-in protection on the instances it is using.
    managed_termination_protection = "ENABLED"

    managed_scaling {
      status = "ENABLED"
      # 100% target: keep exactly enough capacity for the running tasks and no spare. Spare capacity is
      # an idle instance being paid for.
      target_capacity           = 100
      minimum_scaling_step_size = 1
      maximum_scaling_step_size = 1
      instance_warmup_period    = 300
    }
  }

  tags = { Name = "${local.name_prefix}-spot" }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  count = local.spot_enabled ? 1 : 0

  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = [aws_ecs_capacity_provider.spot[0].name]

  default_capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.spot[0].name
    weight            = 100
    base              = 1
  }
}
