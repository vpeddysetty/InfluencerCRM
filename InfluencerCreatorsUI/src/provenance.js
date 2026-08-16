/**
 * How a metric came to be on screen, as data rather than markup.
 *
 * <p><b>COPY of `InfluencerUI/src/shell/provenance.js`.</b> This remote has no `shell/` and cannot
 * import across project roots, so the module is duplicated until the shared UI layer is extracted to
 * `@influencer/ui` as `components/ui/index.js` describes. If the vocabulary below changes, change it
 * in both places — a `metrics_source` value that renders one way in the gateway's bundled fallback
 * and another way in this remote is worse than either alone.
 *
 * <p>Plain `.js` rather than living beside the component: this is the logic that decides whether a
 * follower count is presented as measured or simulated, and getting it wrong misrepresents a
 * creator's audience. The repo's test runner is bare `node --test` with no JSX loader, so logic
 * worth asserting has to sit in a module Node can import — the same reason `shell/dateRange.js`
 * exists.
 */

/**
 * Provenance vocabulary, matching the `metrics_source` values the BFF writes.
 *
 * <p>Tones are deliberate. `platform_api` is the only positive claim; `mock` is a warning because
 * acting on a simulated audience size is a real mistake; `manual` and `import` are neutral because
 * a number a human supplied is neither measured by us nor invented by us.
 */
export const PROVENANCE = Object.freeze({
  platform_api: { tone: 'success', label: 'Platform verified', title: 'Read from the platform API.' },
  mock: { tone: 'warning', label: 'Simulated', title: 'Generated for demonstration — not a real audience size.' },
  manual: { tone: 'neutral', label: 'Entered by hand', title: 'Typed in by someone on your team.' },
  import: { tone: 'neutral', label: 'Imported', title: 'Came from a spreadsheet you uploaded.' },
})

/**
 * Describes a source value.
 *
 * <p>An unrecognised source renders verbatim rather than being hidden: a metric carrying a
 * provenance nobody planned for is still a fact, and hiding it would be the one outcome worse
 * than showing it.
 */
export function describeProvenance(source) {
  const key = String(source || '').trim().toLowerCase()
  return PROVENANCE[key] || { tone: 'neutral', label: key || 'Unknown', title: 'Provenance was not recorded.' }
}

/** Whether a source string means "a platform actually answered". */
export function isPlatformVerified(source) {
  return String(source || '').trim().toLowerCase() === 'platform_api'
}

/**
 * Relative age of a metric, in words.
 *
 * <p>A real number of unknown age is its own trust problem: a follower count read eight months ago
 * is not evidence of anything today. Rounded deliberately — the decision turns on "3 days ago",
 * and a precise timestamp would imply a precision the refresh cadence does not have.
 *
 * @param {string} value ISO timestamp
 * @param {number} [now] epoch ms; injectable so the arithmetic is testable
 */
export function formatFetchedAt(value, now = Date.now()) {
  if (!value) {
    return ''
  }
  const then = new Date(value).getTime()
  if (Number.isNaN(then)) {
    return ''
  }
  const seconds = Math.round((now - then) / 1000)
  // Clock skew between browser and server can make a fresh read look future-dated. "Just now" is
  // the honest reading of a small negative, rather than "in -3 seconds".
  if (seconds < 60) {
    return 'just now'
  }
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) {
    return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  }
  const hours = Math.round(minutes / 60)
  if (hours < 24) {
    return `${hours} hour${hours === 1 ? '' : 's'} ago`
  }
  const days = Math.round(hours / 24)
  if (days < 30) {
    return `${days} day${days === 1 ? '' : 's'} ago`
  }
  const months = Math.round(days / 30)
  return `${months} month${months === 1 ? '' : 's'} ago`
}
