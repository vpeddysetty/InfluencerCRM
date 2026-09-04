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

  # The legal pages, served by the shell distribution from the pre-existing hand-built bucket.
  #
  # WHY THIS IS HERE AT ALL. `aliases` below claims www.${var.root_domain} for the shell. A CNAME can
  # only be attached to ONE distribution, so that claim silently TOOK IT AWAY from the legal site
  # (ESJ9LTY0C74G0), whose Aliases are now empty. The static-site.tf header says that site is
  # untouched; it was true of the bucket and the distribution, and false of the hostname. The pages
  # went offline the moment the shell claimed the apex — /privacy/ and /terms/ answer with S3's
  # AccessDenied XML, and they are linked from the signup form and registered with Meta and TikTok.
  #
  # WHY NOT JUST GIVE THE ALIAS BACK: www serves the app. Moving it back takes the whole app down.
  #
  # So the shell serves the legal paths itself, from the SAME bucket the legal distribution uses.
  # Nothing is copied — one set of objects, two readers — so there is no second copy to drift.
  legal_bucket        = "tejdux-legal-static"
  legal_origin_domain = "${local.legal_bucket}.s3.${var.aws_region}.amazonaws.com"

  # Exactly the prefixes that exist in the bucket. Listed rather than wildcarded so a request for
  # anything else keeps falling through to the SPA.
  #
  # /pricing/ is here for the same reason the other three are, and was missing for the same reason
  # they broke: the page was written and uploaded, but nothing routed to it, so it answered S3's
  # AccessDenied through the shell. A public pricing page that 403s is worse than no pricing page --
  # it is linked from the marketing copy and is the last step before signup. Adding a prefix here
  # WITHOUT uploading the object produces that same 403, so the two changes belong together.
  #
  # /government-requests/ is linked from Meta's Data Protection Assessment, so it is fetched by a
  # reviewer rather than by a user who can be told to try again later. Upload the object BEFORE
  # applying this, for the reason the paragraph above gives.
  legal_path_patterns = ["/privacy/*", "/terms/*", "/data-deletion/*", "/pricing/*", "/dpa/*", "/subprocessors/*", "/government-requests/*"]

  # The shell is the only distribution that claims the hostname the policies are linked under, so it
  # is the only one that needs the origin.
  legal_on_shell = local.apex_on_shell

  # Does the shell claim the apex and www? Needs the certificate as well as the flag: claiming an alias
  # without a certificate covering it is rejected by CloudFront at apply time.
  apex_on_shell = local.static_enabled && var.shell_serves_apex && var.apex_certificate_arn != "" && var.root_domain != ""

  # Certificate per distribution, "" meaning CloudFront's default.
  #
  # The shell prefers the apex certificate because the apex is the name a user types; the wildcard does
  # not cover it. When both are set the shell claims app., tejdux.com AND www.tejdux.com, so the
  # certificate it uses must cover all three — hence the precondition below.
  distribution_certificate = {
    for k, cfg in local.micro_frontends :
    k => (
      k == "shell" && local.apex_on_shell ? var.apex_certificate_arn :
      local.static_aliased ? var.static_site_certificate_arn :
      ""
    )
  }
}

# Fails the plan on a combination CloudFront would reject at apply time, after it had already spent
# fifteen minutes deploying. The shell cannot claim app.${var.root_domain} and the apex under one
# certificate unless that certificate covers BOTH — a wildcard alone does not, because *.example.com
# does not match example.com.
#
# WHAT THIS USED TO ASSERT, AND WHY IT WAS WRONG (corrected 2026-09-01). The condition was
# `!(apex_on_shell && static_aliased)` — the two were held to be mutually exclusive outright, on the
# stated premise that "the live apex certificate covers only the apex and www". That premise stopped
# being true once ONE certificate was issued covering both: `*.tejdux.com` with `tejdux.com` as an
# explicit SAN, which is exactly the resolution the old message asked for and which prod.tfvars has
# pointed both variables at ever since.
#
# So the check fired on every plan while the configuration it objected to was live, correct and
# serving all three names. That is worse than no check: a warning that is always wrong trains the
# reader to skip the warnings that are not, and this one appeared in the same output as genuine
# infrastructure diffs. Verified before changing it — ACM reports SANs [*.tejdux.com, tejdux.com],
# distribution E3GALL4Q8H611Y serves all three aliases under that ARN, and all three pass an
# openssl SAN check against the live endpoint.
#
# The real invariant is not "never both" but "if both, one certificate covering all three names",
# which is what is asserted now. Kept as a `check` rather than promoted to a `precondition`
# deliberately: it reads two variables and cannot see the certificate's SANs, so it can only compare
# the ARNs. Two different-but-both-valid certificates would trip it, and failing an apply on
# something this check cannot actually verify would be the more expensive error.
check "apex_and_wildcard_share_one_certificate_on_the_shell" {
  assert {
    condition = !(local.apex_on_shell && local.static_aliased) || (
      var.apex_certificate_arn == var.static_site_certificate_arn
    )
    error_message = <<-EOT
      shell_serves_apex and static_site_certificate_arn are both set, which makes the shell claim
      app.${var.root_domain}, ${var.root_domain} and www.${var.root_domain} — but apex_certificate_arn
      and static_site_certificate_arn are DIFFERENT certificates.

      A distribution serves one certificate, and CloudFront rejects an alias that certificate does not
      cover. Point both variables at a single certificate covering all three names (a wildcard plus the
      apex as an explicit SAN), or leave static_site_certificate_arn empty.
    EOT
  }
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

  # The subdomain when there is a wildcard certificate, PLUS the apex and www on the shell only.
  #
  # A distribution serves a hostname only if it is listed here: CloudFront answers 403 for a Host it
  # does not claim, even when the certificate is valid for that name. So this list and the certificate
  # below must always agree — see var.shell_serves_apex.
  aliases = concat(
    local.static_aliased ? ["${each.value.subdomain}.${var.root_domain}"] : [],
    local.apex_on_shell && each.key == "shell" ? [var.root_domain, "www.${var.root_domain}"] : [],
  )

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

  # The legal pages, on the shell only. See local.legal_on_shell for why the shell serves these at all.
  dynamic "origin" {
    for_each = local.legal_on_shell && each.key == "shell" ? [1] : []
    content {
      domain_name              = local.legal_origin_domain
      origin_id                = "legal-bucket"
      origin_access_control_id = aws_cloudfront_origin_access_control.legal[0].id
      # No origin_path: the objects already live at privacy/index.html, terms/index.html and
      # data-deletion/index.html, so the request URI maps to the key directly.
    }
  }

  # /privacy/, /terms/, /data-deletion/, /pricing/, /dpa/, /subprocessors/ -> the legal bucket.
  #
  # These MUST be ordered_cache_behavior, not a change to the default: the default serves the SPA and
  # is what every other route depends on. An ordered behavior matches first and leaves the rest alone.
  dynamic "ordered_cache_behavior" {
    for_each = local.legal_on_shell && each.key == "shell" ? local.legal_path_patterns : []
    content {
      path_pattern     = ordered_cache_behavior.value
      target_origin_id = "legal-bucket"

      viewer_protocol_policy = "redirect-to-https"
      allowed_methods        = ["GET", "HEAD"]
      cached_methods         = ["GET", "HEAD"]
      compress               = true

      # Managed-CachingOptimized, as for any other static document.
      cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"

      # The SAME rewrite the legal distribution's own tejdux-dir-index function performs: /privacy/
      # is a directory, and S3 has no index-document behaviour behind an OAC. Without this the
      # request asks for the key "privacy/" — which does not exist — and answers 403, which is the
      # exact failure being fixed here. spa_router already does this for a trailing slash, and reusing
      # it keeps one function rather than a near-duplicate.
      function_association {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.spa_router[0].arn
      }
    }
  }

  dynamic "ordered_cache_behavior" {
    # The API, both DPS prefixes, and the public hosted landing pages — generated rather than written
    # out four times: they differ only in the path pattern.
    #
    # `/s/*` is served by WebExperience, the same origin as /api/*. Without it the apex falls through
    # to the default (the SPA bucket), which answers 200 with the marketing shell — so a published
    # landing page looks reachable at tejdux.com/s/... and is not. The builder shows brands that
    # link, so the dead one is the one they copy to creators.
    for_each = local.cloudfront_backend_origin_domain != "" ? ["/api/*", "/dps", "/dps/*", "/s/*"] : []
    content {
      path_pattern     = ordered_cache_behavior.value
      target_origin_id = "api-origin"

      viewer_protocol_policy = "redirect-to-https"
      allowed_methods        = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
      cached_methods         = ["GET", "HEAD", "OPTIONS"]
      compress               = true
      # Managed-CachingDisabled. The id is exact and was WRONG here (…-6df8-4f0b-9f0d-979c4b3c8d9a),
      # which CloudFront rejects with `NoSuchCachePolicy` — a 404 that reads like the distribution is
      # missing rather than the policy. Verified against `aws cloudfront list-cache-policies`.
      #
      # Caching disabled is right for /api/* and /dps/*: these are authenticated, per-user responses, and
      # caching them would serve one tenant's data to another.
      cache_policy_id = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"

      # WITHOUT THIS EVERY API CALL THROUGH CLOUDFRONT RETURNS 401. A cache policy governs what forms the
      # cache key; an ORIGIN REQUEST policy governs what is forwarded at all. With none set, CloudFront
      # strips the Authorization header and cookies — so the BFF sees an anonymous request and the DPS
      # never receives its session cookie.
      #
      # AllViewerExceptHostHeader, not AllViewer: the Host header must remain api.tejdux.com so Caddy
      # matches its site block and serves the right certificate. Forwarding the VIEWER's Host
      # (tejdux.com) would make Caddy fall through to its default and answer with the wrong cert.
      origin_request_policy_id = "b689b0a8-53d0-40ab-baf2-68738e2966ac"
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

  # Which certificate, chosen per distribution rather than globally.
  #
  # The apex certificate is NOT a wildcard and the wildcard does NOT cover the apex, so the shell can
  # need a different certificate from the other six. Resolved in local.distribution_certificate so the
  # precedence is stated once; a distribution with no alias keeps CloudFront's default certificate,
  # which is correct — a default certificate is only wrong when a real hostname points at it.
  viewer_certificate {
    cloudfront_default_certificate = local.distribution_certificate[each.key] == ""
    acm_certificate_arn            = local.distribution_certificate[each.key] != "" ? local.distribution_certificate[each.key] : null
    ssl_support_method             = local.distribution_certificate[each.key] != "" ? "sni-only" : null
    # TLS 1.2 minimum. The apex was left on "TLSv1" by the hand-built configuration this replaced;
    # 1.0/1.1 are deprecated and browsers warn on them.
    minimum_protocol_version = local.distribution_certificate[each.key] != "" ? "TLSv1.2_2021" : null
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
  # Needed by the UI records, the apex records, or the API record, so the lookup cannot be gated on one
  # alone — setting only api_domain would otherwise index a zero-length data source and fail at plan time.
  need_dns_zone = local.static_aliased || local.apex_on_shell || var.api_domain != ""
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

# The apex and www, both pointing at the shell distribution.
#
# A *and* AAAA. The distributions set is_ipv6_enabled = true, so CloudFront answers on IPv6 and a
# client on an IPv6-only network needs the AAAA to reach it at all. An A-only apex is not broken for
# most users, which is exactly what makes the omission easy to miss.
#
# ALIAS rather than CNAME. DNS forbids a CNAME at a zone apex (it cannot coexist with the zone's own SOA
# and NS records), so www could be a CNAME but the apex could not — and an ALIAS works for both, costs
# nothing to resolve, and needs no TTL. That is why the CNAME www -> apex you might expect is not here:
# chaining www through the apex adds a resolution hop and gains nothing over aliasing both directly.
resource "aws_route53_record" "apex" {
  # One record per (name, type) pair: the apex and www, in A and AAAA.
  for_each = local.apex_on_shell ? {
    "apex-a"    = { name = var.root_domain, type = "A" }
    "apex-aaaa" = { name = var.root_domain, type = "AAAA" }
    "www-a"     = { name = "www.${var.root_domain}", type = "A" }
    "www-aaaa"  = { name = "www.${var.root_domain}", type = "AAAA" }
  } : {}

  zone_id = data.aws_route53_zone.root[0].zone_id
  name    = each.value.name
  type    = each.value.type

  alias {
    name    = aws_cloudfront_distribution.ui["shell"].domain_name
    zone_id = aws_cloudfront_distribution.ui["shell"].hosted_zone_id
    # A CloudFront distribution has no health check to evaluate; setting this true is rejected.
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
