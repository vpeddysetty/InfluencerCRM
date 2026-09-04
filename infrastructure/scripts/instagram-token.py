#!/usr/bin/env python3
"""Mint a non-expiring Instagram Page token and store it, with one browser click.

    python infrastructure/scripts/instagram-token.py
    python infrastructure/scripts/instagram-token.py --dry-run     # print, store nothing
    python infrastructure/scripts/instagram-token.py --print-only  # skip AWS entirely

WHY THIS EXISTS. Four hand-run token attempts failed for four different reasons: a short-lived
token stored as if it were durable, shell quotes captured into the secret value, an app access
token passed where a user token was required, and an exchange skipped so the result died in an
hour. Each failure looked the same from the product — Instagram lookups silently reverting to
simulated numbers — because a bad token and no token are indistinguishable downstream. This
script exists to make those four mistakes unrepresentable rather than to save typing.

WHAT CANNOT BE AUTOMATED, AND WHY THAT IS CORRECT. Meta issues no user token without a human
approving a consent dialog in a browser. There is no service-account grant and no headless flow.
That is the security property that stops a leaked app secret from minting tokens for arbitrary
users, so this script does not try to defeat it: it opens the dialog, and automates every step
on either side of the click.

THE CHAIN, AND THE STEP EVERYONE SKIPS.

    consent dialog  ──►  code  ──►  short-lived user token (~1 hour)
                                        │
                                        ▼   fb_exchange_token   ← SKIPPING THIS IS THE BUG
                                    long-lived user token (60 days)
                                        │
                                        ▼   /me/accounts
                                    PAGE TOKEN — no expiry

A Page token inherits the lifetime of the user token it came from. Derived from a short-lived
one it dies within the hour; derived from a long-lived one it does not expire at all. Both paths
return a token that looks identical, which is why this script asserts on `expires_in` rather
than trusting that the exchange did anything.
"""

import argparse
import http.server
import json
import os
import secrets
import subprocess
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser
from datetime import datetime, timezone

GRAPH = "https://graph.facebook.com/v20.0"
DIALOG = "https://www.facebook.com/v20.0/dialog/oauth"

# The Business app. The Consumer app (2214744426037953) signs users in and cannot reach the
# Instagram Graph API at all, so pointing this at it would fail late and confusingly.
DEFAULT_APP_ID = "1532612907951511"

# business_discovery needs instagram_basic; resolving which Page carries the Instagram account
# needs the two pages_ scopes. Requesting more than the adapter uses would be a review risk —
# reviewers check that every granted permission is actually exercised.
#
# KEPT FOR DOCUMENTATION ONLY — THIS APP IGNORES IT. A Business-type app does not read `scope`;
# permissions come from a Business Login Configuration referenced by `config_id`, which REPLACES
# it (see docs/platform-app-registration.md, learned the slow way). Sending scope here was not
# merely inert: with no configuration existing, Meta fell back to the app's entire permission set
# and minted a token carrying 15 scopes — ads_management and business_management among them —
# while this constant said three. Verified 2026-09-03 by debug_token on the resulting token.
#
# That mattered because the App Review screencast films the consent dialog: a reviewer asked for
# three permissions would have watched fifteen being granted.
SCOPES = "instagram_basic,pages_show_list,pages_read_engagement"

# The Business Login Configuration built in the dashboard, holding exactly the three permissions
# above. This is what the dialog actually honours, so the grant is now defined in one place a
# human can inspect rather than by an app-wide default nothing names.
DEFAULT_CONFIG_ID = "2021219268570991"

# ENVIRONMENTS, NAMED RATHER THAN ASSUMED. The `influencrm-prod/` prefix is historical: that
# account is becoming the TEST environment, and a second, genuinely production one follows when
# the paid plan ships. Keeping the real secret prefix visible next to the label we now use for it
# is the point — an operator picking "test" should see exactly which secrets they are about to
# overwrite, rather than trusting that a friendly name maps where they assume.
#
# Adding the production environment later is one entry here. Deliberately NOT defaulted to: a tool
# that writes credentials should make the destination an explicit choice every time.
ENVIRONMENTS = {
    "test": {
        "label": "Test (account 099933382956)",
        "prefix": "influencrm-prod",
        "app_id": DEFAULT_APP_ID,
        "profile": "tejdux",
        "region": "us-east-1",
    },
}

DEFAULT_ENVIRONMENT = "test"


def secret_names(environment):
    """The three secret ids for an environment. Must match application.properties."""
    prefix = ENVIRONMENTS[environment]["prefix"]
    return (f"{prefix}/instagram-access-token",
            f"{prefix}/instagram-business-account-id",
            f"{prefix}/facebook-business-client-secret")

# Long-lived means 60 days; Meta returns ~5183944 seconds. Anything materially under this is a
# short-lived token wearing the same shape, which is the failure this whole script exists to catch.
LONG_LIVED_FLOOR = 60 * 60 * 24 * 50

REDIRECT_PORT = 8765
REDIRECT_URI = f"http://localhost:{REDIRECT_PORT}/callback"


def fail(message, *, hint=None):
    print(f"\n  ERROR  {message}", file=sys.stderr)
    if hint:
        print(f"         {hint}", file=sys.stderr)
    sys.exit(1)


def get(url, params):
    """GET and parse JSON, surfacing Meta's own error text rather than a status code.

    Meta explains itself in the body — "Error validating client secret", "the user logged out" —
    and that sentence is the entire diagnosis. An HTTPError swallowed into "400 Bad Request"
    throws that away, so error bodies are read and returned like any other response.
    """
    query = urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(f"{url}?{query}", timeout=30) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        try:
            return json.loads(error.read().decode())
        except Exception:
            fail(f"{url} returned HTTP {error.code} with an unreadable body")
    except Exception as error:
        fail(f"could not reach {url}: {error}")


def require(payload, what):
    """Stop on Meta's error shape, translating the ones with a known cause."""
    if "error" in payload:
        message = payload["error"].get("message", "unknown error")
        hint = None
        if "client secret" in message.lower():
            hint = (f"the value in {APP_SECRET_NAME} does not match app {DEFAULT_APP_ID}. "
                    "Read the current one from Settings -> Basic -> App Secret -> Show. "
                    "An app secret is 32 hex characters.")
        elif "logged out" in message.lower() or "expired" in message.lower():
            hint = "the app secret was rotated, which invalidates every token minted under it."
        fail(f"{what}: {message}", hint=hint)
    return payload


def read_secret(name, region, profile):
    """Read a secret, stripping quotes that a shell captured into the stored value.

    THE QUOTE BUG, GUARDED HERE BECAUSE IT HAS HAPPENED THREE TIMES. PowerShell passes
    --secret-string 'value' through with the quotes intact, so Secrets Manager stores
    'EAAVx...' rather than EAAVx... . Meta then rejects the token as unparseable, which reads as
    an expired credential rather than a quoting mistake and sends you to regenerate a token that
    was never wrong.
    """
    command = ["aws", "secretsmanager", "get-secret-value", "--secret-id", name,
               "--query", "SecretString", "--output", "text", "--region", region]
    if profile:
        command += ["--profile", profile]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        return None
    return result.stdout.strip().strip("'\"")


def write_secret(name, value, region, profile, dry_run):
    if dry_run:
        print(f"    DRY RUN  would write {len(value)} chars to {name}")
        return True
    command = ["aws", "secretsmanager", "put-secret-value", "--secret-id", name,
               "--secret-string", value, "--query", "VersionId", "--output", "text",
               "--region", region]
    if profile:
        command += ["--profile", profile]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"    FAILED   {name}: {result.stderr.strip()}", file=sys.stderr)
        return False
    # The VersionId is the only proof the write landed. A put that silently no-ops leaves the old
    # value in place, which is exactly how an expired token survived a "successful" update.
    print(f"    stored   {name}  (version {result.stdout.strip()[:8]}...)")
    return True


class ConsentCallback(http.server.BaseHTTPRequestHandler):
    """Catches the one redirect Meta makes after the human approves."""

    code = None
    state = None
    error = None

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/callback":
            self.send_response(404)
            self.end_headers()
            return
        params = urllib.parse.parse_qs(parsed.query)
        ConsentCallback.code = params.get("code", [None])[0]
        ConsentCallback.state = params.get("state", [None])[0]
        ConsentCallback.error = params.get("error_description", params.get("error", [None]))[0]

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        body = ("<h2>Approved — you can close this tab.</h2>"
                if ConsentCallback.code
                else f"<h2>Consent failed</h2><p>{ConsentCallback.error}</p>")
        self.wfile.write(f"<html><body style='font-family:system-ui;padding:3rem'>{body}"
                         "</body></html>".encode())

    def log_message(self, *args):
        pass  # the script narrates its own progress; the HTTP log is noise


def await_consent(app_id, config_id=DEFAULT_CONFIG_ID):
    """Open the dialog and block until Meta redirects back.

    Sends `config_id`, NOT `scope`. This is a Business-type app, where the two are not alternatives
    of equal standing: config_id replaces scope, and a scope parameter is ignored. Sending scope
    alone is what produced a 15-scope token on 2026-09-03 — Meta fell back to the app's whole
    permission set because no configuration was named.
    """
    state = secrets.token_urlsafe(16)
    params = {
        "client_id": app_id,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "state": state,
    }
    if config_id:
        params["config_id"] = config_id
    else:
        # No configuration named. Correct only for a Consumer app; on the Business app this grants
        # the app-wide set, so say so rather than letting a silent fallback look like success.
        print("    WARNING: no config_id — a Business app will grant its FULL permission set.")
        params["scope"] = SCOPES
    url = f"{DIALOG}?" + urllib.parse.urlencode(params)

    server = http.server.HTTPServer(("localhost", REDIRECT_PORT), ConsentCallback)
    thread = threading.Thread(target=server.handle_request, daemon=True)
    thread.start()

    print(f"\n    Opening the consent dialog. Approve as the user who administers the Page.")
    print(f"    If no browser opens, paste this:\n\n    {url}\n")
    try:
        webbrowser.open(url)
    except Exception:
        pass

    # No timeout: a human is reading a consent screen, and guessing how long that takes is how a
    # script fails on someone's slow login. Ctrl-C is the exit.
    thread.join()
    server.server_close()

    if ConsentCallback.error:
        fail(f"consent was refused: {ConsentCallback.error}")
    if not ConsentCallback.code:
        fail("no authorization code arrived on the callback")
    if ConsentCallback.state != state:
        # CSRF, or a stale tab from an earlier run answering this one's listener.
        fail("state mismatch — the response did not belong to this request")
    return ConsentCallback.code


def exchange_for_page_token(code, app_id, app_secret, notify):
    """Turn an authorization code into a verified, non-expiring Page token.

    Shared by the CLI and the local admin UI so the two cannot drift: the assertions below are the
    whole value of this tool, and a second copy of them would eventually be a weaker copy.

    `notify` takes a progress line. Raises TokenError with a human-readable cause on any failure.
    """
    short = require_or_raise(get(f"{GRAPH}/oauth/access_token", {
        "client_id": app_id,
        "client_secret": app_secret,
        "redirect_uri": REDIRECT_URI,
        "code": code,
    }), "exchanging the authorization code")["access_token"]
    notify("got a short-lived user token")

    exchanged = require_or_raise(get(f"{GRAPH}/oauth/access_token", {
        "grant_type": "fb_exchange_token",
        "client_id": app_id,
        "client_secret": app_secret,
        "fb_exchange_token": short,
    }), "exchanging for a long-lived token")

    expires_in = int(exchanged.get("expires_in", 0))
    if expires_in and expires_in < LONG_LIVED_FLOOR:
        # THE ASSERTION THIS TOOL EXISTS FOR. A short-lived token here yields a Page token that
        # dies within the hour and looks identical to a good one until it does.
        raise TokenError(
            f"the exchange returned a token valid for only {expires_in // 3600} hours",
            "Expected ~60 days. The long-lived exchange did not take.")
    notify(f"exchanged for a long-lived user token ({expires_in // 86400 or 60} days)")

    pages = require_or_raise(get(f"{GRAPH}/me/accounts", {
        "access_token": exchanged["access_token"],
        "fields": "id,name,access_token,instagram_business_account{id,username,followers_count}",
    }), "listing Pages")

    entries = pages.get("data", [])
    if not entries:
        raise TokenError("that account administers no Facebook Page",
                         "business_discovery reads through a Page. Create one, then link "
                         "Instagram to it.")

    linked = [p for p in entries if p.get("instagram_business_account")]
    if not linked:
        names = ", ".join(p.get("name", "?") for p in entries)
        raise TokenError(f"no Page has an Instagram account linked (found: {names})",
                         "Page -> Settings -> Linked accounts -> Instagram -> Connect. "
                         "The Instagram account must be Business, not Personal.")

    page = linked[0]
    instagram = page["instagram_business_account"]
    page_token = page["access_token"]

    if len(linked) > 1:
        # Stated rather than silently taking the first: picking the wrong Page produces a token
        # that works and reads the wrong account's data, which is worse than an error.
        notify(f"NOTE: {len(linked)} linked Pages — using '{page.get('name')}'")

    # Verified before storing. A token that debug_token rejects would otherwise be written and
    # only discovered failing in the product, where it presents as simulated numbers.
    debug = require_or_raise(get(f"{GRAPH}/debug_token", {
        "input_token": page_token, "access_token": page_token,
    }), "verifying the Page token")["data"]

    if not debug.get("is_valid"):
        raise TokenError("the Page token did not validate")
    if debug.get("type") != "PAGE":
        raise TokenError(f"expected a PAGE token, got {debug.get('type')}")

    notify("verified")
    return {
        "page_name": page.get("name"),
        "page_id": page["id"],
        "username": instagram.get("username"),
        "account_id": instagram["id"],
        "followers": instagram.get("followers_count", 0),
        "token": page_token,
        "expires_at": debug.get("expires_at", 0),
    }


class TokenError(Exception):
    def __init__(self, message, hint=None):
        super().__init__(message)
        self.hint = hint


def require_or_raise(payload, what):
    """As `require`, but raising rather than exiting — the UI must survive a failed attempt."""
    if "error" in payload:
        message = payload["error"].get("message", "unknown error")
        hint = None
        if "client secret" in message.lower():
            hint = ("The stored app secret does not match this app. Read the current one from "
                    "Settings -> Basic -> App Secret -> Show. An app secret is 32 hex characters.")
        elif "logged out" in message.lower() or "expired" in message.lower():
            hint = "The app secret was rotated, which invalidates every token minted under it."
        raise TokenError(f"{what}: {message}", hint)
    return payload


def describe_expiry(expires_at):
    if not expires_at:
        return "never"
    return datetime.fromtimestamp(expires_at, timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def load_app_secret(environment):
    """Read and sanity-check the app secret for an environment."""
    _, _, app_secret_name = secret_names(environment)
    config = ENVIRONMENTS[environment]
    value = read_secret(app_secret_name, config["region"], config["profile"])
    if not value:
        raise TokenError(f"could not read {app_secret_name}",
                         "Check the AWS profile and region, or that the secret exists.")
    if len(value) != 32:
        # Caught here rather than at the exchange, where Meta reports it as a generic validation
        # failure that reads like a code bug.
        raise TokenError(
            f"{app_secret_name} holds {len(value)} characters; an app secret is 32 hex.",
            "Settings -> Basic -> App Secret -> Show, then store that value.")
    return value


def current_status(environment):
    """What is stored for an environment right now, and whether Meta still accepts it."""
    token_name, account_name, _ = secret_names(environment)
    config = ENVIRONMENTS[environment]
    token = read_secret(token_name, config["region"], config["profile"])
    account = read_secret(account_name, config["region"], config["profile"])

    status = {"account_id": account or None, "token_present": bool(token)}
    if not token:
        status["state"] = "missing"
        return status

    debug = get(f"{GRAPH}/debug_token", {"input_token": token, "access_token": token})
    if "error" in debug or not debug.get("data", {}).get("is_valid"):
        status["state"] = "invalid"
        status["detail"] = debug.get("error", {}).get("message", "Meta rejected the stored token")
        return status

    data = debug["data"]
    status["state"] = "valid"
    status["type"] = data.get("type")
    status["expires"] = describe_expiry(data.get("expires_at", 0))
    status["expires_at"] = data.get("expires_at", 0)
    return status


def run_cli(args):
    environment = args.environment
    config = ENVIRONMENTS[environment]
    token_name, account_name, _ = secret_names(environment)
    dry_run = args.dry_run or args.print_only

    print(f"\n  Instagram token — {config['label']}\n  " + "-" * 46)

    try:
        app_secret = load_app_secret(environment)
        code = await_consent(config["app_id"], getattr(args, "config_id", DEFAULT_CONFIG_ID))
        print("    approved")
        result = exchange_for_page_token(code, config["app_id"], app_secret,
                                         lambda line: print(f"    {line}"))
    except TokenError as error:
        fail(str(error), hint=error.hint)

    print(f"\n    Page       {result['page_name']} ({result['page_id']})")
    print(f"    Instagram  @{result['username']} ({result['account_id']}), "
          f"{result['followers']} followers")
    print(f"    Expires    {describe_expiry(result['expires_at'])}")

    if result["expires_at"]:
        # Not fatal — a dated token still works today — but it means the chain degraded somewhere,
        # and the whole point was to end up with one that does not expire.
        print("    WARNING: this token has an expiry. A Page token derived from a long-lived\n"
              "             user token should report 'never'.")

    if args.print_only:
        print(f"\n    ACCOUNT ID  {result['account_id']}\n    PAGE TOKEN  {result['token']}\n")
        return

    print()
    ok = write_secret(token_name, result["token"], config["region"], config["profile"], dry_run)
    ok &= write_secret(account_name, result["account_id"], config["region"], config["profile"],
                       dry_run)
    if not ok:
        fail("one or more secrets did not store")

    if result["followers"] == 0:
        print("\n    NOTE: this account has no followers or posts, so the product will render an\n"
              "          empty profile. A screencast needs an account with real content.")

    print("\n  Done. The adapter reads these two secrets on start.\n")


def main():
    parser = argparse.ArgumentParser(
        description="Mint and store an Instagram Page token (Tejdux internal admin tool).")
    parser.add_argument("--env", dest="environment", default=DEFAULT_ENVIRONMENT,
                        choices=sorted(ENVIRONMENTS), help="which environment's secrets to write")
    parser.add_argument("--ui", action="store_true",
                        help="serve the local admin console instead of running once on the CLI")
    parser.add_argument("--port", type=int, default=8766, help="port for --ui")
    parser.add_argument("--config-id", dest="config_id", default=DEFAULT_CONFIG_ID,
                        help="Business Login Configuration id the dialog honours. Pass empty to "
                             "fall back to scope, which a Business app ignores.")
    parser.add_argument("--dry-run", action="store_true",
                        help="run the whole chain but write nothing to Secrets Manager")
    parser.add_argument("--print-only", action="store_true",
                        help="print the results instead of storing them; implies --dry-run")
    args = parser.parse_args()

    if args.ui:
        import admin_ui
        admin_ui.serve(args.port, sys.modules[__name__])
    else:
        run_cli(args)


if __name__ == "__main__":
    main()
