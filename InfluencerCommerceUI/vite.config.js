import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { federation } from '@module-federation/vite'

/**
 * Attribution & Commerce: coupons, marketplaces, revenue dashboard.
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
      name: 'mf_commerce',
      filename: 'remoteEntry.js',
      // JavaScript project: the plugin's TypeScript declaration generation has nothing to read.
      dts: false,
      exposes: {
        './CouponsPage': './src/CouponsPage.jsx',
        './MarketplacePage': './src/MarketplacePage.jsx',
        './DashboardPage': './src/DashboardPage.jsx',
        './PortfolioPage': './src/PortfolioPage.jsx',
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
  server: { port: 5177, strictPort: true, cors: true },
  preview: { port: 5177, strictPort: true },
})
