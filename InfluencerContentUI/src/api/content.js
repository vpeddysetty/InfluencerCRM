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

// ---- Curated section editor (PR-39) ----

// Which editor this DEPLOYMENT serves: `sections` or `builder`. Read at runtime rather than
// compiled into the bundle, because the flag's value is that flipping it is a variable change and
// an instance refresh — a build-time constant would need a rebuild and a cache invalidation.
export async function loadLandingEditorMode(token) {
  const payload = await request('/api/landing-templates/editor', { token })
  return payload?.editor === 'sections' ? 'sections' : 'builder'
}

export async function listPageTemplates(token) {
  return unwrapList(await request('/api/brand-page-templates', { token }))
}

export async function savePageTemplate(token, payload) {
  return request('/api/brand-page-templates', { method: 'POST', token, body: payload })
}

export async function deletePageTemplate(token, id) {
  return request(`/api/brand-page-templates/${encodeURIComponent(id)}`, { method: 'DELETE', token })
}

// ---- AI campaign-page generation (PR-35) ----

// Always resolves with drafts on a 2xx: the server substitutes a template draft rather than
// failing, and reports which generator ran via `generator` / `fallback` on the payload. The UI
// shows that distinction rather than presenting a template draft as an AI one.
export async function generateCampaignPage(token, brief) {
  return request('/api/campaign-pages/generate', { method: 'POST', token, body: brief })
}

// Rewrite one section of a draft. Always resolves on a 2xx: `rewritten: false` with a `detail`
// means the generator had no suggestion, which is an answer rather than a failure — the caller's
// own text is untouched either way.
export async function rewriteCampaignPageSection(token, payload) {
  return request('/api/campaign-pages/sections/rewrite', { method: 'POST', token, body: payload })
}

// One more draft, skipping headlines already on screen. Returns zero variants when the generator
// has nothing new — not an error, just nothing further to offer.
export async function regenerateCampaignPageVariant(token, payload) {
  return request('/api/campaign-pages/variants/regenerate', { method: 'POST', token, body: payload })
}

// Schedule / cancel a timed publish. `publishAt` is an ISO-8601 instant in UTC; the server
// refuses a past time rather than publishing immediately, since "9am" typed after 9am is far more
// likely a wrong date than a request to go live now.
export async function scheduleLandingPublish(token, templateId, publishAt) {
  return request(`/api/landing-pages/${encodeURIComponent(templateId)}/schedule`, {
    method: 'PUT', token, body: { publishAt },
  })
}

export async function cancelLandingPublishSchedule(token, templateId) {
  return request(`/api/landing-pages/${encodeURIComponent(templateId)}/schedule`, {
    method: 'DELETE', token,
  })
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
