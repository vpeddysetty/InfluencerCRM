output "public_address" {
  description = <<-EOT
    Where the platform answers from outside: the Elastic IP that Caddy serves on. Stable across Spot
    replacements, because the replacement instance re-associates it to itself at boot.

    An IP is a smoke-test address only: OAuth providers reject an IP as a redirect URI and Let's Encrypt
    cannot certify one, so sign-in needs api_domain pointed at this address.
  EOT
  value       = aws_eip.app.public_ip
}

output "application_url" {
  description = "Where the API actually answers, accounting for whether a custom domain is configured."
  value       = local.public_base_url
}

output "ecr_repository_urls" {
  description = "Push targets, one per service. scripts/build-and-push.sh derives these itself; this is for eyeballing."
  value       = { for k, v in aws_ecr_repository.service : k => v.repository_url }
}

output "ecr_registry" {
  description = "Registry host for `docker login`."
  value       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com"
}

output "database_endpoint" {
  description = "RDS endpoint. Reachable only from inside the VPC - the platform instance's security group is the only ingress."
  value       = var.use_rds ? aws_db_instance.main[0].address : "no database (use_rds is false)"
}

output "efs_file_system_id" {
  description = "EFS filesystem holding the shared log directory and the asset store."
  value       = aws_efs_file_system.main.id
}

output "autoscaling_group_name" {
  description = <<-EOT
    The ASG holding the single platform instance. Deploying a new image tag is an instance refresh on
    this group, because the compose file and the image tag are baked into the launch template's user
    data - so `terraform apply` followed by a refresh is the deploy.
  EOT
  value       = aws_autoscaling_group.compose.name
}

output "deploy_command" {
  description = "Roll the instance to pick up a new image tag or compose file, after `terraform apply`."
  value       = "aws autoscaling start-instance-refresh --auto-scaling-group-name ${aws_autoscaling_group.compose.name} --region ${var.aws_region}"
}

output "ui_bucket" {
  description = "Bucket holding all seven micro-frontend bundles, one prefix each. Empty when manage_static_site is false."
  value       = local.static_enabled ? aws_s3_bucket.ui[0].id : ""
}

output "ui_distribution_ids" {
  description = "Distribution id per micro-frontend, for cache invalidation after a deploy. deploy-ui.sh reads these itself."
  value       = { for k, d in aws_cloudfront_distribution.ui : k => d.id }
}

output "ui_urls" {
  description = "Where each micro-frontend is served. The shell is the one a user visits; the rest are federation origins the shell fetches from."
  value = {
    for k, cfg in local.micro_frontends :
    k => local.static_aliased ? "https://${cfg.subdomain}.${var.root_domain}" : "https://${aws_cloudfront_distribution.ui[k].domain_name}"
  }
}

output "shell_build_env" {
  description = <<-EOT
    The env vars the SHELL must be built with, so its federation config points at the deployed
    remotes. These are BUILD-TIME (Vite inlines them), so changing an origin means rebuilding and
    redeploying the shell — not just editing config.

    Feed them to scripts/deploy-ui.sh, which writes them into an .env.production before building.
  EOT
  value = merge(
    {
      VITE_USE_REMOTES = "true"
      VITE_BFF_URL     = local.public_base_url
      VITE_DPS_URL     = local.public_base_url

      # Where the landing page sends a returning creator. The portal is NOT a federated remote --
      # it is a separate app with its own sign-in -- so it carries no env_var in
      # local.micro_frontends and the loop below skips it. Named here instead.
      #
      # Falls back to the distribution's own domain when no custom domain is aliased, matching how
      # the remotes below resolve; an empty string would render a dead link rather than none,
      # because the component only tests whether the value is set.
      VITE_CREATOR_PORTAL_URL = local.static_aliased ? "https://portal.${var.root_domain}" : try("https://${aws_cloudfront_distribution.ui["creator-portal"].domain_name}", "")
    },
    {
      for k, cfg in local.micro_frontends :
      cfg.env_var => (local.static_aliased ? "https://${cfg.subdomain}.${var.root_domain}" : try("https://${aws_cloudfront_distribution.ui[k].domain_name}", ""))
      if cfg.env_var != ""
    },
  )
}

output "secrets_requiring_values" {
  description = <<-EOT
    Secrets created EMPTY that must be populated before the features they gate will work. The BFF and
    the DAO will not start at all until jwt-signing-key, dao-keystore-b64 and dao-keystore-password
    hold real values.
  EOT
  value       = { for k, v in aws_secretsmanager_secret.external : k => v.arn }
}

output "logs_command" {
  description = "Tail the platform's CloudWatch logs."
  value       = "aws logs tail ${aws_cloudwatch_log_group.platform.name} --follow --region ${var.aws_region}"
}

output "shell_command" {
  description = <<-EOT
    Open a shell ON THE INSTANCE via SSM Session Manager - there is no SSH key and no inbound 22. From
    there, `docker compose -f /opt/influencrm/docker-compose.yml ps` and
    `docker exec -it influencrm-dao-1 /bin/sh` reach the containers.

    This replaces `aws ecs execute-command`: without a control plane, the way in is the host.
  EOT
  value       = "aws ssm start-session --target <instance-id> --region ${var.aws_region}"
}

output "boot_log_command" {
  description = "Read the boot script's output when the platform never comes up. Works even before SSM is running."
  value       = "aws ec2 get-console-output --instance-id <instance-id> --query Output --output text --region ${var.aws_region}"
}
