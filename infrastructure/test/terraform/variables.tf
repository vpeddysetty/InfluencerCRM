variable "aws_region" {
  description = "Region for every resource in this configuration."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name. Prefixes every resource name, so two environments can share an account without colliding."
  type        = string
  default     = "prod"

  validation {
    # Resource names are built from this; a slash or space produces confusing API errors far from
    # here, and some names (RDS identifiers, target groups) reject them outright.
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.environment))
    error_message = "environment must be 2-16 chars, lowercase alphanumeric or hyphen, starting with a letter."
  }
}

variable "image_tag" {
  description = <<-EOT
    Tag of the images to deploy, for every container. Set this to the release tag you pushed —
    the same value passed to scripts/build-and-push.sh.

    NOT `latest` by default, deliberately. `latest` makes a task definition non-reproducible: two
    deploys of the same revision can run different code, and a rollback has nothing to roll back to.
  EOT
  type        = string

  validation {
    condition     = var.image_tag != "latest"
    error_message = "Refusing `latest`: a task definition must pin an immutable tag or a rollback cannot be expressed. Use a release tag or a commit SHA."
  }
}

# ---------------------------------------------------------------------------
# Networking
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR for the VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "availability_zone_count" {
  description = <<-EOT
    Number of AZs. 1 for this phase, as decided.

    WHAT ONE AZ COSTS: an AZ outage takes the platform down, and RDS cannot be Multi-AZ. The ALB
    also requires subnets in at least two AZs, so a SECOND (empty) public subnet is created purely
    to satisfy that — the ECS task still runs only in the first. Raising this to 2 makes the second
    subnet real and lets RDS go Multi-AZ, with no other change here.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.availability_zone_count >= 1 && var.availability_zone_count <= 3
    error_message = "availability_zone_count must be between 1 and 3."
  }
}

variable "enable_nat_gateway" {
  description = <<-EOT
    Whether to create a NAT gateway and run the task in a private subnet.

    FALSE for this phase. The task instead runs in a public subnet with a public IP and egresses via
    the internet gateway, which is free where a NAT gateway is ~$32/month plus data processing.

    This is safe because a public IP is not the same as being reachable: the task's security group
    allows NO inbound traffic except from the ALB. The public IP exists only so the task can make
    OUTBOUND calls, which it genuinely needs — Google and Facebook OAuth, the OpenAI API, SES, and
    pulling its own images from ECR.

    VPC endpoints were the alternative and are not cheaper here: ECR (2), S3, Secrets Manager,
    CloudWatch Logs and KMS at ~$7/month each exceed the NAT gateway, and they would not carry the
    OAuth or OpenAI traffic anyway — those are public internet endpoints, so egress is required
    regardless.

    Set true when the task should have no public IP; the private subnet, route table and NAT are
    then created and the service switches to them with no other change.
  EOT
  type        = bool
  default     = false
}

variable "alb_ingress_cidrs" {
  description = <<-EOT
    Who may reach the ALB. Defaults to the whole internet because the BFF and DPS serve a browser.

    Narrow this to your own address while testing: the OAuth callbacks and the marketplace webhook
    are the only endpoints that genuinely need to be publicly reachable, and neither works before
    DNS and certificates exist.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.small is the smallest that holds this schema comfortably; t4g.micro will run but swaps under the E2E suite."
  type        = string
  default     = "db.t4g.small"
}

variable "db_allocated_storage" {
  description = "GB of storage for RDS. gp3, so IOPS are not tied to size at this scale."
  type        = number
  default     = 20
}

variable "db_name" {
  description = "Initial database name. Must match what the services connect to."
  type        = string
  default     = "influencercrm_db"
}

variable "db_master_username" {
  description = <<-EOT
    RDS master username, and the role the DAO connects as.

    The DAO connects as this role because it owns every schema — it is the monolith. The seven
    extracted services connect as their own svc_* roles created by the context-roles migration, which
    is what makes a cross-context query fail at the database rather than at review.
  EOT
  type        = string
  default     = "influencercrm_user"

  validation {
    condition     = !contains(["admin", "postgres", "root", "rdsadmin"], lower(var.db_master_username))
    error_message = "Reserved by RDS or trivially guessable. Use a specific name."
  }
}

variable "db_multi_az" {
  description = "Multi-AZ RDS. Forced off when availability_zone_count is 1 - it has nowhere to fail over to."
  type        = bool
  default     = false
}

variable "db_deletion_protection" {
  description = <<-EOT
    Blocks `terraform destroy` from deleting the database.

    Default TRUE, which means `destroy` will FAIL on this resource until you set it false and apply.
    That is the intent: an accidental destroy of an environment is recoverable, an accidental destroy
    of its data is not.
  EOT
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------
# ECS sizing
# ---------------------------------------------------------------------------

variable "task_cpu" {
  description = <<-EOT
    Task-level CPU units (1024 = 1 vCPU) shared by all eleven containers.

    2048 is the floor for the memory below — Fargate only permits certain CPU/memory pairs, and
    8192MB requires at least 2048 CPU. Ten JVMs starting at once is CPU-bound for the first ~60s,
    which is why startPeriod on the health checks matters as much as this number.
  EOT
  type        = number
  default     = 2048
}

variable "task_memory" {
  description = <<-EOT
    Task-level memory (MB). The sum of the per-container limits in locals.tf, plus headroom.

    Per-container limits are NOT optional here: each JVM runs with MaxRAMPercentage=75, and a
    container with no limit of its own reads 75% of the TASK total — eleven times over, which
    overcommits immediately and gets containers OOM-killed under load.
  EOT
  type        = number
  # 5120, not 7168: the seven extracted context services were dropped to fit the 10-container ECS limit,
  # so the containers now sum to 4096. The extra 1024 is headroom for a GC spike, and leaves ~2.5GB of
  # the 8GB instance for the OS, the ECS agent and the Docker daemon.
  default = 5120
}

variable "desired_count" {
  description = <<-EOT
    Number of tasks. ONE for this phase, and raising it needs more than this number.

    The DPS holds sessions in its own heap while Redis is out of scope, so a second task serves a
    second, disjoint set of sessions: users get logged out whenever the load balancer moves them.
    Bring Redis/ElastiCache back before setting this above 1.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.desired_count == 1
    error_message = "desired_count must be 1 while DPS sessions are in-memory. See the description; remove this validation once a shared session store exists."
  }
}

# ---------------------------------------------------------------------------
# Application configuration
# ---------------------------------------------------------------------------

variable "public_base_url" {
  description = <<-EOT
    The externally reachable origin of the BFF, e.g. https://app.tejdux.com.

    This is not cosmetic: it builds the OAuth redirect URIs, which must EXACTLY match what is
    registered in Google Cloud Console and Meta for Developers, or sign-in fails at the provider.

    Empty by default, in which case the ALB's own DNS name is used. That works for a smoke test but
    cannot be registered as an OAuth callback in practice, and it is http-only.
  EOT
  type        = string
  default     = ""
}

variable "ui_base_url" {
  description = "Origin of the React UI (the CloudFront/S3 static site). Used for CORS and post-login redirects."
  type        = string
  default     = ""
}

variable "acm_certificate_arn" {
  description = <<-EOT
    ACM certificate for the ALB's HTTPS listener, in THIS region (not us-east-1 unless that is the
    region — that constraint is CloudFront's, not the ALB's).

    Empty means HTTP only, which is acceptable for a first smoke test and not otherwise: the session
    cookie cannot carry `Secure` over plain HTTP, so it would travel in clear text.
  EOT
  type        = string
  default     = ""
}

variable "ses_from_address" {
  description = <<-EOT
    Envelope sender for transactional email, and the ONLY address the task role may send as.

    Must be a verified SES identity in this region. SES rejects anything else outright, so a
    deployment with permission but no verified sender fails every send while looking configured.
  EOT
  type        = string
  default     = "no-reply@tejdux.com"
}

variable "instagram_own_username" {
  description = <<-EOT
    The Instagram handle of the Business account the app's token belongs to.

    Not a secret — a public username — so it lives here rather than in Secrets Manager.

    WHAT IT UNLOCKS. business_discovery, which reads OTHER creators, is gated on Advanced Access
    for instagram_basic and answers "(#10) Application does not have permission" for every target
    while the app is in review. Setting this routes a lookup of THIS handle to /{ig-user-id}
    instead, which is not gated and returns the same fields with the same platform_api provenance.
    It is the only Instagram lookup that can be demonstrated before Meta approves the submission.

    Unset, isOwnAccount() is false for every handle and every lookup takes the gated path.
  EOT
  type        = string
  default     = ""
}

# ---------------------------------------------------------------------------
# Static site hosting (the micro-frontends)
# ---------------------------------------------------------------------------

variable "root_domain" {
  description = "Apex domain. An existing Route 53 hosted zone for it is looked up, not created."
  type        = string
  default     = "tejdux.com"
}

variable "manage_static_site" {
  description = <<-EOT
    Whether this configuration creates the S3 bucket and CloudFront distribution for the UIs.

    Distribution `ESJ9LTY0C74G0` (bucket `tejdux-legal-static`, built by hand on 2026-08-05, see
    docs/infrastructure/static-site-deployment-log.md) is NOT managed by Terraform. This configuration
    creates a SEPARATE app bucket and its own distributions for the micro-frontends rather than trying
    to adopt it: importing the hand-built distribution would mean reconstructing its exact current
    state in code first, and getting that wrong takes pages linked from an app store listing down.

    HISTORY, because the previous note here was wrong in a way that caused an outage. It said
    www.tejdux.com was served by that legal distribution and must stay untouched. In fact the legal
    distribution never claimed www as an alias — it held the certificate but no hostname, while DNS for
    both the apex and www aliased to the SHELL distribution, which claimed neither the hostnames nor
    the certificate. The result was a site that resolved but failed Chrome's SAN check. Both names are
    now aliases of the shell distribution and are managed here; see var.shell_serves_apex.

    SECOND CORRECTION (2026-08-11). Moving both names onto the shell fixed the SAN check and had a
    consequence not noticed at the time: the legal pages at /privacy/, /terms/ and /data-deletion/
    are objects in `tejdux-legal-static`, and with www pointing at the shell there was no route to
    them. They answered S3's AccessDenied XML while `/privacy` WITHOUT the trailing slash returned a
    200 SPA shell — so a status-code check looked healthy. They are linked from the signup form and
    registered with Meta and TikTok. The shell now serves those three prefixes from that same bucket
    as a second origin; see local.legal_on_shell in static-site-cdn.tf.
  EOT
  type        = bool
  default     = true
}

variable "legal_distribution_id" {
  description = <<-EOT
    The hand-built legal-pages distribution (bucket `tejdux-legal-static`).

    Needed only so aws_s3_bucket_policy.legal can keep granting it read access while adding the app
    shell alongside. Managing that bucket policy in Terraform REPLACES it, so the pre-existing
    statement has to be reproduced, and this is the id it names. The distribution itself is still not
    managed here.
  EOT
  type        = string
  default     = "ESJ9LTY0C74G0"
}

# NOTE: there is no `static_site_domain` variable. Each micro-frontend gets its own SUBDOMAIN of
# root_domain (app., workflow., campaigns., …) because Module Federation requires each remote to sit at
# the root of its own origin — see the header of static-site.tf. The subdomains are defined in
# local.micro_frontends, and hostnames appear only when static_site_certificate_arn is also set.
#
# The APEX and www are separate from that, and are governed by var.shell_serves_apex below rather than
# by static_site_certificate_arn — see the comment on that variable for why the two cannot share one
# certificate.

variable "shell_serves_apex" {
  description = <<-EOT
    Whether the apex (tejdux.com) and www.tejdux.com resolve to the SHELL distribution and are claimed
    as its aliases.

    WHY THIS IS SEPARATE FROM static_site_certificate_arn. That variable wants a WILDCARD covering the
    seven micro-frontend subdomains. The apex is not covered by a wildcard — *.tejdux.com does not
    match tejdux.com — so the two hostname sets need two different certificates and cannot be gated on
    one flag. Gating them together is what left the apex on the default *.cloudfront.net certificate
    while DNS already pointed at it, which is the outage this variable exists to prevent:

      - DNS resolved (an A ALIAS to the shell distribution existed)
      - the shell distribution declared no aliases and served the default certificate
      - so Chrome failed the SAN check with ERR_CERT_COMMON_NAME_INVALID, and CloudFront would have
        answered 403 for an unclaimed Host even with a valid certificate

    Both halves — the alias on the distribution and the certificate that covers it — must move
    together, which is why one flag drives both.

    Set apex_certificate_arn alongside this. Turning this on without it is rejected at plan time.
  EOT
  type        = bool
  default     = true
}

variable "apex_certificate_arn" {
  description = <<-EOT
    ACM certificate in us-east-1 covering BOTH tejdux.com and www.tejdux.com, for the shell
    distribution when shell_serves_apex is true.

    us-east-1 regardless of var.aws_region — a CloudFront requirement.

    d38a2767-198a-491b-892c-3da19aed9ef0 is the live one: SANs www.tejdux.com + tejdux.com, DNS
    validated, expires 2027-02-19. It is NOT a wildcard, so it cannot serve the micro-frontend
    subdomains — that is what static_site_certificate_arn is for.
  EOT
  type        = string
  default     = "arn:aws:acm:us-east-1:099933382956:certificate/d38a2767-198a-491b-892c-3da19aed9ef0"

  validation {
    # A certificate for a CloudFront alias MUST be us-east-1. Catching it here turns a slow, confusing
    # apply-time failure into an immediate one.
    condition     = var.apex_certificate_arn == "" || can(regex("^arn:aws:acm:us-east-1:", var.apex_certificate_arn))
    error_message = "apex_certificate_arn must be an ACM certificate in us-east-1 (CloudFront requirement)."
  }
}

variable "api_domain" {
  description = <<-EOT
    Hostname for the ALB, e.g. api.tejdux.com. An ALIAS record in the existing tejdux.com zone.

    Empty means no DNS record and the ALB is reached on its own *.elb.amazonaws.com name. Set this
    together with acm_certificate_arn and public_base_url — an OAuth callback must be a stable name,
    and the ALB's default name is neither pretty nor certificate-backed.
  EOT
  type        = string
  default     = ""
}

variable "static_site_certificate_arn" {
  description = <<-EOT
    ACM certificate covering every micro-frontend subdomain. MUST be in us-east-1 regardless of
    var.aws_region — that is a CloudFront requirement, unlike the ALB certificate.

    A WILDCARD (*.tejdux.com) is the practical choice: seven subdomains otherwise means seven SANs and
    a reissue every time one is added.

    The existing certificate d38a2767-198a-491b-892c-3da19aed9ef0 covers tejdux.com and
    www.tejdux.com — check whether it is a wildcard. If it is not, issue one; a name not covered by
    the certificate fails at apply, not at request time.

    Empty means no custom hostnames at all: every distribution serves on its own *.cloudfront.net
    name, no Route 53 record is touched, and CORS falls back to "*". Workable for a smoke test.
  EOT
  type        = string
  default     = ""
}

variable "log_retention_days" {
  description = "CloudWatch retention for the container log streams. Structured JSON logs also go to EFS; this is the copy that survives a task that dies before writing a file."
  type        = number
  default     = 30
}

# ---------------------------------------------------------------------------
# Compose deployment
# ---------------------------------------------------------------------------

variable "workflow_service_enabled" {
  description = <<-EOT
    Whether the BFF routes workflow traffic to the extracted workflow service instead of serving it from
    the monolith DAO.

    FALSE by default and it should stay false until a dual-run soak says otherwise: flipping it is a
    deliberate act, and it is the rollback too. Seconds either way.

    TRUE also requires the `contexts` compose profile to be running, or the BFF routes to a service that
    is not there.
  EOT
  type        = bool
  default     = false
}

variable "dao_certificate_has_service_san" {
  description = <<-EOT
    Whether the DAO's keystore carries `dao` as a subject alternative name.

    THIS IS A MIGRATION FLAG, and it exists because leaving ECS changed the network shape. In the ECS
    task every container shared one network namespace, so the BFF reached the DAO at
    https://localhost:8443 and the committed keystore's `localhost` SAN matched. Under Compose they are
    separate containers on a bridge network, so the URL is https://dao:8443 — and a certificate carrying
    only `localhost` fails hostname verification on every single call.

    FALSE therefore sets WEBE_DAO_TLS_VERIFICATION=false, which keeps the connection encrypted but stops
    verifying who is on the other end. That is a REAL reduction in the zero-trust chain and is acceptable
    only because both ends are containers on one host's private bridge.

    Set this TRUE once the keystore is reissued with `dao` (and ideally `localhost`) as SANs. That is the
    correct end state and should not be left undone.
  EOT
  type        = bool
  default     = false
}
