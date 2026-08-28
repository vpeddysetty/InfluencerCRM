import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import SectionEditor from '@influencer/ui/SectionEditor.jsx'
import { handBackPage, previewPageSections, rewriteSection, savePageSections } from '../api/client'

/**
 * The creator's editor screen (roadmap PR-44).
 *
 * **The same SectionEditor the brand uses, imported from `@influencer/ui` — not copied.** That is
 * not a style preference: copying this component into a project is what caused OP-19, a production
 * outage where the remote's copy had lost its import block and rendered a blank editor. The alias
 * in vite.config.js compiles these sources as part of this project's build, so there is exactly one
 * of them. Do not vendor it here to avoid the alias.
 *
 * **What a creator does NOT get, and why each is missing rather than disabled.** No status control,
 * no stage control, no slug, no campaign picker, no template picker. Those are brand decisions, and
 * the BFF refuses them regardless — but offering a control the server will reject teaches the
 * creator that the product is broken rather than that the action is not theirs. The one thing they
 * get that the brand does not is "send back to the brand", because handing the turn over is the
 * creator's half of the loop.
 *
 * **Sections are seeded once**, guarded by page id. The brand-side editor learned this the hard
 * way: re-seeding on every parent render replaces `sections` with a fresh array, which restarts the
 * preview debounce so the canvas never resolves, and discards whatever was just typed. Same guard,
 * same reason.
 */
export default function EditPage({ entry, onClose, onSignOut }) {
  const page = entry.page || entry
  const readOnly = entry.rights === 'comment'

  const [sections, setSections] = useState([])
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState({ type: '', message: '' })
  const [sendingBack, setSendingBack] = useState(false)
  const [note, setNote] = useState('')
  const [returned, setReturned] = useState(false)

  // Seeded once per page, never on re-render. See the note above.
  const seededFor = useRef(null)
  useEffect(() => {
    if (seededFor.current === page.id) return
    seededFor.current = page.id
    setSections(Array.isArray(page.sections) ? page.sections : [])
  }, [page.id, page.sections])

  // A revoked session is a sign-out, not an error to display — the client already cleared the
  // token, so the only honest thing left is to send them back to the door.
  const guard = useCallback((error) => {
    if (error?.code === 'session_expired') {
      onSignOut()
      return true
    }
    return false
  }, [onSignOut])

  /**
   * `useCallback` here is not an optimisation — it is required.
   *
   * SectionEditor's preview effect depends on `onPreview` directly, because the ref that used to
   * stand in for it captured a stale closure and blanked the canvas. A new function identity on
   * every render would re-fire that effect on every render instead.
   */
  const onPreview = useCallback(async (next) => {
    try {
      return await previewPageSections(page.id, next)
    } catch (error) {
      guard(error)
      // An empty string means "declined" to SectionEditor, which keeps the last good preview on
      // screen rather than blanking it.
      return ''
    }
  }, [page.id, guard])

  const onSave = useCallback(async (next) => {
    setBusy(true)
    setFeedback({ type: '', message: '' })
    try {
      await savePageSections(page.id, next)
      setFeedback({ type: 'success', message: 'Saved. The brand will see your changes.' })
    } catch (error) {
      if (guard(error)) return
      setFeedback({
        type: 'error',
        message: error instanceof Error ? error.message : 'We could not save your changes.',
      })
    } finally {
      setBusy(false)
    }
  }, [page.id, guard])

  const onRewrite = useCallback(async (payload) => {
    try {
      return await rewriteSection(page.id, payload)
    } catch (error) {
      if (guard(error)) return null
      // SectionEditor reads a falsy `rewritten` as "no suggestion" and shows `detail` while leaving
      // the creator's own words alone — the right outcome for a failed call too.
      return {
        rewritten: false,
        detail: 'The rewrite is unavailable right now. Your text is unchanged.',
      }
    }
  }, [page.id, guard])

  const sendBack = async () => {
    setSendingBack(true)
    setFeedback({ type: '', message: '' })
    try {
      await handBackPage(page.id, note.trim())
      setReturned(true)
    } catch (error) {
      if (guard(error)) return
      // 409 is the "you already sent this back" case. It is not breakage — the creator's intent
      // holds — so it lands on the same confirmation screen rather than on an error.
      if (error?.status === 409) {
        setReturned(true)
        return
      }
      setFeedback({
        type: 'error',
        message: error instanceof Error ? error.message : 'We could not send the page back.',
      })
    } finally {
      setSendingBack(false)
    }
  }

  const brandName = useMemo(
    () => entry.brandName || page.brandName || 'the brand',
    [entry.brandName, page.brandName],
  )

  if (returned) {
    return (
      <main className="cp-shell">
        <h1>Sent back to {brandName}</h1>
        <p className="cp-lede">
          Thanks — your changes are with them now. They will let you know if they need anything
          else, and the page will show up here again if it comes back to you.
        </p>
        <button type="button" className="cp-btn cp-btn--primary" onClick={onClose}>
          Back to your pages
        </button>
      </main>
    )
  }

  return (
    <main className="cp-shell cp-shell--wide">
      <header className="cp-head">
        <div>
          <p className="cp-eyebrow">{brandName}</p>
          <h1>{page.name || 'Campaign page'}</h1>
        </div>
        <button type="button" className="cp-btn cp-btn--quiet" onClick={onClose}>
          Your pages
        </button>
      </header>

      {readOnly ? (
        <p className="cp-note" role="status">
          You have view-only access to this page, so it is shown as the brand last saved it.
        </p>
      ) : null}

      {feedback.message ? (
        <p className={feedback.type === 'error' ? 'cp-error' : 'cp-success'} role="status">
          {feedback.message}
        </p>
      ) : null}

      <SectionEditor
        sections={sections}
        onChange={readOnly ? undefined : setSections}
        onPreview={onPreview}
        // Omitted entirely for view-only rather than disabled: SectionEditor hides the save and
        // rewrite controls when the callback is absent, which is the honest rendering of "this is
        // not yours to change".
        onSave={readOnly ? undefined : onSave}
        onRewrite={readOnly ? undefined : onRewrite}
        busy={busy}
        // The template gallery is deliberately absent, and `savedTemplates` is empty rather than
        // unfetched: saving a page as a template is metered against the BRAND's
        // PlanPolicy.Resource.SAVED_TEMPLATE quota, so a creator saving one would spend a quota
        // that is not theirs, on an account they cannot see. `templates` is omitted for the
        // related reason that a creator is editing an existing page, not choosing its shape --
        // applying a starter template here would discard the brand's work.
        savedTemplates={[]}
      />

      {readOnly ? null : (
        <section className="cp-group cp-group--emphasis">
          <h2>Done for now?</h2>
          <p className="cp-group__note">
            Send the page back to {brandName} when you have finished your changes. They decide when
            it goes live — sending it back does not publish anything.
          </p>
          <div className="cp-form">
            <label htmlFor="cp-handback-note">Anything to tell them? (optional)</label>
            <input
              id="cp-handback-note"
              type="text"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="I rewrote the intro in my own words."
            />
          </div>
          <button
            type="button"
            className="cp-btn cp-btn--primary"
            onClick={sendBack}
            disabled={sendingBack}
          >
            {sendingBack ? 'Sending…' : `Send back to ${brandName}`}
          </button>
        </section>
      )}
    </main>
  )
}
