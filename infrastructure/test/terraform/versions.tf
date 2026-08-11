terraform {
  # 1.5+ for `check` blocks and the improved `moved` semantics. Pinned as a floor rather than an
  # exact version so a newer CLI still works; the provider is where drift actually hurts.
  required_version = ">= 1.5.0"

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

  # STATE IS LOCAL BY DEFAULT, WHICH IS WRONG FOR ANYTHING SHARED.
  #
  # Left commented rather than configured because a backend cannot be created by the configuration
  # that uses it (the chicken-and-egg: the S3 bucket holding the state would be described by the
  # state it holds). Create the bucket and lock table once, by hand or in a separate root module,
  # then uncomment.
  #
  # Until then the state file sits on one machine: a second person running `apply` creates a SECOND
  # copy of every resource rather than seeing the first, and losing the file means Terraform no
  # longer knows anything it built. Do this before more than one person deploys.
  #
  # backend "s3" {
  #   bucket       = "influencrm-tfstate-<account-id>"
  #   key          = "influencrm/prod/terraform.tfstate"
  #   region       = "us-east-1"
  #   encrypt      = true
  #   use_lockfile = true          # S3-native locking; no DynamoDB table needed on provider >= 5.40
  # }
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
