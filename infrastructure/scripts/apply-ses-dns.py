"""
OP-06: authorise SES in the apex SPF record, and publish DKIM CNAMEs for inbox.tejdux.com.

WHY THIS EXISTS
    Two DNS gaps on the live domain, both additive:

    1. The apex SPF record authorises Google but not SES, so mail sent through SES fails SPF
       alignment -- a weak signal when AWS reviews the sandbox-exit request, and a deliverability
       problem after it clears.
    2. inbox.tejdux.com's three DKIM CNAMEs were never published, so AWS gave up verifying after
       three days and emailed about it. That domain RECEIVES (GDPR deletion requests forwarded
       from privacy@) and never sends, and DKIM signs OUTBOUND mail -- so nothing is broken.
       Publishing the records tidies a failed verification out of the account, which matters only
       because a reviewer reading the next sandbox appeal sees it.

WHY THE SPF CHANGE IS AN EDIT AND NOT AN ADDITION
    Two `v=spf1` TXT records at the same name is a PERMANENT ERROR that breaks BOTH senders. There
    can only ever be one, so authorising SES means rewriting the existing record with an extra
    include. The batch restates the two google-site-verification strings byte-for-byte alongside
    it; dropping them would break Google Search Console and Workspace domain ownership.

WHAT THIS DELIBERATELY DOES NOT TOUCH
    The apex MX (`1 smtp.google.com`) carries all real business mail through Google Workspace. It
    is not in the change batch and must never be -- repointing it to automate one address would
    break every mailbox. The preflight refuses to run if MX appears in the batch at all.

WHY A SCRIPT AND NOT A DOCUMENTED COMMAND
    The DKIM tokens must not be hardcoded. Re-creating an SES identity issues new ones, and
    publishing stale tokens verifies nothing while looking perfectly correct. This asks SES what
    it currently wants on every run.

USAGE
    Run through the .bat, or directly:
        python infrastructure/scripts/apply-ses-dns.py            # prompts before applying
        python infrastructure/scripts/apply-ses-dns.py --dry-run  # prints the diff and exits
"""

import json
import os
import subprocess
import sys
import tempfile

ZONE_ID = "Z0068206CHFI6QYONX9W"
DOMAIN = "tejdux.com"
RECEIVE_DOMAIN = "inbox.tejdux.com"
PROFILE = os.environ.get("AWS_PROFILE", "tejdux")
REGION = os.environ.get("AWS_REGION", "us-east-1")

DRY_RUN = "--dry-run" in sys.argv


def say(message):
    print("\n== %s" % message)


def aws(*args):
    """Run an aws CLI command and return parsed stdout.

    shell=False so no argument is ever re-parsed by cmd.exe -- these arguments contain JMESPath
    expressions full of quotes and brackets, which is exactly the shape of string that gets
    mangled when a shell sees it twice.
    """
    command = ["aws", "--profile", PROFILE, "--region", REGION] + list(args)
    result = subprocess.run(command, capture_output=True, text=True, shell=False)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        raise SystemExit("aws call failed: %s" % " ".join(args[:3]))
    return result.stdout.strip()


def main():
    # -- 1. Read the live zone. Every decision below is made against THIS, never against an
    #       assumption about what the zone contains.
    say("Reading the live zone...")
    zone = json.loads(aws("route53", "list-resource-record-sets",
                          "--hosted-zone-id", ZONE_ID, "--output", "json"))["ResourceRecordSets"]

    apex_txt = [r for r in zone if r["Type"] == "TXT" and r["Name"] == DOMAIN + "."]
    spf_values = [v["Value"] for r in apex_txt for v in r["ResourceRecords"]
                  if "v=spf1" in v["Value"]]

    if len(spf_values) == 0:
        raise SystemExit(
            "STOP: no v=spf1 record at the apex. This script EDITS an existing record;\n"
            "      creating one from nothing is a policy decision for a human.")
    if len(spf_values) > 1:
        raise SystemExit(
            "STOP: more than one v=spf1 record at the apex. That is already a permanent\n"
            "      error and must be reduced to one BY HAND before anything is automated.")

    current_spf = spf_values[0]
    spf_needed = "amazonses.com" not in current_spf
    if not spf_needed:
        print("SES is already authorised in SPF; nothing to change there.")

    # -- 2. Ask SES what it currently wants, rather than trusting a hardcoded list.
    say("Asking SES for %s's DKIM tokens..." % RECEIVE_DOMAIN)
    tokens = aws("sesv2", "get-email-identity", "--email-identity", RECEIVE_DOMAIN,
                 "--query", "DkimAttributes.Tokens", "--output", "text").split()
    if len(tokens) != 3:
        raise SystemExit("STOP: expected 3 DKIM tokens, got %d." % len(tokens))

    published = {r["Name"] for r in zone if r["Type"] == "CNAME"}

    # -- 3. Build the change batch.
    changes = []

    if spf_needed:
        record = apex_txt[0]
        values = []
        for entry in record["ResourceRecords"]:
            value = entry["Value"]
            if "v=spf1" in value:
                # Inserted before the ~all qualifier, which must stay last.
                value = value.replace("include:_spf.google.com",
                                      "include:_spf.google.com include:amazonses.com")
            values.append({"Value": value})
        changes.append({
            "Action": "UPSERT",
            "ResourceRecordSet": {
                "Name": DOMAIN + ".", "Type": "TXT",
                "TTL": record.get("TTL", 300), "ResourceRecords": values,
            },
        })

    dkim_pending = []
    for token in tokens:
        name = "%s._domainkey.%s." % (token, RECEIVE_DOMAIN)
        if name in published:
            continue
        dkim_pending.append(token)
        changes.append({
            "Action": "UPSERT",
            "ResourceRecordSet": {
                "Name": name, "Type": "CNAME", "TTL": 300,
                "ResourceRecords": [{"Value": "%s.dkim.amazonses.com" % token}],
            },
        })

    batch = {"Comment": "OP-06: SES SPF include and inbox DKIM CNAMEs", "Changes": changes}
    encoded = json.dumps(batch, indent=2)

    # -- 4. Preflight. The MX check is the one that matters: it is the record whose loss would
    #       take down all business mail, so the batch is refused if it appears at all.
    if '"MX"' in encoded:
        raise SystemExit("STOP: the batch references an MX record. It must not.")
    if '"DELETE"' in encoded:
        raise SystemExit("STOP: the batch contains a DELETE. Every change here must be additive.")

    if not changes:
        say("Nothing to do -- SPF already authorises SES and every DKIM record is published.")
        return

    # -- 5. Show the diff.
    say("The change (%d records, all UPSERT, no deletes, no MX):" % len(changes))
    if spf_needed:
        print("  apex TXT (SPF)")
        print("    - %s" % current_spf)
        print("    + %s" % current_spf.replace(
            "include:_spf.google.com", "include:_spf.google.com include:amazonses.com"))
    for token in dkim_pending:
        print("  + %s._domainkey.%s  CNAME  %s.dkim.amazonses.com"
              % (token, RECEIVE_DOMAIN, token))
    print("\n  UNCHANGED: %s MX -> 1 smtp.google.com   (Google Workspace; all business mail)"
          % DOMAIN)

    if DRY_RUN:
        say("Dry run -- nothing applied.")
        return

    reply = input("\nApply this to the LIVE zone? [y/N] ").strip().lower()
    if reply != "y":
        print("Aborted. Nothing changed.")
        return

    # -- 6. Apply, then wait for propagation rather than assuming it.
    say("Applying...")
    handle, path = tempfile.mkstemp(suffix=".json")
    try:
        with os.fdopen(handle, "w") as batch_file:
            batch_file.write(encoded)
        change_id = aws("route53", "change-resource-record-sets",
                        "--hosted-zone-id", ZONE_ID,
                        "--change-batch", "file://%s" % path,
                        "--query", "ChangeInfo.Id", "--output", "text")
    finally:
        os.unlink(path)

    print("Change %s submitted. Waiting for INSYNC..." % change_id)
    aws("route53", "wait", "resource-record-sets-changed", "--id", change_id)

    # -- 7. Verify against the live API rather than reporting success from an exit code.
    say("Applied and INSYNC. Verifying against the live zone...")
    after = json.loads(aws("route53", "list-resource-record-sets",
                           "--hosted-zone-id", ZONE_ID, "--output", "json"))["ResourceRecordSets"]

    for record in after:
        if record["Type"] == "TXT" and record["Name"] == DOMAIN + ".":
            for entry in record["ResourceRecords"]:
                if "v=spf1" in entry["Value"]:
                    print("  SPF now: %s" % entry["Value"])
    for record in after:
        if record["Type"] == "MX" and record["Name"] == DOMAIN + ".":
            values = [v["Value"] for v in record["ResourceRecords"]]
            print("  MX  now: %s   (must be unchanged)" % ", ".join(values))

    print("""
Next:
  - DKIM verification is SES's own poll; it usually completes within minutes:
      aws sesv2 get-email-identity --email-identity inbox.tejdux.com ^
        --query DkimAttributes.Status --profile tejdux

  - NEITHER change unblocks sending. The account is still in the SES SANDBOX, which is a
    separate review (case 178750875200560, previously DENIED). That request is missing its
    UseCaseDescription, which is almost certainly why it was refused.""")


if __name__ == "__main__":
    main()
