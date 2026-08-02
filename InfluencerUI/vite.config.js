import { defineConfig } from 'vite'
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
