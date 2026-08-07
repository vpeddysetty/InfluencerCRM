import { Link } from 'react-router-dom'

/**
 * First-session checklist.
 *
 * Empty states told a new user what they did not have; none of them said what to do about it.
 * This is the one surface that sequences the first session, so it renders above the workspace
 * content until every step is done — then disappears permanently rather than lingering as a
 * dismissed-forever banner someone has to tidy away.
 *
 * Steps are derived from workspace data rather than stored progress flags: a user who imported
 * a sheet in a previous session should not see step 2 outstanding because a flag was missed.
 */
function GettingStarted({ creatorCount = 0, campaignCount = 0, cardCount = 0, importedCount = 0 }) {
  const steps = [
    {
      key: 'workspace',
      label: 'Create your workspace',
      done: true,
      hint: 'Done when you signed up.',
    },
    {
      key: 'import',
      label: 'Bring in your creators',
      done: importedCount > 0 || creatorCount > 0,
      hint: 'Import the spreadsheet you already keep them in.',
      action: { to: '/import', label: 'Import a spreadsheet' },
    },
    {
      key: 'campaign',
      label: 'Set up a campaign',
      done: campaignCount > 0,
      hint: 'A campaign is what you tie creators to.',
      action: { to: '/campaigns', label: 'Create a campaign' },
    },
    {
      key: 'board',
      label: 'Move someone through your board',
      done: cardCount > 0,
      hint: 'Outreach through to paid — this is the daily view.',
      action: { to: '/workflow', label: 'Open the board' },
    },
  ]

  const doneCount = steps.filter((step) => step.done).length
  if (doneCount === steps.length) {
    return null
  }

  // The first unfinished step is the only one offering an action, so the checklist reads as a
  // sequence rather than four competing calls to action.
  const nextStep = steps.find((step) => !step.done)

  return (
    <section className="getting-started" aria-label="Getting started">
      <div className="getting-started-header">
        <h3>Get started</h3>
        <span className="getting-started-progress">
          {doneCount} of {steps.length} done
        </span>
      </div>
      <ol className="getting-started-steps">
        {steps.map((step) => {
          const isNext = step.key === nextStep?.key
          return (
            <li
              key={step.key}
              className={`getting-started-step${step.done ? ' is-done' : ''}${isNext ? ' is-next' : ''}`}
            >
              <span className="getting-started-check" aria-hidden="true">
                {step.done ? '✓' : '○'}
              </span>
              <span className="getting-started-label">
                {step.label}
                {isNext ? <span className="getting-started-hint">{step.hint}</span> : null}
              </span>
              {isNext && step.action ? (
                <Link className="primary-btn getting-started-action" to={step.action.to}>
                  {step.action.label}
                </Link>
              ) : null}
            </li>
          )
        })}
      </ol>
    </section>
  )
}

export default GettingStarted
