"""
OP-06: request SES production access (sandbox exit).

THE API ROUTE IS CLOSED AFTER A DENIAL -- USE THE CONSOLE
    Tried on 2026-08-27 and it does not work: with ReviewDetails.Status == DENIED on the account,
    `put-account-details` returns

        ConflictException  {"message": null}

    with no detail, and the account is left completely unchanged -- ProductionAccessEnabled stays
    false and UseCaseDescription is still absent, so nothing is queued and nothing is submitted.

    That appears to be deliberate rather than a bug: it stops a denied account resubmitting the
    same request in a loop, and pushes the appeal through a human who can see the previous case.
    So after a denial the ONLY route is the console:

        Support -> Your support cases -> case 178750875200560 -> Reply

    Reply to the DENIED case with the text below. Do not open a brand-new case: the reviewer needs
    the history, and a duplicate case for the same account is likely to be closed as such.

    This script therefore still earns its place -- it holds the reviewed text, checks the live
    state, and will work again if the account is ever back in a state the API accepts -- but if it
    reports a conflict, copy USE_CASE into the console reply instead.

WHY THE FIRST ONE WAS ALMOST CERTAINLY DENIED
    `aws sesv2 get-account --query Details` showed MailType, WebsiteURL and ContactLanguage all
    populated and NO UseCaseDescription at all. That field is the one a human reviewer reads. A
    request without it gives them nothing to approve, and "insufficient detail" is the most common
    denial reason AWS publishes.

WHAT REVIEWERS LOOK FOR, AND WHERE THIS PRODUCT STANDS
    1. Who receives the mail, and how they consented.
         Account holders who signed up, colleagues they invited, and creators a brand invited to
         collaborate. No purchased lists, no marketing, no bulk send.
    2. What happens to bounces and complaints.
         Configuration set `influencrm-prod` exists with ReputationMetricsEnabled, and SES
         account-level suppression is on for BOUNCE and COMPLAINT.
    3. How recipients stop receiving mail.
         Every message is transactional and tied to an action the recipient took; accounts can be
         closed in-app, and a documented deletion path exists at /data-deletion/.
    4. A real website that explains the product.
         https://www.tejdux.com, with /privacy/ and /terms/ both live.

    Points 1-4 are true today. This script asserts nothing that is not.

BEFORE RUNNING
    Read the actual denial reason in the console first if you have not:
        Support -> Your support cases -> include resolved -> case 178750875200560
    If it names something other than the missing description, EDIT THE TEXT BELOW to address what
    it actually says. Re-submitting without addressing the stated reason is the reliable way to be
    denied twice, and repeat denials make later appeals harder.

USAGE
    python infrastructure/scripts/request-ses-production.py --dry-run   # print, change nothing
    python infrastructure/scripts/request-ses-production.py             # prompts, then submits
"""

import json
import os
import subprocess
import sys

PROFILE = os.environ.get("AWS_PROFILE", "tejdux")
REGION = os.environ.get("AWS_REGION", "us-east-1")
DRY_RUN = "--dry-run" in sys.argv

WEBSITE = "https://www.tejdux.com"

# Kept as one string rather than assembled from fragments: this is the text a human reads, and it
# should be reviewable here exactly as it will be submitted.
USE_CASE = (
    "Tejdux (tejdux.com) is an influencer-marketing CRM for small brands. All mail is "
    "transactional and sent only to people who took an action that causes it.\n"
    "\n"
    "What we send, and to whom:\n"
    "1. Account email to people who signed up on tejdux.com: address verification, password "
    "reset, subscription and payment notifications, and account-closure confirmations.\n"
    "2. Team invitations, sent only to an address a signed-in account owner typed in order to "
    "invite a colleague to their own workspace.\n"
    "3. Collaboration email to creators a brand has explicitly invited to co-author a campaign "
    "page: the invitation itself, and a notification when that page is published.\n"
    "4. Service notices to the account owner, such as a warning before hosting for a published "
    "page expires.\n"
    "5. Operational digests to our own staff addresses.\n"
    "\n"
    "We do not send marketing, newsletters, promotional or bulk mail. We do not buy, rent or "
    "upload contact lists. Every recipient is either an account holder, somebody an account "
    "holder deliberately invited, or our own staff.\n"
    "\n"
    "Bounce and complaint handling: SES account-level suppression is enabled for BOUNCE and "
    "COMPLAINT, and we send through a configuration set (influencrm-prod) with reputation "
    "metrics enabled, so bounces and complaints are tracked and repeat sends to failing "
    "addresses are suppressed automatically.\n"
    "\n"
    "Unsubscribing: because all mail is transactional and tied to an account, recipients stop "
    "receiving it by closing their account, which they can do in the application. We also "
    "publish a data-deletion path at https://tejdux.com/data-deletion/ and a privacy policy at "
    "https://tejdux.com/privacy/, and requests to privacy@tejdux.com are recorded and actioned.\n"
    "\n"
    "Volume: low. We are pre-launch with a small number of accounts, and expect well under "
    "1,000 messages per month initially.\n"
    "\n"
    "Sending domain tejdux.com is verified with DKIM signing enabled, SPF authorises "
    "amazonses.com, and a DMARC record is published with reporting to privacy@tejdux.com."
)


def aws(*args, **kwargs):
    """Run an aws CLI call. With check=False, return None on failure instead of exiting."""
    check = kwargs.pop("check", True)
    command = ["aws", "--profile", PROFILE, "--region", REGION] + list(args)
    result = subprocess.run(command, capture_output=True, text=True, shell=False)
    if result.returncode != 0:
        if not check:
            return None
        sys.stderr.write(result.stderr)
        raise SystemExit("aws call failed: %s" % " ".join(args[:3]))
    return result.stdout.strip()


def main():
    # Read the live state first. If access was granted since this was written, submitting another
    # request would be noise on the account for no benefit.
    account = json.loads(aws("sesv2", "get-account", "--output", "json"))
    if account.get("ProductionAccessEnabled"):
        print("Production access is ALREADY ENABLED. Nothing to request.")
        return

    review = account.get("Details", {}).get("ReviewDetails", {})
    print("Current review status: %s (case %s)"
          % (review.get("Status", "none"), review.get("CaseId", "n/a")))
    if review.get("Status") == "PENDING":
        print("\nA review is already PENDING. Submitting again would not speed it up and")
        print("resets the queue position. Wait for the outcome.")
        return

    print("\n" + "=" * 78)
    print("USE CASE DESCRIPTION TO BE SUBMITTED")
    print("=" * 78)
    print(USE_CASE)
    print("=" * 78)
    print("\nMail type:  TRANSACTIONAL")
    print("Website:    %s" % WEBSITE)

    if DRY_RUN:
        print("\nDry run -- nothing submitted.")
        return

    print("\nBefore submitting: have you read the denial reason on case %s"
          % review.get("CaseId", "n/a"))
    print("in the console, and does the text above address it?")
    reply = input("Submit this request? [y/N] ").strip().lower()
    if reply != "y":
        print("Aborted. Nothing submitted.")
        return

    aws("sesv2", "put-account-details",
        "--production-access-enabled",
        "--mail-type", "TRANSACTIONAL",
        "--website-url", WEBSITE,
        "--contact-language", "EN",
        "--use-case-description", USE_CASE)

    print("\nSubmitted. Review is typically ~24 hours.")
    print("Check with:")
    print("  aws sesv2 get-account --query Details.ReviewDetails --profile %s" % PROFILE)


if __name__ == "__main__":
    main()
