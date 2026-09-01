import { useEffect, useState } from 'react'

/**
 * What happened to a published page (roadmap PR-57).
 *
 * <p>`landing_page_views` has recorded a row per public render since the feature shipped, and
 * nothing has ever displayed one — a brand who published a page could not learn whether anyone
 * arrived, and the `performance_tracking` stage was reachable with nothing behind it. This is that
 * missing half.
 *
 * <p>Lives in `packages/ui` rather than in either app, so the shell and the ContentUI remote share
 * ONE copy. `remoteCopies.test.mjs` exists because duplicated modules drifted and the section
 * editor shipped broken for two days; a new component belongs on the shared side of that line.
 *
 * <p>Takes a bound `loadAnalytics(campaignId, days)` rather than a token, matching how every
 * other data dependency reaches this page: the host owns the session, the component owns the
 * display.
 *
 * <p><b>It says VIEWS, everywhere, deliberately.</b> The log is anonymous by design — no IP, no
 * session, no device — so "visitors" is a number this data cannot honestly produce, and a label
 * implying it would be read as one. Counting page loads is what the table supports, and naming it
 * exactly is the difference between a modest true number and a confident wrong one.
 */
export default function LandingAnalytics({ campaignId, loadAnalytics }) {
  const [days, setDays] = useState(30)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!campaignId || !loadAnalytics) return
    let cancelled = false
    setLoading(true)
    setError(null)
    loadAnalytics(campaignId, days)
      .then((result) => {
        // The request that finished last is not necessarily the one the user is waiting for:
        // switching 7 -> 30 -> 7 quickly can land them out of order. Ignoring a cancelled effect's
        // result is what stops an older window overwriting a newer one.
        if (!cancelled) setData(result)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Could not load the view counts.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => { cancelled = true }
  }, [campaignId, days, loadAnalytics])

  const total = data?.totalViews ?? 0
  const byCreator = data?.byCreator ?? []
  const byDay = data?.byDay ?? []
  const peak = byDay.reduce((max, d) => Math.max(max, d.views || 0), 0)

  return (
    <section className="lp-analytics" aria-labelledby="lp-analytics-heading">
      <header className="lp-analytics__head">
        <h3 id="lp-analytics-heading">Page views</h3>
        <div className="lp-analytics__range" role="group" aria-label="Time range">
          {[7, 30, 90].map((option) => (
            <button
              key={option}
              type="button"
              className={option === days ? 'is-selected' : ''}
              aria-pressed={option === days}
              onClick={() => setDays(option)}
            >
              {option} days
            </button>
          ))}
        </div>
      </header>

      {error && <p className="lp-analytics__error" role="alert">{error}</p>}

      {loading && !data && <p className="lp-analytics__muted">Counting…</p>}

      {data && (
        <>
          <p className="lp-analytics__total">
            <strong>{total.toLocaleString()}</strong>{' '}
            {/* Not "visitors". The log cannot tell two loads by one person from two people. */}
            {total === 1 ? 'view' : 'views'} in the last {data.windowDays} days
          </p>

          {/* A page with no codes is not a page with no traffic, and the server distinguishes
              them. Repeating its note rather than rendering an empty table that reads as zero. */}
          {data.note && <p className="lp-analytics__muted">{data.note}</p>}

          {total === 0 && !data.note && (
            <p className="lp-analytics__muted">
              No views yet. Views appear here once someone opens the published page.
            </p>
          )}

          {byCreator.length > 0 && (
            <table className="lp-analytics__table">
              <caption className="lp-analytics__caption">Views by creator code</caption>
              <thead>
                <tr>
                  <th scope="col">Code</th>
                  <th scope="col">Views</th>
                  <th scope="col">Share</th>
                </tr>
              </thead>
              <tbody>
                {byCreator.map((row) => (
                  <tr key={row.creatorId}>
                    <td>{row.code || 'Unknown code'}</td>
                    <td>{row.views.toLocaleString()}</td>
                    <td>
                      {/* Guarded: total is 0 only when this list is empty, but a divide-by-zero
                          rendering NaN% on a brand's report is a bad way to find that out. */}
                      {total > 0 ? Math.round((row.views / total) * 100) : 0}%
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {byDay.length > 0 && (
            <div className="lp-analytics__days">
              <h4 className="lp-analytics__subhead">By day</h4>
              <ol className="lp-analytics__bars">
                {byDay.map((day) => (
                  <li key={day.date}>
                    <span className="lp-analytics__date">{day.date}</span>
                    <span
                      className="lp-analytics__bar"
                      // Proportional to the busiest day, so the shape is readable whether the
                      // peak is 4 views or 4,000. A fixed scale would flatten every real page.
                      style={{ inlineSize: peak > 0 ? `${Math.max(2, (day.views / peak) * 100)}%` : '0%' }}
                      aria-hidden="true"
                    />
                    <span className="lp-analytics__count">{day.views.toLocaleString()}</span>
                  </li>
                ))}
              </ol>
            </div>
          )}
        </>
      )}
    </section>
  )
}
