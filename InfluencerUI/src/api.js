function unwrapList(payload) {
  if (Array.isArray(payload)) {
    return payload
  }

  if (payload && Array.isArray(payload.items)) {
    return payload.items
  }

  return []
}

function buildHeaders(token, extraHeaders = {}) {
  const headers = {
    ...extraHeaders,
  }

  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  return headers
}

async function readResponse(response) {
  const text = await response.text()
  const data = text ? JSON.parse(text) : null

  if (!response.ok) {
    const message = data?.message || data?.error || `Request failed with status ${response.status}`
    throw new Error(message)
  }

  return data
}

async function request(path, { method = 'GET', token, body, headers, isFormData = false } = {}) {
  const response = await fetch(path, {
    method,
    headers: buildHeaders(
      token,
      isFormData ? headers : { 'Content-Type': 'application/json', ...headers },
    ),
    body: body == null ? undefined : isFormData ? body : JSON.stringify(body),
  })

  return readResponse(response)
}

export async function signup(payload) {
  return request('/api/auth/signup', { method: 'POST', body: payload })
}

export async function login(payload) {
  return request('/api/auth/login', { method: 'POST', body: payload })
}

export async function logout(accessToken) {
  return request('/api/auth/logout', { method: 'POST', body: { accessToken } })
}

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

export async function updateCampaignCreatorStage(token, id, stage) {
  return request(`/api/campaign-creators/${id}/stage`, { method: 'PATCH', token, body: { stage } })
}

export async function discoverImport(token, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request('/api/import-batches/discover', { method: 'POST', token, body: formData, isFormData: true })
}

export async function updateImportColumnMapping(token, id, columnMapping) {
  return request(`/api/import-batches/${id}/column-mapping`, {
    method: 'PATCH',
    token,
    body: { columnMapping },
  })
}

export async function previewImportBatch(token, id, rows) {
  return request(`/api/import-batches/${id}/preview`, {
    method: 'POST',
    token,
    body: { rows, dryRun: true },
  })
}

export async function hydrateImportBatch(token, id, rows) {
  return request(`/api/import-batches/${id}/hydrate`, {
    method: 'POST',
    token,
    body: { rows, dryRun: false },
  })
}

export async function generateAgentColumnMapping(token, id) {
  return request(`/api/import-batches/${id}/agent-column-mapping`, {
    method: 'POST',
    token,
  })
}