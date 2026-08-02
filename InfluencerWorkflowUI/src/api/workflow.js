import { request, unwrapList } from './core'

// Collaboration Workflow context: boards, stages, cards.
// Split out in Phase 6 so each micro-frontend owns its own API surface rather than
// importing one 68-function module and pulling in every other context with it.

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

// ---- coupons / campaign codes ----
