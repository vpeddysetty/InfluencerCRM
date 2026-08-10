# ECS cluster, the single task definition holding all eleven containers, and the service.
#
# ONE TASK DEFINITION, ELEVEN CONTAINERS. Containers in a task share a network namespace, so they
# reach each other on localhost:<port> — no Service Connect, no Cloud Map, no mesh. That is why the
# URL variables below are mostly absent: `localhost` is already every one of their defaults.

locals {
  # Per-container memory. NOT optional: each JVM runs -XX:MaxRAMPercentage=75, and a container with
  # no limit of its own measures that 75% against the TASK total — eleven times over. The sum here is
  # 7936MB against a task_memory of 8192MB, leaving 256MB for the agent's headroom and Fargate's own
  # overhead.
  container_memory = {
    dao            = 1024 # 45 JPA repositories, the whole schema.
    web-experience = 1024 # Every outbound integration lives here.
    dps            = 768  # Holds sessions in-heap while Redis is out of scope.
    workflow       = 512
    identity       = 512
    creator        = 512
    campaign       = 512
    attribution    = 512
    finance        = 512
    content        = 512
    agent          = 1024 # Python + openai + langgraph; the largest resident set of the non-JVM containers.
  }

  # Which services the platform cannot serve a request without. A non-essential container that dies
  # is restarted without stopping the task; an essential one takes the whole task down. In a
  # single-task deployment "the whole task" is the entire platform, so this list is short on purpose:
  # losing `finance` should degrade payouts, not sign-in.
  essential_containers = ["dao", "web-experience", "dps"]

  # The externally reachable origin. Falls back to the ALB's own DNS name, which is enough to smoke
  # test but cannot be registered as an OAuth callback.
  public_base_url = var.public_base_url != "" ? var.public_base_url : "http://${aws_lb.main.dns_name}"
  ui_base_url     = var.ui_base_url != "" ? var.ui_base_url : local.public_base_url

  # Environment shared by every Spring container.
  common_environment = [
    { name = "LOG_DIR", value = "/mnt/logs" },
    { name = "TZ", value = "UTC" },
    # Container-aware heap sizing. Set here as well as in the image so the value is visible in the
    # task definition rather than only inside a Dockerfile.
    { name = "JAVA_OPTS", value = "-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport" },
  ]

  # The JDBC URL every service uses. One database, many roles: the DAO connects as the master user
  # because it owns every schema; the extracted services connect as their own svc_* roles, which is
  # what makes a cross-context query fail at the database.
  jdbc_url = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${var.db_name}?stringtype=unspecified"

  # Repeated for each of the seven extracted services, differing only in the variable NAME prefix.
  # The svc_* passwords are the `change-me-<ctx>` defaults set by the context-roles migration and are
  # NOT rotated here — see the note in the README. They are reachable only from inside the task.
  context_services = {
    workflow    = { port = 8444, env_prefix = "WORKFLOW" }
    identity    = { port = 8445, env_prefix = "IDENTITY" }
    creator     = { port = 8446, env_prefix = "CREATOR" }
    campaign    = { port = 8447, env_prefix = "CAMPAIGN" }
    attribution = { port = 8448, env_prefix = "ATTRIBUTION" }
    finance     = { port = 8449, env_prefix = "FINANCE" }
    content     = { port = 8450, env_prefix = "CONTENT" }
  }

  # Mount both EFS access points into every Spring container. The log directory is genuinely shared —
  # all ten append into it so `rid` ties one browser action across the chain — while only the BFF
  # writes assets. Mounting assets everywhere costs nothing and means the next service that needs it
  # is a property change rather than a task definition change.
  common_mount_points = [
    { sourceVolume = "logs", containerPath = "/mnt/logs", readOnly = false },
    { sourceVolume = "assets", containerPath = "/mnt/assets", readOnly = false },
  ]

  # awslogs for every container. This is the copy that survives a task dying before it can write to
  # EFS — which is exactly the failure you most need to read.
  log_configuration = {
    logDriver = "awslogs"
    options = {
      "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
      "awslogs-region"        = var.aws_region
      "awslogs-stream-prefix" = "influencrm"
    }
  }

  # ---------------------------------------------------------------------------
  # Container definitions
  # ---------------------------------------------------------------------------

  dao_container = {
    name      = "dao"
    image     = "${aws_ecr_repository.service["dao"].repository_url}:${var.image_tag}"
    essential = true
    memory    = local.container_memory["dao"]
    # No portMappings entry is needed for localhost traffic between containers in the same task, but
    # declaring it documents the contract and is required for a container to be an ALB target.
    portMappings = [{ containerPort = 8443, protocol = "tcp" }]

    environment = concat(local.common_environment, [
      { name = "DAO_DB_URL", value = local.jdbc_url },
      { name = "DAO_DB_USER", value = var.db_master_username },
      # Points server.ssl.key-store at the file the entrypoint decodes from DAO_KEYSTORE_B64.
      # /dev/shm is tmpfs: the private key never touches a disk and never survives the task.
      { name = "DAO_KEYSTORE", value = "file:/dev/shm/influencrm/keystore.p12" },
    ])

    secrets = [
      { name = "DAO_DB_PASSWORD", valueFrom = local.secret_arns["db-password"] },
      { name = "DAO_SERVICE_TOKEN", valueFrom = local.secret_arns["dao-service-token"] },
      # Verifies the workload tokens the BFF presents — the signed tenant id that closes the
      # optional-brandId IDOR. Same secret on both sides.
      { name = "DAO_WORKLOAD_SIGNING_KEY", valueFrom = local.secret_arns["workload-signing-key"] },
      { name = "DAO_KEYSTORE_B64", valueFrom = local.secret_arns["dao-keystore-b64"] },
      { name = "DAO_KEYSTORE_PASSWORD", valueFrom = local.secret_arns["dao-keystore-password"] },
    ]

    mountPoints = local.common_mount_points

    healthCheck = {
      # /health, not /actuator/health: DaoSecurityConfig and both filters exempt that exact path from
      # the service token, and a health check cannot present one. -k because the certificate is
      # self-signed; this checks liveness, not trust — the BFF is what verifies the certificate.
      command  = ["CMD-SHELL", "curl -fsk https://localhost:8443/health || exit 1"]
      interval = 15
      timeout  = 5
      retries  = 3
      # Hibernate against this schema is slower than the interval on a cold start. Without this the
      # first checks fail and ECS restarts a container that was only still booting.
      startPeriod = 120
    }

    logConfiguration = local.log_configuration
  }

  bff_container = {
    name         = "web-experience"
    image        = "${aws_ecr_repository.service["web-experience"].repository_url}:${var.image_tag}"
    essential    = true
    memory       = local.container_memory["web-experience"]
    portMappings = [{ containerPort = 8081, protocol = "tcp" }]

    environment = concat(local.common_environment, [
      # localhost, because these containers share a network namespace. The DAO's certificate carries
      # `localhost` as a SAN precisely so this stays https with verification ON.
      { name = "WEBE_DAO_BASE_URL", value = "https://localhost:8443" },
      { name = "WEBE_DAO_TLS_VERIFICATION", value = "true" },
      { name = "WEBE_AGENT_BASE_URL", value = "http://localhost:8000" },
      { name = "WEBE_DPS_BASE_URL", value = "http://localhost:8090" },
      { name = "WORKFLOW_SERVICE_URL", value = "http://localhost:8444" },
      # Externally reachable origins. These end up in redirects and CORS headers, so they must be
      # what a BROWSER can reach — not localhost.
      { name = "WEBE_PUBLIC_BASE_URL", value = local.public_base_url },
      { name = "WEBE_UI_BASE_URL", value = local.ui_base_url },
      # Uploaded assets must live on the mount: a container's own filesystem does not survive the
      # task, so an upload would vanish on the next deploy.
      { name = "WEBE_ASSET_ROOT", value = "/mnt/assets" },
      { name = "WEBE_EMAIL_FROM", value = var.ses_from_address },
      { name = "AWS_SES_REGION", value = var.aws_region },
      # The workflow service exists and is healthy, but the cutover flag stays OFF: flipping it is a
      # deliberate act after a dual-run soak, and it is the rollback too. Seconds either way.
      { name = "WORKFLOW_SERVICE_ENABLED", value = "false" },
    ])

    secrets = [
      { name = "DAO_SERVICE_TOKEN", valueFrom = local.secret_arns["dao-service-token"] },
      { name = "WORKFLOW_SERVICE_TOKEN", valueFrom = local.secret_arns["workflow-service-token"] },
      # Without this the BFF REFUSES TO START, deliberately: tokens signed by an ephemeral key cannot
      # be verified after a restart, which presents as intermittent auth failures rather than an error.
      { name = "WEBE_JWT_SIGNING_KEY", valueFrom = local.secret_arns["jwt-signing-key"] },
      { name = "WEBE_WORKLOAD_SIGNING_KEY", valueFrom = local.secret_arns["workload-signing-key"] },
      { name = "WEBE_WORKLOAD_DPS_KEY", valueFrom = local.secret_arns["workload-signing-key"] },
      { name = "WEBE_MARKETPLACE_CREDENTIAL_KEY", valueFrom = local.secret_arns["marketplace-credential-key"] },
      { name = "GOOGLE_OAUTH_CLIENT_ID", valueFrom = local.secret_arns["google-oauth-client-id"] },
      { name = "GOOGLE_OAUTH_CLIENT_SECRET", valueFrom = local.secret_arns["google-oauth-client-secret"] },
      { name = "FACEBOOK_OAUTH_CLIENT_ID", valueFrom = local.secret_arns["facebook-oauth-client-id"] },
      { name = "FACEBOOK_OAUTH_CLIENT_SECRET", valueFrom = local.secret_arns["facebook-oauth-client-secret"] },
      { name = "STRIPE_SECRET_KEY", valueFrom = local.secret_arns["stripe-secret-key"] },
      { name = "WEBE_BILLING_WEBHOOK_SECRET", valueFrom = local.secret_arns["billing-webhook-secret"] },
      { name = "YOUTUBE_API_KEY", valueFrom = local.secret_arns["youtube-api-key"] },
      # STILL KEY-BASED. The task role already holds ses:SendEmail, but SesEmailSender reads explicit
      # access keys from configuration, so the role alone would not be used. Populate these secrets
      # with an IAM user's keys, or change the adapter to the default credentials provider — the
      # latter is better and is a code change, noted in the README.
      { name = "AWS_SES_ACCESS_KEY_ID", valueFrom = local.secret_arns["ses-access-key-id"] },
      { name = "AWS_SES_SECRET_ACCESS_KEY", valueFrom = local.secret_arns["ses-secret-access-key"] },
    ]

    mountPoints = local.common_mount_points

    # The BFF must not accept traffic before the DAO can answer. Without this every container starts
    # at once and the BFF's first calls fail until the DAO finishes booting.
    dependsOn = [{ containerName = "dao", condition = "HEALTHY" }]

    healthCheck = {
      # 9081 is the separate management port. Operational endpoints deliberately do not share the
      # public-facing one, so exposing the app does not expose its internals by the same route.
      command     = ["CMD-SHELL", "curl -fs http://localhost:9081/actuator/health || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 120
    }

    logConfiguration = local.log_configuration
  }

  dps_container = {
    name         = "dps"
    image        = "${aws_ecr_repository.service["dps"].repository_url}:${var.image_tag}"
    essential    = true
    memory       = local.container_memory["dps"]
    portMappings = [{ containerPort = 8090, protocol = "tcp" }]

    environment = concat(local.common_environment, [
      { name = "DPS_BFF_URL", value = "http://localhost:8081" },
      { name = "DPS_UI_BASE_URL", value = local.ui_base_url },
      # Secure cookies over HTTPS only. With a certificate on the ALB this must be true, or the
      # session cookie travels in clear text; without one it must be false, or the browser silently
      # never sends the cookie and every request looks unauthenticated.
      { name = "DPS_COOKIE_SECURE", value = var.acm_certificate_arn != "" ? "true" : "false" },
      # Cross-origin micro-frontends need SameSite=None, which the spec only permits alongside Secure.
      { name = "DPS_COOKIE_SAME_SITE", value = var.acm_certificate_arn != "" ? "None" : "Lax" },
      { name = "DPS_ALLOWED_ORIGINS", value = local.ui_base_url },
      # DPS_SESSION_STORE deliberately unset: in-memory Caffeine. Correct for one instance, wrong for
      # more than one — which is why desired_count is validated to 1.
    ])

    secrets = [
      { name = "DPS_SERVICE_TOKEN", valueFrom = local.secret_arns["dps-service-token"] },
      { name = "DPS_WORKLOAD_SIGNING_KEY", valueFrom = local.secret_arns["workload-signing-key"] },
    ]

    mountPoints = local.common_mount_points
    dependsOn   = [{ containerName = "web-experience", condition = "HEALTHY" }]

    healthCheck = {
      command     = ["CMD-SHELL", "curl -fs http://localhost:8090/actuator/health || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 120
    }

    logConfiguration = local.log_configuration
  }

  # The seven extracted services, generated rather than written out: they differ only in name, port,
  # and the prefix of their database variables.
  context_containers = [
    for name, cfg in local.context_services : {
      name  = name
      image = "${aws_ecr_repository.service[name].repository_url}:${var.image_tag}"
      # Non-essential: one context service dying degrades that context, it does not take the platform
      # down. In a single-task deployment that distinction is the whole blast radius.
      essential    = false
      memory       = local.container_memory[name]
      portMappings = [{ containerPort = cfg.port, protocol = "tcp" }]

      environment = concat(local.common_environment, [
        { name = "${cfg.env_prefix}_DB_URL", value = local.jdbc_url },
        # Username and password are left at their application defaults (svc_<ctx> /
        # change-me-<ctx>), created by the context-roles migration. Reachable only from inside the
        # task; see the README for why rotating them is a migration, not a variable.
      ])

      secrets = [
        { name = "WORKLOAD_SIGNING_KEY", valueFrom = local.secret_arns["workload-signing-key"] },
      ]

      mountPoints = local.common_mount_points

      healthCheck = {
        # /health, not /actuator/health: these services exempt that exact path from their service
        # token and nothing else. Before it was mapped there, /actuator/health returned 401 and an
        # ALB target group would have refused to register any of them.
        command     = ["CMD-SHELL", "curl -fs http://localhost:${cfg.port}/health || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 120
      }

      logConfiguration = local.log_configuration
    }
  ]

  agent_container = {
    name         = "agent"
    image        = "${aws_ecr_repository.service["agent"].repository_url}:${var.image_tag}"
    essential    = false
    memory       = local.container_memory["agent"]
    portMappings = [{ containerPort = 8000, protocol = "tcp" }]

    environment = [
      { name = "LOG_DIR", value = "/mnt/logs" },
      { name = "TZ", value = "UTC" },
      # libpq, not JDBC — the agent uses psycopg. Same database, different URL grammar.
      {
        name  = "DATABASE_URL"
        value = "postgresql://${var.db_master_username}@${aws_db_instance.main.address}:5432/${var.db_name}"
      },
      { name = "OPENAI_MODEL", value = "gpt-4.1-mini" },
      { name = "OPENAI_EMBEDDING_MODEL", value = "text-embedding-3-small" },
      { name = "RETRIEVAL_TOP_K", value = "3" },
      { name = "REVIEW_THRESHOLD", value = "0.7" },
    ]

    secrets = [
      # EXTERNALIZED, as asked. The agent already reads this from the environment, and python-dotenv
      # loads with override=False, so a real environment variable wins over any .env that might exist.
      # No code change was needed.
      { name = "OPENAI_API_KEY", valueFrom = local.secret_arns["openai-api-key"] },
      # psycopg reads the password from the URL or PGPASSWORD; the latter keeps it out of the
      # process's own connection string.
      { name = "PGPASSWORD", valueFrom = local.secret_arns["db-password"] },
    ]

    mountPoints = local.common_mount_points

    healthCheck = {
      # Python images ship no curl, and the interpreter is already here. /docs is FastAPI's own
      # endpoint and needs no application state, so it answers as soon as uvicorn is listening.
      command     = ["CMD-SHELL", "python -c \"import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/docs').status==200 else 1)\""]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }

    logConfiguration = local.log_configuration
  }

  all_containers = concat(
    [local.dao_container, local.bff_container, local.dps_container, local.agent_container],
    local.context_containers,
  )
}

resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/${local.name_prefix}"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.main.arn

  tags = { Name = "${local.name_prefix}-logs" }
}

resource "aws_ecs_cluster" "main" {
  name = "${local.name_prefix}-cluster"

  setting {
    # Per-service and per-task CloudWatch metrics. Costs a little; not having them means a
    # performance question has no data behind it.
    name  = "containerInsights"
    value = "enhanced"
  }

  tags = { Name = "${local.name_prefix}-cluster" }
}

resource "aws_ecs_task_definition" "main" {
  family = "${local.name_prefix}-platform"

  requires_compatibilities = ["FARGATE"]
  # awsvpc is the only mode Fargate supports, and it is what gives every container in the task one
  # shared network namespace — the reason localhost works between them.
  network_mode = "awsvpc"

  cpu    = var.task_cpu
  memory = var.task_memory

  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    # X86_64 explicitly. The images are built on an x86 machine; ARM64 would be cheaper but the
    # Fargate platform must match what was pushed, and a mismatch fails at task start with an
    # exec-format error that reads like a corrupt image.
    cpu_architecture = "X86_64"
  }

  volume {
    name = "logs"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.main.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.logs.id
        # IAM authorization, so the task role's ClientMount/ClientWrite condition is what actually
        # gates the mount rather than only the security group.
        iam = "ENABLED"
      }
    }
  }

  volume {
    name = "assets"
    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.main.id
      transit_encryption = "ENABLED"
      authorization_config {
        access_point_id = aws_efs_access_point.assets.id
        iam             = "ENABLED"
      }
    }
  }

  container_definitions = jsonencode(local.all_containers)

  tags = { Name = "${local.name_prefix}-platform" }
}

resource "aws_ecs_service" "main" {
  name            = "${local.name_prefix}-platform"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.main.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"
  # Pinned rather than LATEST: a platform version change is an infrastructure change and should
  # appear in a plan, not arrive on the next task replacement.
  platform_version = "1.4.0"

  # A shell into a running container — the only practical way to debug a Fargate task, since there is
  # no host to log into.
  enable_execute_command = true

  network_configuration {
    subnets         = local.task_subnet_ids
    security_groups = [aws_security_group.task.id]
    # True while there is no NAT gateway: without a public IP the task cannot reach ECR to pull its
    # own images and would fail to start. It is still unreachable inbound — the security group admits
    # only the ALB.
    assign_public_ip = local.task_public_ip
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.bff.arn
    container_name   = "web-experience"
    container_port   = 8081
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.dps.arn
    container_name   = "dps"
    container_port   = 8090
  }

  # Ten JVMs starting together, each with a 120s startPeriod, is slow. Without this grace period the
  # service's own health check logic can kill the task before the containers have finished booting —
  # a crash loop that looks exactly like a broken image.
  health_check_grace_period_seconds = 300

  # With desired_count = 1 there is no rolling deploy to be had: 100/200 means ECS starts the new
  # task, waits for it to be healthy, then stops the old one. minimum_healthy_percent = 0 would be
  # faster and would take the platform down between the two.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    # Stop a deploy that cannot become healthy, and put the previous task definition back. Without
    # this a bad image retries until the deployment timeout with the platform down the whole time.
    enable   = true
    rollback = true
  }

  lifecycle {
    # The task definition revision is managed here, but if a deploy is ever performed out-of-band
    # (a console rollback, a CI `update-service`), Terraform should not fight it on the next apply.
    # Remove this line if Terraform is to be the only thing that ever deploys.
    ignore_changes = [desired_count]
  }

  depends_on = [
    # The listener must exist before the service registers targets, or registration races it.
    aws_lb_listener.http,
    # IAM propagation: the task fails to pull or to read secrets if the policy is not yet in effect.
    aws_iam_role_policy.execution_secrets,
    aws_iam_role_policy.task,
    # A missing mount target makes the task hang on mount until it times out.
    aws_efs_mount_target.main,
  ]

  tags = { Name = "${local.name_prefix}-platform" }
}
