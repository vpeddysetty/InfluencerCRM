output "alb_dns_name" {
  description = "The ALB hostname. Reach the BFF here when api_domain is unset."
  value       = aws_lb.main.dns_name
}

output "application_url" {
  description = "Where the API actually answers, accounting for whether a custom domain and certificate are configured."
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
  description = "RDS endpoint. Reachable only from inside the VPC — the task's security group is the only ingress."
  value       = aws_db_instance.main.address
}

output "efs_file_system_id" {
  description = "EFS filesystem holding the shared log directory and the asset store."
  value       = aws_efs_file_system.main.id
}

output "cluster_name" {
  description = "ECS cluster name, for `aws ecs` commands and ECS Exec."
  value       = aws_ecs_cluster.main.name
}

output "service_name" {
  description = "ECS service name."
  value       = aws_ecs_service.main.name
}

output "task_definition_arn" {
  description = "The revision currently deployed. Note the revision number — it is what a rollback targets."
  value       = aws_ecs_task_definition.main.arn
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
  value       = "aws logs tail ${aws_cloudwatch_log_group.ecs.name} --follow --region ${var.aws_region}"
}

output "exec_command_example" {
  description = "Open a shell in a running container (container names: dao, web-experience, dps, agent, identity, …)."
  value       = "aws ecs execute-command --cluster ${aws_ecs_cluster.main.name} --task <task-id> --container dao --interactive --command /bin/sh --region ${var.aws_region}"
}
