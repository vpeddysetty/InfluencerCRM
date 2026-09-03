import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import { federation } from '@module-federation/vite'

/**
 * Content & Landing: briefs, drafting, landing templates.
 *
 * Served from its own origin and consumed by the presentation gateway. React is shared as a
 * singleton because two copies of React in one page break hooks — the classic federation failure,
 * and the reason `singleton: true` is not optional.
 *
 * This remote never authenticates. It receives session facts and an `authorizedFetch` capability
 * from the gateway, so no token is ever held here.
 */
export default defineConfig({
  // `@influencer/ui` is a source directory, not an npm package (packages/ui). Aliased rather than
  // installed because deploy-ui.sh runs `npm ci` per project, and a file: dependency makes that
  // fail unless every lockfile is regenerated in lockstep. Vite compiles these sources as part of
  // this project's own build, exactly as it compiled the copy that used to live here.
  resolve: {
    // packages/ui lives OUTSIDE this project root, so Rolldown looks for `react` in a node_modules
    // beside those sources and finds none. dedupe pins the resolution to this project's copy --
    // which is also what federation needs, since two Reacts in one page break hooks.
    // react-qr-code joins the list for the same reason react does: ShareSheet imports it from
    // packages/ui, which lives OUTSIDE this project root, so Rolldown looks for it in a
    // node_modules beside those sources and finds none. Deduping pins it to this project's copy.
    dedupe: ['react', 'react-dom', 'react-qr-code'],
    alias: {
      '@influencer/ui': fileURLToPath(new URL('../packages/ui/src', import.meta.url)),
    },
  },
  plugins: [
    react(),
    federation({
      name: 'mf_content',
      filename: 'remoteEntry.js',
      // JavaScript project: the plugin's TypeScript declaration generation has nothing to read.
      dts: false,
      exposes: {
        './ContentPage': './src/ContentPage.jsx',
      },
      shared: {
        react: { singleton: true, requiredVersion: '^19.0.0' },
        'react-dom': { singleton: true, requiredVersion: '^19.0.0' },
        'react-router-dom': { singleton: true },
      },
    }),
  ],
  // Federation's runtime uses top-level await.
  build: { target: 'esnext' },
  server: { port: 5179, strictPort: true, cors: true },
  preview: { port: 5179, strictPort: true },
})
