import { Navigate, Route, Routes } from 'react-router-dom'
import { useEffect, useState } from 'react'
import './App.css'
import LandingPage from './pages/LandingPage'
import ImportPage from './pages/ImportPage'
import CampaignsPage from './pages/CampaignsPage'
import CreatorsPage from './pages/CreatorsPage'
import WorkflowPage from './pages/WorkflowPage'
import CouponsPage from './pages/CouponsPage'
import MarketplacePage from './pages/MarketplacePage'
import DashboardPage from './pages/DashboardPage'
import PayoutsPage from './pages/PayoutsPage'
import ContentPage from './pages/ContentPage'
import WorkspaceLayout from './components/WorkspaceLayout'
import { SessionProvider } from './shell/SessionContext'
import {
  createCampaign,
  createCreator,
  listWorkflowBoards,
  createWorkflowBoard,
  updateWorkflowBoard,
  deleteWorkflowBoard,
  listWorkflowBoardStages,
  replaceWorkflowBoardStages,
  listWorkflowCards,
  createWorkflowCard,
  placeWorkflowCard,
  deleteWorkflowCard,
  deleteImportBatch,
  discoverImports,
  generateAgentColumnMapping,
  getImportBatch,
  getImportBatchColumns,
  hydrateImportBatch,
  listImportBatches,
  listCampaigns,
  listCreators,
  listCoupons,
  generateCoupon,
  generateCouponsBulk,
  deleteCoupon,
  pushCoupon,
  personalizeCoupon,
  decideCouponPersonalization,
  listMarketplaceProviders,
  listMarketplaceConnections,
  connectMarketplace,
  deleteMarketplaceConnection,
  simulateOrder,
  getInfluencerRevenue,
  listCommissions,
  approveCommission,
  listPayouts,
  createPayoutBatch,
  listPayoutProviders,
  listCampaignBriefs,
  createCampaignBrief,
  updateCampaignBrief,
  listLandingTemplates,
  saveLandingTemplate,
  previewLandingTemplate,
  draftContent,
  login,
  previewImportBatch,
  logout,
  setAuthHandlers,
  setActiveBrandId,
  listBrands,
  switchBrand,
  signup,
  updateCampaign,
  updateImportColumnMapping,
  updateCreator,
} from './api'
import { createImportMappingJson, createImportMappingJsonFromAgent, parseSpreadsheetFile, DEFAULT_BOARD_STAGES } from './constants'

// v3: tenancy moved from the user to the active brand. A v2 snapshot has no brandId, so its
// cached domain rows belong to an unknown tenant — versioning the key discards them rather than
// risk showing one brand's data under another's name.
const STORAGE_KEY = 'tejdux_ui_state_v3'

// Origin of the Digital Presentation Service, which owns federated sign-in end to end. Kept in
// step with the same constant in shell/gateway/PresentationGateway.js.
const DPS_BASE_URL = import.meta.env?.VITE_DPS_URL || 'http://localhost:8090'
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

/**
 * Reads the permission list out of the access token.
 *
 * Purely for deciding what to show — the server re-checks every action, so a tampered token
 * buys nothing here beyond a UI that offers links the API will refuse.
 */
function readPermissionsFromToken(accessToken) {
  if (!accessToken) {
    return []
  }
  try {
    const payload = accessToken.split('.')[1]
    if (!payload) {
      return []
    }
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(atob(normalized))
    return Array.isArray(claims.perms) ? claims.perms : []
  } catch {
    return []
  }
}

function loadPersistedState() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') {
      return null
    }
    // Snapshots written before tokens were removed still contain them. Strip and rewrite on
    // first load, so upgrading actually clears the credential instead of leaving it sitting
    // in storage until the user happens to log out.
    if (parsed.authToken || parsed.refreshToken) {
      delete parsed.authToken
      delete parsed.refreshToken
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(parsed))
    }
    return parsed
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

function App() {
  const persistedState = loadPersistedState()
  const initialCampaigns = persistedState?.campaigns?.length ? persistedState.campaigns : []
  const initialCreators = persistedState?.creators?.length ? persistedState.creators : []
  const defaultCampaignId = initialCampaigns[0]?.id || ''
  const defaultCreatorId = initialCreators[0]?.id || ''

  const [isSignUp, setIsSignUp] = useState(persistedState?.isSignUp ?? true)
  const [isLoggedIn, setIsLoggedIn] = useState(persistedState?.isLoggedIn ?? false)
  const [brandName, setBrandName] = useState(persistedState?.brandName ?? 'tejdux.io')
  const [userName, setUserName] = useState(persistedState?.userName ?? '')
  // Never seeded from persisted state: tokens are no longer written to localStorage, and an
  // older snapshot that still carries one must not be a way to resurrect it.
  const [authToken, setAuthToken] = useState('')
  const [refreshToken, setRefreshToken] = useState('')
  const [userId, setUserId] = useState(persistedState?.userId ?? '')
  // Active brand plus the set the user may switch to. Solo accounts have exactly one entry,
  // which is why the switcher can be hidden without needing a separate code path.
  const [brandId, setBrandId] = useState(persistedState?.brandId ?? '')
  const [accountId, setAccountId] = useState(persistedState?.accountId ?? '')
  const [role, setRole] = useState(persistedState?.role ?? '')
  const [availableBrands, setAvailableBrands] = useState(persistedState?.availableBrands ?? [])
  // Derived from the token rather than stored, so it can never drift out of step with the
  // credential the server will actually evaluate.
  const permissions = readPermissionsFromToken(authToken)
  const [authError, setAuthError] = useState('')
  const [workspaceError, setWorkspaceError] = useState('')

  const [campaigns, setCampaigns] = useState(initialCampaigns)
  const [creators, setCreators] = useState(initialCreators)

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

  const [workflowBoards, setWorkflowBoards] = useState(persistedState?.workflowBoards ?? [])
  const [workflowBoardStages, setWorkflowBoardStages] = useState(persistedState?.workflowBoardStages ?? [])
  const [workflowCards, setWorkflowCards] = useState(persistedState?.workflowCards ?? [])
  const [activeBoardId, setActiveBoardId] = useState(persistedState?.activeBoardId ?? '')

  const [coupons, setCoupons] = useState(persistedState?.coupons ?? [])
  const [marketplaceProviders, setMarketplaceProviders] = useState([])
  const [marketplaceConnections, setMarketplaceConnections] = useState(persistedState?.marketplaceConnections ?? [])

  const refreshWorkspaceData = async () => {
    setWorkspaceError('')
    const [
      campaignPayload,
      creatorPayload,
      importBatchPayload,
      boardPayload,
      boardStagePayload,
      cardPayload,
      couponPayload,
      providerPayload,
      connectionPayload,
    ] = await Promise.all([
      listCampaigns(authToken),
      listCreators(authToken),
      listImportBatches(authToken),
      listWorkflowBoards(authToken),
      listWorkflowBoardStages(authToken),
      listWorkflowCards(authToken),
      listCoupons(authToken),
      listMarketplaceProviders(authToken).catch(() => []),
      listMarketplaceConnections(authToken).catch(() => []),
    ])

    setCampaigns(campaignPayload)
    setCreators(creatorPayload)
    setImportBatches(importBatchPayload)
    setWorkflowCards(cardPayload)
    setCoupons(couponPayload)
    setMarketplaceProviders(providerPayload)
    setMarketplaceConnections(connectionPayload)
    setAssignmentForm((prev) => ({
      ...prev,
      campaignId: prev.campaignId || campaignPayload[0]?.id || '',
      creatorId: prev.creatorId || creatorPayload[0]?.id || '',
    }))

    // Auto-create a default template board for brand users with no boards yet.
    if (!boardPayload.length) {
      try {
        const { board, stages } = await createDefaultBoard()
        setWorkflowBoards([board])
        setWorkflowBoardStages(stages)
        setActiveBoardId(board.id)
        return
      } catch {
        // Fall through to empty state if seeding fails; user can add manually.
      }
    }

    setWorkflowBoards(boardPayload)
    setWorkflowBoardStages(boardStagePayload)
    setActiveBoardId((prev) => {
      if (prev && boardPayload.some((b) => b.id === prev)) return prev
      const active = boardPayload.find((b) => b.isActive)
      return active?.id || boardPayload[0]?.id || ''
    })
  }

  useEffect(() => {
    // Credentials are deliberately absent from this snapshot. The session lives server-side in
    // the DPS behind an httpOnly cookie; writing the access or refresh token to localStorage
    // would hand an XSS payload the one thing that design exists to keep out of reach.
    const snapshot = {
      isSignUp,
      isLoggedIn,
      brandName,
      userName,
      userId,
      brandId,
      accountId,
      role,
      availableBrands,
      campaigns,
      creators,
      campaignForm,
      creatorForm,
      assignmentForm,
      importSummary,
      importBatches,
      importBatchHydrationStatus,
      workflowBoards,
      workflowBoardStages,
      workflowCards,
      activeBoardId,
      coupons,
      marketplaceConnections,
    }
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot))
  }, [
    isSignUp,
    isLoggedIn,
    brandName,
    userName,
    userId,
    brandId,
    accountId,
    role,
    availableBrands,
    campaigns,
    creators,
    campaignForm,
    creatorForm,
    assignmentForm,
    importSummary,
    importBatches,
    importBatchHydrationStatus,
    workflowBoards,
    workflowBoardStages,
    workflowCards,
    activeBoardId,
    coupons,
    marketplaceConnections,
  ])

  // Let the API layer renew an expired access token behind the scenes. Without this a user is
  // silently logged out the moment the short-lived access token expires mid-session.
  useEffect(() => {
    setAuthHandlers({
      getRefreshToken: () => refreshToken,
      onRefreshed: (authResponse) => {
        setAuthToken(authResponse?.accessToken || '')
        setRefreshToken(authResponse?.refreshToken || '')
        applyBrandFromAuth(authResponse)
      },
      onSessionExpired: () => {
        setIsLoggedIn(false)
        setAuthToken('')
        setRefreshToken('')
        setUserId('')
        setBrandId('')
        setAvailableBrands([])
        setActiveBrandId('')
        setAuthError('Your session expired. Please sign in again.')
      },
    })
  }, [refreshToken])

  // Restore the API layer's brand after a page reload: module state does not survive it,
  // so without this the first request would go out with no brand header.
  useEffect(() => {
    setActiveBrandId(brandId)
  }, [brandId])

  // Load the brands this user may switch between. Solo accounts get exactly one entry and
  // the switcher stays hidden — same code path, different data.
  useEffect(() => {
    if (!isLoggedIn || !authToken) {
      return
    }
    let isActive = true
    listBrands(authToken)
      .then((brands) => {
        if (isActive) {
          setAvailableBrands(Array.isArray(brands) ? brands : [])
        }
      })
      .catch(() => {
        // Non-fatal: the workspace still works on the active brand from the token.
      })
    return () => {
      isActive = false
    }
  }, [isLoggedIn, authToken, brandId])

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
      setRefreshToken(authResponse.refreshToken || '')
      applyBrandFromAuth(authResponse)
      setIsLoggedIn(true)
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : 'Authentication failed.')
      throw error
    }
  }

  // Captures the active brand from an auth/refresh/switch response. Called before any
  // workspace fetch so requests never go out under a stale brand.
  const applyBrandFromAuth = (authResponse) => {
    const nextBrandId = authResponse?.brandId || ''
    setBrandId(nextBrandId)
    setAccountId(authResponse?.accountId || '')
    setRole(authResponse?.role || '')
    if (authResponse?.brandName) {
      setBrandName(authResponse.brandName)
    }
    // Set the header source synchronously — a later effect would let the first
    // workspace request escape without the brand header.
    setActiveBrandId(nextBrandId)
  }

  const handleSwitchBrand = async (nextBrandId) => {
    if (!nextBrandId || nextBrandId === brandId) {
      return
    }
    try {
      setWorkspaceError('')
      // The server re-mints the token: role and permissions are per-brand, so the
      // current token cannot simply be reused against a different brand.
      const switched = await switchBrand(authToken, nextBrandId)
      setAuthToken(switched.accessToken || '')
      applyBrandFromAuth(switched)

      // Cached rows belong to the previous brand; clear them rather than briefly
      // rendering one brand's data under another's name.
      setCampaigns([])
      setCreators([])
      setWorkflowBoards([])
      setWorkflowBoardStages([])
      setWorkflowCards([])
      setCoupons([])
      setImportBatches([])
      setMarketplaceConnections([])
      setActiveBoardId('')
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to switch brand.')
    }
  }

  const establishSession = (authResponse) => {
    const email = authResponse.email || ''
    const inferredName = email.includes('@') ? email.split('@')[0] : email
    setUserName(inferredName || 'Brand Operator')
    setBrandName(authResponse.brandName || '')
    setUserId(authResponse.userId || '')
    setAuthToken(authResponse.accessToken || '')
    setRefreshToken(authResponse.refreshToken || '')
    applyBrandFromAuth(authResponse)
    setIsLoggedIn(true)
  }

  /**
   * Starts a federated sign-in by navigating to the DPS.
   *
   * Previously this opened a popup and waited for the callback page to postMessage the tokens
   * back, which meant the access and refresh tokens passed through JavaScript. The DPS now
   * completes the flow server-side and returns with an httpOnly session cookie already set, so
   * there is nothing for the SPA to receive — hence a plain redirect rather than a promise.
   *
   * A full-page navigation also sidesteps popup blockers, and lands the user back on the shell
   * authenticated instead of on an intermediate page.
   */
  const handleSocialLogin = (provider, { brandName: socialBrandName = '' } = {}) => {
    setAuthError('')
    setWorkspaceError('')

    const query = socialBrandName ? `?brandName=${encodeURIComponent(socialBrandName)}` : ''
    window.location.assign(`${DPS_BASE_URL}/dps/auth/oauth/${provider}/start${query}`)
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

  // ---- coupons -------------------------------------------------------
  const generateCouponRecord = async (payload) => {
    setWorkspaceError('')
    const created = await generateCoupon(authToken, { userId, ...payload })
    setCoupons((prev) => [created, ...prev])
    return created
  }

  const generateCouponsBulkRecord = async (payload) => {
    setWorkspaceError('')
    const created = await generateCouponsBulk(authToken, { userId, ...payload })
    setCoupons((prev) => [...created, ...prev])
    return created
  }

  const deleteCouponRecord = async (id) => {
    setWorkspaceError('')
    try {
      await deleteCoupon(authToken, id)
      setCoupons((prev) => prev.filter((coupon) => coupon.id !== id))
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to delete coupon.')
    }
  }

  const pushCouponRecord = async (id, connectionId) => {
    setWorkspaceError('')
    const updated = await pushCoupon(authToken, id, { connectionId })
    setCoupons((prev) => prev.map((coupon) => (coupon.id === id ? updated : coupon)))
    return updated
  }

  const personalizeCouponRecord = async (id, payload) => {
    setWorkspaceError('')
    const updated = await personalizeCoupon(authToken, id, payload)
    setCoupons((prev) => prev.map((coupon) => (coupon.id === id ? updated : coupon)))
    return updated
  }

  const decideCouponPersonalizationRecord = async (id, decision) => {
    setWorkspaceError('')
    const updated = await decideCouponPersonalization(authToken, id, decision)
    setCoupons((prev) => prev.map((coupon) => (coupon.id === id ? updated : coupon)))
    return updated
  }

  // ---- marketplace connections ---------------------------------------
  const connectMarketplaceRecord = async (payload) => {
    setWorkspaceError('')
    const created = await connectMarketplace(authToken, { userId, ...payload })
    setMarketplaceConnections((prev) => [created, ...prev])
    return created
  }

  const disconnectMarketplaceRecord = async (id) => {
    setWorkspaceError('')
    try {
      await deleteMarketplaceConnection(authToken, id)
      setMarketplaceConnections((prev) => prev.filter((conn) => conn.id !== id))
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to disconnect marketplace.')
    }
  }

  // ---- analytics / attribution ---------------------------------------
  const loadInfluencerRevenue = async () => getInfluencerRevenue(authToken)

  const simulateOrderRecord = async (payload) => {
    const result = await simulateOrder(authToken, { userId, ...payload })
    // Refresh coupons so sync/attribution-derived state stays current.
    try {
      const refreshed = await listCoupons(authToken)
      setCoupons(refreshed)
    } catch {
      // Non-fatal; dashboard refetches its own analytics.
    }
    return result
  }

  // ---- commissions & payouts -----------------------------------------
  const loadCommissions = async () => listCommissions(authToken)
  const loadPayouts = async () => listPayouts(authToken)
  const loadPayoutProviders = async () => listPayoutProviders(authToken)
  const approveCommissionRecord = async (id) => approveCommission(authToken, id)
  const createPayoutRecord = async (payload) => createPayoutBatch(authToken, { userId, ...payload })

  // ---- content: campaign briefs ---------------------------------------
  const loadCampaignBriefs = async () => listCampaignBriefs(authToken)
  const saveCampaignBrief = async (id, payload) => {
    if (id) return updateCampaignBrief(authToken, id, { userId, ...payload })
    return createCampaignBrief(authToken, { userId, ...payload })
  }
  const loadLandingTemplates = async () => listLandingTemplates(authToken)
  const saveLandingTemplateRecord = async (payload) => saveLandingTemplate(authToken, { userId, ...payload })
  const loadCouponsForContent = async () => listCoupons(authToken)
  const draftContentRecord = async (payload) => draftContent(authToken, payload)
  const previewLandingRecord = async (payload) => previewLandingTemplate(authToken, { userId, ...payload })

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

  // ---- workflow boards ----
  const createBoardRecord = async ({ name, startDate, endDate, stages }) => {
    const trimmed = String(name || '').trim()
    if (!trimmed) {
      throw new Error('A board name is required.')
    }
    const nextPosition = workflowBoards.reduce((max, b) => Math.max(max, Number(b.position || 0) + 1), 0)
    // First board (or an explicitly-first one) becomes the active selection.
    const makeActive = workflowBoards.length === 0
    const board = await createWorkflowBoard(authToken, {
      userId,
      name: trimmed,
      startDate: startDate || null,
      endDate: endDate || null,
      isActive: makeActive,
      position: nextPosition,
    })

    let savedStages = []
    const stageNames = (Array.isArray(stages) ? stages : [])
      .map((s) => String(s?.stageName ?? s ?? '').trim())
      .filter(Boolean)
    if (stageNames.length) {
      savedStages = await replaceWorkflowBoardStages(authToken, {
        userId,
        boardId: board.id,
        stages: stageNames.map((stageName, index) => ({ stageName, position: index })),
      })
    }

    setWorkflowBoards((prev) => [...prev, board])
    setWorkflowBoardStages((prev) => [...prev, ...savedStages])
    if (makeActive) {
      setActiveBoardId(board.id)
    }
    return { board, stages: savedStages }
  }

  // Returns { board, stages } without touching state (caller updates state).
  const createDefaultBoard = async () => {
    const board = await createWorkflowBoard(authToken, {
      userId,
      name: 'Default Board',
      isActive: true,
      position: 0,
    })
    const stages = await replaceWorkflowBoardStages(authToken, {
      userId,
      boardId: board.id,
      stages: DEFAULT_BOARD_STAGES.map((stageName, index) => ({ stageName, position: index })),
    })
    return { board, stages }
  }

  const createDefaultBoardRecord = async () => {
    const { board, stages } = await createDefaultBoard()
    setWorkflowBoards((prev) => [...prev, board])
    setWorkflowBoardStages((prev) => [...prev, ...stages])
    setActiveBoardId(board.id)
    // Creating an active board deactivates the others server-side; mirror locally.
    setWorkflowBoards((prev) => prev.map((b) => (b.id === board.id ? b : { ...b, isActive: false })))
    return board
  }

  const selectBoard = async (boardId) => {
    const existing = workflowBoards.find((b) => b.id === boardId)
    if (!existing || boardId === activeBoardId) {
      setActiveBoardId(boardId)
      return
    }
    // Optimistic: mark this board active, others inactive.
    setActiveBoardId(boardId)
    setWorkflowBoards((prev) => prev.map((b) => ({ ...b, isActive: b.id === boardId })))
    try {
      const updated = await updateWorkflowBoard(authToken, boardId, {
        userId,
        name: existing.name,
        startDate: existing.startDate || null,
        endDate: existing.endDate || null,
        isActive: true,
        position: existing.position ?? 0,
      })
      setWorkflowBoards((prev) => prev.map((b) => (b.id === boardId ? updated : b)))
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to select board.')
    }
  }

  const updateBoardRecord = async (id, patch) => {
    const existing = workflowBoards.find((b) => b.id === id)
    if (!existing) return
    const merged = {
      ...existing,
      ...patch,
      userId,
      isActive: patch?.isActive ?? existing.isActive ?? false,
      position: existing.position ?? 0,
    }
    setWorkflowBoards((prev) => prev.map((b) => (b.id === id ? merged : b)))
    try {
      setWorkspaceError('')
      const updated = await updateWorkflowBoard(authToken, id, merged)
      setWorkflowBoards((prev) => prev.map((b) => (b.id === id ? updated : b)))
    } catch (error) {
      setWorkflowBoards((prev) => prev.map((b) => (b.id === id ? existing : b)))
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to update board.')
      throw error
    }
  }

  const deleteBoardRecord = async (id) => {
    const existing = workflowBoards.find((b) => b.id === id)
    if (!existing) return
    const remaining = workflowBoards.filter((b) => b.id !== id)
    setWorkflowBoards(remaining)
    setWorkflowBoardStages((prev) => prev.filter((s) => s.boardId !== id))
    if (activeBoardId === id) {
      setActiveBoardId(remaining[0]?.id || '')
    }
    try {
      setWorkspaceError('')
      await deleteWorkflowBoard(authToken, id)
    } catch (error) {
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to delete board.')
      await refreshWorkspaceData()
      throw error
    }
  }

  const saveBoardStagesRecord = async (boardId, stages) => {
    const stageNames = (Array.isArray(stages) ? stages : [])
      .map((s, i) => ({ stageName: String(s?.stageName || '').trim(), position: i }))
      .filter((s) => s.stageName)
    const saved = await replaceWorkflowBoardStages(authToken, {
      userId,
      boardId,
      stages: stageNames,
    })
    setWorkflowBoardStages((prev) => [...prev.filter((s) => s.boardId !== boardId), ...saved])
    return saved
  }

  // ---- workflow cards (campaign<->creator relationship tasks) ----
  const createCardRecord = async ({ campaignId, creatorId, name, status, agreedFee, feeCurrency, notes, tags }) => {
    if (!campaignId || !creatorId) {
      throw new Error('Select both a campaign and a creator.')
    }
    if (!String(name || '').trim()) {
      throw new Error('A card name is required.')
    }
    const created = await createWorkflowCard(authToken, {
      userId,
      campaignId,
      creatorId,
      name: String(name).trim(),
      status: status || 'todo',
      agreedFee: agreedFee ? String(agreedFee).trim() : null,
      feeCurrency: feeCurrency || 'USD',
      notes: String(notes || '').trim() || null,
      tags: Array.isArray(tags) ? tags : [],
      boardId: null,
      stageId: null,
    })
    setWorkflowCards((prev) => [created, ...prev])
    return created
  }

  const placeCardRecord = async (cardId, { boardId, stageId }) => {
    const existing = workflowCards.find((c) => c.id === cardId)
    if (!existing) return
    const optimistic = { ...existing, boardId: boardId || null, stageId: stageId || null }
    setWorkflowCards((prev) => prev.map((c) => (c.id === cardId ? optimistic : c)))
    try {
      setWorkspaceError('')
      const updated = await placeWorkflowCard(authToken, cardId, { boardId, stageId })
      setWorkflowCards((prev) => prev.map((c) => (c.id === cardId ? updated : c)))
    } catch (error) {
      setWorkflowCards((prev) => prev.map((c) => (c.id === cardId ? existing : c)))
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to place card.')
      throw error
    }
  }

  const deleteCardRecord = async (cardId) => {
    const existing = workflowCards.find((c) => c.id === cardId)
    if (!existing) return
    setWorkflowCards((prev) => prev.filter((c) => c.id !== cardId))
    try {
      setWorkspaceError('')
      await deleteWorkflowCard(authToken, cardId)
    } catch (error) {
      setWorkflowCards((prev) => [existing, ...prev])
      setWorkspaceError(error instanceof Error ? error.message : 'Unable to delete card.')
      throw error
    }
  }

  const handleLogout = async () => {
    try {
      // Logout revokes the refresh token; the access token stays valid until it expires.
      if (refreshToken) {
        await logout(refreshToken)
      }
    } catch {
      // Ignore logout API failures and clear local session state anyway.
    }

    setIsLoggedIn(false)
    setAuthToken('')
    setRefreshToken('')
    setUserId('')
    setBrandId('')
    setAccountId('')
    setRole('')
    setAvailableBrands([])
    setActiveBrandId('')
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
                onSocialLogin={handleSocialLogin}
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
              <SessionProvider
                value={{
                  userId,
                  userName,
                  email: '',
                  authToken,
                  accountId,
                  brandId,
                  brandName,
                  availableBrands,
                  onSwitchBrand: handleSwitchBrand,
                  role,
                  permissions,
                }}
              >
                <WorkspaceLayout
                  brandName={brandName}
                  userName={userName}
                  onLogout={handleLogout}
                  workspaceError={workspaceError}
                  brands={availableBrands}
                  activeBrandId={brandId}
                  onSwitchBrand={handleSwitchBrand}
                  role={role}
                  permissions={permissions}
                />
              </SessionProvider>
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
                  boards={workflowBoards}
                  boardStages={workflowBoardStages}
                  activeBoardId={activeBoardId}
                  onSelectBoard={selectBoard}
                  onCreateBoard={createBoardRecord}
                  onCreateDefaultBoard={createDefaultBoardRecord}
                  onDeleteBoard={deleteBoardRecord}
                  onUpdateBoard={updateBoardRecord}
                  onSaveBoardStages={saveBoardStagesRecord}
                  campaigns={campaigns}
                  creators={creators}
                  cards={workflowCards}
                  onCreateCard={createCardRecord}
                  onPlaceCard={placeCardRecord}
                  onDeleteCard={deleteCardRecord}
                />
              }
            />
            <Route
              path="coupons"
              element={
                <CouponsPage
                  coupons={coupons}
                  campaigns={campaigns}
                  creators={creators}
                  campaignCreators={workflowCards}
                  brandName={brandName}
                  connections={marketplaceConnections}
                  onGenerateCoupon={generateCouponRecord}
                  onGenerateCouponsBulk={generateCouponsBulkRecord}
                  onDeleteCoupon={deleteCouponRecord}
                  onPushCoupon={pushCouponRecord}
                  onPersonalizeCoupon={personalizeCouponRecord}
                  onDecidePersonalization={decideCouponPersonalizationRecord}
                />
              }
            />
            <Route
              path="marketplace"
              element={
                <MarketplacePage
                  providers={marketplaceProviders}
                  connections={marketplaceConnections}
                  onConnect={connectMarketplaceRecord}
                  onDisconnect={disconnectMarketplaceRecord}
                />
              }
            />
            <Route
              path="dashboard"
              element={
                <DashboardPage
                  coupons={coupons}
                  onLoadRevenue={loadInfluencerRevenue}
                  onSimulateOrder={simulateOrderRecord}
                />
              }
            />
            <Route
              path="payouts"
              element={
                <PayoutsPage
                  creators={creators}
                  onLoadCommissions={loadCommissions}
                  onLoadPayouts={loadPayouts}
                  onLoadProviders={loadPayoutProviders}
                  onApproveCommission={approveCommissionRecord}
                  onCreatePayout={createPayoutRecord}
                />
              }
            />
            <Route
              path="content"
              element={
                <ContentPage
                  campaigns={campaigns}
                  coupons={coupons}
                  onLoadBriefs={loadCampaignBriefs}
                  onSaveBrief={saveCampaignBrief}
                  onLoadTemplates={loadLandingTemplates}
                  onSaveTemplate={saveLandingTemplateRecord}
                  onReloadCoupons={loadCouponsForContent}
                  onDraftContent={draftContentRecord}
                  onPreviewLanding={previewLandingRecord}
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
