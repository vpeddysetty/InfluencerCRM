import { NavLink, Outlet } from 'react-router-dom'
import { MdsKicker, MdsNote } from './Mds'
import { visibleRoutes } from '../shell/routeManifest'

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
  // Nav comes from the route manifest, so adding a page — or later moving one behind a
  // federated remote — is a manifest edit rather than a change here.
  const navItems = visibleRoutes(permissions)

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
        {navItems.map((item) => (
          <NavLink key={item.path} to={item.path}>
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
