import { request, unwrapList } from './core'

// Campaign context: campaigns and briefs.
// Split out in Phase 6 so each micro-frontend owns its own API surface rather than
// importing one 68-function module and pulling in every other context with it.

export async function listCampaigns(token) {
  const payload = await request('/api/campaigns', { token })
  return unwrapList(payload)
}

export async function createCampaign(token, payload) {
  return request('/api/campaigns', { method: 'POST', token, body: payload })
}

export async function updateCampaign(token, id, payload) {
  return request(`/api/campaigns/${id}`, { method: 'PUT', token, body: payload })
}

export async function listCampaignBriefs(token, { campaignId } = {}) {
  const query = campaignId ? `?campaignId=${encodeURIComponent(campaignId)}` : ''
  const payload = await request(`/api/campaign-briefs${query}`, { token })
  return unwrapList(payload)
}

export async function createCampaignBrief(token, payload) {
  return request('/api/campaign-briefs', { method: 'POST', token, body: payload })
}

export async function updateCampaignBrief(token, id, payload) {
  return request(`/api/campaign-briefs/${id}`, { method: 'PUT', token, body: payload })
}

// ---- landing templates (content Phase 2) ----
