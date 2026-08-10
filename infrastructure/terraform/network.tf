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

# The ALB security group and the ECS task security group are GONE, with the ALB and the task themselves.
# The platform instance is now the edge: aws_security_group.compose_instance in compose-ec2.tf opens 80
# and 443 to the internet for Caddy, and nothing else.
#
# THE NINE SERVICE PORTS NEED NO RULES AT ALL. Under ECS they were reachable only on the task's loopback
# interface; under Compose they are reachable only on the bridge network, which is internal to the
# instance and has no route from outside. That is why this file no longer carries a rule per service —
# not because the ports were opened, but because there is nothing to open them to.

resource "aws_security_group" "database" {
  name_prefix = "${local.name_prefix}-db-"
  description = "RDS: 5432 from the platform instance only."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-db-sg" }
}

resource "aws_security_group" "efs" {
  name_prefix = "${local.name_prefix}-efs-"
  description = "EFS: NFS from the platform instance only."
  vpc_id      = aws_vpc.main.id

  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = "${local.name_prefix}-efs-sg" }
}

# The ingress rules for both groups live in compose-ec2.tf, next to the security group they admit —
# database_from_compose and efs_from_compose. Without them the platform cannot reach its own database,
# and every EFS mount hangs until it times out.
