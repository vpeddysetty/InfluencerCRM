# S3 + CloudFront for the seven micro-frontend bundles.
#
# WHAT THIS DOES NOT TOUCH: `www.tejdux.com`. That site already exists — bucket
# `tejdux-legal-static`, distribution ESJ9LTY0C74G0, OAC E1YGJV27KRKXI1, a viewer-request
# CloudFront Function, all built by hand on 2026-08-05 and recorded in
# docs/infrastructure/static-site-deployment-log.md. It serves /terms/ and /privacy/, which are
# linked from an app-store listing.
#
# Importing it would mean reconstructing its exact current state in code first, and getting that
# wrong takes live legal pages down. So this creates a SEPARATE bucket and distribution for the app,
# and the two coexist. Adopting the legal site into Terraform later is a contained job: `terraform
# import` the four resources and reconcile the plan until it is empty.
#
# LAYOUT — ONE ORIGIN PER MICRO-FRONTEND, NOT ONE PREFIX PER MICRO-FRONTEND.
#
# This is forced by Module Federation, and it is worth being explicit about because the obvious
# design (one distribution, /workflow/, /campaigns/, …) does not work here:
#
#   1. Each remote's built index.html and remoteEntry.js reference assets ABSOLUTELY — verified in
#      InfluencerWorkflowUI/dist/index.html: `src="/assets/mf-entry-bootstrap-0-….js"`. Served under
#      /workflow/ those requests go to /assets/… at the domain root, which is the SHELL's asset
#      directory, and load the wrong bundle or 404.
#   2. The shell resolves each remote as `${origin}/remoteEntry.js` (originRegistry.js
#      `federationRemotes()`), so the entry must sit at the ROOT of whatever origin it is given.
#
# Fixing (1) with vite `base: '/workflow/'` would also change remoteEntry's own URL, and the registry
# would need per-remote paths rather than origins — a change to the shell's contract, not a config
# change. Subdomains keep the registry exactly as designed:
#
#   app.tejdux.com        <- InfluencerUI          the shell / gateway (dev 5173)
#   workflow.tejdux.com   <- InfluencerWorkflowUI  (5174)  VITE_MF_WORKFLOW_ORIGIN
#   campaigns.tejdux.com  <- InfluencerCampaignsUI (5175)  VITE_MF_CAMPAIGNS_ORIGIN
#   creators.tejdux.com   <- InfluencerCreatorsUI  (5176)  VITE_MF_CREATORS_ORIGIN
#   commerce.tejdux.com   <- InfluencerCommerceUI  (5177)  VITE_MF_COMMERCE_ORIGIN
#   finance.tejdux.com    <- InfluencerFinanceUI   (5178)  VITE_MF_FINANCE_ORIGIN
#   content.tejdux.com    <- InfluencerContentUI   (5179)  VITE_MF_CONTENT_ORIGIN
#
# One bucket still holds all seven, one prefix each — the prefix is chosen by the distribution's
# ORIGIN PATH, so each distribution sees its own prefix as its root and the absolute /assets/…
# references resolve correctly with no rebuild and no vite `base`.
#
# CORS: a federated remote is executable code fetched cross-origin, so each distribution must send
# Access-Control-Allow-Origin for the shell. That is the response headers policy below.
#
# ONE WILDCARD CERTIFICATE (*.tejdux.com) covers every name here. The existing certificate
# d38a2767-198a-491b-892c-3da19aed9ef0 covers tejdux.com and www.tejdux.com — check whether it is a
# wildcard; if not, issue one, or apply fails.

locals {
  # S3 prefix -> the project that builds it and the subdomain that serves it.
  #
  # `scope` is the federation name from that remote's own vite.config.js. It must match
  # ORIGIN_REGISTRY in InfluencerUI/src/shell/gateway/originRegistry.js — a mismatch fails at runtime
  # with an opaque module-resolution error, which is why the registry keeps them visibly adjacent and
  # why they are repeated here rather than inferred.
  micro_frontends = {
    shell     = { project = "InfluencerUI", subdomain = "app", scope = "shell", env_var = "" }
    workflow  = { project = "InfluencerWorkflowUI", subdomain = "workflow", scope = "mf_workflow", env_var = "VITE_MF_WORKFLOW_ORIGIN" }
    campaigns = { project = "InfluencerCampaignsUI", subdomain = "campaigns", scope = "mf_campaigns", env_var = "VITE_MF_CAMPAIGNS_ORIGIN" }
    creators  = { project = "InfluencerCreatorsUI", subdomain = "creators", scope = "mf_creators", env_var = "VITE_MF_CREATORS_ORIGIN" }
    commerce  = { project = "InfluencerCommerceUI", subdomain = "commerce", scope = "mf_commerce", env_var = "VITE_MF_COMMERCE_ORIGIN" }
    finance   = { project = "InfluencerFinanceUI", subdomain = "finance", scope = "mf_finance", env_var = "VITE_MF_FINANCE_ORIGIN" }
    content   = { project = "InfluencerContentUI", subdomain = "content", scope = "mf_content", env_var = "VITE_MF_CONTENT_ORIGIN" }
  }

  static_enabled = var.manage_static_site

  # Whether each distribution gets a real hostname. Without a domain they serve on their own
  # *.cloudfront.net names, which still works — the shell just needs those URLs in its env vars.
  static_aliased = local.static_enabled && var.root_domain != "" && var.static_site_certificate_arn != ""

  # The shell's own origin, which every remote must allow via CORS.
  shell_origin = local.static_aliased ? "https://app.${var.root_domain}" : ""
}

resource "aws_s3_bucket" "ui" {
  count = local.static_enabled ? 1 : 0

  bucket = "${local.name_prefix}-ui-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "${local.name_prefix}-ui" }
}

resource "aws_s3_bucket_public_access_block" "ui" {
  count = local.static_enabled ? 1 : 0

  bucket = aws_s3_bucket.ui[0].id
  # All four ON. The bucket is private; CloudFront reaches it through Origin Access Control, so there
  # is never a reason for a public object ACL. A "website" bucket that is publicly readable also
  # bypasses CloudFront entirely, which loses the WAF, the logs and the cache.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "ui" {
  count = local.static_enabled ? 1 : 0

  bucket = aws_s3_bucket.ui[0].id
  versioning_configuration {
    # A bad deploy overwrites index.html and the hashed asset it points at. Versioning is what makes
    # that recoverable without a rebuild.
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ui" {
  count = local.static_enabled ? 1 : 0

  bucket = aws_s3_bucket.ui[0].id
  rule {
    apply_server_side_encryption_by_default {
      # SSE-S3, not the KMS key: these are public assets, and per-object KMS decryption on every
      # CloudFront origin fetch costs money for no confidentiality gain.
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "ui" {
  count = local.static_enabled ? 1 : 0

  bucket = aws_s3_bucket.ui[0].id

  rule {
    id     = "expire-old-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      # Versioning is for recovering a bad deploy, which is measured in days. Keeping every version
      # of every hashed bundle forever is a slowly growing bill for nothing.
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ---------------------------------------------------------------------------
# CloudFront
# ---------------------------------------------------------------------------

resource "aws_cloudfront_origin_access_control" "ui" {
  count = local.static_enabled ? 1 : 0

  name                              = "${local.name_prefix}-ui-oac"
  description                       = "CloudFront -> private UI bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

