import { useEffect, useRef } from 'react'

/**
 * Confirmation for a destructive action.
 *
 * <p>Replaces `window.confirm`, which cannot be styled, cannot describe consequences beyond one
 * line, reads to the user as a browser error rather than a decision, and blocks the whole tab
 * while it is open. The `consequence` prop is the point of the component: people approve
 * destructive actions they understand and regret the ones they do not.
 */
function ConfirmDialog({
  title,
  consequence,
  confirmLabel = 'Delete',
  cancelLabel = 'Cancel',
  tone = 'danger',
  busy = false,
  onConfirm,
  onCancel,
}) {
  const panelRef = useRef(null)
  const cancelRef = useRef(null)
  const openerRef = useRef(null)

  // Refs so the effects below need not depend on their identity. An effect that focuses and lists
  // a changing dependency re-runs whenever the parent re-renders, and pulls focus with it — the
  // same defect that made typing impossible in the Drawer. Here `busy` flipping mid-confirm would
  // have yanked focus back to Cancel while the user was acting.
  const onCancelRef = useRef(onCancel)
  const busyRef = useRef(busy)
  useEffect(() => {
    onCancelRef.current = onCancel
    busyRef.current = busy
  }, [onCancel, busy])

  // MOUNT ONLY: entering and leaving the dialog.
  useEffect(() => {
    openerRef.current = document.activeElement

    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    // Focus lands on Cancel, not Confirm. A destructive dialog whose default action is
    // destructive turns a reflexive Enter keypress into data loss.
    cancelRef.current?.focus()

    return () => {
      document.body.style.overflow = originalOverflow
      if (openerRef.current instanceof HTMLElement) {
        openerRef.current.focus()
      }
    }
  }, [])

  useEffect(() => {
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !busyRef.current) {
        event.preventDefault()
        onCancelRef.current()
        return
      }

      if (event.key !== 'Tab') {
        return
      }

      const focusable = Array.from(
        panelRef.current?.querySelectorAll('button:not([disabled])') || [],
      )
      if (focusable.length < 2) {
        return
      }

      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])

  return (
    <div className="confirm-overlay" role="presentation" onClick={busy ? undefined : onCancel}>
      <div
        className="confirm-panel"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        aria-describedby={consequence ? 'confirm-consequence' : undefined}
        ref={panelRef}
        onClick={(event) => event.stopPropagation()}
      >
        <h2 className="confirm-title" id="confirm-title">{title}</h2>
        {consequence ? (
          <p className="confirm-consequence" id="confirm-consequence">{consequence}</p>
        ) : null}
        <div className="confirm-actions">
          <button type="button" className="ghost-btn" onClick={onCancel} disabled={busy} ref={cancelRef}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={tone === 'danger' ? 'danger-btn' : 'primary-btn'}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

export default ConfirmDialog
