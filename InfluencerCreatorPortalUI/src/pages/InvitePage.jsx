import { useEffect, useState } from 'react'
import { previewInvite, redeemInvite, signup } from '../api/client'

/**
 * The invitation screen — the entire cold start (roadmap PR-43).
 *
 * **No login wall.** A creator arriving here has no account, which is the whole reason they were
 * sent a link. Asking them to sign in first would be asking them to do something impossible.
 *
 * **They see why before they are asked for anything.** Brand, campaign and one line first;
 * password and consent below the fold, after the reason. The order is the design: a form that
 * opens with "choose a password" from an unknown sender is one people close.
 *
 * **The teaser is redacted, and that is enforced server-side.** This screen shows only what the
 * preview endpoint returns — never the page. Email scanners, Slack and WhatsApp unfurlers and
 * link prewarmers all fetch GETs automatically, so a screen that rendered the page would leak an
 * unreleased campaign to whoever the recipient happened to paste the link in front of.
 */
export default function InvitePage({ token, onSignedIn }) {
  const [state, setState] = useState({ status: 'loading' })
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [accepted, setAccepted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    if (!token) {
      setState({ status: 'missing' })
      return undefined
    }
    previewInvite(token)
      .then((invite) => { if (!cancelled) setState({ status: 'ready', invite }) })
      .catch(() => { if (!cancelled) setState({ status: 'unknown' }) })
    return () => { cancelled = true }
  }, [token])

  async function accept(event) {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      // Redeeming creates the identity and the CONFIRMED link together, server-side, in one
      // transaction. Doing it in two calls from here would let a creator end up with an account
      // and no relationship — an account that signs in and sees nothing.
      await redeemInvite({ token, displayName: displayName.trim() })
      // Then sign in, so they land on their pages rather than on a login form having just proved
      // who they are.
      const session = await signup({
        email: state.invite.email,
        password,
        displayName: displayName.trim(),
        acceptedTerms: accepted,
      })
      onSignedIn(session)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'That did not work. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  if (state.status === 'loading') {
    return <main className="cp-shell"><p>Checking your invitation…</p></main>
  }

  if (state.status === 'missing' || state.status === 'unknown') {
    return (
      <main className="cp-shell">
        <h1>We could not find that invitation</h1>
        <p>
          The link may have been mistyped, or it may have already been used. Ask the brand that
          invited you to send a new one.
        </p>
      </main>
    )
  }

  const { invite } = state

  // Expired is deliberately NOT a 404. A dead end that looks like a broken link sends people to
  // support; one that explains itself sends them back to the brand, which is where the fix is.
  if (invite.status === 'expired') {
    return (
      <main className="cp-shell">
        <h1>This invitation has expired</h1>
        <p>Invitations last seven days. Ask the brand to send you a new one.</p>
      </main>
    )
  }

  if (invite.status === 'accepted') {
    return (
      <main className="cp-shell">
        <h1>You have already accepted this</h1>
        <p>Sign in to see the pages you have been asked to work on.</p>
      </main>
    )
  }

  if (invite.status === 'revoked') {
    return (
      <main className="cp-shell">
        <h1>This invitation was withdrawn</h1>
        <p>The brand cancelled it. If you think that is a mistake, get in touch with them.</p>
      </main>
    )
  }

  return (
    <main className="cp-shell">
      {/* The reason, first and alone. */}
      <p className="cp-eyebrow">You have been invited</p>
      <h1>Work on a campaign page together</h1>
      <p className="cp-lede">
        A brand on Tejdux has asked you to help write
        {invite.hasPage ? ' a campaign page' : ' campaign pages'} with them. You will be able to
        edit the words and pictures. Publishing stays with the brand.
      </p>

      {/* Only now the form. */}
      <form className="cp-form" onSubmit={accept}>
        <label htmlFor="cp-name">What should we call you?</label>
        <input
          id="cp-name"
          type="text"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder="Your name or handle"
          autoComplete="name"
        />

        <label htmlFor="cp-password">Choose a password</label>
        <input
          id="cp-password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="new-password"
          required
          minLength={8}
        />

        <label className="cp-check">
          <input
            type="checkbox"
            checked={accepted}
            onChange={(e) => setAccepted(e.target.checked)}
            required
          />
          <span>
            I agree to the <a href="https://tejdux.com/terms/" target="_blank" rel="noreferrer">terms</a>
            {' '}and the <a href="https://tejdux.com/privacy/" target="_blank" rel="noreferrer">privacy policy</a>.
          </span>
        </label>

        {error ? <p className="cp-error" role="alert">{error}</p> : null}

        <button type="submit" className="cp-btn cp-btn--primary" disabled={busy || !accepted}>
          {busy ? 'Setting up…' : 'Accept and get started'}
        </button>
      </form>
    </main>
  )
}
