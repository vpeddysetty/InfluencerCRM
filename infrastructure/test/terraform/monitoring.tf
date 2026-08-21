# Alerting. There was none before this file.
#
# WHY THIS ONE ALARM FIRST. The DAO's TLS certificate expires 2028-11-12 and NOTHING renews it. Caddy's
# public certificate renews itself through Let's Encrypt; this internal one is self-signed by
# bootstrap-secrets.sh and simply stops working one day. When it does, the BFF fails every call to the
# DAO and the platform is down — and the symptom (a healthy-looking BFF returning 502s) points nowhere
# near an expiry date.
#
# An expiry that is 800+ days away is exactly the kind of thing that gets forgotten, which is the whole
# argument for wiring the alarm now rather than "closer to the time".
#
# WHAT THIS IS NOT. This is not general platform monitoring. There are no alarms here for CPU, for the
# instance disappearing, for RDS, or for the application returning errors — all of which a production
# environment needs and this test environment does not have. See ../../prod/README.md.

# ---------------------------------------------------------------------------
# Where alerts go
# ---------------------------------------------------------------------------

resource "aws_sns_topic" "alerts" {
  name = "${local.name_prefix}-alerts"

  # The topic is encrypted, because an alarm body can name hosts and resources. `alias/aws/sns` rather
  # than the platform's own key: the CloudWatch alarm service must be able to publish, and granting it
  # use of a customer-managed key means a key policy statement for a service principal — more moving
  # parts than an alert topic is worth.
  kms_master_key_id = "alias/aws/sns"

  tags = { Name = "${local.name_prefix}-alerts" }
}

resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "AllowCloudWatchAlarmsToPublish"
      Effect = "Allow"
      Principal = {
        Service = "cloudwatch.amazonaws.com"
      }
      Action   = "SNS:Publish"
      Resource = aws_sns_topic.alerts.arn
      Condition = {
        # Confused-deputy guard: without it, any account's alarm could publish to this topic.
        StringEquals = {
          "AWS:SourceAccount" = data.aws_caller_identity.current.account_id
        }
      }
    }]
  })
}

# The subscription is created ONLY when an address is given, because an SNS email subscription must be
# confirmed by clicking a link in an email. Creating one nobody confirms leaves a topic that looks
# wired up and delivers nothing — worse than no alarm, because it invites trust.
resource "aws_sns_topic_subscription" "alerts_email" {
  count = var.alert_email != "" ? 1 : 0

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email

  lifecycle {
    # AWS reports the subscription as "PendingConfirmation" until the link is clicked, and Terraform
    # would otherwise try to replace it on every plan.
    ignore_changes = [id]
  }
}

variable "alert_email" {
  description = <<-EOT
    Address that receives alarm notifications.

    EMPTY MEANS NO SUBSCRIPTION, and therefore no delivery: the alarm still fires and is visible in the
    CloudWatch console, but nothing reaches a human. That is a deliberate default — an unconfirmed
    subscription is indistinguishable from a working one until the day it matters.

    AWS sends a confirmation email when this is first set. The subscription does not deliver anything
    until that link is clicked.
  EOT
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------
# The metric, and the alarm on it
# ---------------------------------------------------------------------------
# CloudWatch has no built-in metric for "days until this self-signed certificate expires" — it only
# knows about ACM certificates, and this one is not in ACM. So the instance publishes it: a systemd
# timer reads the certificate the DAO is actually serving and puts the number of days remaining.
#
# READ FROM THE LIVE ENDPOINT, not from the secret. The question worth alarming on is "what is the DAO
# serving right now", which catches a rotation that updated the secret but never reached the container —
# precisely the drift that went unnoticed for a day on 2026-08-10.

resource "aws_cloudwatch_metric_alarm" "dao_cert_expiry" {
  alarm_name        = "${local.name_prefix}-dao-cert-expiry"
  alarm_description = <<-EOT
    The DAO's internal TLS certificate is approaching expiry. Nothing renews it automatically.

    To rotate:
      bash infrastructure/scripts/bootstrap-secrets.sh ${var.environment} ${var.aws_region} tls
      # then, on the instance:
      sudo systemctl restart influencrm-secrets influencrm

    When it expires the BFF fails every call to the DAO and the platform is down, with a symptom that
    does not obviously point at a certificate.
  EOT

  namespace   = "InfluenCRM/TLS"
  metric_name = "DaoCertificateDaysRemaining"
  statistic   = "Minimum"
  period      = 3600

  comparison_operator = "LessThanThreshold"
  threshold           = var.cert_expiry_alarm_days
  evaluation_periods  = 1

  # BREACHING, and this is the important part. The metric is published hourly by one instance; if that
  # instance is gone, or the timer is broken, or the metric was never published at all, the data is
  # MISSING. Treating missing as "not breaching" would make a silently-broken monitor look identical to
  # a healthy certificate — which is the failure mode this alarm exists to prevent.
  treat_missing_data = "breaching"

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]

  tags = { Name = "${local.name_prefix}-dao-cert-expiry" }
}

variable "cert_expiry_alarm_days" {
  description = <<-EOT
    Days before expiry at which the alarm fires.

    45 by default. Rotation needs a certificate regenerated, two secrets updated and the platform
    restarted — minutes of work, but it has to be noticed and scheduled first. Six weeks leaves room
    for a holiday and a busy fortnight.

    The alarm also fires when the metric is MISSING, so this threshold governs the healthy case only.
  EOT
  type        = number
  default     = 45
}
