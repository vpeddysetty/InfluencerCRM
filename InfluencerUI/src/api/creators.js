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

/**
 * Look up a handle on its platform without saving anything (C.2).
 *
 * <p>Reads only. The BFF answers with `resolved: false` and a `reason` for a private account, a
 * typo or a deleted profile rather than failing — all three are ordinary, and the caller is
 * expected to fall back to typing the details in. So a rejected promise here means the request
 * itself failed, not that the handle was not found.
 *
 * <p>Whatever comes back carries `metricsSource`, and it has to stay attached: the same shape
 * describes a number Instagram answered with and one the simulation generated, and the badge
 * that tells them apart is the only thing standing between the two.
 */
export async function resolveCreatorHandle(token, { platform, handle }) {
  return request('/api/creators/resolve-handle', {
    method: 'POST',
    token,
    body: { platform, handle },
  })
}

export async function updateCreator(token, id, payload) {
  return request(`/api/creators/${id}`, { method: 'PUT', token, body: payload })
}

/**
 * Remove a creator from this brand's list (MANAGER and above).
 *
 * <p>The BFF checks CREATOR_DELETE and then that the row belongs to the caller's brand, answering
 * 404 rather than 403 for another tenant's id — confirming an id exists is itself a disclosure. So
 * a 404 here means "not yours or not there", and neither is worth distinguishing to the caller.
 *
 * <p>Deletes the creator for THIS brand only. The same person under another brand is a separate
 * row with its own rate, notes and score, and is untouched.
 */
export async function deleteCreator(token, id) {
  return request(`/api/creators/${id}`, { method: 'DELETE', token })
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

/**
 * Which of the caller's OTHER brands work with this same creator (roadmap PR-66).
 *
 * Scoped server-side to brands the caller already reaches — there is no brand parameter here
 * because there is nothing for a client to choose.
 */
export async function getCreatorAlsoAt(token, creatorId) {
  return request(`/api/creators/${creatorId}/also-at`, { token })
}
