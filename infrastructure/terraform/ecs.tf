# ECS cluster, the single task definition, and the service.
#
# ONE TASK DEFINITION, SEVEN CONTAINERS: postgres, migrate, dao, web-experience, dps, agent, caddy.
# Containers in a task share a network namespace, so they reach each other on localhost:<port> — no
# Service Connect, no Cloud Map, no mesh. That is why the URL variables below are mostly absent:
# `localhost` is already every one of their defaults.
#
# ECS caps a task at TEN containers. The seven extracted context services are therefore not here; see
# local.app_containers for why that costs nothing today.

locals {
  # Per-container memory. NOT optional: each JVM runs -XX:MaxRAMPercentage=75, and a container with no
  # limit of its own measures that 75% against the TASK total — eleven times over.
  #
  # SIZED FROM MEASUREMENT, not estimate. A Spring service was run under a hard 320MB cap: it started
  # cleanly and settled at 222MB (69%). The 384MB below is that floor plus headroom for a GC spike.
  #
  # The budget is tight and the arithmetic matters, because an 8GB instance leaves ~7.6GB usable after
  # the OS and the ECS agent, and ECS refuses to register a task definition whose containers exceed the
  # task total:
  #     postgres 512 + migrate 256 + caddy 128            =  896
  #     dao 896 + bff 896 + dps 640                       = 2432
  #     agent 768                                         =  768
  #                                                   total 4096  against task_memory 5120
  #
  # The seven context entries below are unused while those containers are dropped, and are kept so
  # re-enabling one does not also require re-deriving its size.
  container_memory = {
    dao            = 896 # 45 JPA repositories, the whole schema — the heaviest JVM here.
    web-experience = 896 # Every outbound integration lives here.
    dps            = 640 # Holds sessions in-heap while Redis is out of scope.
    workflow       = 384
    identity       = 384
    creator        = 384
    campaign       = 384
    attribution    = 384
    finance        = 384
    content        = 384
    agent          = 768 # Python + openai + langgraph; largest resident set of the non-JVM containers.
  }

  # Which services the platform cannot serve a request without. A non-essential container that dies
  # is restarted without stopping the task; an essential one takes the whole task down. In a
  # single-task deployment "the whole task" is the entire platform, so this list is short on purpose:
  # losing `finance` should degrade payouts, not sign-in.
  essential_containers = ["dao", "web-experience", "dps"]

  # Where a browser reaches the platform. With an ALB that is its DNS name; with Caddy it is the
  # Elastic IP, or api_domain once DNS points at it.
  #
  # An IP-based URL is a smoke-test address only: OAuth providers reject an IP as a redirect URI, and
  # Let's Encrypt cannot issue a certificate for one — so sign-in needs api_domain set either way.
  public_base_url = (
    var.public_base_url != "" ? var.public_base_url :
    var.use_alb ? "http://${aws_lb.main[0].dns_name}" :
    var.api_domain != "" ? "https://${var.api_domain}" :
    local.caddy_enabled ? "http://${aws_eip.app[0].public_ip}" : ""
  )
  ui_base_url = var.ui_base_url != "" ? var.ui_base_url : local.public_base_url

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
  jdbc_url = "jdbc:postgresql://${local.db_host}:5432/${var.db_name}?stringtype=unspecified"

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
  # every service appends into it so `rid` ties one browser action across the chain — while only the BFF
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
        value = "postgresql://${var.db_master_username}@${local.db_host}:5432/${var.db_name}"
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

  # Every application container must wait for the schema. `SUCCESS` means the migrate container exited
  # ZERO — not merely that it ran — so a failed migration stops the deploy instead of producing services
  # that fail later on a query against a table that does not exist.
  migrate_dependency = [{ containerName = "migrate", condition = "SUCCESS" }]

  # Merge the migration dependency into whatever a container already depends on. The BFF already waits
  # for the DAO, the DPS for the BFF; those orderings are preserved and the migration is added.
  # ECS ALLOWS AT MOST 10 CONTAINERS PER TASK DEFINITION. With postgres, migrate and caddy alongside the
  # application that is a hard ceiling, and 14 was rejected outright: "ClientException: Too many
  # containers."
  #
  # The seven extracted context services are the ones dropped, and it costs no functionality TODAY:
  # `web-experience.workflow-service-enabled` is false and no *_SERVICE_URL routing is enabled, so the
  # BFF already serves every domain from the monolith DAO. Those containers would have started, passed
  # their health checks, and received no traffic at all.
  #
  # To bring one back: set its flag on the BFF, and give it its own task definition and service — a
  # second task means cross-task calls, which needs ECS Service Connect rather than localhost. That is
  # the migration this single-task shape defers, not one it forecloses.
  #
  # local.context_containers stays defined but unused, so re-enabling is an edit here rather than
  # rewriting the generator.
  app_containers = [
    for c in [local.dao_container, local.bff_container, local.dps_container, local.agent_container] :
    merge(c, {
      dependsOn = concat(lookup(c, "dependsOn", []), local.migrate_dependency)
    })
  ]

  all_containers = concat(
    local.postgres_container, # first: everything else depends on it, directly or through migrate
    local.migrate_container,
    local.app_containers,
    local.caddy_container, # last: depends on the BFF and DPS being healthy
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

  # BOTH, deliberately. The same task definition then runs on Fargate or on the EC2 Spot capacity
  # provider, so `use_ec2_spot` is a genuine switch rather than two divergent definitions — and
  # flipping back to Fargate is a supported rollback rather than a rewrite.
  #
  # Declaring EC2 costs nothing when running on Fargate; it only widens what the definition is
  # *allowed* to run on.
  # EC2 ONLY. Declaring FARGATE as well was the original intent — one task definition runnable on
  # either, so switching back was a variable change — but it is INCOMPATIBLE with this design:
  # Fargate has no host filesystem, so it rejects the host_path volumes that Postgres data and Caddy
  # certificates require ("Fargate compatible task definitions do not support sourcePath").
  #
  # The Fargate rollback therefore is not a flag flip any more: it means use_rds = true and use_alb =
  # true as well, because Fargate cannot host the database or the proxy that replaced them. That is a
  # real coupling and worth stating rather than discovering.
  requires_compatibilities = ["EC2"]
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

  # The Postgres data directory, on the EBS volume that outlives the instance. A HOST path, not EFS:
  # Postgres on NFS is a documented data-corruption risk (locking semantics differ), and EFS would also
  # be slower for the write pattern of a database.
  #
  # The path is where the boot script mounts the EBS volume.
  dynamic "volume" {
    for_each = local.pg_container_enabled ? [1] : []
    content {
      name      = "pgdata"
      host_path = "/mnt/pgdata"
    }
  }

  # Caddy's certificate store. Also a host path on the EBS volume: losing it means re-requesting
  # certificates, and Let's Encrypt allows only 5 per domain per week.
  dynamic "volume" {
    for_each = local.caddy_enabled ? [1] : []
    content {
      name      = "caddydata"
      host_path = "/mnt/caddydata"
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

  # launch_type and capacity_provider_strategy are MUTUALLY EXCLUSIVE — setting both is an API error,
  # which is why each is conditional rather than one being a default.
  #
  # On Spot the strategy replaces the launch type entirely, and platform_version must be absent: it is
  # a Fargate-only concept and passing it with an EC2 capacity provider is rejected.
  launch_type = local.spot_enabled ? null : "FARGATE"

  dynamic "capacity_provider_strategy" {
    for_each = local.spot_enabled ? [1] : []
    content {
      capacity_provider = aws_ecs_capacity_provider.spot[0].name
      weight            = 100
      # base = 1: the first task must go to this provider. With base = 0 and one provider it behaves
      # the same, but stating it means adding an on-demand provider later does not silently change
      # where the first task lands.
      base = 1
    }
  }

  # Pinned rather than LATEST when it applies at all: a platform version change is an infrastructure
  # change and should appear in a plan, not arrive on the next task replacement.
  platform_version = local.spot_enabled ? null : "1.4.0"

  # A shell into a running container. On Fargate it is the only way in at all; on EC2 the instance is
  # also reachable via SSM Session Manager, which is how the manual RDS schema step gets a psql prompt
  # inside the VPC.
  enable_execute_command = true

  network_configuration {
    subnets         = local.task_subnet_ids
    security_groups = [aws_security_group.task.id]
    # FARGATE ONLY. On EC2 the ENI inherits its addressing from the instance, and ECS rejects the
    # parameter outright: "Assign public IP is not supported for this launch type."
    #
    # Reaching ECR still works, because the INSTANCE has a public IP — assigned by the ASG's subnet,
    # which is public while there is no NAT gateway. The task is still unreachable inbound: its
    # security group admits only what alb-toggle.tf opens on 80/443.
    assign_public_ip = local.spot_enabled ? null : local.task_public_ip
  }

  # Only when there IS a load balancer. With Caddy the task has no target group at all — Caddy reaches
  # both services on loopback inside the same task, so there is nothing to register.
  dynamic "load_balancer" {
    for_each = var.use_alb ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.bff[0].arn
      container_name   = "web-experience"
      container_port   = 8081
    }
  }

  dynamic "load_balancer" {
    for_each = var.use_alb ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.dps[0].arn
      container_name   = "dps"
      container_port   = 8090
    }
  }

  # Ten JVMs starting together, each with a 120s startPeriod, is slow. Without this grace period the
  # service's own health check logic can kill the task before the containers have finished booting —
  # a crash loop that looks exactly like a broken image.
  # Only meaningful with a load balancer; ECS rejects it otherwise.
  health_check_grace_period_seconds = var.use_alb ? 300 : null

  # With desired_count = 1 there is no rolling deploy to be had: 100/200 means ECS starts the new
  # task, waits for it to be healthy, then stops the old one. minimum_healthy_percent = 0 would be
  # faster and would take the platform down between the two.
  # ON FARGATE: 100/200 means start the new task, wait for healthy, then stop the old one — no downtime.
  #
  # ON EC2 SPOT this must be 0/100, and the difference is physical rather than a preference. The task
  # reserves ~4.7GB of an 8GB instance, so a SECOND task cannot be placed alongside the first; with
  # 100/200 the deployment would wait forever for capacity that the ASG can only provide by launching
  # another instance, doubling the cost to deploy.
  #
  # 0/100 therefore accepts a GAP: the old task stops, then the new one starts, and the platform is down
  # for the ~3-5 minutes ten JVMs take to boot. That is the real cost of one Spot instance, and it is
  # the same window a Spot reclamation causes anyway.
  deployment_minimum_healthy_percent = local.spot_enabled ? 0 : 100
  deployment_maximum_percent         = local.spot_enabled ? 100 : 200

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
    aws_lb_listener.http, # empty list when use_alb is false
    # IAM propagation: the task fails to pull or to read secrets if the policy is not yet in effect.
    aws_iam_role_policy.execution_secrets,
    aws_iam_role_policy.task,
    # A missing mount target makes the task hang on mount until it times out.
    aws_efs_mount_target.main,
    # On Spot: the capacity provider must be ATTACHED TO THE CLUSTER before the service references it,
    # or CreateService fails with "capacity provider not found" — the provider existing is not enough.
    # An empty list on Fargate, so this dependency simply is not there.
    aws_ecs_cluster_capacity_providers.main,
  ]

  tags = { Name = "${local.name_prefix}-platform" }
}
