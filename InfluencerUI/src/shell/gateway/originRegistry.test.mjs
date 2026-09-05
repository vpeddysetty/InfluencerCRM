import test from 'node:test'
import assert from 'node:assert/strict'
import { federationRemotes, ORIGIN_REGISTRY } from './originRegistry.js'

/**
 * Where the gateway looks for each remote (roadmap OP-43).
 *
 * <p>The bug this guards: `import.meta.env` is populated for APPLICATION code and EMPTY inside
 * `vite.config.js`. This module is imported by both, so called from the config every origin
 * silently became its localhost fallback — and the shell shipped to production wired to
 * `http://localhost:5176`, with no error, no warning, and no request to a remote origin. The
 * deployed page rendered perfectly while missing every control that lives in a remote.
 *
 * <p>A build gate could not catch it and neither could a render check: both pass on a shell that
 * serves its own bundled pages. Only asking "which origin did the config compile in" does.
 */

test('an explicit env source is used in preference to import.meta.env', () => {
  // This is the path vite.config.js takes, via loadEnv. If it ever stops working the config falls
  // back to localhost and production silently serves the shell's own pages again.
  const remotes = federationRemotes({
    VITE_MF_CREATORS_ORIGIN: 'https://creators.tejdux.com',
    VITE_MF_COMMERCE_ORIGIN: 'https://commerce.tejdux.com',
  })

  assert.equal(remotes.mf_creators.entry, 'https://creators.tejdux.com/remoteEntry.js')
  assert.equal(remotes.mf_commerce.entry, 'https://commerce.tejdux.com/remoteEntry.js')
})

test('a remote with no configured origin still falls back to its dev port', () => {
  // The fallback is correct for `npm run dev`; it is only wrong when it reaches a production build,
  // which is what passing the env source prevents.
  const remotes = federationRemotes({ VITE_MF_CREATORS_ORIGIN: 'https://creators.tejdux.com' })

  assert.equal(remotes.mf_creators.entry, 'https://creators.tejdux.com/remoteEntry.js')
  assert.match(remotes.mf_workflow.entry, /localhost:\d+\/remoteEntry\.js$/)
})

test('every registry entry names a scope and an origin', () => {
  // A missing scope fails at runtime with an opaque module-resolution error, which is the same
  // class of silent failure as the one above.
  for (const entry of ORIGIN_REGISTRY) {
    assert.ok(entry.scope, `entry for ${entry.context} must name a federation scope`)
    assert.ok(entry.origin, `entry for ${entry.context} must name an origin`)
    assert.ok(Object.keys(entry.exposes || {}).length > 0, `${entry.scope} must expose something`)
  }
})

test('no two remotes share a federation scope', () => {
  // Two entries with one scope resolve to whichever the reducer saw last, silently.
  const scopes = ORIGIN_REGISTRY.map((e) => e.scope)
  assert.equal(new Set(scopes).size, scopes.length, `duplicate scope in ${scopes.join(', ')}`)
})
