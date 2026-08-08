import { useEffect, useRef } from 'react'

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

/**
 * A modal side panel.
 *
 * <p>Replaces four hand-rolled copies of the same overlay markup, each of which handled Escape
 * and body-scroll but none of which trapped focus or announced itself. A drawer that does not
 * trap focus is a dialog a keyboard user tabs straight out of, into a page they cannot see is
 * still there — which is why this is a shared component rather than a pattern to copy again.
 */
function Drawer({ title, onClose, children, labelledBy }) {
  const panelRef = useRef(null)
  // Captured on mount rather than read on unmount: by the time the drawer closes, the element
  // that opened it may already have lost focus to the body.
  const openerRef = useRef(null)

  useEffect(() => {
    openerRef.current = document.activeElement

    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    // Focus the panel itself rather than its first field: a screen reader then reads the
    // dialog's label and role before its contents, which is the announcement that tells
    // someone a modal opened at all.
    panelRef.current?.focus()

    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
        return
      }

      if (event.key !== 'Tab') {
        return
      }

      const focusable = Array.from(panelRef.current?.querySelectorAll(FOCUSABLE) || [])
        .filter((node) => node.offsetParent !== null)
      if (!focusable.length) {
        // Nothing to cycle between; keep focus on the panel rather than letting Tab escape.
        event.preventDefault()
        return
      }

      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement

      // Wrap in both directions. Without the panel-itself check, Shift+Tab from the panel
      // (which is where focus starts) would fall out of the dialog on the first keystroke.
      if (event.shiftKey && (active === first || active === panelRef.current)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)

    return () => {
      document.body.style.overflow = originalOverflow
      document.removeEventListener('keydown', onKeyDown)
      // Returning focus to the opener is what makes the drawer feel like it belongs to the
      // button that opened it, instead of dumping the user at the top of the document.
      if (openerRef.current instanceof HTMLElement) {
        openerRef.current.focus()
      }
    }
  }, [onClose])

  return (
    <div className="drawer-overlay" onClick={onClose} role="presentation">
      <aside
        className="drawer-panel"
        role="dialog"
        aria-modal="true"
        aria-label={labelledBy ? undefined : title}
        aria-labelledby={labelledBy}
        tabIndex={-1}
        ref={panelRef}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="drawer-header">
          <h2 className="drawer-title">{title}</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={`Close ${title}`}>
            <span aria-hidden="true">✕</span>
          </button>
        </div>
        <div className="drawer-body">{children}</div>
      </aside>
    </div>
  )
}

export default Drawer
