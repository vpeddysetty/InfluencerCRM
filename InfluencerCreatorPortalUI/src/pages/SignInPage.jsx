import { useState } from 'react'
import { login } from '../api/client'

/**
 * Sign in for a creator who already accepted an invitation (roadmap PR-43).
 *
 * There is deliberately NO "create an account" link. A creator account exists only because a brand
 * invited one — that is what makes the confirmed link meaningful, and a self-serve signup here
 * would create identities with no brand relationship, which can see nothing and only generate
 * support questions.
 */
export default function SignInPage({ onSignedIn }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(event) {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      onSignedIn(await login({ email: email.trim(), password }))
    } catch (e) {
      // One message for unknown email and wrong password alike — distinguishing them tells
      // somebody which addresses are registered, and the server already answers identically.
      setError('That email and password did not match.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="cp-shell">
      <h1>Sign in</h1>
      <p className="cp-lede">Pick up where you left off on the pages brands have shared with you.</p>
      <form className="cp-form" onSubmit={submit}>
        <label htmlFor="cp-email">Email</label>
        <input
          id="cp-email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          autoComplete="username"
          required
        />
        <label htmlFor="cp-pw">Password</label>
        <input
          id="cp-pw"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
        />
        {error ? <p className="cp-error" role="alert">{error}</p> : null}
        <button type="submit" className="cp-btn cp-btn--primary" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="cp-note">
        Accounts are created from a brand&rsquo;s invitation. If you have not had one, ask the
        brand you are working with to invite you.
      </p>
    </main>
  )
}
