import { useEffect, useMemo, useState } from 'react'
import { listMyPages } from '../api/client'

/**
 * Everything a creator has been asked to work on (roadmap PR-43).
 *
 * **"Waiting on you" first, and that IS the whose-turn answer.** The `turn` column exists so this
 * list can be ordered by obligation rather than by date. A creator working with four brands does
 * not want a reverse-chronological feed; they want the two things somebody is currently waiting
 * on, and then everything else.
 *
 * Grouped rather than filtered: a filter hides the rest behind a control nobody clicks, and the
 * "In progress" group is what tells a creator their work went somewhere after they sent it back.
 */
export default function MyPagesPage({ onOpen, onSignOut }) {
  const [pages, setPages] = useState([])
  const [state, setState] = useState('loading')
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    listMyPages()
      .then((rows) => {
        if (cancelled) return
        setPages(rows)
        setState('ready')
      })
      .catch((e) => {
        if (cancelled) return
        // A revoked session is not an error to display — it is a sign-out. The client already
        // cleared the token; the shell sends them back to sign in.
        if (e.code === 'session_expired') {
          onSignOut()
          return
        }
        setError(e instanceof Error ? e.message : 'We could not load your pages.')
        setState('error')
      })
    return () => { cancelled = true }
  }, [onSignOut])

  const groups = useMemo(() => {
    const waiting = []
    const inProgress = []
    const live = []
    for (const entry of pages) {
      const page = entry.page || entry
      if (page.status === 'published') live.push(entry)
      else if (page.turn === 'creator') waiting.push(entry)
      else inProgress.push(entry)
    }
    return { waiting, inProgress, live }
  }, [pages])

  if (state === 'loading') {
    return <main className="cp-shell"><p>Loading your pages…</p></main>
  }

  if (state === 'error') {
    return (
      <main className="cp-shell">
        <h1>Something went wrong</h1>
        <p className="cp-error" role="alert">{error}</p>
      </main>
    )
  }

  if (pages.length === 0) {
    return (
      <main className="cp-shell">
        <h1>Nothing yet</h1>
        <p>
          When a brand asks you to help with a campaign page, it will appear here. You will get an
          email when that happens.
        </p>
        <button type="button" className="cp-btn" onClick={onSignOut}>Sign out</button>
      </main>
    )
  }

  return (
    <main className="cp-shell">
      <header className="cp-head">
        <h1>Your pages</h1>
        <button type="button" className="cp-btn cp-btn--quiet" onClick={onSignOut}>Sign out</button>
      </header>

      <PageGroup
        title="Waiting on you"
        description="These brands are waiting for your changes."
        entries={groups.waiting}
        onOpen={onOpen}
        emphasis
      />
      <PageGroup
        title="In progress"
        description="You have sent these back. The brand is reviewing them."
        entries={groups.inProgress}
        onOpen={onOpen}
      />
      <PageGroup
        title="Live"
        description="Published and out in the world."
        entries={groups.live}
        onOpen={onOpen}
      />
    </main>
  )
}

function PageGroup({ title, description, entries, onOpen, emphasis = false }) {
  if (entries.length === 0) return null
  return (
    <section className={emphasis ? 'cp-group cp-group--emphasis' : 'cp-group'}>
      <h2>{title}</h2>
      <p className="cp-group__note">{description}</p>
      <ul className="cp-list">
        {entries.map((entry) => {
          const page = entry.page || entry
          return (
            <li key={page.id}>
              <button type="button" className="cp-card" onClick={() => onOpen(entry)}>
                <span className="cp-card__name">{page.name || 'Campaign page'}</span>
                {/* Read-only is a real state: `comment` rights exist in the schema. The whole
                    ENTRY is passed to onOpen, not just the page, so the editor knows this before
                    it renders — a creator with comment rights sees the page without the save and
                    rewrite controls, rather than seeing them and being refused on save. */}
                {entry.rights === 'comment' ? (
                  <span className="cp-card__tag">view only</span>
                ) : null}
                <span className="cp-card__when">{describeWhen(page.turnChangedAt)}</span>
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function describeWhen(value) {
  if (!value) return ''
  const when = new Date(value)
  if (Number.isNaN(when.getTime())) return ''
  const days = Math.floor((Date.now() - when.getTime()) / 86400000)
  if (days <= 0) return 'today'
  if (days === 1) return 'since yesterday'
  return `for ${days} days`
}
