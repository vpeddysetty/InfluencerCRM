import { request, unwrapList } from './core'

// Content & Landing context: templates, previews, drafting.
// Split out in Phase 6 so each micro-frontend owns its own API surface rather than
// importing one 68-function module and pulling in every other context with it.

export async function listLandingTemplates(token, { campaignId } = {}) {
  const query = campaignId ? `?campaignId=${encodeURIComponent(campaignId)}` : ''
  const payload = await request(`/api/landing-templates${query}`, { token })
  return unwrapList(payload)
}

export async function saveLandingTemplate(token, payload) {
  return request('/api/landing-templates/save', { method: 'POST', token, body: payload })
}

// Returns rendered HTML (text, not JSON) for the live builder preview.

export async function previewLandingTemplate(token, payload) {
  const response = await fetch('/api/landing-templates/preview', {
    method: 'POST',
    headers: buildHeaders(token, { 'Content-Type': 'application/json' }),
    body: JSON.stringify(payload),
  })
  const text = await response.text()
  if (!response.ok) {
    throw new Error(`Preview failed with status ${response.status}`)
  }
  return text
}

// ---- version history (Phase A.5) ----

export async function listLandingVersions(token, campaignId) {
  const payload = await request(
    `/api/landing-templates/versions?campaignId=${encodeURIComponent(campaignId)}`,
    { token },
  )
  return unwrapList(payload)
}

// Restoring writes the old content forward as a NEW version rather than rewinding, so
// the history of what was undone survives. The restored page comes back as a draft.
export async function restoreLandingVersion(token, campaignId, versionNo) {
  return request(`/api/landing-templates/versions/${versionNo}/restore`, {
    method: 'POST',
    token,
    body: { campaignId },
  })
}

// ---- content draft assist (content Phase 4) ----

export async function draftContent(token, payload) {
  return request('/api/content/draft', { method: 'POST', token, body: payload })
}

// ---- commissions & payouts ----
