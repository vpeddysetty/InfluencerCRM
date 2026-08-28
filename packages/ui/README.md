# `@influencer/ui`

The UI that more than one project needs, held **once**.

## Why this exists

`InfluencerUI` is the shell and the six micro-frontends are separate Vite projects that cannot
import across project roots. The workaround was to keep byte-identical copies of the shared
modules in each project, with tests (`remoteCopies.test.mjs`, `contentRemoteCopies.test.mjs`)
failing when they drifted.

That worked until it did not. `OP-19`: the content remote's `SectionEditor.jsx` lost its entire
import block while still referencing `useState`, `useEffect` and `SECTION_TYPES`. Vite built it
without a word — bundlers do not resolve undefined globals — so the section editor was **blank in
production for every brand from 2026-08-25 until it was found**, while working perfectly in local
dev, because `VITE_USE_REMOTES=true` means only production loads the remote's copy.

The guard could not catch it: it compares the two copies *below* their header comments, so each can
explain its own side of the duplication, and imports live above that line.

So the modules that bug lived in are held here once. Drift is now impossible rather than detected.

## Why a source directory and a Vite alias, not an npm package

`infrastructure/scripts/deploy-ui.sh` runs `npm ci` in each project independently. A `file:../..`
dependency makes that fail unless every lockfile is regenerated in lockstep, so a real package means
either adopting npm workspaces — restructuring eight projects and the deploy script — or publishing
to a registry.

CLAUDE.md's guidance on this debt is explicit: *"do it inside other stories, never as a sprint."* An
alias achieves the one property that matters (one copy on disk) with no lockfile changes, no
`npm ci` risk, and no change to how anything deploys. Each project's Vite build compiles these
sources as its own, exactly as it compiled its former copy.

## Using it

In a project's `vite.config.js`:

```js
resolve: {
  alias: { '@influencer/ui': fileURLToPath(new URL('../packages/ui/src', import.meta.url)) },
}
```

Then `import { SECTION_TYPES } from '@influencer/ui/sectionTypes.js'`.

Node's test runner does not read Vite's aliases, so `.test.mjs` files import these by relative path.
That is why the tests here sit beside the source rather than in a project.

## What is deliberately NOT here

The duplicated **pages** — `ContentPage.jsx` and its siblings — are still copied per project. They
genuinely differ in structure rather than being identical modules, and they are the larger, harder
half. Extracting the editor and the section vocabulary is what removes the bug that actually
occurred; the pages remain guarded by the existing copy tests.
