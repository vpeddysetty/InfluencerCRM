/**
 * What to do first, on the screen a new workspace lands on (roadmap PR-02).
 *
 * <p>Signing up produces a correct, complete and entirely empty workspace. Every page already has a
 * considered empty state, but each answers "why is this list empty?" in isolation; none answers the
 * question a new user actually has, which is what to do first and in what order. This is that
 * answer, and it is the difference between a product someone evaluates and one they abandon.
 *
 * <p>Lives in `packages/ui` so the shell and any remote share ONE copy — the drift that shipped a
 * blank section editor for two days is the reason `remoteCopies.test.mjs` exists, and a new
 * component belongs on the shared side of that line.
 *
 * <p><b>It disappears.</b> Once every step is done, or once the workspace has real revenue, it
 * renders nothing at all. Guidance that outstays its usefulness is read as clutter, and clutter on
 * the dashboard is how people learn to stop reading the dashboard.
 *
 * <p><b>The order is load-bearing</b> and lives in `shell/activation.js` with the tests that pin it.
 * This component renders that decision; it does not make it.
 */
export default function ActivationChecklist({ state }) {
  if (!state || state.complete) return null

  const { steps, done, total, next } = state
  const pct = Math.round((done / total) * 100)

  return (
    <section className="activation" aria-labelledby="activation-heading">
      <header className="activation__head">
        <div>
          <h3 id="activation-heading">Get your first sale attributed</h3>
          <p className="activation__sub">
            {/* The count is the reassurance: five short steps, and you can see how far in you are.
                "Setup" with no end in sight is what people bounce off. */}
            {done} of {total} done — about ten minutes end to end.
          </p>
        </div>
        <div
          className="activation__meter"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={done}
          aria-label={`${done} of ${total} setup steps complete`}
        >
          <span className="activation__meter-fill" style={{ inlineSize: `${pct}%` }} />
        </div>
      </header>

      <ol className="activation__steps">
        {steps.map((step) => {
          const isNext = next && step.id === next.id
          return (
            <li
              key={step.id}
              className={`activation__step${step.done ? ' is-done' : ''}${isNext ? ' is-next' : ''}`}
            >
              {/* The tick is decorative: the state is already carried by the class and, for a
                  screen reader, by the visually-hidden word below. A bare ✓ read aloud between two
                  sentences is noise. */}
              <span className="activation__tick" aria-hidden="true">{step.done ? '✓' : '○'}</span>
              <span className="activation__body">
                <span className="activation__label">
                  <span className="visually-hidden">{step.done ? 'Done: ' : 'To do: '}</span>
                  {step.label}
                </span>
                {/* Shown only on the step being pointed at. Five rationales at once is a wall of
                    text; one, on the thing you are about to do, is a reason. */}
                {isNext ? <span className="activation__why">{step.why}</span> : null}
              </span>
              {/* A plain anchor, not a router Link and not a button with a handler.
                  `packages/ui` lives outside every project root, so an import of react-router-dom
                  from here does not resolve at build time -- and a hard navigation to an in-app
                  route costs one reload on a screen someone visits a handful of times, while
                  keeping what a button would throw away: middle-click, open-in-new-tab, and the
                  destination visible on hover. */}
              {isNext ? (
                <a className="primary-btn activation__cta" href={step.route}>
                  {step.cta}
                </a>
              ) : null}
            </li>
          )
        })}
      </ol>
    </section>
  )
}
