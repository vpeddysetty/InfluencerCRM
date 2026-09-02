import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { federation } from '@module-federation/vite'

/**
 * The Workflow micro-frontend remote.
 *
 * Exposes the page the shell's route manifest points at. React is shared as a singleton because
 * two copies of React in one page break hooks — the classic federation failure, and the reason
 * `singleton: true` is not optional here.
 */
export default defineConfig({
  // `@influencer/ui` is a source directory, not an npm package (packages/ui). Aliased rather than
  // installed because deploy-ui.sh runs `npm ci` per project, and a file: dependency makes that
  // fail unless every lockfile is regenerated in lockstep. Same arrangement as ContentUI.
  resolve: {
    // packages/ui lives OUTSIDE this project root, so Rolldown looks for `react` in a node_modules
    // beside those sources and finds none. dedupe pins the resolution to this project's copy --
    // which is also what federation needs, since two Reacts in one page break hooks.
    dedupe: ['react', 'react-dom'],
    alias: {
      '@influencer/ui': fileURLToPath(new URL('../packages/ui/src', import.meta.url)),
    },
  },
  plugins: [
    react(),
    federation({
      name: 'mf_workflow',
      filename: 'remoteEntry.js',
      exposes: {
        './WorkflowPage': './src/WorkflowPage.jsx',
      },
      // This is a JavaScript project; the plugin's TypeScript declaration generation has
      // nothing to read and fails on the missing tsconfig.
      dts: false,
      shared: {
        react: { singleton: true, requiredVersion: '^19.0.0' },
        'react-dom': { singleton: true, requiredVersion: '^19.0.0' },
        'react-router-dom': { singleton: true },
      },
    }),
  ],
  build: {
    // Federation requires a modern target: the runtime uses top-level await.
    target: 'esnext',
  },
  server: { port: 5174, strictPort: true, cors: true },
  preview: { port: 5174, strictPort: true },
})
