import { Badge } from './Primitives'
import {
  describePlan,
  describeUsage,
  formatUsage,
  usageMessage,
  usageMeterAria,
  usageTone,
} from '../../shell/plan'

/**
 * What this account's plan includes, and how much of it is spent (roadmap M2.3).
 *
 * <p><b>Why this exists.</b> The server enforces plan limits and refuses with 402. Enforcement
 * alone would mean a user meets their plan for the first time by being blocked mid-task, which
 * reads as a bug rather than a boundary. This panel is the difference between "you have 2 creator
 * slots left" and an error at the moment they were trying to get something done.
 *
 * <p>All the arithmetic is in `shell/plan.js` so it can be tested under the repo's bare
 * `node --test` runner; this file is the markup around it.
 */
export function PlanUsage({ plan, usage = [], loading = false, error = '' }) {
  const { label, nextTier } = describePlan(plan)

  if (loading) {
    return <p className="helper">Loading plan…</p>
  }

  if (error) {
    // Says what is unknown rather than implying a limit. Rendering "0 of 25" from a failed read
    // would be a fabricated number about the user's own account.
    return (
      <p className="helper">
        Your plan could not be loaded, so the figures below are unavailable. Limits are still
        enforced — if something is refused, this is why.
      </p>
    )
  }

  const rows = usage.map(describeUsage)

  return (
    <div className="plan-usage">
      <div className="plan-usage-header">
        <span className="plan-usage-name">
          <strong>{label} plan</strong>
        </span>
        {nextTier ? (
          <span className="plan-usage-next helper">{nextTier} lifts these limits.</span>
        ) : null}
      </div>

      <ul className="plan-usage-list">
        {rows.map((row) => {
          const message = usageMessage(row, plan)
          // Null for unlimited rows — see usageMeterAria. Spreading null would throw, so the
          // fallback is an empty object and those rows stay plain text.
          const meter = usageMeterAria(row)
          return (
            <li key={row.resource} className="plan-usage-row">
              <span className="plan-usage-label">{row.label}</span>
              <Badge tone={usageTone(row)} {...(meter || {})}>
                {formatUsage(row)}
              </Badge>
              {/* Only rows worth commenting on carry a line. A note on every row is noise, and
                  noise is what makes the row that matters invisible. */}
              {message ? <span className="plan-usage-message helper">{message}</span> : null}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

export default PlanUsage
