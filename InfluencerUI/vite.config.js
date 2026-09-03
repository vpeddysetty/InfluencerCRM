import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import { federation } from '@module-federation/vite'
import { federationRemotes } from './src/shell/gateway/originRegistry.js'

/**
 * The presentation gateway (federation host).
 *
 * One origin the user actually visits. It authenticates once, holds the session, and pulls each
 * bounded context's UI from that context's own origin — so many deployables look like one
 * application and there is exactly one login.
 *
 * Remotes come from `originRegistry.js` rather than being listed here, so moving one to a CDN or a
 * preview environment is a config change and the registry cannot drift from what the shell loads.
 *
 * React is shared as a singleton: two copies in one page break hooks, and that is also what lets a
 * remote read the gateway's React context despite being served from a different origin.
 */
const useRemotes = process.env.VITE_USE_REMOTES === 'true'

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
    ...(useRemotes
      ? [
          federation({
            name: 'shell',
            dts: false,
            remotes: federationRemotes(),
            shared: {
              react: { singleton: true, requiredVersion: '^19.0.0' },
              'react-dom': { singleton: true, requiredVersion: '^19.0.0' },
              'react-router-dom': { singleton: true },
            },
          }),
        ]
      : []),
  ],
  build: { target: 'esnext' },
  server: {
    proxy: {
      // The BFF listens on 8081. This pointed at 18081, where nothing listens, so every
      // /api call — including the login the shell still routes this way — failed with a
      // 502 and the app could not be signed into from the browser at all.
      '/api': {
        target: process.env.VITE_BFF_URL || 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
