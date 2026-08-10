# CloudFront: one distribution per micro-frontend, the DNS records, and the bucket policy.
#
# Split from static-site.tf only to keep each file readable; they are one logical unit and the header
# comment in static-site.tf explains WHY this is one-origin-per-remote rather than one-prefix-per-remote.

locals {
  # The public app origin used by the browser for the API and DPS endpoints. The Terraform module
  # already infers the externally reachable base URL from the deployment mode, so the same value can
  # drive both the service config and the CloudFront proxy behavior.
  cloudfront_backend_origin_host = local.public_base_url != "" ? replace(replace(local.public_base_url, "https://", ""), "http://", "") : ""

  # CloudFront REJECTS AN IP as an origin: "InvalidArgument: The parameter origin name cannot be an IP
  # address". With api_domain unset, public_base_url falls back to the Elastic IP — a perfectly good
  # smoke-test address for a browser, but not something CloudFront will accept.
  #
  # So the test is "is this a hostname", NOT "is this non-empty". An IP is non-empty and would sail
  # through an emptiness check — which is exactly the bug this replaced.
  cloudfront_backend_is_hostname = (
    local.cloudfront_backend_origin_host != "" &&
    length(regexall("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$", local.cloudfront_backend_origin_host)) == 0
  )

  cloudfront_backend_origin_domain = local.cloudfront_backend_is_hostname ? local.cloudfront_backend_origin_host : ""
  cloudfront_backend_origin_policy = startswith(local.public_base_url, "https://") ? "https-only" : "http-only"
}

# SPA routing.
#
# Each micro-frontend is a single-page app: a deep link like /boards/42 is a client-side route with no
# S3 object behind it, and a private bucket answers 403 (not 404). Without rewriting, a refresh on any
# in-app route shows an error page.
#
# Because every distribution has its own origin path, this function is trivial and IDENTICAL for all
# of them — there is no prefix to reason about, so one function is shared rather than seven.
resource "aws_cloudfront_function" "spa_router" {
  count = local.static_enabled ? 1 : 0

  name    = "${local.name_prefix}-spa-router"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrites extension-less paths to index.html for SPA client-side routing"
  publish = true

  code = <<-EOT
    // Viewer-request. Runs on every request, so it must stay trivial — no network, no state.
    function handler(event) {
      var request = event.request;
      var uri = request.uri;

      // A real object: hashed JS/CSS, remoteEntry.js, an image, a source map. Leave it alone —
      // rewriting would break the asset and mask a genuine 404.
      if (uri.match(/\.[a-zA-Z0-9]+$/)) {
        return request;
      }

      // Directory-style, including the bare root.
      if (uri.endsWith('/')) {
        request.uri = uri + 'index.html';
        return request;
      }

      // Extension-less and not a directory: a client-side route. The SPA's own router resolves it.
      request.uri = '/index.html';
      return request;
    }
  EOT
}

# CORS for the federated remotes.
#
# A remote is EXECUTABLE CODE fetched cross-origin by the shell, so without these headers the browser
# blocks the fetch of remoteEntry.js and the shell renders an empty route with a console error. This is
# the single most likely thing to break a federated deployment that otherwise looks correct.
resource "aws_cloudfront_response_headers_policy" "remote_cors" {
  count = local.static_enabled ? 1 : 0

  name    = "${local.name_prefix}-remote-cors"
  comment = "Allows the shell origin to load federated remote modules"

  cors_config {
    access_control_allow_credentials = false

    access_control_allow_headers {
      items = ["*"]
    }

    access_control_allow_methods {
      items = ["GET", "HEAD", "OPTIONS"]
    }

    access_control_allow_origins {
      # The shell only. A wildcard would let any page on the internet load these modules — they are not
      # secret, but they are code, and an allowlist of one is no harder to maintain.
      #
      # Falls back to "*" when there is no custom domain: the shell's own *.cloudfront.net name is not
      # known until after apply, and referencing it here would be a dependency cycle. Acceptable for a
      # smoke test; set root_domain and the certificate to narrow it.
      items = local.shell_origin != "" ? [local.shell_origin] : ["*"]
    }

    origin_override = true
  }

  security_headers_config {
    content_type_options {
      override = true
    }
    frame_options {
      # The shell loads remotes as ES modules, not iframes, so framing is never legitimate here.
      frame_option = "DENY"
      override     = true
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      override                   = true
    }
  }
}

# ---------------------------------------------------------------------------
# One distribution per micro-frontend
# ---------------------------------------------------------------------------
# Seven rather than one. Distributions cost nothing to exist (billing is per request and per GB), and
# the origin path is what makes each one's prefix look like its root — so the built bundles' absolute
# /assets/… references resolve with no rebuild and no vite `base`.

resource "aws_cloudfront_distribution" "ui" {
  for_each = local.static_enabled ? local.micro_frontends : {}

  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${local.name_prefix} ${each.key}"
  default_root_object = "index.html"
  price_class         = "PriceClass_100"

  aliases = local.static_aliased ? ["${each.value.subdomain}.${var.root_domain}"] : []

  origin {
    domain_name              = aws_s3_bucket.ui[0].bucket_regional_domain_name
    origin_id                = "ui-bucket"
    origin_access_control_id = aws_cloudfront_origin_access_control.ui[0].id
    # THE LOAD-BEARING LINE. This distribution sees s3://bucket/<prefix> as its root, so a request for
    # /assets/index-abc.js fetches <prefix>/assets/index-abc.js. Without it every remote would read the
    # shell's assets and load the wrong bundle.
    origin_path = "/${each.key}"
  }

  # ONLY WHEN THERE IS A HOSTNAME. CloudFront rejects an IP address as an origin outright:
  # "InvalidArgument: The parameter origin name cannot be an IP address".
  #
  # With api_domain unset, local.public_base_url falls back to the Elastic IP — which is a usable
  # smoke-test address for the browser to call directly, but cannot be a CloudFront origin. So the API
  # origin and the three behaviors that target it appear together with the domain, or not at all.
  #
  # While they are absent the micro-frontends still serve; the SPA simply calls the BFF directly at
  # VITE_BFF_URL rather than through the distribution. Set api_domain to route /api and /dps through
  # CloudFront.
  dynamic "origin" {
    for_each = local.cloudfront_backend_origin_domain != "" ? [1] : []
    content {
      domain_name = local.cloudfront_backend_origin_domain
      origin_id   = "api-origin"

      custom_origin_config {
        http_port              = 80
        https_port             = 443
        origin_protocol_policy = local.cloudfront_backend_origin_policy
        # An ARGUMENT, not a block, in provider v5 — a nested block here fails validation with
        # "Blocks of type origin_ssl_protocols are not expected here".
        origin_ssl_protocols = ["TLSv1.2"]
      }
    }
  }

  dynamic "ordered_cache_behavior" {
    # The API and both DPS prefixes, generated rather than written out three times: they differ only in
    # the path pattern.
    for_each = local.cloudfront_backend_origin_domain != "" ? ["/api/*", "/dps", "/dps/*"] : []
    content {
      path_pattern     = ordered_cache_behavior.value
      target_origin_id = "api-origin"

      viewer_protocol_policy = "redirect-to-https"
      allowed_methods        = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
      cached_methods         = ["GET", "HEAD", "OPTIONS"]
      compress               = true
      cache_policy_id        = "4135ea2d-6df8-4f0b-9f0d-979c4b3c8d9a"
    }
  }

  default_cache_behavior {
    target_origin_id       = "ui-bucket"
    viewer_protocol_policy = "redirect-to-https"
    # OPTIONS included for the CORS preflight the shell issues before fetching a remote module.
    allowed_methods = ["GET", "HEAD", "OPTIONS"]
    cached_methods  = ["GET", "HEAD", "OPTIONS"]
    compress        = true

    # AWS-managed CachingOptimized. Vite emits content-hashed filenames, so a long TTL is right for
    # assets; deploy-ui.sh uploads index.html and remoteEntry.js with `no-cache`, so a new release is
    # visible without waiting on an invalidation.
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
    # AWS-managed CORS-S3Origin: forwards the Origin header and includes it in the cache key, so a
    # cached response cannot serve the wrong Access-Control-Allow-Origin to a different caller.
    origin_request_policy_id   = "88a5eaf4-2fd4-4709-b370-b4c650ea3fcf"
    response_headers_policy_id = aws_cloudfront_response_headers_policy.remote_cors[0].id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_router[0].arn
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = !local.static_aliased
    acm_certificate_arn            = local.static_aliased ? var.static_site_certificate_arn : null
    ssl_support_method             = local.static_aliased ? "sni-only" : null
    minimum_protocol_version       = local.static_aliased ? "TLSv1.2_2021" : null
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  tags = { Name = "${local.name_prefix}-ui-${each.key}" }
}

# Bucket policy admitting exactly these distributions. The SourceArn condition is what stops any other
# distribution, in any account, from using this bucket as an origin.
data "aws_iam_policy_document" "ui_bucket" {
  count = local.static_enabled ? 1 : 0

  statement {
    sid       = "AllowCloudFrontRead"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.ui[0].arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [for d in aws_cloudfront_distribution.ui : d.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "ui" {
  count = local.static_enabled ? 1 : 0

  bucket = aws_s3_bucket.ui[0].id
  policy = data.aws_iam_policy_document.ui_bucket[0].json
}

# ---------------------------------------------------------------------------
# DNS
# ---------------------------------------------------------------------------
# The hosted zone for tejdux.com already exists (Z0068206CHFI6QYONX9W) and is LOOKED UP, never created:
# a second zone for a domain that already has one gets different nameservers from the ones the
# registrar publishes, and nothing resolves.

locals {
  # Needed by either the UI records or the API record, so the lookup cannot be gated on one alone —
  # setting only api_domain would otherwise index a zero-length data source and fail at plan time.
  need_dns_zone = local.static_aliased || var.api_domain != ""
}

data "aws_route53_zone" "root" {
  count = local.need_dns_zone ? 1 : 0

  name         = "${var.root_domain}."
  private_zone = false
}

resource "aws_route53_record" "ui" {
  for_each = local.static_aliased ? local.micro_frontends : {}

  zone_id = data.aws_route53_zone.root[0].zone_id
  name    = "${each.value.subdomain}.${var.root_domain}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.ui[each.key].domain_name
    zone_id                = aws_cloudfront_distribution.ui[each.key].hosted_zone_id
    evaluate_target_health = false
  }
}

# The BFF's hostname: an A record to the Elastic IP that Caddy serves on. There is no ALB to ALIAS to
# any more, and the EIP is stable across Spot replacements — the replacement instance re-associates it
# to itself at boot — so a plain A record is correct rather than merely adequate.
#
# THIS RECORD IS WHAT MAKES TLS POSSIBLE. Caddy requests a Let's Encrypt certificate for api_domain via
# the HTTP-01 challenge, which only succeeds once this name already resolves to the instance. On a first
# apply the record and the instance appear together, so the first certificate attempt may fail and retry
# — Caddy retries with backoff and gets there, but the platform serves plain HTTP until it does.
resource "aws_route53_record" "api" {
  count = var.api_domain != "" ? 1 : 0

  zone_id = data.aws_route53_zone.root[0].zone_id
  name    = var.api_domain
  type    = "A"

  records = [aws_eip.app.public_ip]
  # 60s, deliberately short. The address does not normally change, but when it does — a new EIP, a move
  # to an ALB — a long TTL is the difference between a minute of downtime and an hour of it.
  ttl = 60
}
