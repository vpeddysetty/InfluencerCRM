# Copy to prod.tfvars (gitignored) and edit:
#   terraform apply -var-file=prod.tfvars -var image_tag=v1.0.0
#
# Every value here has a documented default in variables.tf. This file exists to show the ones worth
# setting deliberately for a real deployment, and the order they become relevant.

aws_region  = "us-east-1"
environment = "prod"

# ---------------------------------------------------------------------------
# Phase 1 — smoke test. No DNS, no certificates.
# ---------------------------------------------------------------------------
# Leave the four values below empty for the first apply. The ALB serves HTTP on its own
# *.elb.amazonaws.com name and each CloudFront distribution serves on its own *.cloudfront.net name.
# That is enough to prove the task runs, the containers reach each other, and the UIs load.
#
# It is NOT enough for sign-in: an OAuth redirect URI must exactly match what is registered with the
# provider, and neither Google nor Meta will accept an ALB's default hostname. So expect to do phase 2
# before anyone can log in.

# ---------------------------------------------------------------------------
# HTTPS and OAuth — enabled 2026-08-10
# ---------------------------------------------------------------------------
# api_domain is what unlocks all three of: TLS, OAuth sign-in, and /api routing through CloudFront.
# Everything below follows from it.
api_domain = "api.tejdux.com"

# NO ACM CERTIFICATE, and that is not an omission. Caddy runs on the instance and obtains its own
# certificate from Let's Encrypt via the HTTP-01 challenge — which is why there is no ALB here to
# terminate TLS and no certificate to buy or renew. acm_certificate_arn stays empty; it exists for the
# ALB path, which this deployment does not use.
#
# The existing certificate d38a2767 covers tejdux.com and www.tejdux.com ONLY (verified, not assumed —
# it is not a wildcard), so it could not cover api.tejdux.com even if an ALB were wanted.
acm_certificate_arn = ""

# Where the BROWSER reaches the API. This is what ends up in OAuth redirect URIs and CORS headers, so it
# must be the public https:// name, not a service name and not the Elastic IP.
public_base_url = "https://api.tejdux.com"

# Where the SHELL is served. The apex, because shell_serves_apex is on by default and tejdux.com already
# aliases the shell distribution under certificate d38a2767.
#
# ONE origin, not seven, and this is worth being precise about: the six remotes are Module Federation
# modules that the shell FETCHES and executes in its own page. They run on the shell's origin, and none
# of them contains a reference to VITE_BFF_URL or VITE_DPS_URL (verified). So the DPS only ever sees
# requests from this one origin, and DPS_ALLOWED_ORIGINS is correct as a single value.
ui_base_url = "https://tejdux.com"

# Still empty: this would give app./workflow./campaigns./… their own hostnames, and needs a WILDCARD
# certificate. d38a2767 is not one. The micro-frontends keep serving on their *.cloudfront.net names,
# which works because the shell is told each remote's origin at build time.
static_site_certificate_arn = ""

# The apex. ON by default in variables.tf, so it applies without being set here: tejdux.com and
# www.tejdux.com are aliases of the SHELL distribution, A and AAAA, under the certificate below.
#
# VERIFIED 2026-08-10, not assumed: certificate d38a2767 has SANs www.tejdux.com and tejdux.com only —
# it is NOT a wildcard. So it cannot serve app./workflow./… and static_site_certificate_arn above needs
# a different (wildcard) certificate. Setting both at once trips the check block in static-site-cdn.tf.
# shell_serves_apex    = true
# apex_certificate_arn = "arn:aws:acm:us-east-1:099933382956:certificate/d38a2767-198a-491b-892c-3da19aed9ef0"

# ---------------------------------------------------------------------------
# Phase 2 — real hostnames. Set all of these together.
# ---------------------------------------------------------------------------
# api_domain           = "api.tejdux.com"
#
# # ALB certificate: must be in aws_region (NOT necessarily us-east-1 — that constraint is
# # CloudFront's). Must cover api_domain.
# acm_certificate_arn  = "arn:aws:acm:us-east-1:099933382956:certificate/REPLACE-ME"
#
# # Must match api_domain, with the scheme. This builds the OAuth redirect URIs, so it has to be
# # byte-identical to what is registered in Google Cloud Console and Meta for Developers.
# public_base_url      = "https://api.tejdux.com"
#
# # Where the shell is served — used for CORS and post-login redirects.
# ui_base_url          = "https://app.tejdux.com"
#
# # CloudFront certificate: MUST be in us-east-1 whatever aws_region says, and should be a WILDCARD
# # (*.tejdux.com) — there are seven subdomains, and a SAN list means a reissue every time one is added.
# #
# # d38a2767 is NOT reusable here — verified 2026-08-10: its SANs are tejdux.com and www.tejdux.com
# # only, and *.tejdux.com would not cover the apex anyway. Issue a wildcard for this one.
# static_site_certificate_arn = "arn:aws:acm:us-east-1:099933382956:certificate/REPLACE-ME"

# ---------------------------------------------------------------------------
# Sizing
# ---------------------------------------------------------------------------
# 2048/8192 is the floor for eleven containers with the per-container limits in ecs.tf. Fargate only
# permits certain CPU/memory pairs, and 8192MB requires at least 2048 CPU.
task_cpu    = 2048
task_memory = 8192

# MUST be 1 while DPS sessions are in-memory — there is a validation rule enforcing it. A second task
# serves a disjoint set of sessions, so users get logged out when the balancer moves them.
desired_count = 1

db_instance_class    = "db.t4g.small"
db_allocated_storage = 20

# ---------------------------------------------------------------------------
# This phase's deliberate trade-offs
# ---------------------------------------------------------------------------
# One AZ: an AZ outage is a full outage, and RDS cannot be Multi-AZ. Raise to 2 to allow both.
availability_zone_count = 1

# No NAT gateway: the task runs in a public subnet with a public IP, saving ~$32/month. It is still
# unreachable inbound — its security group admits only the ALB, on two ports. See the README.
enable_nat_gateway = false

# Narrow this to your own address for the smoke test. Only the OAuth callbacks and the billing webhook
# genuinely need to be publicly reachable, and neither works before phase 2 anyway.
# alb_ingress_cidrs = ["203.0.113.4/32"]

# Must be a VERIFIED SES identity in aws_region, and it is the only address the task role may send as.
ses_from_address = "no-reply@tejdux.com"
