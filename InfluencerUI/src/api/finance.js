import { request, unwrapList } from './core'

// Payouts & Finance context: commissions and payout batches.
// Split out in Phase 6 so each micro-frontend owns its own API surface rather than
// importing one 68-function module and pulling in every other context with it.

export async function listCommissions(token, { status } = {}) {
  const query = status ? `?status=${encodeURIComponent(status)}` : ''
  const payload = await request(`/api/influencer-commissions${query}`, { token })
  return unwrapList(payload)
}

export async function approveCommission(token, id) {
  return request(`/api/influencer-commissions/${id}/approve`, { method: 'POST', token, body: {} })
}

export async function listPayouts(token) {
  const payload = await request('/api/influencer-payouts', { token })
  return unwrapList(payload)
}

export async function createPayoutBatch(token, payload) {
  return request('/api/influencer-payouts/create', { method: 'POST', token, body: payload })
}

export async function listPayoutProviders(token) {
  const payload = await request('/api/payout-providers', { token })
  return unwrapList(payload)
}
