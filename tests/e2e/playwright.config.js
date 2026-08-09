import { defineConfig, devices } from '@playwright/test'

/**
 * Recorded end-to-end journeys.
 *
 * <p>VIDEO IS THE DELIVERABLE, so `video: 'on'` rather than 'retain-on-failure'. A passing run has
 * to produce a watchable artifact — the point is to show the journeys working, not only to catch
 * regressions.
 *
 * <p>ONE WORKER, NOT PARALLEL. Two reasons, both learned the hard way in recorded suites: parallel
 * videos interleave into something nobody can follow, and these journeys share one backend, so
 * concurrent runs create and delete each other's rows. Slower, but the artifact is usable.
 *
 * <p>720p rather than the 800x600 default: the workspace has a sidebar and a data table, and at
 * the default the table is cropped out of the frame — a video of a layout nobody uses.
 */
const VIEWPORT = { width: 1280, height: 720 }

export default defineConfig({
  testDir: '.',
  // A recorded journey is slower than an assertion-only one: it waits on real animations and real
  // network. The default 30s times out on the workspace load on a cold backend.
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'results.json' }]],
  outputDir: 'artifacts',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    viewport: VIEWPORT,
    video: { mode: 'on', size: VIEWPORT },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 20_000,
    // The stack uses self-signed certs locally; without this every HTTPS hop fails.
    ignoreHTTPSErrors: true,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: VIEWPORT } },
  ],
})
