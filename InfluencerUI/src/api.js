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

// ---- workflow boards ----
export async function listWorkflowBoards(token) {
  const payload = await request('/api/workflow-boards', { token })
  return unwrapList(payload)
}

export async function createWorkflowBoard(token, payload) {
  return request('/api/workflow-boards', { method: 'POST', token, body: payload })
}

export async function updateWorkflowBoard(token, id, payload) {
  return request(`/api/workflow-boards/${id}`, { method: 'PUT', token, body: payload })
}

export async function deleteWorkflowBoard(token, id) {
  return request(`/api/workflow-boards/${id}`, { method: 'DELETE', token })
}

// ---- workflow board stages ----
export async function listWorkflowBoardStages(token, boardId) {
  const query = boardId ? `?boardId=${encodeURIComponent(boardId)}` : ''
  const payload = await request(`/api/workflow-board-stages${query}`, { token })
  return unwrapList(payload)
}

export async function replaceWorkflowBoardStages(token, payload) {
  const response = await request('/api/workflow-board-stages/replace', {
    method: 'PUT',
    token,
    body: payload,
  })
  return unwrapList(response)
}

// ---- workflow cards ----
export async function listWorkflowCards(token) {
  const payload = await request('/api/workflow-cards', { token })
  return unwrapList(payload)
}

export async function createWorkflowCard(token, payload) {
  return request('/api/workflow-cards', { method: 'POST', token, body: payload })
}

export async function updateWorkflowCard(token, id, payload) {
  return request(`/api/workflow-cards/${id}`, { method: 'PUT', token, body: payload })
}

export async function placeWorkflowCard(token, id, { boardId, stageId, position }) {
  return request(`/api/workflow-cards/${id}/placement`, {
    method: 'PUT',
    token,
    body: { boardId: boardId || null, stageId: stageId || null, position: position ?? null },
  })
}

export async function deleteWorkflowCard(token, id) {
  return request(`/api/workflow-cards/${id}`, { method: 'DELETE', token })
}

export async function discoverImport(token, file) {
  const formData = new FormData()
  formData.append('file', file)
  return request('/api/import-batches/discover', { method: 'POST', token, body: formData, isFormData: true })
}

export async function discoverImports(token, files) {
  const formData = new FormData()
  files.forEach((file) => {
    formData.append('files', file)
  })
  return request('/api/import-batches/discover-multi', { method: 'POST', token, body: formData, isFormData: true })
}

export async function listImportBatches(token) {
  const payload = await request('/api/import-batches', { token })
  return unwrapList(payload)
}

export async function getImportBatch(token, id) {
  return request(`/api/import-batches/${id}`, { token })
}

export async function getImportBatchColumns(token, id) {
  return request(`/api/import-batches/${id}/columns`, { token })
}

export async function deleteImportBatch(token, id) {
  return request(`/api/import-batches/${id}/delete`, { method: 'POST', token })
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