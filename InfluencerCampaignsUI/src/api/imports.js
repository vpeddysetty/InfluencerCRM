import { request, unwrapList } from './core'

// Campaign context: spreadsheet import and hydration.
// Split out in Phase 6 so each micro-frontend owns its own API surface rather than
// importing one 68-function module and pulling in every other context with it.

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
