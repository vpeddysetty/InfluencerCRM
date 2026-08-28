import { useCallback, useEffect, useMemo, useState } from 'react'
import { Badge, EmptyState, Field } from './ui'

/**
 * Who is working on this page, and whose move it is (roadmap PR-42).
 *
 * The panel answers two questions that are deliberately separate everywhere else in the system:
 * `stage` says how far along the page is, and `turn` says whose move it is. They change for
 * different reasons — a page sits at content_needed while the turn bounces brand → creator → brand
 * three times over — so this shows them as two facts rather than deriving one from the other.
 *
 * Presentational: every call is a prop. That is what lets the shell and the remote hold identical
 * copies (contentRemoteCopies.test.mjs) while their surrounding pages differ, and it is the same
 * reason SectionEditor takes callbacks rather than importing an api module.
 */
export default function CollaboratorPanel({
  page,
  collaborators = [],
  invites = [],
  busy = false,
  can = () => true,
  onHandOff,
  onTakeBack,
  onRevoke,
  onInvite,
  onRefresh,
}) {
  const [email, setEmail] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pendingLink, setPendingLink] = useState('')

  const turn = page?.turn || null
  const stage = page?.stage || 'draft'

  // Only creators with a confirmed link can be handed a page — the server refuses otherwise, and
  // showing the button for someone it would refuse is a worse experience than not showing it.
  const editable = useMemo(
    () => collaborators.filter((row) => row.rights === 'edit' && !row.revokedAt),
    [collaborators],
  )

  const canHandOff = ['approved', 'creator_assigned', 'content_needed'].includes(stage)

  useEffect(() => {
    // Clear the one-time link when the page changes: it belongs to one invitation, and leaving it
    // on screen after switching pages would invite pasting the wrong brand's token.
    setPendingLink('')
    setNotice('')
    setError('')
  }, [page?.id])

  const run = useCallback(async (action, onDone) => {
    setError('')
    setNotice('')
    try {
      const result = await action()
      if (onDone) onDone(result)
      if (onRefresh) await onRefresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'That did not work. Please try again.')
    }
  }, [onRefresh])

  async function invite(event) {
    event.preventDefault()
    if (!email.trim()) return
    await run(
      () => onInvite({ email: email.trim() }),
      (result) => {
        setEmail('')
        // The token comes back ONCE. Surfacing it is not a debug affordance: SES is in the sandbox,
        // so delivery failure is the expected path today, and without the link in front of them the
        // brand has no way to invite anyone at all.
        if (result?.token) {
          setPendingLink(`${window.location.origin}/invite?token=${encodeURIComponent(result.token)}`)
        }
        setNotice(result?.delivered
          ? 'Invitation sent.'
          : 'Invitation created, but the email could not be sent. Share the link below instead.')
      },
    )
  }

  return (
    <section className="collab-panel" aria-label="Collaboration">
      <header className="collab-panel__head">
        <h3>Working on this page</h3>
        <TurnBadge turn={turn} />
      </header>

      {error ? <p className="collab-panel__error" role="alert">{error}</p> : null}
      {notice ? <p className="collab-panel__notice">{notice}</p> : null}

      {pendingLink ? (
        <div className="collab-panel__link">
          <p>Send this link to the creator. It works once and expires in 7 days.</p>
          {/* readOnly rather than disabled: a disabled input cannot be selected, and copying this
              by hand is the entire point of showing it. */}
          <input type="text" value={pendingLink} readOnly onFocus={(e) => e.target.select()} />
        </div>
      ) : null}

      {editable.length === 0 ? (
        <EmptyState
          title="No creator on this page yet"
          description="Invite a creator to co-author it with you. They can edit the content; publishing stays with you."
        />
      ) : (
        <ul className="collab-panel__list">
          {editable.map((row) => (
            <li key={row.id}>
              <span className="collab-panel__who">
                {row.creatorEmail || row.creatorDisplayName || 'Creator'}
              </span>
              <Badge tone="neutral">can edit</Badge>
              {can('content:write') && onRevoke ? (
                <button
                  type="button"
                  className="btn btn-quiet"
                  disabled={busy}
                  onClick={() => run(() => onRevoke(row.id))}
                >
                  Remove
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {can('content:write') ? (
        <>
          {turn === 'creator' ? (
            <div className="collab-panel__actions">
              <p className="collab-panel__waiting">
                Waiting on the creator since {formatWhen(page?.turnChangedAt)}.
              </p>
              {/* Always offered, even when the turn already reads brand — this is how a brand
                  recovers from an accidental handoff or an unresponsive creator, so refusing it in
                  the drifted state would block the case it exists for. */}
              <button type="button" className="btn" disabled={busy} onClick={() => run(onTakeBack)}>
                Take it back
              </button>
            </div>
          ) : (
            <div className="collab-panel__actions">
              {editable.length > 0 && canHandOff ? (
                <>
                  <Field label="Anything you want them to focus on?" htmlFor="handoff-note">
                    <textarea
                      id="handoff-note"
                      rows={2}
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      placeholder="e.g. add your own intro and swap the photo"
                    />
                  </Field>
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={busy}
                    onClick={() => run(
                      () => onHandOff({ creatorIdentityId: editable[0].creatorIdentityId, note }),
                      () => setNote(''),
                    )}
                  >
                    Hand over to creator
                  </button>
                </>
              ) : null}
              {editable.length > 0 && !canHandOff ? (
                <p className="collab-panel__hint">
                  Approve this page before handing it to a creator.
                </p>
              ) : null}
            </div>
          )}

          <form className="collab-panel__invite" onSubmit={invite}>
            <Field label="Invite a creator by email" htmlFor="collab-invite-email">
              <input
                id="collab-invite-email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="creator@example.com"
              />
            </Field>
            <button type="submit" className="btn" disabled={busy || !email.trim()}>
              Send invitation
            </button>
          </form>

          {invites.length > 0 ? (
            <ul className="collab-panel__invites">
              {invites.filter((row) => row.status === 'pending').map((row) => (
                <li key={row.id}>
                  <span>{row.email}</span>
                  <Badge tone="warning">invited</Badge>
                </li>
              ))}
            </ul>
          ) : null}
        </>
      ) : null}
    </section>
  )
}

/**
 * Whose move it is.
 *
 * `null` is a real state and not "unknown" — nobody owes anything, which is true of a solo draft
 * and of a published page. Rendering it as "waiting on brand" would put every page a brand ever
 * made into a list that is supposed to mean something.
 */
function TurnBadge({ turn }) {
  if (turn === 'creator') return <Badge tone="warning">With the creator</Badge>
  if (turn === 'brand') return <Badge tone="info">Your move</Badge>
  return null
}

function formatWhen(value) {
  if (!value) return 'recently'
  const when = new Date(value)
  if (Number.isNaN(when.getTime())) return 'recently'
  const days = Math.floor((Date.now() - when.getTime()) / 86400000)
  if (days <= 0) return 'today'
  if (days === 1) return 'yesterday'
  return `${days} days ago`
}
