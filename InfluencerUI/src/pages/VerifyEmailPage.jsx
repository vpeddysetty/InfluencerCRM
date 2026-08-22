import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

/**
 * Redeems an email-verification link.
 *
 * <p>The other half of a flow that was live but unreachable: signup sent a real email whose link
 * pointed at `/verify-email`, and nothing in the SPA handled that path — the app loaded, the
 * catch-all swallowed the route, and the token was never presented to the API. Confirmed on
 * 2026-08-22 by clicking a real link and finding zero verify calls on the server.
 *
 * <p><b>Redeems on load rather than behind a button.</b> Clicking the link in the email IS the
 * confirmation; a second "yes I mean it" click adds a step and nothing else. This differs from
 * accepting an invitation, where the visitor is agreeing to join someone else's workspace and the
 * deliberate click is the point.
 *
 * <p><b>Reachable signed out</b> — the whole premise is that the account cannot be used yet.
 */
function VerifyEmailPage({ onVerify, onResend, onGoToSignIn }) {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()

  const token = searchParams.get('token') || ''
  const [status, setStatus] = useState(token ? 'verifying' : 'missing')
  const [error, setError] = useState('')
  const [resendState, setResendState] = useState('idle')
  const [email, setEmail] = useState('')

  // React 18 StrictMode mounts effects twice in development. The token is single-use, so a second
  // call would consume it and then report failure for the very click that just succeeded.
  const attempted = useRef(false)

  useEffect(() => {
    if (!token || attempted.current) {
      return
    }
    attempted.current = true

    let cancelled = false
    const run = async () => {
      try {
        await onVerify(token)
        if (cancelled) return
        setStatus('verified')
        // Long enough to read the confirmation, short enough not to feel stuck.
        setTimeout(() => navigate('/', { replace: true }), 2000)
      } catch (err) {
        if (cancelled) return
        setError(err instanceof Error ? err.message : 'This link could not be confirmed.')
        setStatus('failed')
      }
    }
    run()
    return () => {
      cancelled = true
    }
  }, [token, onVerify, navigate])

  const requestNewLink = async () => {
    if (!email.trim()) {
      return
    }
    try {
      setResendState('sending')
      await onResend(email.trim())
    } catch {
      // Deliberately ignored. The endpoint answers the same way for every address so it cannot be
      // used to discover which ones are registered, and surfacing a failure here would leak the
      // difference the API is careful to hide.
    }
    setResendState('sent')
  }

  if (status === 'missing') {
    return (
      <main className="auth-shell" id="workspace-main">
        <section className="auth-card">
          <h1>Confirmation link incomplete</h1>
          <p>
            This link is missing its token. Open the link from the email itself rather than typing
            the address — the token cannot be looked up without it.
          </p>
        </section>
      </main>
    )
  }

  return (
    <main className="auth-shell" id="workspace-main">
      <section className="auth-card">
        <h1>Confirming your email</h1>

        {status === 'verifying' && (
          <p aria-live="polite">Confirming your address…</p>
        )}

        {status === 'verified' && (
          <>
            <p className="row-save-feedback success" aria-live="polite">
              Your email is confirmed. Taking you to Tejdux…
            </p>
            <button type="button" className="btn-primary" onClick={() => navigate('/', { replace: true })}>
              Continue
            </button>
          </>
        )}

        {status === 'failed' && (
          <>
            <p className="row-save-feedback error" aria-live="polite">{error}</p>
            <p>
              Confirmation links work once and expire after 24 hours. Enter your email address and
              we will send a new one.
            </p>
            {resendState === 'sent' ? (
              <p className="row-save-feedback success" aria-live="polite">
                If that address needs confirming, a new link is on its way.
              </p>
            ) : (
              <>
                <label htmlFor="verify-resend-email">Email address</label>
                <input
                  id="verify-resend-email"
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="you@company.com"
                />
                <button
                  type="button"
                  className="btn-primary"
                  onClick={requestNewLink}
                  disabled={resendState === 'sending' || !email.trim()}
                >
                  {resendState === 'sending' ? 'Sending…' : 'Send a new link'}
                </button>
              </>
            )}
            <button type="button" className="btn-secondary" onClick={onGoToSignIn}>
              Back to sign in
            </button>
          </>
        )}
      </section>
    </main>
  )
}

export default VerifyEmailPage
