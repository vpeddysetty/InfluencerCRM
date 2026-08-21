# ============================================================================
#  Which account this configuration is allowed to touch.
#
#  WHY THIS FILE EXISTS. Every resource here is named `influencrm-prod-*`,
#  because `var.environment` defaults to "prod" (variables.tf). That name is a
#  historical accident: this is the TEST environment, and it is the only
#  environment that exists today. Renaming it was considered and declined --
#  changing `environment` forces replacement of nearly every one of the ~139
#  resources in this state, on the deployment currently serving the live
#  tejdux.com domain with real credentials. See MASTER-ROADMAP.md section 7.1.
#
#  So the name cannot tell you which environment you are in, and once a second
#  AWS account exists for real production, `terraform apply` in the wrong
#  terminal is a plausible mistake with an implausible blast radius.
#
#  THE NAME WAS NEVER THE SAFETY PROPERTY. The account id is. This check makes
#  that explicit: applying this configuration against any account other than
#  the test account fails at plan time, before a single resource is evaluated.
#
#  A failing precondition is the point. It costs nothing when correct and stops
#  the one mistake that cannot be undone by editing a variable.
#
#  WHEN THE PROD ACCOUNT EXISTS, it gets its own configuration directory with
#  its own guard naming its own account id. Do not add a second id here -- an
#  assertion that accepts two accounts asserts nothing about which one you are
#  in, which is exactly the confusion this file exists to remove.
#
#  ALWAYS PASS THE LIVE image_tag WHEN PLANNING. `image_tag` is required and has
#  no default, so it must be supplied on every plan -- and supplying a stale one
#  silently rewrites all 12 image references in the rendered compose file. While
#  adding this guard, a plan run with `-var image_tag=v1.0.19` (a value copied
#  from an old script name) proposed rolling every service back from the live
#  v1.0.21. Nothing about that plan looked alarming: no deletes, no replaces,
#  just two "update" lines. Read the rendered diff, not the action counts.
# ============================================================================

variable "expected_account_id" {
  description = <<-EOT
    AWS account this configuration may be applied to. A mismatch fails the plan.
    Overridable so a fork or a rebuild in a fresh account is not blocked by a
    hardcoded id -- but it must be set deliberately, never by accident.
  EOT
  type        = string
  default     = "099933382956" # test (serves tejdux.com today)

  validation {
    condition     = can(regex("^[0-9]{12}$", var.expected_account_id))
    error_message = "expected_account_id must be a 12-digit AWS account id."
  }
}

# `data.aws_caller_identity.current` is declared in secrets.tf and reused here.
# A second declaration of the same data source would be a duplicate-name error.

# A `precondition`, NOT a `check` block. This distinction is the whole value of the file:
# a failed `check` assertion is a WARNING -- terraform prints it and applies anyway -- whereas a
# failed precondition is an ERROR that stops the run. Both were tried here; the check block
# reported "Warning: Check block assertion failed" and would have let the apply proceed against
# the wrong account, which is precisely the outcome this is meant to prevent.
resource "terraform_data" "account_guard" {
  input = var.expected_account_id

  lifecycle {
    precondition {
      condition = data.aws_caller_identity.current.account_id == var.expected_account_id

      error_message = format(
        "WRONG AWS ACCOUNT. This is the TEST configuration (resources named influencrm-prod-* for historical reasons) and it may only be applied to %s, but the current credentials belong to %s. Check AWS_PROFILE -- it should be `tejdux`. If you meant to deploy production, use its own configuration directory, not this one.",
        var.expected_account_id,
        data.aws_caller_identity.current.account_id,
      )
    }
  }
}
