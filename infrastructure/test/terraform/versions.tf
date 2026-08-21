terraform {
  # A floor, not an exact version, so a newer CLI still works; the provider is where drift hurts.
  #
  # Was 1.5.0 (for `check` blocks and `moved` semantics). Raised to 1.10.0 because the S3 backend
  # below uses `use_lockfile`, which older Terraform rejects outright with "An argument named
  # use_lockfile is not expected here" -- a backend error that says nothing about the version being
  # the cause. This floor turns it into a clear version error. Verified on 1.15.9.
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # ~> 5.x: v5 is where `aws_ecs_service` gained the arguments this configuration uses and
      # where EFS access-point handling settled. A v6 major bump should be a deliberate upgrade
      # with a plan reviewed, not something a fresh `init` picks up silently.
      version = "~> 5.40"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # STATE IS REMOTE AS OF 2026-08-19. It was local until then -- one file, on one machine, holding
  # the only record of ~147 live resource instances. Losing it would have meant rebuilding the
  # environment by hand from the console.
  #
  # The bucket is NOT managed by this configuration, deliberately: a backend cannot be created by
  # the configuration that uses it (the bucket holding the state would be described by the state it
  # holds). It was created once by hand -- versioned, AES256-encrypted, all public access blocked --
  # and versioning is the real safety net: every previous state revision is recoverable.
  #
  # State lives in S3, versioned and encrypted, with S3-NATIVE LOCKING.
  #
  # `use_lockfile` writes a .tflock object beside the state and relies on S3 conditional writes.
  # It replaces the DynamoDB table the old pattern required: one bucket instead of two resources,
  # nothing to keep in sync, and no second service to pay for. It needs Terraform >= 1.10 -- on
  # 1.9.8 `init` fails with "An argument named use_lockfile is not expected here", which is what
  # prompted the upgrade to 1.15.9.
  #
  # The key says `test`, not `prod`, even though every RESOURCE here is named influencrm-prod-*
  # (see account-guard.tf for why that name is stuck). Production gets its own account and its own
  # bucket; naming this key `prod` would have meant either a collision or a stranger name later.
  backend "s3" {
    bucket       = "influencrm-tfstate-099933382956"
    key          = "influencrm/test/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    # Applied to every taggable resource. This is what makes cost allocation possible after the
    # fact — without it, an ECS task's share of the bill is indistinguishable from anything else in
    # the account, and "what does this environment cost" has no answer.
    tags = {
      Project     = "influencrm"
      Environment = var.environment
      ManagedBy   = "terraform"
      Repo        = "InfluencerCRM"
    }
  }
}
