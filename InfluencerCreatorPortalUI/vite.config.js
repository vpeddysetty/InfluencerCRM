import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The creator portal (roadmap PR-43).
 *
 * DELIBERATELY NOT A MODULE-FEDERATION REMOTE, unlike the six under InfluencerUI. Those are
 * consumed by the shell and receive session facts from it. This is a standalone site on its own
 * domain, for people who have no account in that shell at all:
 *
 *   - `App.jsx` in the shell assumes an operator bearer token, an accountId and a brand switcher.
 *     A creator has none of those, so every one would grow an "unless creator" branch.
 *   - It talks to the BFF DIRECTLY rather than through the DPS. Adding a CREATOR_PORTAL app to
 *     the registry is not merely insufficient but structurally wrong: `scope()` intersects against
 *     account_role permissions a creator provably lacks and returns empty for every creator, and
 *     `ApiProxyController` attaches an operator access token where a creator needs
 *     X-Creator-Token.
 *
 * The cost of going direct is that the token lives in JS rather than an httpOnly cookie. That is a
 * documented trade, not an oversight — see docs/Creator-Handoff-Design.md §4.
 */
export default defineConfig({
  plugins: [react()],
  server: { port: 5180, strictPort: true },
  build: { outDir: 'dist', sourcemap: false },
})
