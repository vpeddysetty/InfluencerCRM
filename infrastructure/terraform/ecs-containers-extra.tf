# The three containers that replace RDS and the ALB, plus the migration runner.
#
# All of them live in the SAME task as the application, which is what makes this cheap: one instance,
# one network namespace, no load balancer, no managed database. It is also what makes it fragile — see
# postgres-ebs.tf for the durability trade in full.

locals {
  # ---------------------------------------------------------------------------
  # Postgres
  # ---------------------------------------------------------------------------
  postgres_container = local.pg_container_enabled ? [{
    name = "postgres"
    # pgvector's own image, not plain postgres: the agent's mapping_examples table needs the `vector`
    # type and an ivfflat index. Plain postgres:16 would fail the migration at `create extension vector`.
    # Same image family as local development, so behaviour matches what was tested.
    image     = "pgvector/pgvector:pg16"
    essential = true
    memory    = 512

    portMappings = [{ containerPort = 5432, protocol = "tcp" }]

    environment = [
      { name = "POSTGRES_DB", value = var.db_name },
      { name = "POSTGRES_USER", value = var.db_master_username },
      # The data directory is a SUBDIRECTORY of the mount, not the mount itself. An EBS volume formatted
      # with ext4 has a lost+found at its root, and initdb refuses to initialise a directory that is not
      # empty — so pointing PGDATA at the mount root fails on a fresh volume with a message about the
      # directory not being empty, which reads like a bug rather than a filesystem detail.
      { name = "PGDATA", value = "/var/lib/postgresql/data/pgdata" },
      { name = "TZ", value = "UTC" },
    ]

    secrets = [
      { name = "POSTGRES_PASSWORD", valueFrom = local.secret_arns["db-password"] },
    ]

    mountPoints = [{
      sourceVolume  = "pgdata"
      containerPath = "/var/lib/postgresql/data"
      readOnly      = false
    }]

    healthCheck = {
      # pg_isready, not a TCP check: the port opens before the server accepts queries, and every other
      # container's startup depends on this being genuinely ready rather than merely listening.
      command     = ["CMD-SHELL", "pg_isready -U ${var.db_master_username} -d ${var.db_name} || exit 1"]
      interval    = 10
      timeout     = 5
      retries     = 5
      startPeriod = 60
    }

    logConfiguration = local.log_configuration
  }] : []

  # ---------------------------------------------------------------------------
  # Schema migration
  # ---------------------------------------------------------------------------
  # A NON-ESSENTIAL container that runs to completion and exits 0. Every application container declares
  # `dependsOn: [{ migrate: SUCCESS }]`, so ECS will not start them until the schema exists.
  #
  # This is what replaces the manual psql step. It runs on every deploy, which is safe because every
  # migration in this repo is idempotent — and the script VERIFIES the result rather than trusting that
  # psql exiting zero means the schema is correct.
  migrate_container = [{
    name  = "migrate"
    image = "${aws_ecr_repository.service["migrate"].repository_url}:${var.image_tag}"
    # FALSE, and it matters: an `essential` container that exits stops the whole task, so a successful
    # migration would tear down the platform it just prepared.
    essential = false
    memory    = 256

    environment = [
      { name = "PGHOST", value = local.db_host },
      { name = "PGDATABASE", value = var.db_name },
      { name = "PGUSER", value = var.db_master_username },
    ]

    secrets = [
      # The same secret the DAO reads, so there is exactly one copy of the credential.
      { name = "PGPASSWORD", valueFrom = local.secret_arns["db-password"] },
    ]

    # Must wait for Postgres to be healthy before connecting. The script also polls pg_isready itself,
    # because with RDS there is no container to depend on.
    dependsOn = local.pg_container_enabled ? [{ containerName = "postgres", condition = "HEALTHY" }] : []

    logConfiguration = local.log_configuration
  }]

  # ---------------------------------------------------------------------------
  # Caddy — replaces the ALB
  # ---------------------------------------------------------------------------
  caddy_container = local.caddy_enabled ? [{
    name = "caddy"
    # Official image. Caddy is used rather than nginx for one reason: it obtains and renews Let's Encrypt
    # certificates automatically, with no cron, no certbot and no ACM certificate. That removes the
    # *.tejdux.com ACM dependency entirely — which does not exist in this account yet.
    image     = "caddy:2-alpine"
    essential = true
    memory    = 128

    # The only ports open to the internet. Everything else in the task is reachable on loopback only.
    portMappings = [
      { containerPort = 80, hostPort = 80, protocol = "tcp" },
      { containerPort = 443, hostPort = 443, protocol = "tcp" },
    ]

    environment = [
      # Caddy reads these in the Caddyfile below. Without a real hostname it serves on :80 only, because
      # Let's Encrypt cannot issue a certificate for an IP address.
      { name = "SITE_ADDRESS", value = var.api_domain != "" ? var.api_domain : ":80" },
      { name = "ACME_EMAIL", value = var.acme_email },
    ]

    # The Caddyfile, written at startup rather than baked into a custom image, so a routing change is a
    # task-definition change rather than a rebuild-and-push.
    #
    # THE ROUTING MIRRORS THE ALB IT REPLACES: /dps/* to the DPS, everything else to the BFF. Verified
    # against the controllers — SessionController is @RequestMapping("/dps") and ApiProxyController is
    # @RequestMapping("/dps/api"), so one prefix covers every DPS route.
    command = ["sh", "-c", <<-EOT
      cat > /etc/caddy/Caddyfile <<'CADDY'
      {
        email {$ACME_EMAIL}
        # Caddy stores certificates under /data. That is a MOUNTED volume: without it, every task
        # restart re-requests certificates and Let's Encrypt's rate limit (5 per domain per week)
        # is reached in a day, after which the site serves an untrusted certificate.
        storage file_system /data
      }

      {$SITE_ADDRESS} {
        encode gzip

        # The DPS owns sessions and the OAuth callbacks.
        handle /dps/* {
          reverse_proxy localhost:8090
        }

        # Everything else is the BFF, including /api/*.
        handle {
          reverse_proxy localhost:8081
        }

        header {
          Strict-Transport-Security "max-age=31536000; includeSubDomains"
          X-Content-Type-Options nosniff
          X-Frame-Options DENY
          -Server
        }
      }
      CADDY
      exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
    EOT
    ]

    mountPoints = [{
      sourceVolume  = "caddydata"
      containerPath = "/data"
      readOnly      = false
    }]

    # Both upstreams must be up before Caddy accepts traffic, or the first requests 502.
    dependsOn = [
      { containerName = "web-experience", condition = "HEALTHY" },
      { containerName = "dps", condition = "HEALTHY" },
    ]

    healthCheck = {
      # Caddy's admin API on 2019, which answers as soon as the config loads. Checking :80 would follow
      # the redirect to :443 and fail before a certificate exists.
      command     = ["CMD-SHELL", "wget -q -O- http://localhost:2019/config/ >/dev/null 2>&1 || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }

    logConfiguration = local.log_configuration
  }] : []
}

variable "use_caddy" {
  description = <<-EOT
    TRUE runs a Caddy container as the TLS terminator and reverse proxy, and creates NO load balancer —
    saving ~$17/month. Caddy obtains Let's Encrypt certificates automatically, so no ACM certificate is
    needed either.

    FALSE creates the ALB in alb.tf instead.

    WHAT CADDY COSTS YOU versus the ALB:
      DNS points at an Elastic IP attached to the instance. A Spot replacement re-associates it (the
      instance does this itself at boot), but there is a window of ~2-4 minutes where the name resolves
      to nothing.
      No health-check-based target registration, so the ECS deployment circuit breaker has less to go on.
      Certificates live on a mounted volume; losing it means re-issuing, which is rate-limited to 5 per
      domain per week.
  EOT
  type        = bool
  default     = true
}

variable "acme_email" {
  description = <<-EOT
    Email for Let's Encrypt registration. They use it for expiry warnings, which is the only notice you
    get that automatic renewal has stopped working.

    Required when use_caddy is true AND api_domain is set. With no api_domain Caddy serves plain HTTP
    and never contacts Let's Encrypt.
  EOT
  type        = string
  default     = "peddysetty@gmail.com"
}

locals {
  caddy_enabled = var.use_caddy && !var.use_alb
}
