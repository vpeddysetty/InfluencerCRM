import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { federation } from '@module-federation/vite'

/**
 * The shell (host).
 *
 * Consumes context remotes declared below. React is shared as a singleton because two copies of
 * React in one page break hooks — the classic federation failure and the reason this is not
 * optional.
 *
 * Remotes are opt-in via VITE_USE_REMOTES: with it unset the shell renders its local pages, so a
 * remote that is down or mid-deploy cannot take the whole app with it. That fallback is what makes
 * federation safe to adopt incrementally rather than as a big-bang cutover.
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
            remotes: {
              mf_workflow: {
                type: 'module',
                name: 'mf_workflow',
                entry: process.env.VITE_MF_WORKFLOW_ENTRY || 'http://localhost:5174/remoteEntry.js',
              },
            },
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
      '/api': {
        target: 'http://localhost:18081',
        changeOrigin: true,
      },
    },
  },
})
