import { useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { MdsNote } from './Mds'
import { groupedVisibleRoutes, ROUTE_MANIFEST } from '../shell/routeManifest'
import GettingStarted from './GettingStarted'
import { ThemeToggle } from './ui'

/** Glyph per route. Icons make a rail scannable by shape once the labels stop being read. */
const ROUTE_ICONS = {
  '/workflow': '▤',
  '/campaigns': '◈',
  '/creators': '◍',
  '/content': '✎',
  '/dashboard': '◔',
  '/coupons': '◆',
  '/payouts': '⬡',
  '/marketplace': '⬢',
  '/import': '↥',
  '/members': '⚇',
}

function WorkspaceLayout({
  brandName,
  userName,
  onLogout,
  workspaceError = '',
  brands = [],
  activeBrandId = '',
  onSwitchBrand,
  role = '',
  permissions = [],
  progress = null,
  onCreateBrand,
}) {
  const { pathname } = useLocation()

  const [brandFormOpen, setBrandFormOpen] = useState(false)
  const [newBrandName, setNewBrandName] = useState('')
  const [brandFormError, setBrandFormError] = useState('')
  const [creatingBrand, setCreatingBrand] = useState(false)

  // Gated on the same permission the BFF enforces (`brand:create` — OWNER and ADMIN only). The
  // server check is the one that matters; hiding the control keeps a MARKETER from being offered
  // an action that would 403, rather than pretending it is a security boundary.
  const canCreateBrand = Boolean(onCreateBrand) && permissions.includes('brand:create')

  const submitNewBrand = async (event) => {
    event.preventDefault()
    const name = newBrandName.trim()
    if (!name) {
      setBrandFormError('Give the brand a name.')
      return
    }
    try {
      setCreatingBrand(true)
      setBrandFormError('')
      await onCreateBrand(name)
      setNewBrandName('')
      setBrandFormOpen(false)
    } catch (error) {
      setBrandFormError(error instanceof Error ? error.message : 'Unable to create the brand.')
    } finally {
      setCreatingBrand(false)
    }
  }

  // Nav comes from the route manifest, so adding a page — or later moving one behind a
  // federated remote — is a manifest edit rather than a change here.
  const navGroups = groupedVisibleRoutes(permissions)

  // Solo accounts have exactly one brand: render the name as plain text rather than a
  // one-option dropdown. Same data path, different affordance.
  const showSwitcher = brands.length > 1

  // The page's own name, from the manifest that already knows it. The header used to read
  // "Welcome back, {name}" on all ten routes — the largest text on screen, carrying no
  // information after the first visit, while the page itself went unnamed.
  const currentRoute = ROUTE_MANIFEST.find((route) => route.path === pathname)

  return (
    <div className="workspace-grid">
      <a className="skip-link" href="#workspace-main">Skip to content</a>

      <div className="workspace-rail">
        <div className="rail-brand">
          {showSwitcher ? (
            <label className="brand-switcher">
              <span className="visually-hidden">Active brand</span>
              <select
                value={activeBrandId}
                onChange={(event) => onSwitchBrand?.(event.target.value)}
                aria-label="Active brand"
              >
                {brands.map((brand) => (
                  <option key={brand.brandId} value={brand.brandId}>
                    {brand.brandName}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <p className="rail-brand-name" title={brandName}>{brandName}</p>
          )}

          {/* The whole multi-brand story hangs off this control. Tenancy, per-brand roles, and
              isolation are built and live-verified, but an account with one brand had no way to
              create a second through the product at all — which made the capability
              undemonstrable. Note it renders in BOTH branches above: the switcher only appears
              at >1 brand, so putting it there would leave solo accounts with no path to a
              second one, which is exactly the account that needs it. */}
          {canCreateBrand ? (
            brandFormOpen ? (
              <form className="rail-brand-form" onSubmit={submitNewBrand}>
                <label className="visually-hidden" htmlFor="new-brand-name">New brand name</label>
                <input
                  id="new-brand-name"
                  type="text"
                  value={newBrandName}
                  placeholder="Brand name"
                  onChange={(event) => setNewBrandName(event.target.value)}
                  disabled={creatingBrand}
                  // The form is opened by an explicit click, so focusing here moves the caret to
                  // the only field rather than stealing focus on page load.
                  autoFocus
                />
                {brandFormError ? (
                  <p className="field-error" role="alert">{brandFormError}</p>
                ) : null}
                <div className="rail-brand-form-actions">
                  <button type="submit" className="primary-btn" disabled={creatingBrand}>
                    {creatingBrand ? 'Creating…' : 'Create'}
                  </button>
                  <button
                    type="button"
                    className="ghost-btn"
                    disabled={creatingBrand}
                    onClick={() => {
                      setBrandFormOpen(false)
                      setNewBrandName('')
                      setBrandFormError('')
                    }}
                  >
                    Cancel
                  </button>
                </div>
              </form>
            ) : (
              <button
                type="button"
                className="rail-brand-add"
                onClick={() => setBrandFormOpen(true)}
              >
                + Add brand
              </button>
            )
          ) : null}
        </div>

        <nav className="rail-nav" aria-label="Workspace views">
          {navGroups.map((bucket) => (
            <div className="rail-group" key={bucket.group}>
              <span className="rail-group-label">{bucket.group}</span>
              {bucket.routes.map((item) => (
                <NavLink key={item.path} to={item.path} className="rail-link">
                  <span className="rail-icon" aria-hidden="true">{ROUTE_ICONS[item.path] || '•'}</span>
                  {item.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="rail-footer">
          <div className="rail-user">
            <span className="rail-user-name" title={userName}>{userName}</span>
            {role ? <span className="rail-user-role">{role.toLowerCase()}</span> : null}
          </div>
          {/* The dark palette and the data-theme override were fully built in index.css and had
              no control anywhere in the UI, so the theme could only be reached by changing the
              OS setting. */}
          <ThemeToggle />
          <button type="button" className="ghost-btn" onClick={onLogout}>
            Log out
          </button>
        </div>
      </div>

      <main className="workspace-main" id="workspace-main">
        {workspaceError ? <MdsNote className="workspace-error-banner">{workspaceError}</MdsNote> : null}

        {/* The first-run checklist is the path from empty workspace to first attributed sale,
            so it stays above the page until there is real data to look at instead. */}
        {progress ? <GettingStarted {...progress} /> : null}

        <section className="workspace-content mds-theme">
          <Outlet context={{ routeLabel: currentRoute?.label }} />
        </section>
      </main>
    </div>
  )
}

export default WorkspaceLayout
