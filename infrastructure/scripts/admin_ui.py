"""The local admin console for the Instagram token tool.

    python infrastructure/scripts/instagram-token.py --ui

WHY THIS RUNS LOCALLY AND NOT IN THE PRODUCT. The tool needs two things that cannot meet in one
place: a browser, for the consent dialog Meta will not issue a token without, and the app secret
plus AWS credentials, which must never reach a browser. Only an operator's own machine holds both.

It is also not tenant-scoped. Every role in InfluencerUI — including OWNER — belongs to a customer
account, and this tool writes ONE credential that serves every tenant. A button for it inside the
product would let a customer overwrite the platform's Meta credential, which is not a permissions
bug to be fixed with a role check but a category error: the thing being edited sits outside the
tenancy model entirely.

So: bound to 127.0.0.1, no auth beyond "you are sitting at this machine and hold its AWS profile",
and it dies when the operator closes it. That is the correct security boundary for support,
developers, and testers to share.
"""

import http.server
import json
import threading
import urllib.parse
import webbrowser

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Instagram Token — Tejdux Admin</title>
<style>
  /* Light palette on :root, redefined under both the media query and the attribute, so an
     explicit toggle wins in either direction and a "system" default still tracks the OS. */
  :root{
    --bg:#f6f7f9; --panel:#fff; --ink:#14171f; --muted:#5b6472; --line:#e2e6ec;
    --accent:#2f6feb; --accent-ink:#fff; --ok:#1a7f4b; --warn:#8a5a00; --bad:#b3261e;
    --ok-bg:#e8f5ee; --warn-bg:#fdf3e0; --bad-bg:#fdeceb; --mono:ui-monospace,SFMono-Regular,Menlo,monospace;
  }
  @media (prefers-color-scheme:dark){:root:not([data-theme="light"]){
    --bg:#0f1116; --panel:#171a21; --ink:#e8eaed; --muted:#9aa3b2; --line:#272c36;
    --accent:#5b8def; --accent-ink:#0f1116; --ok:#5cd39b; --warn:#e0b558; --bad:#f2837b;
    --ok-bg:#12271d; --warn-bg:#2a2312; --bad-bg:#2b1917;
  }}
  :root[data-theme="dark"]{
    --bg:#0f1116; --panel:#171a21; --ink:#e8eaed; --muted:#9aa3b2; --line:#272c36;
    --accent:#5b8def; --accent-ink:#0f1116; --ok:#5cd39b; --warn:#e0b558; --bad:#f2837b;
    --ok-bg:#12271d; --warn-bg:#2a2312; --bad-bg:#2b1917;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);
       font:15px/1.55 system-ui,-apple-system,Segoe UI,sans-serif;padding:2.5rem 1.25rem}
  .wrap{max-width:660px;margin:0 auto}
  header{display:flex;align-items:baseline;gap:.6rem;margin-bottom:.35rem}
  h1{font-size:1.3rem;margin:0;letter-spacing:-.01em}
  .badge{font-size:.7rem;text-transform:uppercase;letter-spacing:.06em;color:var(--muted);
         border:1px solid var(--line);border-radius:999px;padding:.15rem .5rem}
  .sub{color:var(--muted);font-size:.9rem;margin:0 0 1.5rem}
  .panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;
         padding:1.25rem;margin-bottom:1rem}
  label{display:block;font-weight:600;font-size:.85rem;margin-bottom:.4rem}
  select{width:100%;padding:.6rem .7rem;border:1px solid var(--line);border-radius:8px;
         background:var(--panel);color:var(--ink);font:inherit}
  .hint{color:var(--muted);font-size:.8rem;margin-top:.45rem}
  .status{display:flex;gap:.6rem;align-items:flex-start;padding:.8rem .9rem;border-radius:8px;
          font-size:.88rem;margin-top:.9rem}
  .status.ok{background:var(--ok-bg);color:var(--ok)} .status.warn{background:var(--warn-bg);color:var(--warn)}
  .status.bad{background:var(--bad-bg);color:var(--bad)} .status.idle{background:transparent;color:var(--muted);padding-left:0}
  .row{display:flex;justify-content:space-between;gap:1rem;padding:.45rem 0;border-bottom:1px solid var(--line);font-size:.88rem}
  .row:last-child{border-bottom:0} .row span:first-child{color:var(--muted)}
  .row span:last-child{font-family:var(--mono);text-align:right;word-break:break-all}
  button{width:100%;padding:.75rem;border:0;border-radius:8px;background:var(--accent);
         color:var(--accent-ink);font:600 15px/1 system-ui;cursor:pointer}
  button:disabled{opacity:.55;cursor:not-allowed}
  button.ghost{background:transparent;color:var(--accent);border:1px solid var(--line);margin-top:.5rem}
  .log{font-family:var(--mono);font-size:.8rem;background:var(--bg);border:1px solid var(--line);
       border-radius:8px;padding:.8rem;margin-top:.9rem;max-height:230px;overflow:auto;white-space:pre-wrap}
  .log:empty{display:none}
  .warnbox{font-size:.82rem;color:var(--muted);border-left:2px solid var(--line);padding-left:.7rem;margin-top:1rem}
</style></head><body><div class="wrap">

<header><h1>Instagram Token</h1><span class="badge">Tejdux internal</span></header>
<p class="sub">Mints a non-expiring Page token and writes it to Secrets Manager.</p>

<div class="panel">
  <label for="env">Environment</label>
  <select id="env"></select>
  <p class="hint" id="envhint"></p>
  <div class="status idle" id="status">Checking what is stored…</div>
  <div id="detail"></div>
</div>

<div class="panel">
  <button id="mint">Mint new token</button>
  <button class="ghost" id="refresh">Re-check status</button>
  <div class="log" id="log"></div>
  <div class="warnbox">
    Opens Meta's consent dialog in a new tab. Approve as the user who administers the Page —
    Meta issues no token without a human approving, which is the one step that cannot be automated.
  </div>
</div>

<script>
const $ = id => document.getElementById(id)
let ENVS = {}

async function loadEnvs(){
  ENVS = await (await fetch('/api/environments')).json()
  $('env').innerHTML = Object.entries(ENVS)
    .map(([k,v]) => `<option value="${k}">${v.label}</option>`).join('')
  onEnvChange()
}

function onEnvChange(){
  const env = ENVS[$('env').value]
  $('envhint').textContent = `Writes ${env.prefix}/instagram-access-token and …-business-account-id`
  checkStatus()
}

async function checkStatus(){
  const box = $('status'), detail = $('detail')
  box.className = 'status idle'; box.textContent = 'Checking what is stored…'; detail.innerHTML = ''
  try{
    const s = await (await fetch('/api/status?env=' + $('env').value)).json()
    if(s.state === 'valid'){
      const forever = s.expires === 'never'
      box.className = 'status ' + (forever ? 'ok' : 'warn')
      box.textContent = forever
        ? 'Stored token is valid and does not expire.'
        : `Stored token is valid but EXPIRES ${s.expires}. A Page token from a long-lived user token should never expire.`
    } else if(s.state === 'invalid'){
      box.className = 'status bad'
      box.textContent = 'Stored token is rejected by Meta: ' + (s.detail || 'invalid')
    } else {
      box.className = 'status warn'; box.textContent = 'No token stored yet.'
    }
    detail.innerHTML = [
      s.account_id ? row('Instagram account', s.account_id) : '',
      s.type ? row('Token type', s.type) : '',
      s.expires ? row('Expires', s.expires) : '',
    ].join('')
  }catch(e){
    box.className = 'status bad'; box.textContent = 'Could not read status: ' + e.message
  }
}

const row = (k,v) => `<div class="row"><span>${k}</span><span>${v}</span></div>`

function log(line){
  const el = $('log')
  el.textContent += line + '\\n'
  el.scrollTop = el.scrollHeight
}

$('mint').onclick = async () => {
  $('mint').disabled = true; $('log').textContent = ''
  log('Opening Meta consent dialog — approve in the new tab…')
  try{
    const res = await fetch('/api/mint?env=' + $('env').value, {method:'POST'})
    const out = await res.json()
    ;(out.log || []).forEach(log)
    if(out.ok){
      log('')
      log('Stored. ' + out.username + ' (' + out.account_id + ')')
      if(out.followers === 0) log('NOTE: 0 followers/posts — the product will render an empty profile.')
    } else {
      log('FAILED: ' + out.error)
      if(out.hint) log('        ' + out.hint)
    }
  }catch(e){ log('FAILED: ' + e.message) }
  $('mint').disabled = false
  checkStatus()
}

$('refresh').onclick = checkStatus
$('env').onchange = onEnvChange
loadEnvs()
</script></div></body></html>"""


def serve(port, tool):
    """Serve the console on localhost only, delegating all real work to the tool module."""

    class Handler(http.server.BaseHTTPRequestHandler):

        def _json(self, payload, status=200):
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _env(self):
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            name = query.get("env", [tool.DEFAULT_ENVIRONMENT])[0]
            if name not in tool.ENVIRONMENTS:
                raise ValueError(f"unknown environment {name}")
            return name

        def do_GET(self):
            route = urllib.parse.urlparse(self.path).path
            if route == "/":
                body = PAGE.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif route == "/api/environments":
                self._json({k: {"label": v["label"], "prefix": v["prefix"]}
                            for k, v in tool.ENVIRONMENTS.items()})
            elif route == "/api/status":
                try:
                    self._json(tool.current_status(self._env()))
                except Exception as error:
                    self._json({"state": "invalid", "detail": str(error)}, 200)
            else:
                self.send_response(404)
                self.end_headers()

        def do_POST(self):
            if urllib.parse.urlparse(self.path).path != "/api/mint":
                self.send_response(404)
                self.end_headers()
                return

            lines = []
            try:
                environment = self._env()
                config = tool.ENVIRONMENTS[environment]
                token_name, account_name, _ = tool.secret_names(environment)

                app_secret = tool.load_app_secret(environment)
                code = tool.await_consent(config["app_id"])
                lines.append("approved")

                result = tool.exchange_for_page_token(
                    code, config["app_id"], app_secret, lines.append)

                if result["expires_at"]:
                    lines.append("WARNING: this token has an expiry; expected 'never'.")

                ok = tool.write_secret(token_name, result["token"],
                                       config["region"], config["profile"], False)
                ok &= tool.write_secret(account_name, result["account_id"],
                                        config["region"], config["profile"], False)
                if not ok:
                    raise tool.TokenError("one or more secrets did not store")

                self._json({"ok": True, "log": lines, "username": result["username"],
                            "account_id": result["account_id"], "followers": result["followers"]})
            except tool.TokenError as error:
                self._json({"ok": False, "log": lines,
                            "error": str(error), "hint": error.hint})
            except Exception as error:
                self._json({"ok": False, "log": lines, "error": str(error)})

        def log_message(self, *args):
            pass  # the console narrates itself; the HTTP log is noise

    # 127.0.0.1, never 0.0.0.0. This process can read the app secret and write production
    # credentials; binding it to a routable interface would publish that to the network.
    server = http.server.HTTPServer(("127.0.0.1", port), Handler)
    url = f"http://127.0.0.1:{port}/"
    print(f"\n  Instagram token admin — {url}")
    print("  Ctrl-C to stop.\n")
    threading.Timer(0.4, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n  Stopped.\n")
        server.server_close()
