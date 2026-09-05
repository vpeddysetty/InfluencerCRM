// Mounts this remote's exposed pages. See packages/ui/renderCheck.mjs for why a build gate is not
// enough: `vite build` proves the module parses, not that the component runs.
//
// react/react-dom/vite are resolved HERE and passed in, because the shared harness sits in
// packages/ui, which has no node_modules of its own.
import { fileURLToPath } from 'node:url'
import React from 'react'
import { renderToString } from 'react-dom/server'
import { createServer } from 'vite'
import { renderCheck } from '../../packages/ui/renderCheck.mjs'

await renderCheck({
  root: fileURLToPath(new URL('..', import.meta.url)),
  deps: { React, renderToString, createServer },
  pages: [
    { name: 'CampaignsPage', path: '/src/CampaignsPage.jsx' },
    { name: 'ImportPage', path: '/src/ImportPage.jsx' },
  ],
})
