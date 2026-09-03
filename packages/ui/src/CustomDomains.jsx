import { useCallback, useEffect, useState } from 'react'

/**
 * Connect a brand's own domain to its landing pages (roadmap PR-60).
 *
 * <p><b>Why this is a UI-only change.</b> `BrandDomainService`, `BrandDomainController`,
 * `DnsDomainRegistrar` and the `brand_domains` schema have existed and been tested since `PR-23` —
 * grepping both UI trees for `brand-domains` returned nothing, so a brand could not reach any of
 * it. The backend was done and there was no door.
 *
 * <p><b>The DNS records are the whole screen.</b> Connecting a domain is not a form submission, it
 * is a two-record change a brand makes at a registrar this product cannot reach — so what matters
 * is that the records are copyable, exact, and visible at the moment they are needed rather than in
 * a doc. The server generates them rather than storing them, so they cannot drift from what
 * verification actually checks.
 *
 * <p><b>Two honest limits, stated on screen rather than discovered.</b> SSL issuance is an explicit
 * stub deferred to ACME (`DnsDomainRegistrar` says so in its own comment), and the public serving
 * path does not consult `brand_domains` yet — so a verified domain today proves ownership and does
 * not yet serve pages. Letting a brand point their DNS at us and then find nothing loads would be
 * the worse outcome, so the state that means "verified but not serving" says exactly that.
 */
export default function CustomDomains({ onLoad, onConnect, onVerify, onDisconnect }) {
  const [domains, setDomains] = useState([])
  const [name, setName] = useState('')
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [copied, setCopied] = useState('')

  const refresh = useCallback(async () => {
    if (typeof onLoad !== 'function') return
    try {
      setDomains((await onLoad()) || [])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load your domains.')
    }
  }, [onLoad])

  useEffect(() => { refresh() }, [refresh])

  const run = async (key, action) => {
    setBusy(key)
    setError('')
    try {
      await action()
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'That did not work.')
    } finally {
      setBusy('')
    }
  }

  const copy = async (id, text) => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(id)
      setTimeout(() => setCopied((prev) => (prev === id ? '' : prev)), 1500)
    } catch {
      setError('Clipboard unavailable here — select the record and copy it manually.')
    }
  }

  return (
    <section className="domains" aria-labelledby="domains-heading">
      <h3 id="domains-heading">Your own domain</h3>
      <p className="domains__sub">
        Serve landing pages from a domain you own, so a creator&rsquo;s link carries your name
        rather than ours.
      </p>

      {error ? <p className="row-save-feedback error" role="alert">{error}</p> : null}

      <form
        className="domains__add"
        onSubmit={(event) => {
          event.preventDefault()
          if (!name.trim()) return
          run('connect', async () => {
            await onConnect(name.trim())
            setName('')
          })
        }}
      >
        <label className="auth-label" htmlFor="domains-name">Domain</label>
        <input
          id="domains-name"
          value={name}
          placeholder="pages.yourbrand.com"
          onChange={(event) => setName(event.target.value)}
        />
        {/* A subdomain, suggested rather than enforced: the server accepts an apex too, but a brand
            pointing their ROOT domain at us takes their marketing site down, and the placeholder is
            the cheapest place to steer that. */}
        <p className="helper">A subdomain is usual — your main site keeps working.</p>
        <button type="submit" className="primary-btn" disabled={busy === 'connect' || !name.trim()}>
          {busy === 'connect' ? 'Adding…' : 'Add domain'}
        </button>
      </form>

      {domains.length === 0 ? (
        <p className="custom-attributes-empty">
          No domain connected. Pages are served from our domain until you add one — which works
          fine, and is one fewer thing to set up.
        </p>
      ) : (
        <ul className="domains__list">
          {domains.map((domain) => {
            const dns = domain.dnsInstructions || {}
            const verified = String(domain.dnsStatus || '').toLowerCase() === 'active'
            return (
              <li key={domain.id} className="domains__item">
                <div className="domains__head">
                  <strong>{domain.domainName}</strong>
                  <span className={`badge badge-${verified ? 'success' : 'muted'}`}>
                    {verified ? 'Ownership verified' : (domain.dnsStatus || 'pending')}
                  </span>
                </div>

                {verified ? (
                  /* Said plainly, because the alternative is a brand pointing DNS at us and finding
                     nothing loads. Ownership and serving are two different things today. */
                  <p className="helper">
                    Ownership is confirmed. Serving pages from this domain is not switched on yet —
                    your pages keep working on our domain in the meantime.
                  </p>
                ) : (
                  <>
                    <p className="helper">
                      Add these two records at your DNS provider, then verify. It can take a few
                      minutes to propagate.
                    </p>
                    <dl className="domains__records">
                      <dt>Verification (TXT)</dt>
                      <dd>
                        <code>{dns.verificationRecord}</code>
                        <button
                          type="button"
                          className="linkish-btn"
                          onClick={() => copy(`${domain.id}-txt`, dns.verificationRecord || '')}
                        >
                          {copied === `${domain.id}-txt` ? 'Copied' : 'Copy'}
                        </button>
                      </dd>
                      <dt>Hosting (CNAME)</dt>
                      <dd>
                        <code>{dns.aliasRecord}</code>
                        <button
                          type="button"
                          className="linkish-btn"
                          onClick={() => copy(`${domain.id}-cname`, dns.aliasRecord || '')}
                        >
                          {copied === `${domain.id}-cname` ? 'Copied' : 'Copy'}
                        </button>
                      </dd>
                    </dl>
                    {dns.note ? <p className="helper">{dns.note}</p> : null}
                  </>
                )}

                <div className="row-actions">
                  {!verified ? (
                    <button
                      type="button"
                      className="ghost-btn"
                      disabled={busy === domain.id}
                      onClick={() => run(domain.id, () => onVerify(domain.id))}
                    >
                      {busy === domain.id ? 'Checking…' : 'Verify'}
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="ghost-btn"
                    disabled={busy === domain.id}
                    onClick={() => run(domain.id, () => onDisconnect(domain.id))}
                  >
                    Remove
                  </button>
                </div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
