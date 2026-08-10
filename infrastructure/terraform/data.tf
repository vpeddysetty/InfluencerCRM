# RDS Postgres and EFS.

# ---------------------------------------------------------------------------
# RDS
# ---------------------------------------------------------------------------

resource "aws_db_subnet_group" "main" {
  count = var.use_rds ? 1 : 0

  name = "${local.name_prefix}-db"
  # RDS requires subnets in at least two AZs even for a single-AZ instance, which is the other
  # reason network.tf always creates two public subnets. The instance itself lands in one.
  #
  # These are the PUBLIC subnets while NAT is disabled, but the instance is NOT publicly accessible
  # (publicly_accessible = false below) and its security group admits only the task. "Public subnet"
  # describes the route table, not reachability.
  subnet_ids = aws_subnet.public[*].id

  tags = { Name = "${local.name_prefix}-db-subnets" }
}

resource "aws_db_parameter_group" "main" {
  count = var.use_rds ? 1 : 0

  name_prefix = "${local.name_prefix}-pg16-"
  family      = "postgres16"

  # The application sets stringtype=unspecified on the JDBC URL and relies on the search_path the
  # context-roles migration assigns per role. Nothing here overrides either.
  parameter {
    name = "log_min_duration_statement"
    # Log statements slower than 1s. The DAO runs `show-sql=true` locally; in a deployed environment
    # that volume is noise, so this captures only what is worth looking at.
    value = "1000"
  }

  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot" # Static parameter; cannot take effect without a restart.
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "main" {
  count = var.use_rds ? 1 : 0

  identifier = "${local.name_prefix}-postgres"

  engine = "postgres"
  # 16, matching what pgvector/pgvector:pg15 approximates locally. The version is pinned to a MAJOR
  # only, with auto minor upgrades on: a silent major upgrade can break extensions.
  engine_version              = "16.4"
  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false

  instance_class    = var.db_instance_class
  allocated_storage = var.db_allocated_storage
  # gp3 rather than gp2: at this size the baseline IOPS are better and the price is the same or less.
  storage_type      = "gp3"
  storage_encrypted = true
  kms_key_id        = aws_kms_key.main.arn

  db_name  = var.db_name
  username = var.db_master_username
  # Read from the generated secret rather than written here, so the value exists in exactly one place.
  password = random_password.db_master.result

  db_subnet_group_name   = aws_db_subnet_group.main[0].name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.main[0].name

  # NOT publicly accessible. The task reaches it inside the VPC; nothing else should reach it at all.
  publicly_accessible = false

  # Forced off with one AZ — there is no second AZ to fail over to, and asking for it would fail.
  multi_az = var.availability_zone_count > 1 ? var.db_multi_az : false

  backup_retention_period = 7
  # A window outside likely business hours, and separated from the maintenance window so a backup
  # and a patch do not contend.
  backup_window      = "07:30-08:00"
  maintenance_window = "sun:09:00-sun:10:00"

  # PITR and a final snapshot. `skip_final_snapshot = false` means destroying this instance still
  # leaves a recoverable copy — the one safeguard that survives an intentional teardown.
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name_prefix}-postgres-final"
  deletion_protection       = var.db_deletion_protection

  # Insights at no cost for 7 days of retention; the free tier for Performance Insights.
  performance_insights_enabled          = true
  performance_insights_retention_period = 7
  performance_insights_kms_key_id       = aws_kms_key.main.arn

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  # A password change from a tainted random_password would otherwise apply immediately and drop
  # existing connections mid-request.
  apply_immediately = false

  tags = { Name = "${local.name_prefix}-postgres" }
}

# ---------------------------------------------------------------------------
# EFS — the shared log directory and the asset store
# ---------------------------------------------------------------------------
# One filesystem, two access points. The paths are separate because the LIFECYCLES are separate:
# logs roll under a 3GB-per-service cap, assets must never be deleted by a rolling policy.
#
# UID/GID 1001 ON BOTH ACCESS POINTS IS NOT OPTIONAL. Every image runs as the non-root `influencrm`
# user (1001), Java and Python alike, and a mount shadows the in-image ownership. An access point
# owned by anything else makes every append and every upload fail at RUNTIME — after the container
# has started successfully and reported healthy.

resource "aws_efs_file_system" "main" {
  creation_token = "${local.name_prefix}-efs"
  encrypted      = true
  kms_key_id     = aws_kms_key.main.arn

  # Bursting: this workload is idle-with-spikes (log appends, occasional uploads), which is exactly
  # what burst credits suit. Elastic throughput costs more per GB transferred and is worth it only
  # under sustained load.
  throughput_mode = "bursting"

  lifecycle_policy {
    # Assets that nothing has read for 30 days cost less in IA. Logs older than 30 days are past the
    # 14-day rolling window anyway, so this mostly affects the asset side.
    transition_to_ia = "AFTER_30_DAYS"
  }

  tags = { Name = "${local.name_prefix}-efs" }
}

resource "aws_efs_mount_target" "main" {
  # One per AZ the task can run in. A task in an AZ with no mount target hangs on mount until the
  # task times out — it does not start without logs, it does not start at all.
  count = length(local.task_subnet_ids)

  file_system_id  = aws_efs_file_system.main.id
  subnet_id       = local.task_subnet_ids[count.index]
  security_groups = [aws_security_group.efs.id]
}

resource "aws_efs_access_point" "logs" {
  file_system_id = aws_efs_file_system.main.id

  posix_user {
    uid = 1001
    gid = 1001
  }

  root_directory {
    path = "/logs"
    creation_info {
      owner_uid = 1001
      owner_gid = 1001
      # 0755: the runtime user writes, anything else reads. A log scraper running as another user can
      # tail the directory without being able to rewrite history.
      permissions = "0755"
    }
  }

  tags = { Name = "${local.name_prefix}-efs-logs" }
}

resource "aws_efs_access_point" "assets" {
  file_system_id = aws_efs_file_system.main.id

  posix_user {
    uid = 1001
    gid = 1001
  }

  root_directory {
    path = "/assets"
    creation_info {
      owner_uid   = 1001
      owner_gid   = 1001
      permissions = "0755"
    }
  }

  tags = { Name = "${local.name_prefix}-efs-assets" }
}
