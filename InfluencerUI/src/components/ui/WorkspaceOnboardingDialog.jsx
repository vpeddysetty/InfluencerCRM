import { useEffect, useRef, useState } from 'react'

/**
 * Asks a social sign-up what their workspace is called, and whether it is an agency.
 *
 * <p><b>Why this exists at all.</b> The email form collects a workspace name and type before the
 * account is created. The social path cannot: the user clicks "Continue with Facebook" and the very
 * next thing that happens is a redirect to Meta, so there is no moment in between at which to ask.
 * The account is therefore provisioned named after the provider's display name — signing up as
 * "Ari Rivera" produced a workspace called <em>Ari Rivera</em> — and always as a solo brand, which
 * is why the landing page used to refuse an agency selection outright and tell the user to go back
 * and use a password instead. This dialog is where both are put right.
 *
 * <p><b>Not a ConfirmDialog, and deliberately dismissible anyway.</b> Escape and the skip button
 * both close it. The workspace already exists and works under its provider-derived name; the only
 * cost of skipping is a name the user can change later in settings. Trapping someone in a form to
 * fix cosmetics would be a worse first thirty seconds than a slightly odd workspace name.
 *
 * <p>The name field is pre-filled with the current workspace name rather than left blank: for a
 * solo brand whose provider name IS their business name, the whole step becomes one Enter press.
 */
function WorkspaceOnboardingDialog({
  initialName = '',
  initialAccountType = 'brand',
  onSubmit,
  onSkip,
}) {
  const [workspaceName, setWorkspaceName] = useState(initialName)
  const [accountType, setAccountType] = useState(
    initialAccountType === 'agency' ? 'agency' : 'brand',
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const panelRef = useRef(null)
  const nameRef = useRef(null)

  useEffect(() => {
    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    nameRef.current?.focus()
    nameRef.current?.select()

    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !busy) {
        event.preventDefault()
        onSkip?.()
        return
      }

      if (event.key !== 'Tab') {
        return
      }

      const focusable = Array.from(
        panelRef.current?.querySelectorAll(
          'button:not([disabled]), input:not([disabled])',
        ) || [],
      )
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
  }, [busy, onSkip])

  const handleSubmit = async (event) => {
    event.preventDefault()
    const trimmed = workspaceName.trim()
    if (!trimmed) {
      setError('Enter a name for your workspace.')
      return
    }

    setBusy(true)
    setError('')
    try {
      await onSubmit({ workspaceName: trimmed, accountType })
    } catch (submitError) {
      setBusy(false)
      setError(
        submitError instanceof Error
          ? submitError.message
          : 'Could not save your workspace details.',
      )
    }
  }

  return (
    <div className="confirm-overlay" role="presentation">
      <div
        className="confirm-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="onboarding-title"
        aria-describedby="onboarding-body"
        ref={panelRef}
      >
        <h2 className="confirm-title" id="onboarding-title">
          Tell us about your workspace
        </h2>
        <p className="confirm-consequence" id="onboarding-body">
          You are signed in. Name the workspace after your brand or agency so your team recognises
          it — we started it off with your account name.
        </p>

        <form className="auth-form" onSubmit={handleSubmit}>
          <fieldset className="auth-accounttype">
            <legend className="auth-label">Workspace type</legend>
            <div className="auth-accounttype-options">
              <label
                className={`auth-accounttype-option${accountType === 'brand' ? ' selected' : ''}`}
              >
                <input
                  type="radio"
                  name="onboardingAccountType"
                  value="brand"
                  checked={accountType === 'brand'}
                  onChange={() => setAccountType('brand')}
                  disabled={busy}
                />
                <span className="auth-accounttype-title">Brand</span>
                <span className="auth-accounttype-hint">One brand you run yourself.</span>
              </label>
              <label
                className={`auth-accounttype-option${accountType === 'agency' ? ' selected' : ''}`}
              >
                <input
                  type="radio"
                  name="onboardingAccountType"
                  value="agency"
                  checked={accountType === 'agency'}
                  onChange={() => setAccountType('agency')}
                  disabled={busy}
                />
                <span className="auth-accounttype-title">Agency</span>
                <span className="auth-accounttype-hint">
                  Several client brands, switched between in one login.
                </span>
              </label>
            </div>
          </fieldset>

          <label>
            <span className="auth-label">
              {accountType === 'agency' ? 'Agency name' : 'Brand or startup'}
            </span>
            <div className="auth-input-wrap">
              <span className="auth-input-icon" aria-hidden="true">#</span>
              <input
                ref={nameRef}
                name="workspaceName"
                type="text"
                value={workspaceName}
                onChange={(event) => setWorkspaceName(event.target.value)}
                placeholder={accountType === 'agency' ? 'Northstar Agency' : 'Your brand name'}
                disabled={busy}
                required
              />
            </div>
          </label>

          {error ? <p className="field-error" role="alert">{error}</p> : null}

          <div className="confirm-actions">
            <button type="button" className="ghost-btn" onClick={onSkip} disabled={busy}>
              Skip for now
            </button>
            <button type="submit" className="primary-btn" disabled={busy}>
              {busy ? 'Saving…' : 'Continue'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default WorkspaceOnboardingDialog
