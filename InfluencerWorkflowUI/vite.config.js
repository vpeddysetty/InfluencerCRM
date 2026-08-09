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
