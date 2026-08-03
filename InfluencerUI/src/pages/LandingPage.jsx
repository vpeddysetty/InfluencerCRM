import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MdsNote } from '../components/Mds'

function LandingPage({ isSignUp, setIsSignUp, onAuthSubmit, onSocialLogin, authError = '' }) {
  const navigate = useNavigate()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [socialProvider, setSocialProvider] = useState('')
  // 'brand' | 'agency'. Creators are CRM records owned by a brand, not accounts that sign in,
  // so they are deliberately not an option here — see docs/identity-signup-alignment.md.
  const [accountType, setAccountType] = useState('brand')
  const submitTimerRef = useRef(null)

  useEffect(() => {
    return () => {
      if (submitTimerRef.current) {
        window.clearTimeout(submitTimerRef.current)
      }
    }
  }, [])

  const handleSubmit = async (event) => {
    event.preventDefault()
    if (isSubmitting) {
      return
    }

    setIsSubmitting(true)
      try {
        await onAuthSubmit(event)
        submitTimerRef.current = window.setTimeout(() => {
          navigate('/import')
        }, 340)
      } catch {
        setIsSubmitting(false)
      }
  }

  const handleSocialLogin = (provider) => {
    if (socialProvider || !onSocialLogin) {
      return
    }
    // On sign-up, forward the brand field so a new social account gets a workspace name.
    const brandInput = document.querySelector('input[name="brand"]')
    const brandName = isSignUp ? String(brandInput?.value || '').trim() : ''

    // Navigates away to the DPS, which completes the flow and returns the user already signed in.
    // There is no result to await and no route to push: the redirect replaces this page. The
    // spinner stays on deliberately — it is the last thing rendered before the browser leaves.
    setSocialProvider(provider)
    onSocialLogin(provider, { brandName, accountType: isSignUp ? accountType : '' })
  }

  return (
    <main className="app-shell">
      <section className="hero-panel landing-hero-panel">
        <p className="landing-badge landing-reveal delay-1">Tejdux Influencer CRM</p>
        <h1 className="landing-title landing-reveal delay-2">Turn creator ops into a calm, high-velocity system.</h1>
        <p className="lead landing-lead landing-reveal delay-3">
          A sleek operating layer for startup teams to import lists, launch campaigns, and move every
          creator relationship from outreach to payout without spreadsheet drag.
        </p>

        <div className="landing-stat-grid landing-reveal delay-4" aria-label="Product highlights">
          <article className="landing-stat-card">
            <p className="landing-stat-value">5x</p>
            <p className="landing-stat-label">Faster campaign setup</p>
          </article>
          <article className="landing-stat-card">
            <p className="landing-stat-value">1</p>
            <p className="landing-stat-label">Unified creator workflow board</p>
          </article>
          <article className="landing-stat-card">
            <p className="landing-stat-value">0</p>
            <p className="landing-stat-label">Manual status chaos</p>
          </article>
        </div>

        <div className="hero-points landing-pill-row landing-reveal delay-5">
          <span>CSV, XLS, XLSX import preview</span>
          <span>Campaign and creator control center</span>
          <span>Outreach to paid Kanban flow</span>
        </div>
      </section>

      <section className="auth-panel landing-auth-panel landing-reveal delay-3">
        <div className="landing-auth-header landing-reveal delay-4">
          <p className="eyebrow">Workspace Access</p>
          <h2>{isSignUp ? 'Create your operator workspace' : 'Welcome back to tejdux.io'}</h2>
          <p className="helper">
            {isSignUp
              ? accountType === 'agency'
                ? 'Set up an agency workspace. You can add client brands once you are in.'
                : 'Set up your workspace profile details.'
              : 'Log in with your user name or email and password.'}
          </p>
        </div>

        <div className="landing-auth-shell landing-reveal delay-5">
          <div className="auth-switch" role="tablist" aria-label="Auth view switch">
            <button
              type="button"
              className={isSignUp ? 'active' : ''}
              onClick={() => setIsSignUp(true)}
            >
              Sign up
            </button>
            <button
              type="button"
              className={!isSignUp ? 'active' : ''}
              onClick={() => setIsSignUp(false)}
            >
              Log in
            </button>
          </div>

          <form className="auth-form" onSubmit={handleSubmit}>
            {isSignUp ? (
              <label>
                <span className="auth-label">Full name</span>
                <div className="auth-input-wrap">
                  <span className="auth-input-icon" aria-hidden="true">Aa</span>
                  <input name="fullName" type="text" placeholder="Ari Rivera" required />
                </div>
              </label>
            ) : null}
            {isSignUp ? (
              <fieldset className="auth-accounttype">
                <legend className="auth-label">Workspace type</legend>
                <div className="auth-accounttype-options">
                  <label
                    className={`auth-accounttype-option${accountType === 'brand' ? ' selected' : ''}`}
                  >
                    <input
                      type="radio"
                      name="accountType"
                      value="brand"
                      checked={accountType === 'brand'}
                      onChange={() => setAccountType('brand')}
                    />
                    <span className="auth-accounttype-title">Brand</span>
                    <span className="auth-accounttype-hint">
                      One brand you run yourself.
                    </span>
                  </label>
                  <label
                    className={`auth-accounttype-option${accountType === 'agency' ? ' selected' : ''}`}
                  >
                    <input
                      type="radio"
                      name="accountType"
                      value="agency"
                      checked={accountType === 'agency'}
                      onChange={() => setAccountType('agency')}
                    />
                    <span className="auth-accounttype-title">Agency</span>
                    <span className="auth-accounttype-hint">
                      Several client brands, switched between in one login.
                    </span>
                  </label>
                </div>
              </fieldset>
            ) : null}
            {isSignUp ? (
              <label>
                {/* An agency's first brand is its own name; client brands are added afterwards. */}
                <span className="auth-label">
                  {accountType === 'agency' ? 'Agency name' : 'Brand or startup'}
                </span>
                <div className="auth-input-wrap">
                  <span className="auth-input-icon" aria-hidden="true">#</span>
                  <input
                    name="brand"
                    type="text"
                    placeholder={accountType === 'agency' ? 'Northstar Agency' : 'tejdux.io'}
                    defaultValue="tejdux.io"
                    required
                  />
                </div>
              </label>
            ) : null}
            <label>
              <span className="auth-label">{isSignUp ? 'Email' : 'User name'}</span>
              <div className="auth-input-wrap">
                <span className="auth-input-icon" aria-hidden="true">@</span>
                <input
                  name="email"
                  type={isSignUp ? 'email' : 'text'}
                  placeholder={isSignUp ? 'owner@tejdux.io' : 'your registered email'}
                  required
                />
              </div>
            </label>
            <label>
              <span className="auth-label">Password</span>
              <div className="auth-input-wrap">
                <span className="auth-input-icon" aria-hidden="true">**</span>
                <input name="password" type="password" placeholder="Enter your password" required />
              </div>
            </label>

            {!isSignUp ? (
              <div className="auth-inline-row">
                <label className="auth-checkbox">
                  <input type="checkbox" name="remember" />
                  <span>Remember me</span>
                </label>
                <button type="button" className="auth-link-btn">Forgot password?</button>
              </div>
            ) : null}

            <button
              type="submit"
              className={`primary-btn landing-cta-btn${isSubmitting ? ' submitting' : ''}`}
              disabled={isSubmitting}
              aria-busy={isSubmitting}
            >
              <span className="cta-label">
                {isSubmitting ? 'Opening workspace...' : isSignUp ? 'Create workspace' : 'Enter workspace'}
              </span>
              <span className="cta-check" aria-hidden="true">check</span>
              <span className="cta-shine" aria-hidden="true" />
            </button>
          </form>

          {authError ? <MdsNote className="auth-error-note">{authError}</MdsNote> : null}

          <div className="auth-divider" aria-hidden="true">
            <span>or continue with</span>
          </div>

          <div className="auth-alt-actions">
            <button
              type="button"
              className="ghost-btn auth-alt-btn"
              onClick={() => handleSocialLogin('google')}
              disabled={Boolean(socialProvider)}
              aria-busy={socialProvider === 'google'}
            >
              {socialProvider === 'google' ? 'Connecting…' : 'Google'}
            </button>
            {/* Facebook sign-in temporarily hidden pending Meta app review/config. */}
          </div>
        </div>

        <p className="landing-footnote">
          By continuing you agree to platform terms and data handling policies.
        </p>
      </section>
    </main>
  )
}

export default LandingPage
