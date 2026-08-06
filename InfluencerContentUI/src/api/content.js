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

// ---- asset library (Phase B) ----

export async function listAssets(token) {
  const payload = await request('/api/assets', { token })
  return unwrapList(payload)
}

// Multipart, so this bypasses `request` (which sets a JSON content type). The browser must
// set Content-Type itself to include the multipart boundary — setting it by hand produces a
// body the server cannot parse.
export async function uploadAsset(token, file) {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch('/api/assets', {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  })
  const text = await response.text()
  if (!response.ok) {
    let message = `Upload failed with status ${response.status}`
    try {
      const parsed = JSON.parse(text)
      if (parsed?.message) message = parsed.message
    } catch { /* non-JSON error body; keep the status message */ }
    throw new Error(message)
  }
  return text ? JSON.parse(text) : null
}

export async function deleteAsset(token, id) {
  return request(`/api/assets/${id}`, { method: 'DELETE', token })
}

// ---- content draft assist (content Phase 4) ----

export async function draftContent(token, payload) {
  return request('/api/content/draft', { method: 'POST', token, body: payload })
}

// ---- commissions & payouts ----
