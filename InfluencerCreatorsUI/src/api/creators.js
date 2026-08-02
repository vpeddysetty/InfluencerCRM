import { request, unwrapList } from './core'

// Creator context: creators and their campaign assignments.
// Split out in Phase 6 so each micro-frontend owns its own API surface rather than
// importing one 68-function module and pulling in every other context with it.

export async function listCreators(token) {
  const payload = await request('/api/creators', { token })
  return unwrapList(payload)
}

export async function createCreator(token, payload) {
  return request('/api/creators', { method: 'POST', token, body: payload })
}

export async function updateCreator(token, id, payload) {
  return request(`/api/creators/${id}`, { method: 'PUT', token, body: payload })
}

export async function listCampaignCreators(token) {
  const payload = await request('/api/campaign-creators', { token })
  return unwrapList(payload)
}

export async function createCampaignCreator(token, payload) {
  return request('/api/campaign-creators', { method: 'POST', token, body: payload })
}

export async function updateCampaignCreator(token, id, payload) {
  return request(`/api/campaign-creators/${id}`, { method: 'PUT', token, body: payload })
}

// ---- workflow boards ----
