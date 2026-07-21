import { NavLink, Outlet } from 'react-router-dom'
import { MdsKicker, MdsNote } from './Mds'

function WorkspaceLayout({ brandName, userName, onLogout, workspaceError = '' }) {
  return (
    <main className="workspace-shell">
      <header className="workspace-header">
        <div className="mds-prose">
          <MdsKicker>Workspace</MdsKicker>
          <p className="eyebrow">{brandName}</p>
          <h2>Welcome back, {userName}</h2>
          <p className="subcopy">Campaign execution and creator relationship management dashboard.</p>
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

      {workspaceError ? <MdsNote className="workspace-error-banner">{workspaceError}</MdsNote> : null}

      <section className="workspace-content mds-theme">
        <Outlet />
      </section>
    </main>
  )
}

export default WorkspaceLayout
