// Mount CreatorsPage for real (roadmap: "a passing vite build is not evidence a page works").
// A build proves the module parses; a ReferenceError from an out-of-scope const appears only on
// render, which is why this uses Vite's SSR runner rather than a build gate.
import { createServer } from 'vite'
import { renderToString } from 'react-dom/server'
import React from 'react'

const server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
try {
  const mod = await server.ssrLoadModule('/src/CreatorsPage.jsx')
  const CreatorsPage = mod.default

  const creators = [
    { id: '1', name: 'Bea', handle: 'bea', platform: 'instagram', niche: 'beauty', followerCount: 120000, vettingStatus: 'approved' },
    { id: '2', name: 'Fitz', handle: 'fitz', platform: 'tiktok', niche: 'fitness', followerCount: 5000, vettingStatus: 'pending' },
  ]

  const html = renderToString(
    React.createElement(CreatorsPage, {
      creators,
      creatorForm: { name: '', handle: '', platform: 'instagram' },
      setCreatorForm: () => {},
      customAttributesToPairs: () => [],
      onCreateCreator: async () => {},
      onUpdateCreator: async () => {},
      onLookupHandle: async () => ({}),
      onDeleteCreator: async () => {},
      onLoadAlsoAt: async () => ({ alsoAt: [] }),
    }),
  )
  console.log('RENDER OK, html length', html.length)
  for (const marker of ['All niches', 'Any audience', 'Any status', 'All platforms']) {
    console.log('  contains "' + marker + '":', html.includes(marker))
  }
} catch (err) {
  console.log('RENDER FAILED:', err.constructor.name, '-', err.message)
  console.log((err.stack || '').split('\n').slice(1, 5).join('\n'))
  process.exitCode = 1
} finally {
  await server.close()
}
