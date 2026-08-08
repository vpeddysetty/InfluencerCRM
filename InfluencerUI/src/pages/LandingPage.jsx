import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MdsNote } from '../components/Mds'
import { DEFAULT_ROUTE } from '../shell/routeManifest'
import { BILLING_LIVE, visiblePublicTiers } from '../shell/plan'

function LandingPage({ isSignUp, setIsSignUp, onAuthSubmit, onSocialLogin, authError = '' }) {
  const navigate = useNavigate()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [socialProvider, setSocialProvider] = useState('')
  // 'brand' | 'agency'. Creators are CRM records owned by a brand, not accounts that sign in,
  // so they are deliberately not an option here — see docs/identity-signup-alignment.md.
  const [accountType, setAccountType] = useState('brand')

  // Build-time, not state: the landing page is signed out and cannot ask the BFF which billing
  // provider is configured. Free-only until VITE_BILLING_LIVE=true.
  const billingLive = BILLING_LIVE
  const tiers = visiblePublicTiers(billingLive)

  const handleSubmit = async (event) => {
    event.preventDefault()
    if (isSubmitting) {
      return
    }

    setIsSubmitting(true)
    try {
      await onAuthSubmit(event)
      // Navigate as soon as the session is real. The CTA animation used to hold this back by
      // 340ms, which charged every signup for a decoration; the animation is free to finish
      // while the next route mounts.
      //
      // DEFAULT_ROUTE rather than a literal: signup used to land on /import while the manifest
      // sent everyone else to the board, so "where a user starts" had two answers. The first-run
      // checklist on the board is what points a new account at Import.
      navigate(DEFAULT_ROUTE)
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
        {/* Names the span the product actually owns. The previous headline ("a calm,
            high-velocity system") stacked three abstractions and the subhead called our
            own UI "sleek" — a claim the user should reach, not be told. */}
        <h1 className="landing-title landing-reveal delay-2">
          Every creator, from first DM to final payout, on one board.
        </h1>
        <p className="lead landing-lead landing-reveal delay-3">
          Import your list, launch campaigns, and pay creators without a spreadsheet. Free for 25
          creators — no card, no time limit.
        </p>

        {/* A visible product UI is table stakes for this category — GRIN, Modash and Klaviyo all
            show theirs above the fold, and its absence reads as an immature product. This is the
            workflow board with a representative pipeline on it: the same seven stages a new
            account is seeded with (constants.js DEFAULT_BOARD_STAGES), which is also the span the
            headline promises. Decorative only, hence the empty alt — the caption below carries
            the meaning, so a screen reader is not made to sit through a description of a picture
            of a board it can visit for real after signing in. */}
        <figure className="landing-shot landing-reveal delay-4">
          <img
            src="/marketing/workflow-board.png"
            alt=""
            width="1240"
            height="348"
            loading="lazy"
            decoding="async"
          />
          <figcaption>The workflow board — every creator relationship, stage by stage.</figcaption>
        </figure>

        {/* The stat cards here used to read 5x / 1 / 0. "1" and "0" are rhetorical devices
            wearing the costume of metrics, and they sat in the largest type on the page while
            the real, enforced numbers (25 creators, 3 members) were bullets further down. These
            three are capabilities that are true today and checkable by using the product. */}
        <div className="landing-stat-grid landing-reveal delay-4" aria-label="What you get">
          <article className="landing-stat-card">
            <p className="landing-stat-value">CSV</p>
            <p className="landing-stat-label">Import with a mapping preview before anything saves</p>
          </article>
          <article className="landing-stat-card">
            <p className="landing-stat-value">Kanban</p>
            <p className="landing-stat-label">Outreach to paid on one drag-and-drop board</p>
          </article>
          <article className="landing-stat-card">
            <p className="landing-stat-value">Payouts</p>
            <p className="landing-stat-label">Coupon attribution through to what each creator earned</p>
          </article>
        </div>

        {/* The tier table (M2.3). Signed out, so there is no account to ask and the numbers come
            from shell/plan.js — which carries a note that they must track PlanPolicy in the BFF.
            Advertising a limit the server does not enforce is the thing to avoid here.

            Prices are deliberately absent: none has been decided, and a UI file is not where that
            commitment should get made. What each paid tier LIFTS is knowable and stated; what it
            costs is not.

            PAID TIERS ARE HIDDEN until VITE_BILLING_LIVE=true. Advertising a plan nobody can buy
            is worse than advertising nothing: someone who wants to pay finds no way to, and
            someone who signs up expecting those limits gets the free ones instead. */}
        <div className="landing-tiers landing-reveal delay-5">
          {/* h3, not h2: this is a subsection of the hero. As an h2 it was a sibling of the
              auth panel's "Create your operator workspace", so a screen reader navigating by
              heading heard a pricing title and a form title as peers of the same region. */}
          <h3 className="landing-tiers-title">
            {billingLive
              ? 'Start free. Grow when the ceiling gets close.'
              : 'Free while we are in early access.'}
          </h3>
          <div className={`landing-tier-grid${tiers.length === 1 ? ' landing-tier-grid-single' : ''}`}>
            {tiers.map((tier) => (
              <article
                key={tier.key}
                className={`landing-tier-card${tier.key === 'free' ? ' landing-tier-card-featured' : ''}`}
              >
                <p className="landing-tier-name">{tier.label}</p>
                <p className="landing-tier-tagline">{tier.tagline}</p>
                <ul className="landing-tier-list">
                  {tier.highlights.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
                <p className="landing-tier-note">{tier.note}</p>
              </article>
            ))}
          </div>
          <p className="landing-tier-footnote">
            {billingLive
              ? 'Limits cap what you can add, never what you already have. Nothing is deleted or hidden if you reach one.'
              : 'Paid plans are not open yet. Limits cap what you can add, never what you already have — nothing is deleted or hidden if you reach one.'}
          </p>
        </div>
      </section>

      <section className="auth-panel landing-auth-panel landing-reveal delay-3">
        <div className="landing-auth-header landing-reveal delay-4">
          <p className="eyebrow">Workspace Access</p>
          {/* "operator workspace" was internal vocabulary — nobody self-identifies as an
              operator. The log-in title said "tejdux.io" while the badge says "Tejdux
              Influencer CRM" and the tab says "tejdux"; one spelling of the brand is enough. */}
          <h2>{isSignUp ? 'Create your workspace' : 'Welcome back to Tejdux'}</h2>
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
                  {/* No defaultValue: a pre-filled workspace name is one that gets tabbed past,
                      and every account that did would be named after this platform rather than
                      the brand signing up. */}
                  <input
                    name="brand"
                    type="text"
                    placeholder={accountType === 'agency' ? 'Northstar Agency' : 'Your brand name'}
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
            <button
              type="button"
              className="ghost-btn auth-alt-btn"
              onClick={() => handleSocialLogin('facebook')}
              disabled={Boolean(socialProvider)}
              aria-busy={socialProvider === 'facebook'}
            >
              {socialProvider === 'facebook' ? 'Connecting…' : 'Facebook'}
            </button>
          </div>
        </div>

        {/* These were plain text: the page asked people to agree to terms it gave them no way
            to read, which is a compliance problem as much as a UX one. The targets are the same
            live pages registered with Meta and TikTok — see docs/platform-app-registration.md. */}
        <p className="landing-footnote">
          By continuing you agree to our{' '}
          <a href="https://www.tejdux.com/terms/" target="_blank" rel="noreferrer noopener">
            Terms of Service
          </a>{' '}
          and{' '}
          <a href="https://www.tejdux.com/privacy/" target="_blank" rel="noreferrer noopener">
            Privacy Policy
          </a>
          .
        </p>
      </section>
    </main>
  )
}

export default LandingPage
