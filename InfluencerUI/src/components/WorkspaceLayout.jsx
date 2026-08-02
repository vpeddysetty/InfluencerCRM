import { NavLink, Outlet } from 'react-router-dom'
import { MdsKicker, MdsNote } from './Mds'

/**
 * Nav entries and the permission each requires.
 *
 * Gating here is a UX affordance only — the server is authoritative. Hiding a link the user
 * cannot use avoids handing them a dead end; it is not what stops them acting.
 */
const NAV_ITEMS = [
  { to: '/import', label: 'Import', permission: 'import:execute' },
  { to: '/campaigns', label: 'Campaigns', permission: 'campaign:read' },
  { to: '/creators', label: 'Creators', permission: 'creator:read' },
  { to: '/content', label: 'Content', permission: 'content:read' },
  { to: '/workflow', label: 'Workflow', permission: 'workflow:read' },
  { to: '/coupons', label: 'Coupons', permission: 'coupon:read' },
  { to: '/marketplace', label: 'Marketplace', permission: 'marketplace:connect' },
  { to: '/dashboard', label: 'Dashboard', permission: 'attribution:read' },
  { to: '/payouts', label: 'Payouts', permission: 'payout:read' },
]

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
}) {
  // An empty permission set means the token predates permission claims; showing the full nav
  // is the safe default because the server still enforces every action.
  const canSee = (permission) => permissions.length === 0 || permissions.includes(permission)

  // Solo accounts have exactly one brand: render the name as plain text rather than a
  // one-option dropdown. Same data path, different affordance.
  const showSwitcher = brands.length > 1

  return (
    <main className="workspace-shell">
      <header className="workspace-header">
        <div className="mds-prose">
          <MdsKicker>Workspace</MdsKicker>
          {showSwitcher ? (
            <label className="brand-switcher">
              <span className="brand-switcher-label">Brand</span>
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
            <p className="eyebrow">{brandName}</p>
          )}
          <h2>Welcome back, {userName}</h2>
          <p className="subcopy">
            Campaign execution and creator relationship management dashboard.
            {role ? <span className="role-badge"> {role}</span> : null}
          </p>
        </div>
        <button type="button" className="ghost-btn" onClick={onLogout}>
          Log out
        </button>
      </header>

      <nav className="workspace-nav" aria-label="Workspace views">
        {NAV_ITEMS.filter((item) => canSee(item.permission)).map((item) => (
          <NavLink key={item.to} to={item.to}>
            {item.label}
          </NavLink>
        ))}
      </nav>

      {workspaceError ? <MdsNote className="workspace-error-banner">{workspaceError}</MdsNote> : null}

      <section className="workspace-content mds-theme">
        <Outlet />
      </section>
    </main>
  )
}

export default WorkspaceLayout
