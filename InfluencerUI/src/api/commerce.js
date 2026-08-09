import { request, unwrapList } from './core'

// Attribution & Commerce context: coupons, marketplaces, revenue.
// Split out in Phase 6 so each micro-frontend owns its own API surface rather than
// importing one 68-function module and pulling in every other context with it.

export async function listCoupons(token, { campaignId, creatorId } = {}) {
  const params = new URLSearchParams()
  if (campaignId) params.set('campaignId', campaignId)
  if (creatorId) params.set('creatorId', creatorId)
  const query = params.toString() ? `?${params.toString()}` : ''
  const payload = await request(`/api/influencer-campaign-codes${query}`, { token })
  return unwrapList(payload)
}

export async function createCoupon(token, payload) {
  return request('/api/influencer-campaign-codes', { method: 'POST', token, body: payload })
}

export async function updateCoupon(token, id, payload) {
  return request(`/api/influencer-campaign-codes/${id}`, { method: 'PUT', token, body: payload })
}

export async function deleteCoupon(token, id) {
  return request(`/api/influencer-campaign-codes/${id}`, { method: 'DELETE', token })
}

export async function generateCoupon(token, payload) {
  return request('/api/coupons/generate', { method: 'POST', token, body: payload })
}

export async function generateCouponsBulk(token, payload) {
  const response = await request('/api/coupons/generate-bulk', { method: 'POST', token, body: payload })
  return unwrapList(response)
}

export async function pushCoupon(token, id, { connectionId } = {}) {
  return request(`/api/coupons/${id}/push`, {
    method: 'POST',
    token,
    body: connectionId ? { connectionId } : {},
  })
}

export async function personalizeCoupon(token, id, payload) {
  return request(`/api/coupons/${id}/personalize`, { method: 'POST', token, body: payload })
}

export async function decideCouponPersonalization(token, id, decision) {
  return request(`/api/coupons/${id}/personalization/${decision}`, { method: 'POST', token, body: {} })
}

// ---- marketplace connections ----

export async function listMarketplaceProviders(token) {
  const payload = await request('/api/marketplace-providers', { token })
  return unwrapList(payload)
}

export async function listMarketplaceConnections(token) {
  const payload = await request('/api/marketplace-connections', { token })
  return unwrapList(payload)
}

export async function connectMarketplace(token, payload) {
  return request('/api/marketplace-connections/connect', { method: 'POST', token, body: payload })
}

export async function deleteMarketplaceConnection(token, id) {
  return request(`/api/marketplace-connections/${id}`, { method: 'DELETE', token })
}

// ---- attribution / analytics ----

export async function simulateOrder(token, payload) {
  return request('/api/attribution/simulate', { method: 'POST', token, body: payload })
}

/**
 * Attributed revenue, optionally narrowed to a date window.
 *
 * `from`/`to` are inclusive `yyyy-MM-dd` strings. Omitting both asks for all time, which is what
 * the dashboard requested before it had a range control.
 */
export async function getInfluencerRevenue(token, { from, to } = {}) {
  const params = new URLSearchParams()
  if (from) {
    params.set('from', from)
  }
  if (to) {
    params.set('to', to)
  }
  const query = params.toString()
  return request(`/api/analytics/influencer-revenue${query ? `?${query}` : ''}`, { token })
}

// ---- campaign briefs (content Phase 1) ----
