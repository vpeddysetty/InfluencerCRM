import { useCallback, useEffect, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'
import { ConfirmDialog } from '../components/ui'

const PROVIDERS = [
  { key: 'google', label: 'Google' },
  { key: 'facebook', label: 'Facebook' },
]

/**
 * Account settings. Currently one panel: the sign-in methods connected to this account.
 *
 * <p>This page exists because the product promised it and did not have it. A social sign-in whose
 * email matches an existing account is refused unless the provider states it verified that address —
 * Facebook never does — and the refusal tells the user to "sign in with your password, then link
 * facebook from account settings". There was no account settings screen, so the instruction pointed
 * at nothing and the only route through was to already have signed up with that provider.
 *
 * <p>The refusal itself is deliberate and stays: matching on an unverified email would mean anyone
 * who can present owner@brand.com to a lax provider receives a session for that brand's workspace.
 * Linking from a signed-in session is the safe version of the same convenience — being signed in is
 * what proves the account is yours.
 */
function SettingsPage({
  onLoadConnectedAccounts,
  onConnect,
  onDisconnect,
  linkedNotice = '',
  workspaceName = '',
  onRenameWorkspace,
}) {
  const [accounts, setAccounts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [pendingDisconnect, setPendingDisconnect] = useState(null)
  const [nameDraft, setNameDraft] = useState(workspaceName)
  const [renaming, setRenaming] = useState(false)
  const [renameError, setRenameError] = useState('')
  const [renamed, setRenamed] = useState(false)

  // The prop is the authority: a rename elsewhere, or the re-minted session that follows one,
  // should be reflected here rather than overwritten by a stale local draft.
  useEffect(() => {
    setNameDraft(workspaceName)
  }, [workspaceName])

  const submitRename = async (event) => {
    event.preventDefault()
    const next = nameDraft.trim()
    if (!next || next === workspaceName) {
      return
    }
    setRenaming(true)
    setRenameError('')
    setRenamed(false)
    try {
      await onRenameWorkspace(next)
      setRenamed(true)
    } catch (failure) {
      setRenameError(failure instanceof Error ? failure.message : 'Could not rename the workspace.')
    } finally {
      setRenaming(false)
    }
  }

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const rows = await onLoadConnectedAccounts()
      setAccounts(Array.isArray(rows) ? rows : [])
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Could not load connected accounts.')
    } finally {
      setLoading(false)
    }
  }, [onLoadConnectedAccounts])

  useEffect(() => {
    refresh()
  }, [refresh])

  const connectedByProvider = new Map(accounts.map((account) => [account.provider, account]))

  const confirmDisconnect = async () => {
    const target = pendingDisconnect
    setPendingDisconnect(null)
    if (!target) {
      return
    }
    try {
      await onDisconnect(target.id)
      await refresh()
    } catch (disconnectError) {
      setError(
        disconnectError instanceof Error ? disconnectError.message : 'Could not disconnect that account.',
      )
    }
  }

  return (
    <section className="page-section">
      <MdsKicker>Account</MdsKicker>
      <h1 className="page-title">Settings</h1>

      <MdsSectionRule />

      {/* The rename the rest of the product assumed existed. A social sign-up's workspace is
          provisioned from the provider's display name, and the onboarding dialog was the only
          place that could ever be corrected — so an account that got past it wore a personal name
          permanently. Same endpoint the dialog uses; being signed in is the authority. */}
      {onRenameWorkspace ? (
        <>
          <h2 className="section-title">Workspace name</h2>
          <p className="section-lead">
            The name shown in the sidebar and on everything this workspace owns. If you signed up
            with Google or Facebook, this started as your provider profile name.
          </p>

          {renamed ? <MdsNote>Workspace renamed.</MdsNote> : null}
          {renameError ? (
            <p className="field-error" role="alert">{renameError}</p>
          ) : null}

          <form className="settings-rename-form" onSubmit={submitRename}>
            <label className="visually-hidden" htmlFor="workspace-name">
              Workspace name
            </label>
            <input
              id="workspace-name"
              type="text"
              value={nameDraft}
              onChange={(event) => setNameDraft(event.target.value)}
              placeholder="Your brand name"
              disabled={renaming}
              required
            />
            <button
              type="submit"
              className="primary-btn"
              disabled={renaming || !nameDraft.trim() || nameDraft.trim() === workspaceName}
            >
              {renaming ? 'Saving…' : 'Save'}
            </button>
          </form>

          <MdsSectionRule />
        </>
      ) : null}

      <h2 className="section-title">Sign-in methods</h2>
      <p className="section-lead">
        Connect Google or Facebook to sign in without your password. Connecting does not change your
        password or share anything with the provider — we only read your name and email address.
      </p>

      {linkedNotice ? (
        <MdsNote>Connected {linkedNotice}. You can now use it to sign in.</MdsNote>
      ) : null}
      {error ? <MdsNote className="auth-error-note">{error}</MdsNote> : null}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : (
        <ul className="settings-provider-list">
          {PROVIDERS.map((provider) => {
            const connected = connectedByProvider.get(provider.key)
            return (
              <li key={provider.key} className="settings-provider-row">
                <div className="settings-provider-detail">
                  <span className="settings-provider-name">{provider.label}</span>
                  {connected ? (
                    <span className="settings-provider-meta">
                      Connected{connected.email ? ` as ${connected.email}` : ''}
                    </span>
                  ) : (
                    <span className="settings-provider-meta">Not connected</span>
                  )}
                </div>
                {connected ? (
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => setPendingDisconnect({ id: connected.id, label: provider.label })}
                  >
                    Disconnect
                  </button>
                ) : (
                  <button type="button" className="primary-btn" onClick={() => onConnect(provider.key)}>
                    Connect
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {pendingDisconnect ? (
        <ConfirmDialog
          title={`Disconnect ${pendingDisconnect.label}?`}
          /* Named consequence rather than a generic "are you sure": for someone who only ever
             signed in through this provider, disconnecting it removes the only way they have of
             getting back in. */
          consequence={`You will no longer be able to sign in with ${pendingDisconnect.label}. Your account and its data are unaffected, and you can reconnect at any time — but make sure you know your password first.`}
          confirmLabel="Disconnect"
          onConfirm={confirmDisconnect}
          onCancel={() => setPendingDisconnect(null)}
        />
      ) : null}
    </section>
  )
}

export default SettingsPage
