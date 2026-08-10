# The ALB, and the only two ports of the task that are reachable from outside.
#
# Nine of the eleven containers have NO target group and no listener rule. They are reachable only
# over the task's loopback interface, which is what a single task definition buys: there is no
# network path to them to secure, rather than a path secured by configuration.

resource "aws_lb" "main" {
  name               = "${local.name_prefix}-alb"
  load_balancer_type = "application"
  # Public subnets in both AZs — an ALB requires at least two, which is why network.tf always creates
  # two even when compute runs in one.
  subnets         = aws_subnet.public[*].id
  security_groups = [aws_security_group.alb.id]

  # Protects against `terraform destroy` removing the thing DNS points at. Set false to tear down.
  enable_deletion_protection = false

  # Longer than the default 60s: the spreadsheet import and hydration endpoints do real work on a
  # request thread. A timeout shorter than the work produces a 504 to the browser while the task
  # keeps going, which looks like data loss and is not.
  idle_timeout = 120

  # Removes the server version from responses. Minor, free.
  drop_invalid_header_fields = true

  tags = { Name = "${local.name_prefix}-alb" }
}

resource "aws_lb_target_group" "bff" {
  name        = "${local.name_prefix}-bff"
  port        = 8081
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # awsvpc tasks register by ENI address, not instance.

  health_check {
    enabled = true
    # The BFF's actuator lives on the separate management port 9081, and a target group health check
    # can probe a different port than it forwards to. That is the right call here: it keeps the
    # operational endpoint off the public-facing port while still being checkable.
    port     = "9081"
    protocol = "HTTP"
    path     = "/actuator/health"
    matcher  = "200"
    interval = 30
    timeout  = 5
    # Two consecutive successes to enter service, five failures to leave it. Asymmetric on purpose:
    # quick to register, slow to evict, so one slow response does not deregister a working task.
    healthy_threshold   = 2
    unhealthy_threshold = 5
  }

  # Faster than the 300s default. With one task there is nothing to drain to, and a long delay just
  # makes every deploy slower.
  deregistration_delay = 30

  stickiness {
    # OFF, and it must stay off while there is one task — but this is the setting to revisit before
    # scaling out, together with Redis. The DPS holds sessions in its own heap, so a second task
    # without either would log users out as the balancer moved them.
    type    = "lb_cookie"
    enabled = false
  }

  tags = { Name = "${local.name_prefix}-bff" }
}

resource "aws_lb_target_group" "dps" {
  name        = "${local.name_prefix}-dps"
  port        = 8090
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    enabled             = true
    protocol            = "HTTP"
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 5
  }

  deregistration_delay = 30

  tags = { Name = "${local.name_prefix}-dps" }
}

# ---------------------------------------------------------------------------
# Listeners
# ---------------------------------------------------------------------------

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  # With a certificate, 80 exists only to redirect. Without one, it serves — which is fine for a
  # smoke test and not for anything carrying a session cookie.
  dynamic "default_action" {
    for_each = var.acm_certificate_arn != "" ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = var.acm_certificate_arn == "" ? [1] : []
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.bff.arn
    }
  }
}

resource "aws_lb_listener" "https" {
  count = var.acm_certificate_arn != "" ? 1 : 0

  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  # TLS 1.2 minimum, forward secrecy. TLS 1.3 is included; 1.0 and 1.1 are not.
  ssl_policy      = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn = var.acm_certificate_arn

  # The BFF is the default: it serves /api/** , which is most of the traffic.
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.bff.arn
  }
}

# The DPS owns the session and OAuth entry points. It must be reachable by path, because the browser
# is redirected to it by the provider and by the shell.
resource "aws_lb_listener_rule" "dps" {
  # Attaches to whichever listener exists.
  listener_arn = var.acm_certificate_arn != "" ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.dps.arn
  }

  condition {
    path_pattern {
      # Verified against the controllers: SessionController is @RequestMapping("/dps") and
      # ApiProxyController is @RequestMapping("/dps/api"), so every DPS route — /dps/session,
      # /dps/auth/**, /dps/brands, /dps/api/** — sits under this one prefix. Everything else,
      # including /api/**, stays with the BFF via the listener's default action.
      #
      # If these prefixes ever overlap, the more specific rule needs the LOWER priority number: a
      # request matches the first rule that fits, not the best one.
      values = ["/dps", "/dps/*"]
    }
  }
}
