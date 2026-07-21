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
  createCreator,
  discoverImport,
  generateAgentColumnMapping,
  hydrateImportBatch,
  listCampaignCreators,
  listCampaigns,
  listCreators,
  login,
  previewImportBatch,
  logout,
  signup,
  updateCampaign,
  updateCampaignCreator,
  updateImportColumnMapping,
  updateCreator,
  updateCampaignCreatorStage,
} from './api'
import { createImportMappingJson, createImportMappingJsonFromAgent, parseSpreadsheetFile, STAGES } from './constants'

const STORAGE_KEY = 'tejdux_ui_state_v1'
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

function App() {
  const persistedState = loadPersistedState()
  const initialCampaigns = persistedState?.campaigns?.length ? persistedState.campaigns : []
  const initialCreators = persistedState?.creators?.length ? persistedState.creators : []
  const initialAssignments = persistedState?.assignments?.length ? persistedState.assignments : []
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

  const [campaignForm, setCampaignForm] = useState(persistedState?.campaignForm ?? { name: '', budget: '', status: 'draft' })
  const [creatorForm, setCreatorForm] = useState(
    persistedState?.creatorForm ?? { name: '', handle: '', platform: 'Instagram', email: '' },
  )
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
  const [importRowsPayload, setImportRowsPayload] = useState([])
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

  const groupedAssignments = useMemo(() => {
    return STAGES.reduce((acc, stage) => {
      acc[stage] = assignments.filter((item) => item.stage === stage)
      return acc
    }, {})
  }, [assignments])

  const refreshWorkspaceData = async () => {
    setWorkspaceError('')
    const [campaignPayload, creatorPayload, assignmentPayload] = await Promise.all([
      listCampaigns(authToken),
      listCreators(authToken),
      listCampaignCreators(authToken),
    ])

    setCampaigns(campaignPayload)
    setCreators(creatorPayload)
    setAssignments(assignmentPayload)
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
      campaignForm,
      creatorForm,
      assignmentForm,
      importSummary,
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
    campaignForm,
    creatorForm,
    assignmentForm,
    importSummary,
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

  const handleImport = async (event) => {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }

    try {
      setWorkspaceError('')
      setImportAction('upload')
      const parsedFile = await parseSpreadsheetFile(file)
      const response = await discoverImport(authToken, file)
      const columns = Array.isArray(response.columns) ? response.columns : []
      const batch = response.importBatch || response.batch || response
      const resolvedHeaders = parsedFile.headers.length ? parsedFile.headers : columns
      let mappingText = createImportMappingJson(resolvedHeaders)
      let diagnostics = {
        batchId: batch?.id || '',
        headerCount: resolvedHeaders.length,
        rowPayloadCount: parsedFile.rowObjects.length,
        mappingMode: 'local_fallback',
        sourceFileStored: Boolean(batch?.sourceFileStored),
        agentDebug: null,
        lastAction: 'upload',
      }
      let mappingMessage = resolvedHeaders.length
        ? `Persisted source file, retrieved ${resolvedHeaders.length} headers from batch ${batch?.id || ''}, and prepared ${parsedFile.rowObjects.length} rows from ${batch?.sourceFilename || file.name}.`
        : `Import batch created for ${batch?.sourceFilename || file.name}.`

      try {
        const mappingResponse = batch?.id ? await generateAgentColumnMapping(authToken, batch.id) : null
        mappingText = createImportMappingJsonFromAgent(
          resolvedHeaders,
          mappingResponse?.mapping?.recommendations || [],
        )
        diagnostics = {
          ...diagnostics,
          mappingMode: 'agent_assisted',
          agentDebug: mappingResponse?.mapping?.debug || null,
          recommendationCount: mappingResponse?.mapping?.recommendations?.length || 0,
        }
        mappingMessage = `Persisted source file, retrieved ${resolvedHeaders.length} headers from batch ${batch?.id || ''}, and generated agent-assisted column mappings.`
      } catch (error) {
        mappingMessage = `Source file was persisted for batch ${batch?.id || ''}, but agent mapping is unavailable. A local fallback mapping has been prepared and can still be saved, previewed, or hydrated.`
        diagnostics = {
          ...diagnostics,
          agentError: error instanceof Error ? error.message : 'Agent mapping unavailable.',
        }
      }

      let mappingSaved = false
      try {
        await updateImportColumnMapping(authToken, batch?.id || '', mappingText)
        mappingSaved = true
      } catch {
        mappingMessage = `${mappingMessage} Saving the mapping back to the import batch failed, so use Save mapping before preview or hydrate.`
      }

      setImportRowsPayload(parsedFile.rowObjects)

      setImportSummary({
        batchId: batch?.id || '',
        filename: batch?.sourceFilename || file.name,
        type: parsedFile.type,
        sourceFileStored: Boolean(batch?.sourceFileStored),
        headers: resolvedHeaders,
        rows: parsedFile.rows.slice(0, 5),
        mappingText,
        mappingSaved,
        previewResult: null,
        hydrateResult: null,
        diagnostics,
        message: mappingMessage,
      })
    } catch (error) {
      setImportRowsPayload([])
      setImportSummary({
        ...DEFAULT_IMPORT_SUMMARY,
        filename: file.name,
        type: 'Error',
        sourceFileStored: false,
        mappingSaved: false,
        diagnostics: null,
        message: error instanceof Error ? error.message : 'Unable to discover import schema.',
      })
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
    if (!importSummary.batchId || !importRowsPayload.length) {
      setImportSummary((prev) => ({
        ...prev,
        message: 'Upload a supported spreadsheet with rows before running preview.',
      }))
      return
    }

    try {
      setWorkspaceError('')
      setImportAction('preview')
      await syncImportMapping()
      const previewResult = await previewImportBatch(authToken, importSummary.batchId, importRowsPayload)
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
    if (!importSummary.batchId || !importRowsPayload.length) {
      setImportSummary((prev) => ({
        ...prev,
        message: 'Upload a supported spreadsheet with rows before hydrating.',
      }))
      return
    }

    try {
      setWorkspaceError('')
      setImportAction('hydrate')
      await syncImportMapping()
      const hydrateResult = await hydrateImportBatch(authToken, importSummary.batchId, importRowsPayload)
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
      await refreshWorkspaceData()
    } catch (error) {
      setImportSummary((prev) => ({
        ...prev,
        diagnostics: prev.diagnostics ? { ...prev.diagnostics, lastAction: 'hydrate-failed' } : prev.diagnostics,
        message: error instanceof Error ? error.message : 'Unable to hydrate import batch.',
      }))
    } finally {
      setImportAction('idle')
    }
  }

  const createCampaignRecord = async (event) => {
    event.preventDefault()
    if (!campaignForm.name.trim()) {
      return
    }

    try {
      setWorkspaceError('')
      const nextCampaign = await createCampaign(authToken, {
        userId,
        name: campaignForm.name.trim(),
        budget: campaignForm.budget.trim(),
        status: campaignForm.status,
      })

      setCampaigns((prev) => [nextCampaign, ...prev])
      setAssignmentForm((prev) => ({ ...prev, campaignId: nextCampaign.id || prev.campaignId }))
      setCampaignForm({ name: '', budget: '', status: 'draft' })
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to create campaign.')
    }
  }

  const updateCampaignRecord = async (id, payload) => {
    const existing = campaigns.find((campaign) => campaign.id === id)
    if (!existing) {
      return
    }

    const nextLocal = {
      ...existing,
      name: payload.name,
      budget: payload.budget,
      status: payload.status,
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
      const nextCreator = await createCreator(authToken, {
        userId,
        name: creatorForm.name.trim(),
        handle: creatorForm.handle.trim(),
        platform: creatorForm.platform,
        email: creatorForm.email.trim(),
      })

      setCreators((prev) => [nextCreator, ...prev])
      setAssignmentForm((prev) => ({ ...prev, creatorId: nextCreator.id || prev.creatorId }))
      setCreatorForm({ name: '', handle: '', platform: 'Instagram', email: '' })
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to create creator.')
    }
  }

  const updateCreatorRecord = async (id, payload) => {
    const existing = creators.find((creator) => creator.id === id)
    if (!existing) {
      return
    }

    const nextLocal = {
      ...existing,
      name: payload.name,
      handle: payload.handle,
      platform: payload.platform,
      email: payload.email,
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

    try {
      setWorkspaceError('')
      const nextAssignment = await createCampaignCreator(authToken, {
        userId,
        campaignId: assignmentForm.campaignId,
        creatorId: assignmentForm.creatorId,
        stage: assignmentForm.stage,
        agreedFee: assignmentForm.fee.trim() || null,
        notes: assignmentForm.notes.trim(),
        contentDueAt: assignmentForm.dueDate || null,
        tags: assignmentForm.tags
          .split(',')
          .map((tag) => tag.trim())
          .filter(Boolean),
      })

      setAssignments((prev) => [nextAssignment, ...prev])
      setAssignmentForm((prev) => ({ ...prev, fee: '', notes: '', stage: 'outreach', dueDate: '', tags: '' }))
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to tie creator to campaign.')
    }
  }

  const updateCardStage = async (id, nextStage) => {
    const existing = assignments.find((item) => item.id === id)
    if (!existing) {
      return
    }

    setAssignments((prev) => prev.map((item) => (item.id === id ? { ...item, stage: nextStage } : item)))

    try {
      setWorkspaceError('')
      const updated = await updateCampaignCreatorStage(authToken, id, nextStage)
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

    const nextLocal = {
      ...existing,
      stage: payload.stage,
      fee: payload.fee,
      dueDate: payload.dueDate,
      notes: payload.notes,
      tags: payload.tags,
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
        contentDueAt: payload.dueDate || null,
        tags: Array.isArray(payload.tags)
          ? payload.tags
          : String(payload.tags || '')
            .split(',')
            .map((tag) => tag.trim())
            .filter(Boolean),
      })
      setAssignments((prev) => prev.map((item) => (item.id === id ? updated : item)))
    } catch (error) {
      setAssignments((prev) => prev.map((item) => (item.id === id ? existing : item)))
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to update creator-campaign workflow record.')
      throw error
    }
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
                  onImport={handleImport}
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
                  groupedAssignments={groupedAssignments}
                  campaignById={campaignById}
                  creatorById={creatorById}
                  updateCardStage={updateCardStage}
                  onUpdateAssignment={updateAssignmentRecord}
                />
              }
            />
          </Route>
          <Route path="*" element={<Navigate to="/import" replace />} />
        </>
      )}
    </Routes>
  )
}

export default App
