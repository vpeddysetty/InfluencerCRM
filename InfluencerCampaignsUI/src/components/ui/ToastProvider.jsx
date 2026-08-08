import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'

const ToastContext = createContext(null)

let nextId = 0

/**
 * App-level toasts.
 *
 * <p>Mounted at the shell so a message outlives the component that raised it. Pages previously
 * set a local `rowFeedback` and then closed the drawer, which unmounted the only thing rendering
 * that message — so every successful save confirmed itself to nobody. Hoisting the state fixes
 * that class of bug rather than one instance of it.
 */
export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])
  const timersRef = useRef(new Map())

  const dismiss = useCallback((id) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id))
    const timer = timersRef.current.get(id)
    if (timer) {
      window.clearTimeout(timer)
      timersRef.current.delete(id)
    }
  }, [])

  const push = useCallback((message, tone = 'info', options = {}) => {
    const id = ++nextId
    const text = String(message || '').trim()
    if (!text) {
      return id
    }

    setToasts((prev) => [...prev, { id, message: text, tone }])

    // Errors stay until dismissed. A message that says something went wrong is one the user
    // may need to read twice, or copy — auto-hiding it after four seconds is how error text
    // gets missed by exactly the person who needed it.
    const duration = options.duration ?? (tone === 'error' ? 0 : 4500)
    if (duration > 0) {
      timersRef.current.set(id, window.setTimeout(() => dismiss(id), duration))
    }
    return id
  }, [dismiss])

  useEffect(() => {
    const timers = timersRef.current
    return () => {
      timers.forEach((timer) => window.clearTimeout(timer))
      timers.clear()
    }
  }, [])

  const api = useMemo(() => ({
    success: (message, options) => push(message, 'success', options),
    error: (message, options) => push(message, 'error', options),
    info: (message, options) => push(message, 'info', options),
    dismiss,
  }), [push, dismiss])

  return (
    <ToastContext.Provider value={api}>
      {children}
      {/* Two regions, because assertive and polite cannot share one container: an error must
          interrupt, a save confirmation must not. Both are always mounted — a live region
          added to the DOM at the same time as its message is often not announced at all. */}
      <div className="toast-region" role="status" aria-live="polite" aria-atomic="false">
        {toasts.filter((toast) => toast.tone !== 'error').map((toast) => (
          <ToastItem key={toast.id} toast={toast} onDismiss={dismiss} />
        ))}
      </div>
      <div className="toast-region toast-region-alert" role="alert" aria-live="assertive" aria-atomic="false">
        {toasts.filter((toast) => toast.tone === 'error').map((toast) => (
          <ToastItem key={toast.id} toast={toast} onDismiss={dismiss} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}

function ToastItem({ toast, onDismiss }) {
  return (
    <div className={`toast toast-${toast.tone}`}>
      <span className="toast-message">{toast.message}</span>
      <button
        type="button"
        className="toast-dismiss"
        onClick={() => onDismiss(toast.id)}
        aria-label="Dismiss notification"
      >
        <span aria-hidden="true">✕</span>
      </button>
    </div>
  )
}

/**
 * Toast API. Safe to call outside a provider — returns no-ops rather than throwing, so a
 * federated remote rendered standalone degrades to silence instead of a crash.
 */
export function useToast() {
  const context = useContext(ToastContext)
  return context || FALLBACK_TOAST
}

const FALLBACK_TOAST = {
  success: () => {},
  error: () => {},
  info: () => {},
  dismiss: () => {},
}

export default ToastProvider
