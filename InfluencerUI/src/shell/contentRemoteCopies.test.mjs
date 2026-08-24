import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * The content remote keeps its own copies of the campaign-page authoring UI (roadmap PR-35).
 *
 * <p>Same reason as `remoteCopies.test.mjs` next door: `InfluencerContentUI` is a separate Vite
 * project that cannot import across project roots, and production runs `VITE_USE_REMOTES=true`, so
 * the page a signed-in brand loads is the remote's copy. A fix applied only to the bundled copy in
 * `pages/` works perfectly in local dev and changes nothing in production — which is precisely the
 * failure mode that makes these duplicated pages worth a test at all.
 *
 * <p>Unlike the creators pair, these two files are byte-identical apart from **import depth**: the
 * shell's copies sit one directory deeper (`src/pages/`, `src/components/`) than the remote's
 * (`src/`, `src/components/`), so relative imports climb one more level. The comparison normalizes
 * that one difference and demands everything else match.
 *
 * <p>When this fails, the fix is to copy the change across — not to relax the assertion. The real
 * repair remains extracting `@influencer/ui`.
 */

const here = dirname(fileURLToPath(import.meta.url))
const SHELL = resolve(here, '..')
const REMOTE = resolve(here, '../../../InfluencerContentUI/src')

function read(path) {
  return readFileSync(path, 'utf8').replace(/\r\n/g, '\n').trim()
}

/**
 * The shell's `pages/` copy reaches shared modules with `../`; the remote's root copy uses `./`.
 * Normalizing here rather than in the source keeps both files idiomatic for where they live.
 */
function withRemoteImportDepth(text) {
  return text.replace(/from '\.\.\//g, "from './")
}

test('the content remote carries the same campaign page', () => {
  assert.equal(
    withRemoteImportDepth(read(resolve(SHELL, 'pages/ContentPage.jsx'))),
    read(resolve(REMOTE, 'ContentPage.jsx')),
    'InfluencerContentUI/src/ContentPage.jsx has drifted from pages/ContentPage.jsx — copy the change across',
  )
})

test('the content remote carries the same page generator', () => {
  // Both live in a `components/` directory at the same depth, so these need no normalization.
  assert.equal(
    read(resolve(SHELL, 'components/CampaignPageGenerator.jsx')),
    read(resolve(REMOTE, 'components/CampaignPageGenerator.jsx')),
    'InfluencerContentUI/src/components/CampaignPageGenerator.jsx has drifted — copy the change across',
  )
})

test('both API clients expose campaign-page generation against the same endpoint', () => {
  // The two api/content.js files are a deliberate fork (each remote owns its API surface), so
  // they are not compared wholesale. What must not drift is the endpoint itself: a remote calling
  // a path the BFF does not serve fails only in production, and only for that one context.
  for (const path of [resolve(SHELL, 'api/content.js'), resolve(REMOTE, 'api/content.js')]) {
    assert.match(
      read(path),
      /generateCampaignPage[\s\S]*?\/api\/campaign-pages\/generate/,
      `${path} should call the campaign-page generation endpoint`,
    )
  }
})
