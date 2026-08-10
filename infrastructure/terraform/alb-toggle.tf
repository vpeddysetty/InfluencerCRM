# Whether to create a load balancer at all.

variable "use_alb" {
  description = <<-EOT
    TRUE creates the ALB in alb.tf (~$17/month). FALSE creates nothing there and a Caddy container in
    the task terminates TLS instead — see ecs-containers-extra.tf for what that costs you.

    Both paths are kept because the ALB is the right answer the moment there is more than one task: it
    load-balances, it survives instance replacement without DNS changes, and its health checks are what
    the ECS deployment circuit breaker reads.
  EOT
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Elastic IP — the ALB's replacement for stable DNS
# ---------------------------------------------------------------------------
# Without a load balancer, the public address is the instance's. A Spot instance is replaced without
# warning and gets a NEW public IP, so a DNS record pointing at it would break on every reclamation.
#
# An Elastic IP is a fixed address that the replacement instance re-associates to itself at boot (see
# the user-data script). DNS therefore never changes.
#
# It is not free while unattached (~$3.60/month), but it IS free while attached to a running instance —
# which is the normal state. AWS charges for idle addresses, not used ones.
resource "aws_eip" "app" {
  count = local.caddy_enabled ? 1 : 0

  domain = "vpc"
  tags = {
    Name = "${local.name_prefix}-app"
    # The boot script finds the address by this tag rather than by a hardcoded id, so replacing the EIP
    # does not require a new AMI or launch template.
    Role = "influencrm-app-eip"
  }
}

data "aws_iam_policy_document" "instance_eip" {
  count = local.caddy_enabled ? 1 : 0

  statement {
    sid    = "AssociateAppEip"
    effect = "Allow"
    actions = [
      "ec2:AssociateAddress",
      "ec2:DescribeAddresses",
    ]
    # Neither action supports resource-level permissions for the association target in a way that is
    # useful here; the instance profile is scoped by being attached only to this ASG's instances.
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "instance_eip" {
  count = local.caddy_enabled ? 1 : 0

  name   = "associate-app-eip"
  role   = aws_iam_role.instance[0].id
  policy = data.aws_iam_policy_document.instance_eip[0].json
}

# The instance serves 80/443 directly when Caddy replaces the ALB, so those ports must be open to the
# internet here rather than only to a load balancer's security group.
resource "aws_vpc_security_group_ingress_rule" "instance_http" {
  for_each = local.caddy_enabled ? toset(var.alb_ingress_cidrs) : toset([])

  security_group_id = aws_security_group.instance[0].id
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "HTTP. Caddy redirects to HTTPS, and Lets Encrypt needs :80 reachable for the HTTP-01 challenge."
}

resource "aws_vpc_security_group_ingress_rule" "instance_https" {
  for_each = local.caddy_enabled ? toset(var.alb_ingress_cidrs) : toset([])

  security_group_id = aws_security_group.instance[0].id
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "HTTPS, terminated by Caddy in the task."
}
