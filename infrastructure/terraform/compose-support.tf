# The Elastic IP the platform is reached on, and the variables the compose deployment needs.
#
# use_alb is GONE, along with alb.tf. Caddy on the instance terminates TLS and reverse-proxies to the BFF
# and the DPS, which is what the load balancer did — and a load balancer in front of exactly one instance
# is ~$17/month for a hop. The ALB becomes the right answer again the moment there is more than one
# instance, and that is the same moment DPS needs Redis; the two changes belong together.

# ---------------------------------------------------------------------------
# Elastic IP — stable DNS without a load balancer
# ---------------------------------------------------------------------------
# A Spot instance is replaced without warning and gets a NEW public IP, so a DNS record pointing at one
# would break on every reclamation. An Elastic IP is a fixed address that the replacement instance
# re-associates to itself at boot (see compose-boot.sh.tftpl), so DNS never changes.
#
# It is not free while unattached (~$3.60/month), but it IS free while attached to a running instance —
# which is the normal state. AWS charges for idle addresses, not used ones.

resource "aws_eip" "app" {
  # No count. The compose deployment always has an instance and always needs a stable address, so
  # gating this on a toggle only created a `[0]` index to get wrong.
  domain = "vpc"

  tags = {
    Name = "${local.name_prefix}-app"
    # The boot script finds the address by this tag rather than by a hardcoded id, so replacing the EIP
    # does not require a new launch template.
    Role = "influencrm-app-eip"
  }
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

variable "use_rds" {
  description = <<-EOT
    Whether Postgres is RDS. TRUE, and it is no longer really a toggle — the alternative was a Postgres
    container on an attached EBS volume, and that is what deadlocked the ECS deployment: two instances
    contending for one volume, neither ever starting.

    On RDS the instance is STATELESS, which is the whole point of running it on Spot. A reclamation costs
    availability, not data, and automated backups with PITR replace a pg_dump cron.

    FALSE is left reachable so the variable is not a lie, but nothing creates a Postgres container any
    more; setting it false gives the platform a database host of `localhost` with nothing listening.
  EOT
  type        = bool
  default     = true

  validation {
    condition     = var.use_rds
    error_message = "use_rds must be true: the in-task Postgres container was removed with the ECS deployment, so false leaves the platform with no database."
  }
}

# ---------------------------------------------------------------------------
# Spot
# ---------------------------------------------------------------------------
# Carried over from the ECS deployment unchanged: the instance shape is the same, only what runs on it
# has changed.

variable "spot_instance_types" {
  description = <<-EOT
    Instance types the Spot request may use, cheapest first. ALL must have at least 8GB, because the
    measured footprint of the default container set is ~4.2GB (the Postgres container is gone, so it is
    ~500MB lighter than under ECS) and a 4GB type would be reclaimed by the OOM killer rather than by
    AWS.

    Several types rather than one is the single most effective thing for availability: Spot capacity is
    per-type-per-AZ, so a request that accepts five types is far less likely to go unfulfilled than one
    that insists on t3a.large. Prices observed in us-east-1 on 2026-08-09:
      t3a.large $0.0303/hr  t3.large $0.0309/hr  m5a.large $0.0412/hr  m5.large $0.0458/hr

    Enabling the `contexts` profile adds ~2.7GB and does NOT fit on a .large; that needs an .xlarge list.
  EOT
  type        = list(string)
  default     = ["t3a.large", "t3.large", "m5a.large", "m5.large", "m6a.large"]
}

variable "spot_max_price" {
  description = <<-EOT
    Maximum hourly price. EMPTY means "up to the on-demand price", which is the right default and is what
    AWS recommends: a capped bid does not save money (you pay the market rate regardless), it only makes
    the request fail to be fulfilled when the market moves above the cap.

    Set it only if a hard ceiling matters more than staying up.
  EOT
  type        = string
  default     = ""
}

variable "acme_email" {
  description = <<-EOT
    Email for Let's Encrypt registration. They use it for expiry warnings, which is the only notice you
    get that automatic renewal has stopped working.

    Required when api_domain is set. With no api_domain Caddy serves plain HTTP on the Elastic IP and
    never contacts Let's Encrypt — an IP cannot be certified.
  EOT
  type        = string
  default     = "peddysetty@gmail.com"
}
