import { NavLink, Outlet } from 'react-router-dom'

function WorkspaceLayout({ brandName, userName, onLogout, workspaceError = '' }) {
  return (
    <main className="workspace-shell">
      <header className="workspace-header">
        <div className="mdx-prose">
          <p className="mdx-kicker">Workspace</p>
          <p className="eyebrow">{brandName}</p>
          <h2>Welcome back, {userName}</h2>
          <p className="subcopy">Mock dashboard for campaign execution and creator relationship management.</p>
        </div>
        <button type="button" className="ghost-btn" onClick={onLogout}>
          Log out
        </button>
      </header>

      <nav className="workspace-nav" aria-label="Workspace views">
        <NavLink to="/import">Import</NavLink>
        <NavLink to="/campaigns">Campaigns</NavLink>
        <NavLink to="/creators">Creators</NavLink>
        <NavLink to="/workflow">Workflow</NavLink>
      </nav>

      {workspaceError ? <p className="mdx-note workspace-error-banner">{workspaceError}</p> : null}

      <section className="workspace-content mdx-theme">
        <Outlet />
      </section>
    </main>
  )
}

export default WorkspaceLayout
