import { Badge } from './Primitives'
import { describeProvenance, formatFetchedAt, isPlatformVerified } from '../../shell/provenance'

/**
 * How a follower count came to be on screen.
 *
 * <p>Every metric in this product was hash-derived until M6, and the difference between a measured
 * number and a simulated one was recorded in `metrics_source` but visible only in a CSV export —
 * outside the app, in a file that goes to the customer's client. Influencer fraud is a top-three
 * buyer objection, so "which of these numbers is real" is a question the product should answer on
 * screen rather than in a spreadsheet.
 *
 * <p>The vocabulary and the age arithmetic live in `shell/provenance.js` so they can be tested:
 * this file is the markup around them.
 */

/**
 * A single provenance pill.
 *
 * <p>Renders nothing when there is no source AND no metric to qualify: a creator nobody has looked
 * up yet should not carry a badge saying so. But a metric WITH no recorded source does get
 * "Unknown", because that combination is the one worth noticing.
 */
export function MetricsSourceBadge({ source, hasMetrics = true }) {
  if (!source && !hasMetrics) {
    return null
  }
  const { tone, label, title } = describeProvenance(source)
  return <Badge tone={tone}><span title={title}>{label}</span></Badge>
}

/**
 * Provenance plus age, for a record page or a drawer.
 *
 * <p>The two belong together: "platform verified" and "eight months ago" are each half of whether
 * a number can be trusted, and showing either alone is misleading in a different direction.
 */
export function MetricsProvenance({ source, fetchedAt, hasMetrics = true }) {
  if (!source && !fetchedAt && !hasMetrics) {
    return null
  }
  const age = formatFetchedAt(fetchedAt)
  return (
    <span className="metrics-provenance">
      <MetricsSourceBadge source={source} hasMetrics={hasMetrics} />
      {age ? <span className="metrics-provenance-age">checked {age}</span> : null}
    </span>
  )
}

// Re-exported so call sites can import everything provenance-related from the ui barrel.
export { formatFetchedAt, isPlatformVerified }

export default MetricsProvenance
