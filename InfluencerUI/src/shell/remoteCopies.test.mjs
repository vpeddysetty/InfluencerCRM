import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * The creators remote keeps its own copies of two modules this directory owns.
 *
 * <p>It has to: `InfluencerCreatorsUI` is a separate Vite project with no `shell/` and no way to
 * import across project roots, and production runs with `VITE_USE_REMOTES=true` — so the page a
 * signed-in brand actually loads comes from the remote, not from the bundled fallback beside it.
 * A fix applied only here would appear to work in development and change nothing in production.
 *
 * <p>These tests compare the two copies below their header comments, which are allowed to differ
 * because each explains its own side of the duplication. Everything after that must match. When
 * one of these fails, the fix is to copy the change across — not to relax the assertion.
 *
 * <p>The real repair is extracting the shared UI layer to `@influencer/ui`, which
 * `components/ui/index.js` already describes as the intended next step. Until then this is the
 * only thing standing between the fork and a silent divergence.
 */

const here = dirname(fileURLToPath(import.meta.url))
const REMOTE = resolve(here, '../../../InfluencerCreatorsUI/src')
const CONTENT_REMOTE = resolve(here, '../../../InfluencerContentUI/src')
const WORKFLOW_REMOTE = resolve(here, '../../../InfluencerWorkflowUI/src')
const CAMPAIGNS_REMOTE = resolve(here, '../../../InfluencerCampaignsUI/src')

/** Everything from the first line after the module's opening block comment. */
function bodyOf(path) {
  const text = readFileSync(path, 'utf8').replace(/\r\n/g, '\n')
  const end = text.indexOf('*/')
  assert.ok(end !== -1, `${path} should open with a block comment`)
  return text.slice(end + 2).trim()
}

test('the creators remote carries the same handle-lookup logic', () => {
  assert.equal(
    bodyOf(resolve(REMOTE, 'handleLookup.js')),
    bodyOf(resolve(here, 'handleLookup.js')),
    'InfluencerCreatorsUI/src/handleLookup.js has drifted from shell/handleLookup.js — copy the change across',
  )
})

test('the creators remote carries the same provenance vocabulary', () => {
  assert.equal(
    bodyOf(resolve(REMOTE, 'provenance.js')),
    bodyOf(resolve(here, 'provenance.js')),
    'InfluencerCreatorsUI/src/provenance.js has drifted from shell/provenance.js — copy the change across',
  )
})

test('the section vocabulary is not copied at all any more', () => {
  // PR-44 / OP-19. This used to compare two copies of sectionTypes.js and fail when they drifted.
  // They no longer exist: the module lives once in packages/ui and both projects alias it, so
  // drift is impossible rather than detected.
  //
  // The test is inverted rather than deleted, because a future "the remote should own its own
  // copy" change would otherwise silently reintroduce exactly the duplication that made the
  // section editor blank in production for two days.
  for (const gone of [
    resolve(CONTENT_REMOTE, 'sectionTypes.js'),
    resolve(CONTENT_REMOTE, 'pageTemplates.js'),
    resolve(CONTENT_REMOTE, 'components/SectionEditor.jsx'),
  ]) {
    assert.equal(existsSync(gone), false,
      `${gone} is back. These modules live once in packages/ui — re-copying them is what OP-19 fixed.`)
  }
})

test('the workflow remote carries the same activation logic', () => {
  // PR-02. The checklist renders on DEFAULT_ROUTE, and production serves that route from the
  // REMOTE -- so a fix applied only to the shell copy would work perfectly in dev and do nothing
  // for the users it exists for. That is the exact failure this file was created after.
  //
  // The ORDER of the steps is the opinion being guarded: "connect your store" before "add a
  // creator" sends someone to an integration that cannot pay off yet, and two copies drifting on
  // that would give two different answers to the only question a new user is asking.
  assert.equal(
    bodyOf(resolve(WORKFLOW_REMOTE, 'activation.js')),
    bodyOf(resolve(here, 'activation.js')),
    'InfluencerWorkflowUI/src/activation.js has drifted from shell/activation.js — copy the change across',
  )
})

test('the campaigns remote carries the same sample import file', () => {
  // PR-02. Production serves the import page from the REMOTE, so a sample offered only by the
  // shell is one nobody downloads. The columns are the load-bearing part: they are the ones
  // agent_service's mapper recognises, and a drifted copy would hand someone a file that needs
  // correcting on the very first upload -- teaching exactly the wrong lesson.
  assert.equal(
    bodyOf(resolve(CAMPAIGNS_REMOTE, 'sampleImport.js')),
    bodyOf(resolve(here, 'sampleImport.js')),
    'InfluencerCampaignsUI/src/sampleImport.js has drifted from shell/sampleImport.js — copy the change across',
  )
})
