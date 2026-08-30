import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MdsNote } from '../components/Mds'
import { DEFAULT_ROUTE } from '../shell/routeManifest'
import { BILLING_LIVE, visiblePublicTiers } from '../shell/plan'

function LandingPage({
  isSignUp,
  setIsSignUp,
  onAuthSubmit,
  onSocialLogin,
  authError = '',
  unverifiedEmail = '',
  verificationResendState = 'idle',
  onResendVerification,
}) {
  const navigate = useNavigate()
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [socialProvider, setSocialProvider] = useState('')
  // 'brand' | 'agency'. Creators are not an option HERE because this form creates a workspace and
  // a creator does not own one — they sign in at the creator portal instead, which has its own
  // signup. (This comment used to say creators "are CRM records, not accounts that sign in"; that
  // stopped being true when the portal shipped in Stage 4.)
  const [accountType, setAccountType] = useState('brand')

  // Unticked by default and never pre-ticked: a pre-ticked box is not the clear affirmative act
  // GDPR Article 4(11) requires, so it would not be consent at all. The server rejects a signup
  // without it regardless — this only keeps the user from a pointless round trip.
  const [acceptedTerms, setAcceptedTerms] = useState(false)

  // Build-time for the same reason as the billing flag below: the landing page is signed out. Read
  // from the environment rather than hardcoded, because .env.production is GENERATED from terraform
  // outputs and says "Do not edit" — a literal here would be overwritten on the next deploy in the
  // file but not in this component, which is the drift that produces a dead link nobody notices.
  // Unset renders nothing at all rather than a broken link.
  const creatorPortalUrl = import.meta.env?.VITE_CREATOR_PORTAL_URL || ''

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
      // Passed explicitly rather than left to FormData. The consent checkbox lives BELOW the form,
      // next to the social buttons it also governs, so it is outside <form> and a FormData built
      // from the submit event cannot see it — which silently sent every signup with no consent and
      // had the server refuse it. React state is where this value actually lives; read it from
      // there rather than from the DOM position it happens to occupy.
      await onAuthSubmit(event, { acceptedTerms })
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
    onSocialLogin(provider, {
      brandName,
      accountType: isSignUp ? accountType : '',
      // Consent has to leave WITH the redirect. After this the browser is at Google or Facebook and
      // the callback returns straight into a signed-in session — there is no later moment at which
      // a checkbox could be shown. Sign-in reuses this handler and has no checkbox, hence the guard.
      acceptedTerms: isSignUp ? acceptedTerms : false,
    })
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
          Import your list, launch campaigns, and pay creators without a spreadsheet. Free for one
          person and 25 creators — no card, no time limit.
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
              /* Disabled until ticked, so the requirement is visible before the click rather than
                 as an error after it. The server enforces this too — the button is a courtesy. */
              disabled={isSubmitting || (isSignUp && !acceptedTerms)}
              aria-busy={isSubmitting}
            >
              <span className="cta-label">
                {isSubmitting ? 'Opening workspace...' : isSignUp ? 'Create workspace' : 'Enter workspace'}
              </span>
              <span className="cta-check" aria-hidden="true">check</span>
              <span className="cta-shine" aria-hidden="true" />
            </button>
          </form>

          {/* Sign-in refused because the address is unconfirmed. Deliberately NOT rendered as an
              auth error: the password was right, and telling someone their credentials failed when
              they did not is how a correct user concludes the product is broken. The only way
              forward is a link in an inbox, so the screen offers to send another. */}
          {unverifiedEmail ? (
            <MdsNote className="auth-error-note">
              <strong>Confirm your email to finish signing in.</strong>{' '}
              We sent a link to {unverifiedEmail}. It works once and expires after 24 hours.
              {verificationResendState === 'sent' ? (
                <> A new link is on its way — check your inbox, and your spam folder.</>
              ) : (
                <>
                  {' '}
                  <button
                    type="button"
                    className="auth-link-btn"
                    onClick={onResendVerification}
                    disabled={verificationResendState === 'sending'}
                  >
                    {verificationResendState === 'sending' ? 'Sending…' : 'Send a new link'}
                  </button>
                </>
              )}
            </MdsNote>
          ) : null}

          {authError ? (
            <MdsNote className="auth-error-note">
              {authError}
              {/* The refusal names its own remedy — "sign in with your password, then link facebook
                  from account settings" — but account settings is a page behind a session the user
                  does not yet have, so the instruction is only actionable in that order. Rather than
                  offer a link that would bounce them straight back here, switch them to the form
                  that gets them signed in, which is the step they actually have to do first.

                  Matched on the stable half of the message rather than the whole string, so
                  rewording the prose does not silently drop the affordance. */}
              {/^An account already exists for this email/.test(authError) ? (
                <>
                  {' '}
                  <button
                    type="button"
                    className="auth-link-btn"
                    onClick={() => {
                      setIsSignUp(false)
                      // The password field is the next thing they need; putting the cursor there
                      // saves a click and makes the switch visibly do something.
                      document.querySelector('input[name="email"]')?.focus()
                    }}
                  >
                    Sign in with your password
                  </button>
                  {' — then connect it from Settings once you are in.'}
                </>
              ) : null}
            </MdsNote>
          ) : null}

          <div className="auth-divider" aria-hidden="true">
            <span>or continue with</span>
          </div>

          <div className="auth-alt-actions">
            <button
              type="button"
              className="ghost-btn auth-alt-btn"
              onClick={() => handleSocialLogin('google')}
              /* Sign-up gates on the same checkbox as the form below. Without this the social
                 path would be a way around a consent the email path insists on, and the BFF
                 would reject the redirect anyway — as an error after the click rather than a
                 disabled button before it. */
              disabled={Boolean(socialProvider) || (isSignUp && !acceptedTerms)}
              aria-busy={socialProvider === 'google'}
            >
              {socialProvider === 'google' ? 'Connecting…' : 'Google'}
            </button>
            <button
              type="button"
              className="ghost-btn auth-alt-btn"
              onClick={() => handleSocialLogin('facebook')}
              disabled={Boolean(socialProvider) || (isSignUp && !acceptedTerms)}
              aria-busy={socialProvider === 'facebook'}
            >
              {socialProvider === 'facebook' ? 'Connecting…' : 'Facebook'}
            </button>
          </div>

          {/* One consent, below BOTH sign-up paths, because it governs both.

              It used to sit inside the form, above the social buttons — which read as though it
              applied only to email sign-up, while the social buttons underneath silently required
              the same agreement. Placing it here makes the scope match the wording: whichever way
              you create the account, this is what you agreed to.

              Sign-up only. Someone signing in agreed when they registered, and re-asking on every
              login would train people to tick without reading — which is how consent becomes a
              formality rather than a decision. */}
          {isSignUp ? (
            <label className="auth-consent auth-consent-shared">
              <input
                type="checkbox"
                name="acceptedTerms"
                checked={acceptedTerms}
                onChange={(event) => setAcceptedTerms(event.target.checked)}
                required
              />
              <span>
                I agree to the{' '}
                <a href="https://www.tejdux.com/terms/" target="_blank" rel="noreferrer noopener">
                  Terms of Service
                </a>{' '}
                and{' '}
                <a href="https://www.tejdux.com/privacy/" target="_blank" rel="noreferrer noopener">
                  Privacy Policy
                </a>
                , and I understand how to{' '}
                {/* Meta requires this page to exist and expects it to be reachable from the
                    product, not only from the App Dashboard field. It was previously linked
                    nowhere in the UI — see docs/platform-app-registration.md. */}
                <a
                  href="https://www.tejdux.com/data-deletion/"
                  target="_blank"
                  rel="noreferrer noopener"
                >
                  request deletion of my data
                </a>
                .
              </span>
            </label>
          ) : null}
        </div>

        {/* Sign-in only, now that sign-up has an explicit checkbox above — repeating the links here
            during sign-up would give the same page two different consent affordances.

            These were once plain text: the page asked people to agree to terms it gave them no way
            to read. The targets are the same live pages registered with Meta and TikTok — see
            docs/platform-app-registration.md. Both 403'd from 2026-08-05 until the shell
            distribution was given a route to them; see static-site-cdn.tf. */}
        {/* The way back for a creator who already accepted an invitation. Their account exists only
            because a brand invited them — which is why there is no creator SIGNUP here, and why
            this is a link to the portal's sign-in rather than a third tab. But the portal was
            reachable from nowhere at all: a creator who closed the tab had to know the subdomain,
            and an invitation link is single-use, so re-sending it was not a recovery either.

            On the log-in tab only. Someone signing up is creating a workspace and is by definition
            not a returning creator, and the sign-up tab already carries the consent copy. */}
        {!isSignUp && creatorPortalUrl ? (
          <p className="landing-footnote landing-footnote--portal">
            Invited by a brand as a creator?{' '}
            <a href={creatorPortalUrl}>Sign in to your creator portal</a>.
          </p>
        ) : null}

        {!isSignUp ? (
          <p className="landing-footnote">
            Your use of Tejdux is governed by our{' '}
            <a href="https://www.tejdux.com/terms/" target="_blank" rel="noreferrer noopener">
              Terms of Service
            </a>{' '}
            and{' '}
            <a href="https://www.tejdux.com/privacy/" target="_blank" rel="noreferrer noopener">
              Privacy Policy
            </a>
            . You can{' '}
            <a
              href="https://www.tejdux.com/data-deletion/"
              target="_blank"
              rel="noreferrer noopener"
            >
              request deletion of your data
            </a>{' '}
            at any time.
          </p>
        ) : null}
      </section>
    </main>
  )
}

export default LandingPage
