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
  return text
    // PR-39. The shell keeps shared modules in `shell/`; the remote has no such directory and
    // holds them at its own root. Collapse that FIRST — otherwise the generic `../` rule below
    // turns `../shell/x` into `./shell/x`, which the remote does not have.
    .replace(/from '\.\.\/shell\//g, "from './")
    .replace(/from '\.\.\//g, "from './")
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

test('permission checks use the wire key, not the server-side enum name', () => {
  // `can()` is handed the permission strings from the JWT, which are colon-style (`content:write`).
  // The Java enum CONSTANT name (`CONTENT_WRITE`) never crosses the wire, so checking for it always
  // returns false — and because these gates only DISABLE controls, the failure is silent: the whole
  // authoring form renders greyed out with no error, for a user who has every permission.
  //
  // Caught in production on 2026-08-24: an OWNER with all 33 permissions could not press Generate.
  for (const file of [
    resolve(SHELL, 'components/CampaignPageGenerator.jsx'),
    resolve(SHELL, 'pages/ContentPage.jsx'),
    resolve(REMOTE, 'components/CampaignPageGenerator.jsx'),
    resolve(REMOTE, 'ContentPage.jsx'),
  ]) {
    const text = read(file)
    assert.doesNotMatch(
      text,
      /can\(\s*['"][A-Z_]+['"]\s*\)/,
      `${file} calls can() with an ENUM_NAME; the JWT carries colon-style keys like content:write`,
    )
  }
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

test('the content remote carries the same section editor', () => {
  // PR-39. The editor is where the "cannot look wrong" rules live — which fields exist, what
  // reordering does, what a template switch discards. A drift here is not cosmetic: the two
  // copies would offer different editing behaviour, and only the remote's is served in production.
  //
  // Compared BELOW the header comment, like remoteCopies.test.mjs does: each copy's header
  // explains its own side of the duplication, so those legitimately differ. Everything after
  // must match, modulo the one import path the remote cannot express.
  const body = (path) => {
    const text = read(path)
    return text.slice(text.indexOf('*/') + 2).trim()
  }
  assert.equal(
    body(resolve(SHELL, 'components/SectionEditor.jsx')).replace("from '../shell/sectionTypes'", "from '../sectionTypes.js'"),
    body(resolve(REMOTE, 'components/SectionEditor.jsx')),
    'InfluencerContentUI/src/components/SectionEditor.jsx has drifted — copy the change across',
  )
})

test('both API clients call the same section-editor endpoints', () => {
  // Same reasoning as the generation endpoint below: the api/content.js pair is a deliberate fork,
  // so only the paths are pinned. A remote calling an endpoint the BFF does not serve fails in
  // production only, and only for this one context.
  for (const path of [resolve(SHELL, 'api/content.js'), resolve(REMOTE, 'api/content.js')]) {
    const text = read(path)
    assert.match(text, /\/api\/landing-templates\/editor/, `${path} should read the editor mode`)
    assert.match(text, /\/api\/brand-page-templates/, `${path} should call the saved-template endpoints`)
  }
})

test('the content remote carries the same collaborator panel', () => {
  // PR-42. The panel decides what a brand may do to a handoff in progress — whether the "hand
  // over" button appears at this stage, whether "take it back" is offered, and whether the
  // one-time invitation link is shown when delivery failed. A drift here means the two copies
  // offer different actions, and only the remote's is served in production.
  //
  // These two are byte-identical: the panel imports only `./ui`, which both copies hold at the
  // same depth, so unlike SectionEditor there is no import path to normalize.
  assert.equal(
    read(resolve(SHELL, 'components/CollaboratorPanel.jsx')),
    read(resolve(REMOTE, 'components/CollaboratorPanel.jsx')),
    'InfluencerContentUI/src/components/CollaboratorPanel.jsx has drifted — copy the change across',
  )
})

test('both API clients call the same collaboration and invitation endpoints', () => {
  // The handoff is ONE endpoint on purpose (a grant, a stage change and a turn change that only
  // mean anything together), so a copy that drifted into calling the three underlying endpoints
  // separately would reintroduce exactly the partial-handoff state the single call prevents.
  for (const path of [resolve(SHELL, 'api/content.js'), resolve(REMOTE, 'api/content.js')]) {
    const text = read(path)
    assert.match(text, /\/handoff/, `${path} should hand off in one call`)
    assert.match(text, /\/take-back/, `${path} should offer take-back`)
    assert.match(text, /\/api\/creator-invites/, `${path} should call the invitation endpoints`)
  }
})

test('the remote copies actually import what they use', () => {
  // Found the hard way (PR-44): the remote's SectionEditor.jsx had lost its ENTIRE import block
  // while still referencing useState, useEffect and SECTION_TYPES. Vite built it without a word --
  // bundlers do not resolve undefined globals -- so it would have been a blank screen the first
  // time a creator opened the editor, in production only, since VITE_USE_REMOTES=true means the
  // remote is the copy that gets served.
  //
  // The comparison above could not catch it: it deliberately starts BELOW the header comment so
  // each copy can explain its own side of the duplication, and the imports sit above that.
  const files = [
    'components/SectionEditor.jsx',
    'components/CollaboratorPanel.jsx',
    'components/CampaignPageGenerator.jsx',
  ]
  for (const file of files) {
    const text = read(resolve(REMOTE, file))
    if (/\buseState\b|\buseEffect\b|\buseMemo\b|\buseRef\b|\buseCallback\b/.test(text)) {
      assert.match(text, /^import .*from 'react'/m, `${file} uses hooks but does not import them`)
    }
    if (/\bSECTION_TYPES\b|\bblankSection\b|\bsectionIssues\b/.test(text)) {
      assert.match(text, /^import \{[\s\S]*?\} from '\.\.\/sectionTypes/m,
        `${file} uses sectionTypes but does not import it`)
    }
  }
})
