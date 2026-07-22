import { Navigate, Route, Routes } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'
import './App.css'
import LandingPage from './pages/LandingPage'
import ImportPage from './pages/ImportPage'
import CampaignsPage from './pages/CampaignsPage'
import CreatorsPage from './pages/CreatorsPage'
import WorkflowPage from './pages/WorkflowPage'
import WorkspaceLayout from './components/WorkspaceLayout'
import {
  createCampaign,
  createCampaignCreator,
  replaceCampaignTypeWorkflowStages,
  createCreator,
  deleteImportBatch,
  discoverImports,
  generateAgentColumnMapping,
  getImportBatch,
  getImportBatchColumns,
  hydrateImportBatch,
  listImportBatches,
  listCampaignCreators,
  listCampaigns,
  listCampaignTypeWorkflowStages,
  listCreators,
  login,
  previewImportBatch,
  logout,
  signup,
  updateCampaign,
  updateCampaignCreator,
  updateImportColumnMapping,
  updateCreator,
} from './api'
import { createImportMappingJson, createImportMappingJsonFromAgent, parseSpreadsheetFile, STAGES, stageLabels } from './constants'

const STORAGE_KEY = 'tejdux_ui_state_v1'
const CAMPAIGN_TYPE_OPTIONS = [
  { value: 'product seeding', label: 'Product Seeding' },
  { value: 'sponsored content', label: 'Sponsored Content' },
  { value: 'gifting', label: 'Gifting' },
  { value: 'affiliate campaigns', label: 'Affiliate Campaigns' },
  { value: 'brand ambassador programs', label: 'Brand Ambassador Programs' },
  { value: 'paid', label: 'Paid' },
]

const DEFAULT_IMPORT_SUMMARY = {
  batchId: '',
  filename: '',
  type: '',
  sourceFileStored: false,
  headers: [],
  rows: [],
  mappingText: '',
  mappingSaved: false,
  previewResult: null,
  hydrateResult: null,
  diagnostics: null,
  message: 'Upload CSV, XLS, or XLSX to preview mapped source columns.',
}

function loadPersistedState() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

function normalizeLoginEmail(identifier) {
  const trimmed = String(identifier || '').trim().toLowerCase()
  if (!trimmed) {
    return ''
  }
  if (trimmed.includes('@')) {
    return trimmed
  }
  return `${trimmed}@tejdux.io`
}

function parseCustomAttributesObject(value) {
  if (value == null) {
    return {}
  }

  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) {
      return {}
    }

    try {
      const parsed = JSON.parse(trimmed)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return {}
      }
      return parsed
    } catch {
      return {}
    }
  }

  if (typeof value === 'object' && !Array.isArray(value)) {
    return value
  }

  return {}
}

function customAttributesToPairs(value) {
  if (Array.isArray(value)) {
    return value
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        key: String(item.key || ''),
        value: String(item.value || ''),
        type: item.type || 'text',
      }))
  }

  const parsed = parseCustomAttributesObject(value)
  return Object.entries(parsed).map(([key, rawValue]) => ({
    key,
    value: rawValue == null ? '' : String(rawValue),
    type: typeof rawValue === 'boolean' ? 'boolean' : typeof rawValue === 'number' ? 'number' : 'text',
  }))
}

function normalizeCustomAttributesForPayload(rawValue) {
  if (rawValue == null) {
    return '{}'
  }

  if (Array.isArray(rawValue)) {
    const customAttributes = rawValue.reduce((acc, item) => {
      if (!item || typeof item !== 'object') {
        return acc
      }

      const key = String(item.key || '').trim()
      if (!key) {
        return acc
      }

      const itemType = item.type || 'text'
      const itemValue = item.value == null ? '' : String(item.value)

      if (itemType === 'boolean') {
        acc[key] = itemValue === 'true'
        return acc
      }

      if (itemType === 'number') {
        if (!itemValue.trim()) {
          acc[key] = null
          return acc
        }

        const numericValue = Number(itemValue)
        if (!Number.isFinite(numericValue)) {
          throw new Error(`Custom attribute "${key}" must be a valid number.`)
        }
        acc[key] = numericValue
        return acc
      }

      acc[key] = itemValue
      return acc
    }, {})

    return JSON.stringify(customAttributes)
  }

  if (typeof rawValue === 'string') {
    const trimmed = rawValue.trim()
    if (!trimmed) {
      return '{}'
    }

    let parsed
    try {
      parsed = JSON.parse(trimmed)
    } catch {
      throw new Error('Custom attributes must be valid JSON.')
    }

    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('Custom attributes must be a JSON object.')
    }

    return JSON.stringify(parsed)
  }

  if (typeof rawValue === 'object' && !Array.isArray(rawValue)) {
    return JSON.stringify(rawValue)
  }

  throw new Error('Custom attributes must be a JSON object.')
}

function normalizeBudgetForPayload(rawValue) {
  const text = String(rawValue ?? '').trim()
  if (!text) {
    return null
  }

  const numericValue = Number(text)
  if (!Number.isFinite(numericValue)) {
    throw new Error('Budget must be a valid number.')
  }

  return text
}

function normalizePlatformForPayload(rawValue) {
  const normalized = String(rawValue || '').trim().toLowerCase()
  if (!normalized) {
    return 'instagram'
  }
  return normalized
}

function normalizeCampaignTypeForPayload(rawValue) {
  const normalized = String(rawValue || '').trim().toLowerCase()
  if (!normalized) {
    return 'paid'
  }
  return normalized
}

function normalizeInstantDateForPayload(rawValue) {
  const text = String(rawValue || '').trim()
  if (!text) {
    return null
  }

  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    return `${text}T00:00:00Z`
  }

  return text
}

function normalizeTagsForState(rawValue) {
  if (Array.isArray(rawValue)) {
    return rawValue.map((tag) => String(tag || '').trim()).filter(Boolean)
  }

  return String(rawValue || '')
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean)
}

function App() {
  const persistedState = loadPersistedState()
  const initialCampaigns = persistedState?.campaigns?.length ? persistedState.campaigns : []
  const initialCreators = persistedState?.creators?.length ? persistedState.creators : []
  const initialAssignments = persistedState?.assignments?.length ? persistedState.assignments : []
  const initialCampaignTypeWorkflowStages = persistedState?.campaignTypeWorkflowStages?.length ? persistedState.campaignTypeWorkflowStages : []
  const defaultCampaignId = initialCampaigns[0]?.id || ''
  const defaultCreatorId = initialCreators[0]?.id || ''

  const [isSignUp, setIsSignUp] = useState(persistedState?.isSignUp ?? true)
  const [isLoggedIn, setIsLoggedIn] = useState(persistedState?.isLoggedIn ?? false)
  const [brandName, setBrandName] = useState(persistedState?.brandName ?? 'tejdux.io')
  const [userName, setUserName] = useState(persistedState?.userName ?? '')
  const [authToken, setAuthToken] = useState(persistedState?.authToken ?? '')
  const [userId, setUserId] = useState(persistedState?.userId ?? '')
  const [authError, setAuthError] = useState('')
  const [workspaceError, setWorkspaceError] = useState('')

  const [campaigns, setCampaigns] = useState(initialCampaigns)
  const [creators, setCreators] = useState(initialCreators)
  const [assignments, setAssignments] = useState(initialAssignments)
  const [campaignTypeWorkflowStages, setCampaignTypeWorkflowStages] = useState(initialCampaignTypeWorkflowStages)

  const [campaignForm, setCampaignForm] = useState({
    name: '',
    budget: '',
    status: 'draft',
    campaignType: CAMPAIGN_TYPE_OPTIONS[0].value,
    ...(persistedState?.campaignForm || {}),
    customAttributes: customAttributesToPairs(persistedState?.campaignForm?.customAttributes),
  })
  const [creatorForm, setCreatorForm] = useState({
    name: '',
    handle: '',
    platform: 'instagram',
    email: '',
    ...(persistedState?.creatorForm || {}),
    customAttributes: customAttributesToPairs(persistedState?.creatorForm?.customAttributes),
  })
  const [assignmentForm, setAssignmentForm] = useState(persistedState?.assignmentForm ?? {
    campaignId: defaultCampaignId,
    creatorId: defaultCreatorId,
    stage: 'outreach',
    fee: '',
    notes: '',
    dueDate: '',
    tags: '',
  })

  const [importSummary, setImportSummary] = useState(
    persistedState?.importSummary ?? DEFAULT_IMPORT_SUMMARY,
  )
  const [importBatches, setImportBatches] = useState(persistedState?.importBatches ?? [])
  const [importBatchHydrationStatus, setImportBatchHydrationStatus] = useState(persistedState?.importBatchHydrationStatus ?? {})
  const [importRowsByBatchId, setImportRowsByBatchId] = useState({})
  const [importAction, setImportAction] = useState('idle')

  const campaignById = useMemo(() => {
    return campaigns.reduce((acc, campaign) => {
      acc[campaign.id] = campaign
      return acc
    }, {})
  }, [campaigns])

  const creatorById = useMemo(() => {
    return creators.reduce((acc, creator) => {
      acc[creator.id] = creator
      return acc
    }, {})
  }, [creators])

  const workflowStagesByCampaignType = useMemo(() => {
    return (campaignTypeWorkflowStages || []).reduce((acc, item) => {
      const type = normalizeCampaignTypeForPayload(item?.campaignType)
      if (!acc[type]) {
        acc[type] = []
      }
      acc[type].push(item)
      return acc
    }, {})
  }, [campaignTypeWorkflowStages])

  const getConfiguredStagesForCampaignType = (campaignType) => {
    const type = normalizeCampaignTypeForPayload(campaignType)
    const rows = (workflowStagesByCampaignType[type] || [])
      .filter((item) => item?.isActive !== false)
      .sort((a, b) => Number(a?.position || 0) - Number(b?.position || 0))
      .map((item) => String(item?.stageKey || '').trim())
      .filter(Boolean)
    return rows
  }

  useEffect(() => {
    const selectedCampaign = campaignById[assignmentForm.campaignId]
    const selectedType = normalizeCampaignTypeForPayload(selectedCampaign?.campaignType)
    const allowedStages = getConfiguredStagesForCampaignType(selectedType)
    if (!allowedStages.length) {
      return
    }
    if (allowedStages.includes(assignmentForm.stage)) {
      return
    }
    setAssignmentForm((prev) => ({ ...prev, stage: allowedStages[0] }))
  }, [assignmentForm.campaignId, assignmentForm.stage, campaignById, workflowStagesByCampaignType])

  const refreshWorkspaceData = async () => {
    setWorkspaceError('')
    const [campaignPayload, creatorPayload, assignmentPayload, importBatchPayload, workflowStagesPayload] = await Promise.all([
      listCampaigns(authToken),
      listCreators(authToken),
      listCampaignCreators(authToken),
      listImportBatches(authToken),
      listCampaignTypeWorkflowStages(authToken),
    ])

    setCampaigns(campaignPayload)
    setCreators(creatorPayload)
    setAssignments(assignmentPayload)
    setImportBatches(importBatchPayload)
    setCampaignTypeWorkflowStages(workflowStagesPayload)
    setAssignmentForm((prev) => ({
      ...prev,
      campaignId: prev.campaignId || campaignPayload[0]?.id || '',
      creatorId: prev.creatorId || creatorPayload[0]?.id || '',
    }))
  }

  useEffect(() => {
    const snapshot = {
      isSignUp,
      isLoggedIn,
      brandName,
      userName,
      authToken,
      userId,
      campaigns,
      creators,
      assignments,
      campaignTypeWorkflowStages,
      campaignForm,
      creatorForm,
      assignmentForm,
      importSummary,
      importBatches,
      importBatchHydrationStatus,
    }
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot))
  }, [
    isSignUp,
    isLoggedIn,
    brandName,
    userName,
    authToken,
    userId,
    campaigns,
    creators,
    assignments,
    campaignTypeWorkflowStages,
    campaignForm,
    creatorForm,
    assignmentForm,
    importSummary,
    importBatches,
    importBatchHydrationStatus,
  ])

  useEffect(() => {
    if (!isLoggedIn || !authToken) {
      return
    }

    let isActive = true

    const loadWorkspace = async () => {
      try {
        await refreshWorkspaceData()

        if (!isActive) {
          return
        }
      } catch (error) {
        if (isActive) {
          setWorkspaceError(error instanceof Error ? error.message : 'Unable to load workspace data.')
        }
      }
    }

    loadWorkspace()

    return () => {
      isActive = false
    }
  }, [authToken, isLoggedIn])

  const handleAuthSubmit = async (event) => {
    const form = new FormData(event.currentTarget)
    const rawIdentifier = String(form.get('email') || '')
    const email = isSignUp ? rawIdentifier : normalizeLoginEmail(rawIdentifier)
    const inferredName = email.includes('@') ? email.split('@')[0] : email
    const name = String(form.get('fullName') || inferredName || 'Brand Operator')
    const company = String(form.get('brand') || 'tejdux.io')
    const password = String(form.get('password') || '')

    try {
      setAuthError('')
      setWorkspaceError('')

      const authResponse = isSignUp
        ? await signup({ email, password, brandName: company })
        : await login({ email, password })

      setUserName(name)
      setBrandName(authResponse.brandName || company)
      setUserId(authResponse.userId || '')
      setAuthToken(authResponse.accessToken || '')
      setIsLoggedIn(true)
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : 'Authentication failed.')
      throw error
    }
  }

  const persistImportMapping = async (mappingTextOverride) => {
    if (!importSummary.batchId) {
      throw new Error('Upload a file before saving column mappings.')
    }

    const resolvedMapping = mappingTextOverride ?? importSummary.mappingText ?? '[]'
    JSON.parse(resolvedMapping)
    await updateImportColumnMapping(authToken, importSummary.batchId, resolvedMapping)
    return resolvedMapping
  }

  const selectImportBatch = async (batchId, { messageOverride, rowsByBatchOverride } = {}) => {
    if (!batchId) {
      return
    }

    const [batch, columnsPayload] = await Promise.all([
      getImportBatch(authToken, batchId),
      getImportBatchColumns(authToken, batchId),
    ])

    const columns = Array.isArray(columnsPayload?.columns) ? columnsPayload.columns : []
    const rowLookup = rowsByBatchOverride || importRowsByBatchId
    const cachedRows = rowLookup[batchId] || []
    const cachedPreviewRows = cachedRows.slice(0, 5).map((rowObject) => columns.map((column) => rowObject[column] ?? ''))

    let mappingText = typeof batch?.columnMapping === 'string' ? batch.columnMapping : '[]'
    let mappingSaved = Boolean(mappingText && mappingText.trim() && mappingText.trim() !== '{}' && mappingText.trim() !== '[]')

    if (!mappingSaved && columns.length) {
      mappingText = createImportMappingJson(columns)
      mappingSaved = false
    }

    setImportSummary({
      batchId,
      filename: batch?.sourceFilename || columnsPayload?.sourceFilename || 'Unknown file',
      type: (batch?.sourceFilename || '').split('.').pop()?.toUpperCase() || 'FILE',
      sourceFileStored: Boolean(batch?.sourceFileStored),
      headers: columns,
      rows: cachedPreviewRows,
      mappingText,
      mappingSaved,
      previewResult: null,
      hydrateResult: null,
      diagnostics: {
        batchId,
        headerCount: columns.length,
        rowPayloadCount: cachedRows.length,
        sourceFileStored: Boolean(batch?.sourceFileStored),
        lastAction: 'select-file',
      },
      message: messageOverride
        || (cachedRows.length
          ? `Loaded ${batch?.sourceFilename || 'selected file'} for mapping and import actions.`
          : `Loaded ${batch?.sourceFilename || 'selected file'} from your import history. Re-upload this file to run preview/hydrate rows again.`),
    })
  }

  const handleImportFiles = async (fileList) => {
    const files = Array.from(fileList || [])
    if (!files.length) {
      return
    }

    try {
      setWorkspaceError('')
      setImportAction('upload')

      const parsedFiles = await Promise.all(files.map((file) => parseSpreadsheetFile(file)))
      const response = await discoverImports(authToken, files)
      const items = Array.isArray(response?.items) ? response.items : []

      const nextRowsByBatch = {}
      items.forEach((item, index) => {
        const batch = item?.importBatch || item?.batch || item
        const parsed = parsedFiles[index]
        if (batch?.id && parsed?.rowObjects) {
          nextRowsByBatch[batch.id] = parsed.rowObjects
        }
      })

      if (Object.keys(nextRowsByBatch).length) {
        setImportRowsByBatchId((prev) => ({ ...prev, ...nextRowsByBatch }))
      }

      const refreshed = await listImportBatches(authToken)
      setImportBatches(refreshed)

      const firstBatch = items[0]?.importBatch || items[0]?.batch || items[0]
      if (firstBatch?.id) {
        setImportSummary((prev) => ({
          ...prev,
          batchId: '',
          filename: '',
          headers: [],
          rows: [],
          mappingText: '',
          previewResult: null,
          hydrateResult: null,
          diagnostics: null,
          message: `Uploaded ${items.length} file${items.length === 1 ? '' : 's'}. Click a file name in the summary to view columns, visual mapper, and advanced JSON mapping editor.`,
        }))
      } else {
        setImportSummary((prev) => ({
          ...prev,
          message: 'Upload completed, but no import batch was returned by the API.',
        }))
      }
    } catch (error) {
      setImportSummary((prev) => ({
        ...prev,
        message: error instanceof Error ? error.message : 'Unable to upload files for import.',
      }))
    } finally {
      setImportAction('idle')
    }
  }

  const handleImportMappingChange = (value) => {
    setImportSummary((prev) => ({ ...prev, mappingText: value, mappingSaved: false }))
  }

  const syncImportMapping = async () => {
    const resolvedMapping = await persistImportMapping()
    setImportSummary((prev) => ({ ...prev, mappingText: resolvedMapping, mappingSaved: true }))
  }

  const handleSaveImportMapping = async () => {
    try {
      setImportAction('save-mapping')
      const resolvedMapping = await persistImportMapping()
      setImportSummary((prev) => ({
        ...prev,
        mappingText: resolvedMapping,
        mappingSaved: true,
        diagnostics: prev.diagnostics ? { ...prev.diagnostics, lastAction: 'save-mapping' } : prev.diagnostics,
        message: `Saved column mappings back to import batch ${prev.batchId}.`,
      }))
    } catch (error) {
      setImportSummary((prev) => ({
        ...prev,
        mappingSaved: false,
        diagnostics: prev.diagnostics ? { ...prev.diagnostics, lastAction: 'save-mapping-failed' } : prev.diagnostics,
        message: error instanceof Error ? error.message : 'Unable to save import column mapping.',
      }))
    } finally {
      setImportAction('idle')
    }
  }

  const handleRegenerateImportMapping = async () => {
    if (!importSummary.batchId) {
      setImportSummary((prev) => ({
        ...prev,
        message: 'Upload a file before regenerating column mappings.',
      }))
      return
    }

    try {
      setImportAction('regenerate-mapping')
      const mappingResponse = await generateAgentColumnMapping(authToken, importSummary.batchId)
      const nextMappingText = createImportMappingJsonFromAgent(
        importSummary.headers,
        mappingResponse?.mapping?.recommendations || [],
      )
      await persistImportMapping(nextMappingText)
      setImportSummary((prev) => ({
        ...prev,
        mappingText: nextMappingText,
        mappingSaved: true,
        diagnostics: prev.diagnostics ? {
          ...prev.diagnostics,
          mappingMode: 'agent_assisted',
          agentDebug: mappingResponse?.mapping?.debug || null,
          recommendationCount: mappingResponse?.mapping?.recommendations?.length || 0,
          lastAction: 'regenerate-mapping',
        } : prev.diagnostics,
        message: `Regenerated and saved agent column mappings for import batch ${prev.batchId}.`,
      }))
    } catch (error) {
      setImportSummary((prev) => ({
        ...prev,
        mappingSaved: prev.mappingSaved,
        diagnostics: prev.diagnostics ? { ...prev.diagnostics, lastAction: 'regenerate-mapping-failed' } : prev.diagnostics,
        message: error instanceof Error
          ? `${error.message} The persisted batch is still available, and the current mapping has been preserved.`
          : 'Unable to regenerate column mapping from the persisted batch.',
      }))
    } finally {
      setImportAction('idle')
    }
  }

  const handlePreviewImport = async () => {
    const selectedRows = importRowsByBatchId[importSummary.batchId] || []
    if (!importSummary.batchId || !selectedRows.length) {
      setImportSummary((prev) => ({
        ...prev,
        message: 'Upload this file in the current session before running preview. Stored file history does not include row payloads for dry-run.',
      }))
      return
    }

    try {
      setWorkspaceError('')
      setImportAction('preview')
      await syncImportMapping()
      const previewResult = await previewImportBatch(authToken, importSummary.batchId, selectedRows)
      setImportSummary((prev) => ({
        ...prev,
        previewResult,
        diagnostics: prev.diagnostics ? {
          ...prev.diagnostics,
          lastAction: 'preview',
          previewPlannedOperationCount: previewResult.plannedOperationCount || 0,
          previewCreatedCount: previewResult.createdCount || 0,
          previewUpdatedCount: previewResult.updatedCount || 0,
          previewSkippedCount: previewResult.skippedCount || 0,
        } : prev.diagnostics,
        message: `Preview calculated for ${prev.filename}. ${previewResult.plannedOperationCount || 0} planned operations.`,
      }))
    } catch (error) {
      setImportSummary((prev) => ({
        ...prev,
        diagnostics: prev.diagnostics ? { ...prev.diagnostics, lastAction: 'preview-failed' } : prev.diagnostics,
        message: error instanceof Error ? error.message : 'Unable to preview import batch.',
      }))
    } finally {
      setImportAction('idle')
    }
  }

  const handleHydrateImport = async () => {
    const selectedRows = importRowsByBatchId[importSummary.batchId] || []
    if (!importSummary.batchId || !selectedRows.length) {
      setImportSummary((prev) => ({
        ...prev,
        message: 'Upload this file in the current session before hydrating. Stored file history does not include row payloads for execution.',
      }))
      return
    }

    try {
      setWorkspaceError('')
      setImportAction('hydrate')
      await syncImportMapping()
      const hydrateResult = await hydrateImportBatch(authToken, importSummary.batchId, selectedRows)
      setImportSummary((prev) => ({
        ...prev,
        hydrateResult,
        diagnostics: prev.diagnostics ? {
          ...prev.diagnostics,
          lastAction: 'hydrate',
          hydratePlannedOperationCount: hydrateResult.plannedOperationCount || 0,
          hydrateCreatedCount: hydrateResult.createdCount || 0,
          hydrateUpdatedCount: hydrateResult.updatedCount || 0,
          hydrateSkippedCount: hydrateResult.skippedCount || 0,
        } : prev.diagnostics,
        message: `Hydration completed for ${prev.filename}. Created ${hydrateResult.createdCount || 0}, updated ${hydrateResult.updatedCount || 0}.`,
      }))
      setImportBatchHydrationStatus((prev) => ({
        ...prev,
        [importSummary.batchId]: {
          state: 'hydrated',
          createdCount: hydrateResult.createdCount || 0,
          updatedCount: hydrateResult.updatedCount || 0,
          skippedCount: hydrateResult.skippedCount || 0,
          at: new Date().toISOString(),
        },
      }))
      await refreshWorkspaceData()
    } catch (error) {
      setImportBatchHydrationStatus((prev) => ({
        ...prev,
        [importSummary.batchId]: {
          state: 'failed',
          message: error instanceof Error ? error.message : 'Hydration failed',
          at: new Date().toISOString(),
        },
      }))
      setImportSummary((prev) => ({
        ...prev,
        diagnostics: prev.diagnostics ? { ...prev.diagnostics, lastAction: 'hydrate-failed' } : prev.diagnostics,
        message: error instanceof Error ? error.message : 'Unable to hydrate import batch.',
      }))
    } finally {
      setImportAction('idle')
    }
  }

  const removeImportBatchRecord = async (batchId) => {
    if (!batchId) {
      return
    }

    try {
      setWorkspaceError('')
      await deleteImportBatch(authToken, batchId)
      setImportBatches((prev) => prev.filter((batch) => batch.id !== batchId))
      setImportRowsByBatchId((prev) => {
        const next = { ...prev }
        delete next[batchId]
        return next
      })
      setImportBatchHydrationStatus((prev) => {
        const next = { ...prev }
        delete next[batchId]
        return next
      })

      setImportSummary((prev) => {
        if (prev.batchId !== batchId) {
          return {
            ...prev,
            message: 'File removed from import history.',
          }
        }
        return {
          ...DEFAULT_IMPORT_SUMMARY,
          message: 'File removed. Click another file name to view its columns and mapping.',
        }
      })
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to remove import file.')
    }
  }

  const createCampaignRecord = async (event) => {
    event.preventDefault()
    if (!campaignForm.name.trim()) {
      return
    }

    try {
      setWorkspaceError('')
      const customAttributes = normalizeCustomAttributesForPayload(campaignForm.customAttributes)
      const nextCampaign = await createCampaign(authToken, {
        userId,
        name: campaignForm.name.trim(),
        budget: normalizeBudgetForPayload(campaignForm.budget),
        status: campaignForm.status,
        campaignType: normalizeCampaignTypeForPayload(campaignForm.campaignType),
        customAttributes,
      })

      setCampaigns((prev) => [nextCampaign, ...prev])
      setAssignmentForm((prev) => ({ ...prev, campaignId: nextCampaign.id || prev.campaignId }))
      setCampaignForm({
        name: '',
        budget: '',
        status: 'draft',
        campaignType: CAMPAIGN_TYPE_OPTIONS[0].value,
        customAttributes: [],
      })
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to create campaign.')
    }
  }

  const updateCampaignRecord = async (id, payload) => {
    const existing = campaigns.find((campaign) => campaign.id === id)
    if (!existing) {
      return
    }

    const customAttributes = normalizeCustomAttributesForPayload(payload.customAttributes)

    const nextLocal = {
      ...existing,
      name: payload.name,
      budget: normalizeBudgetForPayload(payload.budget),
      status: payload.status,
      campaignType: normalizeCampaignTypeForPayload(payload.campaignType),
      customAttributes,
    }

    setCampaigns((prev) => prev.map((campaign) => (campaign.id === id ? nextLocal : campaign)))

    try {
      setWorkspaceError('')
      const updated = await updateCampaign(authToken, id, {
        ...existing,
        ...nextLocal,
        userId,
      })
      setCampaigns((prev) => prev.map((campaign) => (campaign.id === id ? updated : campaign)))
    } catch (error) {
      setCampaigns((prev) => prev.map((campaign) => (campaign.id === id ? existing : campaign)))
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to update campaign.')
      throw error
    }
  }

  const createCreatorRecord = async (event) => {
    event.preventDefault()
    if (!creatorForm.name.trim() || !creatorForm.handle.trim()) {
      return
    }

    try {
      setWorkspaceError('')
      const customAttributes = normalizeCustomAttributesForPayload(creatorForm.customAttributes)
      const nextCreator = await createCreator(authToken, {
        userId,
        name: creatorForm.name.trim(),
        handle: creatorForm.handle.trim(),
        platform: normalizePlatformForPayload(creatorForm.platform),
        email: creatorForm.email.trim(),
        customAttributes,
      })

      setCreators((prev) => [nextCreator, ...prev])
      setAssignmentForm((prev) => ({ ...prev, creatorId: nextCreator.id || prev.creatorId }))
      setCreatorForm({ name: '', handle: '', platform: 'instagram', email: '', customAttributes: [] })
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to create creator.')
    }
  }

  const updateCreatorRecord = async (id, payload) => {
    const existing = creators.find((creator) => creator.id === id)
    if (!existing) {
      return
    }

    const customAttributes = normalizeCustomAttributesForPayload(payload.customAttributes)

    const nextLocal = {
      ...existing,
      name: payload.name,
      handle: payload.handle,
      platform: normalizePlatformForPayload(payload.platform),
      email: payload.email,
      customAttributes,
    }

    setCreators((prev) => prev.map((creator) => (creator.id === id ? nextLocal : creator)))

    try {
      setWorkspaceError('')
      const updated = await updateCreator(authToken, id, {
        ...existing,
        ...nextLocal,
        userId,
      })
      setCreators((prev) => prev.map((creator) => (creator.id === id ? updated : creator)))
    } catch (error) {
      setCreators((prev) => prev.map((creator) => (creator.id === id ? existing : creator)))
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to update creator.')
      throw error
    }
  }

  const tieCreatorToCampaign = async (event) => {
    event.preventDefault()
    if (!assignmentForm.campaignId || !assignmentForm.creatorId) {
      return
    }

    const selectedCampaign = campaignById[assignmentForm.campaignId]
    const selectedCampaignType = normalizeCampaignTypeForPayload(selectedCampaign?.campaignType)
    const configuredStages = getConfiguredStagesForCampaignType(selectedCampaignType)
    if (!configuredStages.length) {
      setWorkspaceError(`Set up workflow stages for campaign type "${selectedCampaignType}" before creating work items.`)
      return
    }

    const resolvedStage = configuredStages.includes(assignmentForm.stage)
      ? assignmentForm.stage
      : configuredStages[0]

    try {
      setWorkspaceError('')
      const nextAssignment = await createCampaignCreator(authToken, {
        userId,
        campaignId: assignmentForm.campaignId,
        creatorId: assignmentForm.creatorId,
        stage: resolvedStage,
        agreedFee: assignmentForm.fee.trim() || null,
        notes: assignmentForm.notes.trim(),
        contentDueAt: normalizeInstantDateForPayload(assignmentForm.dueDate),
        tags: assignmentForm.tags
          .split(',')
          .map((tag) => tag.trim())
          .filter(Boolean),
      })

      setAssignments((prev) => [nextAssignment, ...prev])
      setAssignmentForm((prev) => ({ ...prev, fee: '', notes: '', stage: resolvedStage, dueDate: '', tags: '' }))
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to tie creator to campaign.')
    }
  }

  const updateCardStage = async (id, nextStage) => {
    const existing = assignments.find((item) => item.id === id)
    if (!existing) {
      return
    }

    const optimistic = {
      ...existing,
      stage: nextStage,
    }

    setAssignments((prev) => prev.map((item) => (item.id === id ? optimistic : item)))

    try {
      setWorkspaceError('')
      const updated = await updateCampaignCreator(authToken, id, {
        ...existing,
        ...optimistic,
        userId,
        campaignId: existing.campaignId,
        creatorId: existing.creatorId,
        agreedFee: optimistic.fee ? String(optimistic.fee).trim() : null,
        contentDueAt: normalizeInstantDateForPayload(optimistic.dueDate),
        tags: Array.isArray(optimistic.tags)
          ? optimistic.tags
          : String(optimistic.tags || '')
            .split(',')
            .map((tag) => tag.trim())
            .filter(Boolean),
      })
      setAssignments((prev) => prev.map((item) => (item.id === id ? updated : item)))
    } catch (error) {
      setAssignments((prev) => prev.map((item) => (item.id === id ? existing : item)))
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to update workflow stage.')
    }
  }

  const updateAssignmentRecord = async (id, payload) => {
    const existing = assignments.find((item) => item.id === id)
    if (!existing) {
      return
    }

    const normalizedTags = normalizeTagsForState(payload.tags)

    const nextLocal = {
      ...existing,
      stage: payload.stage,
      fee: payload.fee,
      dueDate: payload.dueDate,
      notes: payload.notes,
      tags: normalizedTags,
    }

    setAssignments((prev) => prev.map((item) => (item.id === id ? nextLocal : item)))

    try {
      setWorkspaceError('')
      const updated = await updateCampaignCreator(authToken, id, {
        ...existing,
        ...nextLocal,
        userId,
        campaignId: existing.campaignId,
        creatorId: existing.creatorId,
        agreedFee: payload.fee ? String(payload.fee).trim() : null,
        contentDueAt: normalizeInstantDateForPayload(payload.dueDate),
        tags: normalizedTags,
      })
      setAssignments((prev) => prev.map((item) => (item.id === id ? updated : item)))
    } catch (error) {
      setAssignments((prev) => prev.map((item) => (item.id === id ? existing : item)))
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to update creator-campaign workflow record.')
      throw error
    }
  }

  const saveCampaignTypeWorkflowSetup = async (campaignType, stages) => {
    const normalizedType = normalizeCampaignTypeForPayload(campaignType)
    const preparedStages = (Array.isArray(stages) ? stages : []).map((stage, index) => ({
      stageKey: stage.stageKey,
      stageLabel: stage.stageLabel,
      position: Number.isFinite(Number(stage.position)) ? Number(stage.position) : index,
      isActive: stage.isActive !== false,
    }))

    if (!preparedStages.filter((stage) => stage.isActive).length) {
      throw new Error('At least one active stage is required for a workflow.')
    }

    const saved = await replaceCampaignTypeWorkflowStages(authToken, {
      userId,
      campaignType: normalizedType,
      stages: preparedStages,
    })

    setCampaignTypeWorkflowStages((prev) => {
      const withoutType = prev.filter((item) => normalizeCampaignTypeForPayload(item.campaignType) !== normalizedType)
      return [...withoutType, ...saved]
    })
  }

  const handleLogout = async () => {
    try {
      if (authToken) {
        await logout(authToken)
      }
    } catch {
      // Ignore logout API failures and clear local session state anyway.
    }

    setIsLoggedIn(false)
    setAuthToken('')
    setUserId('')
    setWorkspaceError('')
    setAuthError('')
  }

  return (
    <Routes>
      {!isLoggedIn ? (
        <>
          <Route
            path="*"
            element={
              <LandingPage
                isSignUp={isSignUp}
                setIsSignUp={setIsSignUp}
                onAuthSubmit={handleAuthSubmit}
                authError={authError}
              />
            }
          />
        </>
      ) : (
        <>
          <Route
            path="/"
            element={
              <WorkspaceLayout
                brandName={brandName}
                userName={userName}
                onLogout={handleLogout}
                workspaceError={workspaceError}
              />
            }
          >
            <Route index element={<Navigate to="/import" replace />} />
            <Route
              path="import"
              element={
                <ImportPage
                  importSummary={importSummary}
                  importBatches={importBatches}
                  importBatchHydrationStatus={importBatchHydrationStatus}
                  onImportFiles={handleImportFiles}
                  onSelectImportBatch={selectImportBatch}
                  onDeleteImportBatch={removeImportBatchRecord}
                  onImportMappingChange={handleImportMappingChange}
                  onSaveImportMapping={handleSaveImportMapping}
                  onRegenerateImportMapping={handleRegenerateImportMapping}
                  onPreviewImport={handlePreviewImport}
                  onHydrateImport={handleHydrateImport}
                  importAction={importAction}
                />
              }
            />
            <Route
              path="campaigns"
              element={
                <CampaignsPage
                  campaigns={campaigns}
                  campaignForm={campaignForm}
                  setCampaignForm={setCampaignForm}
                  campaignTypeOptions={CAMPAIGN_TYPE_OPTIONS}
                  customAttributesToPairs={customAttributesToPairs}
                  normalizeCampaignTypeForPayload={normalizeCampaignTypeForPayload}
                  onCreateCampaign={createCampaignRecord}
                  onUpdateCampaign={updateCampaignRecord}
                />
              }
            />
            <Route
              path="creators"
              element={
                <CreatorsPage
                  creators={creators}
                  creatorForm={creatorForm}
                  setCreatorForm={setCreatorForm}
                  customAttributesToPairs={customAttributesToPairs}
                  onCreateCreator={createCreatorRecord}
                  onUpdateCreator={updateCreatorRecord}
                />
              }
            />
            <Route
              path="workflow"
              element={
                <WorkflowPage
                  campaigns={campaigns}
                  creators={creators}
                  assignments={assignments}
                  assignmentForm={assignmentForm}
                  setAssignmentForm={setAssignmentForm}
                  onTieCreatorToCampaign={tieCreatorToCampaign}
                  campaignTypeOptions={CAMPAIGN_TYPE_OPTIONS}
                  campaignTypeWorkflowStages={campaignTypeWorkflowStages}
                  getConfiguredStagesForCampaignType={getConfiguredStagesForCampaignType}
                  onSaveCampaignTypeWorkflowSetup={saveCampaignTypeWorkflowSetup}
                  campaignById={campaignById}
                  creatorById={creatorById}
                  updateCardStage={updateCardStage}
                  onUpdateAssignment={updateAssignmentRecord}
                />
              }
            />
            <Route path="*" element={<Navigate to="/import" replace />} />
          </Route>
        </>
      )}
    </Routes>
  )
}

export default App
