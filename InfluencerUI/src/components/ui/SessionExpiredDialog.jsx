import { useEffect, useRef } from 'react'

/**
 * Offers to restore a session that could not be renewed silently.
 *
 * <p>The app refreshes the access token ahead of expiry, so this is the failure path: the refresh
 * token itself was rejected — revoked, rotated by another tab, or the server restarted with a new
 * signing key. Previously that path called {@code onSessionExpired}, which cleared state and threw
 * the user back to the login screen mid-task, taking any unsaved drawer with it.
 *
 * <p><b>Not a ConfirmDialog.</b> That component dismisses on Escape and on an overlay click, which
 * is right for "are you sure you want to delete this" and wrong here: dismissing does not give the
 * session back, it just hides the only control that can recover it and leaves a workspace whose
 * every request 401s. This dialog is deliberately inescapable — the two buttons are the only exits.
 *
 * <p>Signing in again is a full navigation, so unsaved work is lost either way. What this preserves
 * is the <em>choice</em>: a user mid-edit can retry (a second attempt often succeeds, e.g. another
 * tab rotated the token) or copy their work out before signing out.
 */
function SessionExpiredDialog({ onRetry, onSignOut, busy = false, error = '' }) {
  const panelRef = useRef(null)
  const retryRef = useRef(null)

  useEffect(() => {
    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    // Focus the recovery action, not the destructive one. The inverse of ConfirmDialog: there the
    // reflexive Enter must not delete anything, here it should get the user back to work.
    retryRef.current?.focus()

    const onKeyDown = (event) => {
      // Escape is swallowed on purpose. See the note above — there is no "cancel" for an expired
      // session, and letting Escape close this would strand the user in a dead workspace.
      if (event.key === 'Escape') {
        event.preventDefault()
        return
      }

      if (event.key !== 'Tab') {
        return
      }

      const focusable = Array.from(panelRef.current?.querySelectorAll('button:not([disabled])') || [])
      if (focusable.length < 2) {
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.body.style.overflow = originalOverflow
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [])

  return (
    // No onClick on the overlay: clicking outside must not dismiss this.
    <div className="confirm-overlay" role="presentation">
      <div
        className="confirm-panel"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="session-expired-title"
        aria-describedby="session-expired-body"
        ref={panelRef}
      >
        <h2 className="confirm-title" id="session-expired-title">Your session timed out</h2>
        <p className="confirm-consequence" id="session-expired-body">
          You have been inactive for a while. Continue where you left off, or sign out.
          Anything you have not saved will still be here if you continue.
        </p>
        {error ? <p className="field-error" role="alert">{error}</p> : null}
        <div className="confirm-actions">
          <button type="button" className="ghost-btn" onClick={onSignOut} disabled={busy}>
            Sign out
          </button>
          <button
            type="button"
            className="primary-btn"
            onClick={onRetry}
            disabled={busy}
            ref={retryRef}
          >
            {busy ? 'Reconnecting…' : 'Continue working'}
          </button>
        </div>
      </div>
    </div>
  )
}

export default SessionExpiredDialog
