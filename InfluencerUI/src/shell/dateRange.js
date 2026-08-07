/**
 * Date-range presets for the analytics dashboard.
 *
 * <p>Plain `.js` rather than living beside the component: this is the arithmetic that decides
 * which orders count toward a KPI, and off-by-one there misreports revenue. The repo's test
 * runner is bare `node --test` with no JSX loader, so logic worth asserting has to sit in a
 * module Node can import.
 */

/**
 * The ranges a brand manager actually asks for.
 *
 * `days: null` is all-time — an explicit option rather than a cleared filter, so the current
 * window is always named on screen. A dashboard that shows a number without saying what period it
 * covers is the specific failure the range control exists to fix.
 */
export const RANGE_PRESETS = Object.freeze([
  { value: '7d', label: 'Last 7 days', days: 7 },
  { value: '30d', label: 'Last 30 days', days: 30 },
  { value: '90d', label: 'Last 90 days', days: 90 },
  { value: 'all', label: 'All time', days: null },
])

export const DEFAULT_RANGE = '30d'

/**
 * `yyyy-MM-dd` in local time.
 *
 * `toISOString()` would be wrong here: it converts to UTC first, so for anyone west of Greenwich
 * an evening export reports tomorrow's date.
 */
export function toIsoDate(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/**
 * Turns a preset into the inclusive `{ from, to }` the API expects.
 *
 * <p>All-time sends no bounds at all rather than a very old `from`, so the server keeps treating
 * it as an unfiltered query — which also means attributions with no recorded `occurredAt` stay
 * included in the default view rather than silently vanishing.
 *
 * @param {string} value a RANGE_PRESETS value
 * @param {Date} [today] reference date; injectable so the arithmetic is testable
 */
export function rangeToParams(value, today = new Date()) {
  const preset = RANGE_PRESETS.find((option) => option.value === value)
  if (!preset || preset.days === null) {
    return {}
  }
  const to = new Date(today)
  const from = new Date(today)
  // `days - 1` so "last 7 days" spans today plus the six before it — seven days of data, not
  // eight. Using `days` directly is the classic off-by-one that quietly inflates every window.
  from.setDate(from.getDate() - (preset.days - 1))
  return { from: toIsoDate(from), to: toIsoDate(to) }
}

/** The human label for a preset, for captions and empty states. */
export function rangeLabel(value) {
  return RANGE_PRESETS.find((option) => option.value === value)?.label || ''
}
