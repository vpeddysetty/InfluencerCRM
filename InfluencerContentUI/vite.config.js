import { defineConfig } from 'vite'
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
