import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

/**
 * Redeems a member invitation (roadmap M1.4).
 *
 * <p>The flow was previously broken at both ends: nothing sent the invitation, and
 * `acceptInvitation` existed in the API client with zero call sites, so even a token that reached
 * its recipient had no way to be redeemed through the product. This is the other half.
 *
 * <p><b>Reachable signed out.</b> That is the whole point — an invitee usually has no account
 * yet. The token travels in the URL, is held in component state, and the screen adapts to whether
 * the visitor is already signed in.
 */
function AcceptInvitationPage({ isLoggedIn, onAccept, onGoToSignIn }) {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()

  const token = searchParams.get('token') || ''
  const [status, setStatus] = useState(token ? 'ready' : 'missing')
  const [error, setError] = useState('')

  // Re-evaluate if the visitor signs in with the accept page still open — otherwise they return
  // to a screen still telling them to sign in.
  useEffect(() => {
    if (token && status === 'missing') {
      setStatus('ready')
    }
  }, [token, status])

  const accept = async () => {
    if (!token) {
      return
    }
    try {
      setStatus('accepting')
      setError('')
      await onAccept(token)
      setStatus('accepted')
      // Straight into the workspace they just joined. Leaving them on a success screen with a
      // link to click is one step more than the moment warrants.
      setTimeout(() => navigate('/', { replace: true }), 1200)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'This invitation could not be accepted.')
      setStatus('failed')
    }
  }

  if (status === 'missing') {
    return (
      <main className="auth-shell" id="workspace-main">
        <section className="auth-card">
          <h1>Invitation link incomplete</h1>
          <p>
            This link is missing its invitation token. Ask whoever invited you to send the full
            link — invitations cannot be looked up without it.
          </p>
        </section>
      </main>
    )
  }

  return (
    <main className="auth-shell" id="workspace-main">
      <section className="auth-card">
        <h1>Join the workspace</h1>

        {status === 'accepted' ? (
          <>
            <p className="row-save-feedback success">You're in. Taking you to the workspace…</p>
          </>
        ) : !isLoggedIn ? (
          <>
            {/* An invitation is addressed to a specific email, and the server rejects redemption
                by anyone else. Sign-in has to come first, and saying so plainly beats letting
                someone click Accept and receive a mismatch error they cannot interpret. */}
            <p>
              You've been invited to join a workspace on InfluenCRM. Sign in — or create an account
              with the email address the invitation was sent to — and this link will complete.
            </p>
            <p className="helper">
              The invitation only works for the address it was issued to.
            </p>
            <button type="button" className="primary-btn" onClick={onGoToSignIn}>
              Sign in to continue
            </button>
          </>
        ) : (
          <>
            <p>You've been invited to join a workspace on InfluenCRM.</p>
            {error ? <p className="field-error" role="alert">{error}</p> : null}
            <button
              type="button"
              className="primary-btn"
              onClick={accept}
              disabled={status === 'accepting'}
            >
              {status === 'accepting' ? 'Joining…' : 'Accept invitation'}
            </button>
            {status === 'failed' ? (
              <p className="helper">
                Invitations expire after 7 days and work only once. If this one has lapsed, ask for
                a new invitation.
              </p>
            ) : null}
          </>
        )}
      </section>
    </main>
  )
}

export default AcceptInvitationPage
