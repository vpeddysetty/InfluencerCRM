// Mount a remote's exposed pages and fail if any throws (roadmap OP-42, OP-43).
//
// WHY THIS EXISTS. `vite build` proves a module PARSES. It says nothing about whether the component
// RUNS: a const referenced before its declaration, a hook called in a loop, or a destructured prop
// read off undefined all compile cleanly and throw on the first render. Two such faults shipped to
// production behind a green build, and the five remotes without any test script had nothing at all
// standing between a runtime fault and a customer demo.
//
// WHY IT IS SHARED. Five remotes need the same check and none had a test harness. A copy per
// project is five things to keep in step -- and this file lives in `packages/ui`, which every
// remote already aliases as `@influencer/ui`, so there is one implementation and a two-line
// `render-check.mjs` per project naming what to mount.
//
// PROPS ARE SUPPLIED BY CONVENTION, not per component. The pages take between 6 and 18 props and
// hand-writing nine prop sets would rot the first time one changed signature. Anything named
// `onX`/`loadX` becomes an async no-op, anything plural becomes an empty array, and everything else
// becomes an empty object -- enough for a component to mount and render its empty state, which is
// the state a runtime fault shows up in anyway.
// react-dom, react and vite are passed IN rather than imported here.
//
// Node resolves a bare specifier from the importing file's directory, and this file lives in
// packages/ui, which has no node_modules of its own -- so importing them here fails with
// "Cannot find package 'react-dom'" even though every remote has one. The caller is inside a
// project that does, so it hands them over.

/**
 * @param {object} options
 * @param {string} options.root         the project directory to build in
 * @param {Array<{name: string, path: string, props?: object}>} options.pages
 * @param {object} options.deps  `{ React, renderToString, createServer }`, resolved by the caller
 */
export async function renderCheck({ root, pages, deps }) {
  const { React, renderToString, createServer } = deps
  const server = await createServer({
    root,
    server: { middlewareMode: true },
    appType: 'custom',
    logLevel: 'error',
  })

  let failed = 0
  try {
    for (const page of pages) {
      try {
        const mod = await server.ssrLoadModule(page.path)
        const Component = mod.default
        if (typeof Component !== 'function') {
          console.log(`  FAIL  ${page.name}: no default export`)
          failed++
          continue
        }
        const html = renderToString(React.createElement(Component, defaultProps(Component, page.props)))
        console.log(`  ok    ${page.name}  (${html.length} bytes)`)
      } catch (err) {
        // The line and component name matter more than the stack: this is read by whoever just
        // broke it, and "ReferenceError at CreatorsPage.jsx:140" is the whole answer.
        console.log(`  FAIL  ${page.name}: ${err.constructor.name} — ${err.message.split('\n')[0]}`)
        const frame = (err.stack || '').split('\n')[1]
        if (frame) console.log(`        ${frame.trim()}`)
        failed++
      }
    }
  } finally {
    await server.close()
  }

  if (failed > 0) {
    console.log(`\n  ${failed} page(s) failed to render.`)
    process.exitCode = 1
  } else {
    console.log(`\n  All ${pages.length} page(s) render.`)
  }
}

/**
 * Safe stand-ins for whatever a component destructures.
 *
 * <p>Read off the function's own parameter list rather than guessed: a component that destructures
 * `{ campaigns, onSave }` gets an array and an async no-op without anyone maintaining a fixture.
 * Explicit `props` win, for the cases where a component genuinely needs a shape.
 */
function defaultProps(Component, explicit = {}) {
  const source = Component.toString()
  const match = source.match(/^\s*function[^(]*\(\s*\{([^}]*)\}/) || source.match(/^\s*\(\s*\{([^}]*)\}/)
  const props = {}
  if (match) {
    for (const raw of match[1].split(',')) {
      const name = raw.split('=')[0].split(':')[0].trim()
      if (!name || name.startsWith('...')) continue
      props[name] = stand(name)
    }
  }
  return { ...props, ...explicit }
}

function stand(name) {
  // A callback: async, resolving to something a caller can read a field off without throwing.
  if (/^(on|load|fetch|handle|set)[A-Z]/.test(name)) return async () => ({})
  // A collection: the empty state, which is where a first-render fault surfaces anyway.
  if (/s$/.test(name) && !/status$/i.test(name)) return []
  if (/^(can|is|has|show|allow)[A-Z]/.test(name)) return false
  if (/Id$/.test(name) || /^(id|title|label|name)$/i.test(name)) return ''
  return {}
}
