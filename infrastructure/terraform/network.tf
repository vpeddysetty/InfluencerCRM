# VPC, subnets and security groups.
#
# THE SHAPE OF THIS PHASE: one AZ, one public subnet holding the task, no NAT gateway. See
# var.enable_nat_gateway for why that is safe and what it saves.

data "aws_availability_zones" "available" {
  state = "available"
  filter {
    name = "opt-in-status"
    # Local Zones and Wavelength zones appear here and do not support Fargate or RDS. Without this
    # filter a region that has them can hand back a zone where the task simply cannot be placed.
    values = ["opt-in-not-required"]
  }
}

locals {
  # The ALB needs subnets in at least TWO AZs even when only one is used for compute, so always
  # slice at least two names. The task is placed only in the first.
  azs         = slice(data.aws_availability_zones.available.names, 0, max(var.availability_zone_count, 2))
  task_az     = local.azs[0]
  name_prefix = "influencrm-${var.environment}"
}

resource "aws_vpc" "main" {
  cidr_block = var.vpc_cidr
  # Both required for RDS to resolve, and for the ECS agent to resolve the ECR and Secrets Manager
  # endpoints it must reach before the application starts.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.name_prefix}-vpc" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name_prefix}-igw" }
}

# ---------------------------------------------------------------------------
# Public subnets — the ALB always, and the task too while NAT is disabled
# ---------------------------------------------------------------------------
resource "aws_subnet" "public" {
  count = length(local.azs)

  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  # /20 blocks: 4094 usable addresses each, room for the awsvpc ENIs of many tasks. Carved from the
  # bottom of the VPC range, leaving the top half free for the private subnets below.
  cidr_block = cidrsubnet(var.vpc_cidr, 4, count.index)

  # The task gets its public IP from the service's network configuration, not from this — leaving
  # this false means anything else launched here is private unless it asks not to be.
  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name_prefix}-public-${local.azs[count.index]}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name_prefix}-public-rt" }
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ---------------------------------------------------------------------------
# Private subnets + NAT — created ONLY when enable_nat_gateway is true
# ---------------------------------------------------------------------------
# Written now so switching is a variable change rather than new code, but nothing is billed while
# the flag is false: count = 0 creates no resources at all.

resource "aws_subnet" "private" {
  count = var.enable_nat_gateway ? var.availability_zone_count : 0

  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  # Offset by 8 so private blocks never overlap public ones as either count grows.
  cidr_block = cidrsubnet(var.vpc_cidr, 4, count.index + 8)

  tags = {
    Name = "${local.name_prefix}-private-${local.azs[count.index]}"
    Tier = "private"
  }
}

resource "aws_eip" "nat" {
  count  = var.enable_nat_gateway ? 1 : 0
  domain = "vpc"
  tags   = { Name = "${local.name_prefix}-nat-eip" }
}

resource "aws_nat_gateway" "main" {
  count = var.enable_nat_gateway ? 1 : 0

  allocation_id = aws_eip.nat[0].id
  # Lives in a PUBLIC subnet by definition — it is the thing with the route to the internet gateway.
  subnet_id  = aws_subnet.public[0].id
  depends_on = [aws_internet_gateway.main]

  tags = { Name = "${local.name_prefix}-nat" }
}

resource "aws_route_table" "private" {
  count  = var.enable_nat_gateway ? 1 : 0
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[0].id
  }

  tags = { Name = "${local.name_prefix}-private-rt" }
}

resource "aws_route_table_association" "private" {
  count          = var.enable_nat_gateway ? length(aws_subnet.private) : 0
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[0].id
}

locals {
  # Where the ECS service places its tasks, and whether they get a public IP. The two are tied:
  # a task in a public subnet MUST have a public IP to reach the internet (there is no NAT), and a
  # task in a private subnet must NOT have one (a public IP there routes nowhere).
  task_subnet_ids = var.enable_nat_gateway ? aws_subnet.private[*].id : [aws_subnet.public[0].id]
  task_public_ip  = !var.enable_nat_gateway
}

# ---------------------------------------------------------------------------
# Security groups
# ---------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name_prefix = "${local.name_prefix}-alb-"
  description = "ALB: public ingress on 80/443."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    # name_prefix + this: a rule change that forces replacement would otherwise fail because the
    # old group is still attached to the load balancer.
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-alb-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each = toset(var.alb_ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "HTTP. Redirects to HTTPS when a certificate is configured."
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  # Only opened when there is a certificate to terminate with; an open 443 with no listener is a
  # rule that says something is served there when nothing is.
  for_each = var.acm_certificate_arn == "" ? toset([]) : toset(var.alb_ingress_cidrs)

  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "HTTPS"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_task" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.task.id
  ip_protocol                  = "-1"
  description                  = "To the task only — the ALB has no reason to reach anything else."
}

resource "aws_security_group" "task" {
  name_prefix = "${local.name_prefix}-task-"
  description = "ECS task: inbound ONLY from the ALB, despite having a public IP."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-task-sg" }
}

# THE RULE THAT MAKES A PUBLIC SUBNET SAFE.
#
# Only the two browser-facing ports are reachable, and only from the ALB's security group — not from
# a CIDR, so it stays correct if the ALB's addresses change. The other nine services listen on
# 8443-8450 and are NOT here: they are reachable only over the task's loopback interface, because
# every container in a single task definition shares one network namespace. There is no rule to
# write for localhost, which is precisely why the single-task shape is defensible without a mesh.
resource "aws_vpc_security_group_ingress_rule" "task_from_alb_bff" {
  security_group_id            = aws_security_group.task.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8081
  to_port                      = 8081
  ip_protocol                  = "tcp"
  description                  = "BFF (web-experience) from the ALB."
}

resource "aws_vpc_security_group_ingress_rule" "task_from_alb_dps" {
  security_group_id            = aws_security_group.task.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8090
  to_port                      = 8090
  ip_protocol                  = "tcp"
  description                  = "DPS from the ALB."
}

resource "aws_vpc_security_group_egress_rule" "task_all" {
  security_group_id = aws_security_group.task.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = <<-EOT
    Unrestricted egress, which the task genuinely needs: ECR and Secrets Manager to start at all,
    then Google and Facebook OAuth, the OpenAI API and SES. Those are public endpoints, so this
    cannot be narrowed to a VPC endpoint list.
  EOT
}

resource "aws_security_group" "database" {
  name_prefix = "${local.name_prefix}-db-"
  description = "RDS: 5432 from the task only."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-db-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_task" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.task.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Postgres from the ECS task."
}

resource "aws_security_group" "efs" {
  name_prefix = "${local.name_prefix}-efs-"
  description = "EFS: NFS from the task only."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-efs-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "efs_from_task" {
  security_group_id            = aws_security_group.efs.id
  referenced_security_group_id = aws_security_group.task.id
  from_port                    = 2049
  to_port                      = 2049
  ip_protocol                  = "tcp"
  description                  = "NFS from the ECS task. Without this the task hangs on mount and the whole task times out, rather than starting without logs."
}
