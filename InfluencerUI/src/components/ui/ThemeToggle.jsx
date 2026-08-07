import { useEffect, useState } from 'react'

const STORAGE_KEY = 'tejdux.theme'

const MODES = [
  { value: 'system', label: 'System', glyph: '◐' },
  { value: 'light', label: 'Light', glyph: '☀' },
  { value: 'dark', label: 'Dark', glyph: '☾' },
]

/**
 * Reads the stored preference.
 *
 * <p>Wrapped in try/catch because localStorage throws — not returns null — in Safari private
 * browsing and when a corporate policy blocks site data. An unreadable preference must fall back
 * to following the OS, not take the app down on first render.
 */
function readStoredMode() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return MODES.some((mode) => mode.value === stored) ? stored : 'system'
  } catch {
    return 'system'
  }
}

/**
 * Light / dark / follow-the-OS.
 *
 * <p>index.css already defines the entire dark palette and honours `data-theme` on the root in
 * both directions — the theme was fully built and simply unreachable, because nothing in the UI
 * ever set that attribute. This is the control that was missing.
 *
 * <p>Three states rather than a boolean: "follow my OS" is a real preference and distinct from
 * "always light". A two-way switch would silently pin whichever mode the user happened to be in
 * when they first touched it.
 */
function ThemeToggle() {
  const [mode, setMode] = useState(readStoredMode)

  useEffect(() => {
    const root = document.documentElement
    if (mode === 'system') {
      // Removed, not set to a value: the CSS keys off `:root:not([data-theme='light'])` inside a
      // prefers-color-scheme query, so absence is what hands control back to the OS.
      root.removeAttribute('data-theme')
    } else {
      root.setAttribute('data-theme', mode)
    }

    try {
      localStorage.setItem(STORAGE_KEY, mode)
    } catch {
      // A preference that cannot be persisted still applies for this session. Failing to save is
      // not a reason to refuse to switch.
    }
  }, [mode])

  return (
    <div className="theme-toggle" role="radiogroup" aria-label="Colour theme">
      {MODES.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={mode === option.value}
          className={`theme-toggle-option${mode === option.value ? ' is-active' : ''}`}
          onClick={() => setMode(option.value)}
          // The glyph alone is ambiguous — a sun and a moon do not say "system". The visible
          // label is the glyph; this is what a screen reader announces.
          title={option.label}
        >
          <span aria-hidden="true">{option.glyph}</span>
          <span className="visually-hidden">{option.label}</span>
        </button>
      ))}
    </div>
  )
}

export default ThemeToggle
