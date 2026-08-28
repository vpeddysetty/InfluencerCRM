import { useCallback, useEffect, useState } from 'react'
import EditPage from './pages/EditPage'
import InvitePage from './pages/InvitePage'
import MyPagesPage from './pages/MyPagesPage'
import SignInPage from './pages/SignInPage'
import { getToken, logout, me, setToken } from './api/client'

/**
 * The creator portal shell (roadmap PR-43).
 *
 * Deliberately tiny, and routed by hand rather than with react-router. There are three screens and
 * one of them is reached only from an emailed link; a router would be more configuration than
 * navigation. When the editor lands in PR-44 this is the place to reconsider that, not before.
 *
 * **The invite route takes precedence over any session.** A creator who is already signed in and
 * follows a second brand's invitation must land on the invitation, not on their page list —
 * otherwise accepting the second brand's invite is impossible without signing out first.
 */
export default function App() {
  const [session, setSession] = useState(null)
  const [checking, setChecking] = useState(true)
  const [editing, setEditing] = useState(null)

  const inviteToken = new URLSearchParams(window.location.search).get('token')
  const onInviteRoute = window.location.pathname.startsWith('/invite')

  useEffect(() => {
    let cancelled = false
    if (!getToken()) {
      setChecking(false)
      return undefined
    }
    // The session is re-verified against the server rather than trusted from storage. A token that
    // was revoked while the tab was closed must not produce a signed-in shell that then fails on
    // every request.
    me()
      .then((found) => { if (!cancelled) { setSession(found); setChecking(false) } })
      .catch(() => { if (!cancelled) { setToken(''); setChecking(false) } })
    return () => { cancelled = true }
  }, [])

  const signOut = useCallback(async () => {
    await logout()
    setSession(null)
  }, [])

  if (onInviteRoute && inviteToken) {
    return <InvitePage token={inviteToken} onSignedIn={setSession} />
  }

  if (checking) {
    return <main className="cp-shell"><p>One moment…</p></main>
  }

  if (!session) {
    return <SignInPage onSignedIn={setSession} />
  }

  // PR-44. Still no router, and now for a stronger reason than "there are only three screens":
  // the editor is a MODE of the page list, not a destination. It holds unsaved section edits, so a
  // real URL would invite a back-button that discards them silently. `editing` holds the whole
  // list entry rather than an id because the entry carries `rights` — the editor needs to know a
  // view-only creator before it renders, not after a save is refused.
  if (editing) {
    return (
      <EditPage
        entry={editing}
        onClose={() => setEditing(null)}
        onSignOut={signOut}
      />
    )
  }

  return <MyPagesPage onOpen={setEditing} onSignOut={signOut} />
}
