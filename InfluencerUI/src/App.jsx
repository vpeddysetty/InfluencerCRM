import { Navigate, Route, Routes } from 'react-router-dom'
import { useCallback, useEffect, useState } from 'react'
import './App.css'
import './components/ui/ui.css'
import { SessionExpiredDialog, ToastProvider, WorkspaceOnboardingDialog } from './components/ui'
import { DEFAULT_ROUTE } from './shell/routeManifest'
import { msUntilRefresh } from './shell/sessionExpiry'
import LandingPage from './pages/LandingPage'
import AcceptInvitationPage from './pages/AcceptInvitationPage'
import ImportPage from './pages/ImportPage'
import CampaignsPage from './pages/CampaignsPage'
import CreatorsPage from './pages/CreatorsPage'
import CreatorRecordPage from './pages/CreatorRecordPage'
import WorkflowPage from './pages/WorkflowPage'
import CouponsPage from './pages/CouponsPage'
import MarketplacePage from './pages/MarketplacePage'
import DashboardPage from './pages/DashboardPage'
import PayoutsPage from './pages/PayoutsPage'
import MembersPage from './pages/MembersPage'
import SettingsPage from './pages/SettingsPage'
import BillingPage from './pages/BillingPage'
import ContentPage from './pages/ContentPage'
import WorkspaceLayout from './components/WorkspaceLayout'
import { SessionProvider } from './shell/SessionContext'
import {
  completeOnboarding,
  createBrand,
  disconnectAccount,
  listConnectedAccounts,
  createCampaign,
  createCreator,
  resolveCreatorHandle,
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
  acceptInvitation,
  approveCommission,
  listPayouts,
  createPayoutBatch,
  listPayoutProviders,
  listAccountMembers,
  listInvitations,
  loadPlanUsage,
  loadSubscription,
  loadBillingInvoices,
  subscribeToPlan,
  pauseSubscription,
  resumeSubscription,
  cancelSubscription,
  inviteMember,
  bulkInviteMembers,
  resendInvitation,
  revokeInvitation,
  updateMemberRole,
  removeMember,
  listCampaignBriefs,
  createCampaignBrief,
  updateCampaignBrief,
  listLandingTemplates,
  saveLandingTemplate,
  previewLandingTemplate,
  listLandingVersions,
  restoreLandingVersion,
  listAssets,
  uploadAsset,
  draftContent,
  login,
  previewImportBatch,
  logout,
  refreshSession,
  fetchDpsSession,
  dpsCsrfHeaders,
  useCookieSession,
  clearCookieSession,
  isCookieSession,
  setAuthHandlers,
  setActiveBrandId,
  listBrands,
  switchBrand,
  signup,
  updateCampaign,
  updateImportColumnMapping,
  updateCreator,
} from './api'
// Imported directly rather than through the './api' barrel: analytics is cross-cutting, not a
// context slice, and a remote that imports one slice should not pull instrumentation with it.
import { EVENTS, identify, resetIdentity, setAnalyticsBrand, track } from './api/analytics'
import { createImportMappingJson, createImportMappingJsonFromAgent, parseSpreadsheetFile, DEFAULT_BOARD_STAGES } from './constants'

// v3: tenancy moved from the user to the active brand. A v2 snapshot has no brandId, so its
// cached domain rows belong to an unknown tenant — versioning the key discards them rather than
// risk showing one brand's data under another's name.
const STORAGE_KEY = 'tejdux_ui_state_v3'

// Set immediately before a social SIGN-UP redirect and read once the browser comes back, to decide
// whether to ask for workspace details. sessionStorage rather than localStorage: the marker is only
// meaningful for the tab that left for the provider, and a localStorage copy would outlive the flow
// and greet the user with an onboarding step on some unrelated later visit.
//
// This is deliberately client-side. The alternative — a "needsOnboarding" flag threaded through the
// DPS session, the BFF and the DAO — would mean editing the OAuth path that has to work for Meta's
// review, to carry a hint the browser already has.
const PENDING_SOCIAL_SIGNUP_KEY = 'tejdux_pending_social_signup'
const PENDING_ACCOUNT_TYPE_KEY = 'tejdux_pending_account_type'

// Origin of the Digital Presentation Service, which owns federated sign-in end to end. Kept in
// step with the same constant in shell/gateway/PresentationGateway.js.
const DPS_BASE_URL =
  import.meta.env?.VITE_DPS_URL ||
  (typeof window !== 'undefined' && window.location?.origin ? window.location.origin : 'http://localhost:8090')
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
  /**
   * Never restored from the snapshot, for the same reason the tokens below are not.
   *
   * <p>This flag alone gates the entire workspace, and it *was* persisted while the credentials
   * deliberately were not. A reload therefore rebuilt the shell in its signed-in state with no
   * token to call anything: every request went out unauthenticated and the user got a workspace
   * of error banners rather than a login screen. Restoring "you are logged in" from a source that
   * cannot also restore "here is your proof" is what made the two disagree.
   *
   * <p>The snapshot's other fields — brand, role, cached rows — stay restored. They are display
   * state, harmless without a token, and keeping them is what lets a re-login land back in place.
   */
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [brandName, setBrandName] = useState(persistedState?.brandName ?? 'tejdux.io')
  const [userName, setUserName] = useState(persistedState?.userName ?? '')
  // Never seeded from persisted state: tokens are no longer written to localStorage, and an
  // older snapshot that still carries one must not be a way to resurrect it.
  const [authToken, setAuthToken] = useState('')
  const [refreshToken, setRefreshToken] = useState('')
  // Populated only when the session was restored from the DPS cookie, where there is no token to
  // read them out of. Empty in bearer mode, which is why `permissions` prefers the token.
  const [cookiePermissions, setCookiePermissions] = useState([])
  // Blocks the first paint until the DPS has been asked whether a session exists. Without it the
  // login page renders for a moment before being replaced by the workspace, which reads as a
  // sign-in flashing past on every reload.
  // The reason a social sign-in was refused, as the DPS put it in ?error=.
  //
  // Read once at mount rather than from the router: this arrives on a full-page redirect from the
  // provider, so the value is in the URL before React has rendered anything, and it must survive
  // the render that follows. Held in state so clearing the query string does not erase the message
  // along with it.
  const [oauthErrorFromUrl, setOauthErrorFromUrl] = useState(() => {
    try {
      return new URLSearchParams(window.location.search).get('error') || ''
    } catch {
      return ''
    }
  })

  // Set when the browser returns from a successful provider link, so settings can confirm it
  // happened. Read at mount for the same reason as the error above: it arrives on a redirect.
  //
  // DECLARED BEFORE THE EFFECT THAT READS IT. `const` is not hoisted, so an effect referencing this
  // from above lands in the temporal dead zone and throws ReferenceError on the very first render —
  // which React treats as the whole tree failing to mount, and the page renders blank. The blankness
  // is the symptom of any error thrown here, so declaration order in this block is load-bearing.
  const [linkedProviderNotice] = useState(() => {
    try {
      return new URLSearchParams(window.location.search).get('linked') || ''
    } catch {
      return ''
    }
  })

  // Strip ?error= once it has been read. Left in place it survives a reload and a bookmark, so a
  // user who signs in successfully and later returns to the URL is told again that they failed.
  useEffect(() => {
    if (!oauthErrorFromUrl && !linkedProviderNotice) {
      return
    }
    try {
      const url = new URL(window.location.href)
      url.searchParams.delete('error')
      url.searchParams.delete('linked')
      // The provider appends #_=_ to the fragment on the way back; it is meaningless to the app and
      // ugly in the address bar, so it goes at the same time.
      const cleaned = url.pathname + url.search + (url.hash === '#_=_' ? '' : url.hash)
      window.history.replaceState({}, '', cleaned)
    } catch {
      // A browser that refuses history rewriting keeps the query string. Harmless.
    }
  }, [oauthErrorFromUrl, linkedProviderNotice])

  const [restoringSession, setRestoringSession] = useState(true)
  // Post-social-signup workspace details. Deliberately NOT persisted to localStorage: it describes
  // one moment in one tab, and a restored `open: true` would ambush a returning user with a form
  // asking them to name a workspace they named weeks ago.
  const [onboarding, setOnboarding] = useState({ open: false, accountType: 'brand' })
  const [userId, setUserId] = useState(persistedState?.userId ?? '')
  // Active brand plus the set the user may switch to. Solo accounts have exactly one entry,
  // which is why the switcher can be hidden without needing a separate code path.
  const [brandId, setBrandId] = useState(persistedState?.brandId ?? '')
  const [accountId, setAccountId] = useState(persistedState?.accountId ?? '')
  const [role, setRole] = useState(persistedState?.role ?? '')
  const [availableBrands, setAvailableBrands] = useState(persistedState?.availableBrands ?? [])
  // Brand usage against the plan's cap, for the rail's "+ Add brand" control. Deliberately NOT
  // persisted: the plan is kept out of the JWT so an upgrade takes effect immediately, and a
  // restored copy would reintroduce exactly the staleness that decision avoids.
  const [brandCapacity, setBrandCapacity] = useState(null)
  /**
   * Permissions for rendering decisions.
   *
   * <p>From the token when the SPA holds one, so it cannot drift from the credential the server
   * will actually evaluate. In cookie mode there is no token to read, so they come from the
   * session the DPS returned — which the DPS itself read out of that same token
   * (`UiSessionService.readPermissions`) rather than recomputing. Both paths therefore trace back
   * to one authority; the DPS deliberately does not implement its own permission matrix, because
   * two implementations disagreeing is what once locked FINANCE users out entirely.
   *
   * <p>Either way this only decides what to offer. The server re-checks every call.
   */
  const permissions = authToken ? readPermissionsFromToken(authToken) : cookiePermissions
  const [authError, setAuthError] = useState('')
  // Shown when a token could not be renewed silently. Held as state rather than handled inside
  // the API layer so the workspace stays mounted underneath — the user keeps their place, and
  // anything unsaved survives long enough for them to decide.
  const [sessionPrompt, setSessionPrompt] = useState(false)
  const [sessionRetrying, setSessionRetrying] = useState(false)
  const [sessionRetryError, setSessionRetryError] = useState('')
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
    // Deliberately NOT restored from persisted state, and listed after the spread so a stored
    // value cannot win. A follower count is only meaningful next to the handle it was read for
    // and the moment it was read: restoring one from a previous session would stamp a stale
    // audience — possibly for a different creator entirely — onto the next record saved.
    resolvedMetrics: null,
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

  /**
   * Ask the DPS once, at boot, whether this browser already has a session.
   *
   * <p>This is the missing half of two flows. A hard reload drops the in-memory token but not the
   * httpOnly cookie, and the OAuth flow lands here with that cookie already set by
   * `/dps/auth/oauth/complete` — which redirects to `/` specifically so that "its first
   * /dps/session call already sees an authenticated user". Nothing was making that call.
   *
   * <p>Runs exactly once. A dependency on anything the restore itself sets would re-enter this on
   * every session change and re-restore over a live session.
   */
  useEffect(() => {
    let isActive = true

    const restore = async () => {
      try {
        const session = await fetchDpsSession(DPS_BASE_URL)
        if (!isActive || !session?.authenticated) {
          return
        }

        // Order matters: the transport has to be in cookie mode before establishSession flips
        // isLoggedIn, or the workspace effect fires one round of requests down the bearer path
        // with no token to send.
        useCookieSession(DPS_BASE_URL)
        setCookiePermissions(Array.isArray(session.permissions) ? session.permissions : [])
        if (Array.isArray(session.availableBrands)) {
          setAvailableBrands(session.availableBrands)
        }
        establishSession(session, {
          userName: session.userName || '',
          brandName: session.brandName || '',
        })

        // A social SIGN-UP that has just landed back here still has a workspace named after the
        // provider profile, and — for an agency — the wrong account type. Ask now, while the
        // intent is fresh; the marker is consumed either way so a reload does not re-ask.
        try {
          if (window.sessionStorage.getItem(PENDING_SOCIAL_SIGNUP_KEY)) {
            window.sessionStorage.removeItem(PENDING_SOCIAL_SIGNUP_KEY)
            const pendingType = window.sessionStorage.getItem(PENDING_ACCOUNT_TYPE_KEY) || 'brand'
            window.sessionStorage.removeItem(PENDING_ACCOUNT_TYPE_KEY)
            setOnboarding({ open: true, accountType: pendingType })
          }
        } catch {
          // Storage unavailable: skip the step rather than block a signed-in user behind it.
        }
      } finally {
        if (isActive) {
          setRestoringSession(false)
        }
      }
    }

    restore()
    return () => {
      isActive = false
    }
  }, [])

  useEffect(() => {
    // Credentials are deliberately absent from this snapshot. The session lives server-side in
    // the DPS behind an httpOnly cookie; writing the access or refresh token to localStorage
    // would hand an XSS payload the one thing that design exists to keep out of reach.
    //
    // isLoggedIn is absent for the same reason and is no longer read on load: a snapshot that
    // cannot carry the credential must not carry the claim either. Writing it would leave a
    // stale `true` in storage that reads as authoritative to anyone inspecting it.
    const snapshot = {
      isSignUp,
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
      // Ask rather than evict. This previously cleared the session and returned the user to the
      // login screen mid-task — including any open drawer with unsaved edits. A failed refresh is
      // often recoverable (another tab rotated the token, the server restarted), so the user gets
      // the choice: retry, or sign out deliberately. Everything stays on screen until they pick.
      onSessionExpired: () => {
        setSessionPrompt(true)
      },
    })
  }, [refreshToken])

  /**
   * Renew the access token shortly before it expires.
   *
   * <p>The API layer already retries once through /api/auth/refresh on a 401, but only *after* a
   * request has failed — so the user sees an error banner first and the recovery second. This
   * timer moves the renewal ahead of the failure, which is what makes the refresh invisible.
   *
   * <p>Keyed on the access token: each successful refresh installs a new one and re-arms the timer
   * for the next window.
   */
  useEffect(() => {
    if (!isLoggedIn || !authToken || !refreshToken) {
      return undefined
    }

    const delay = msUntilRefresh(authToken)
    if (delay === null) {
      // No readable `exp`. Leave it to the API layer's reactive 401 retry rather than guessing at
      // a refresh cadence — a wrong guess would either spin or never fire.
      return undefined
    }

    const timer = setTimeout(async () => {
      try {
        const refreshed = await refreshSession(refreshToken)
        setAuthToken(refreshed?.accessToken || '')
        setRefreshToken(refreshed?.refreshToken || '')
        applyBrandFromAuth(refreshed)
      } catch {
        // The refresh token is dead. Prompt rather than evict — same reasoning as
        // onSessionExpired above.
        setSessionPrompt(true)
      }
    }, delay)

    return () => clearTimeout(timer)
  }, [isLoggedIn, authToken, refreshToken])

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

  // Brand capacity for the rail control. Gated on isLoggedIn ALONE, not on authToken: a cookie
  // session holds no bearer token, so an `!authToken` guard would skip this for exactly the
  // federated sign-ins that land on the free tier.
  useEffect(() => {
    if (!isLoggedIn) {
      return
    }
    let isActive = true
    loadPlanUsage(authToken)
      .then((usage) => {
        if (!isActive) {
          return
        }
        // PlanUsageResponse(plan, usage) with ResourceUsage(resource, label, used, limit);
        // `resource` is the lowercased enum name, so BRAND arrives as "brand".
        const brand = (usage?.usage || []).find((row) => row?.resource === 'brand')
        setBrandCapacity(
          brand ? { used: Number(brand.used ?? 0), limit: Number(brand.limit ?? -1) } : null,
        )
      })
      .catch(() => {
        // Non-fatal: capacity stays null, which leaves the control visible and lets the server
        // remain the authority. Better than hiding a legitimate agency's only path to a brand.
      })
    return () => {
      isActive = false
    }
  }, [isLoggedIn, authToken, brandId, availableBrands.length])

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

  const handleAuthSubmit = async (event, options = {}) => {
    const form = new FormData(event.currentTarget)
    const rawIdentifier = String(form.get('email') || '')
    const email = isSignUp ? rawIdentifier : normalizeLoginEmail(rawIdentifier)
    const inferredName = email.includes('@') ? email.split('@')[0] : email
    const name = String(form.get('fullName') || inferredName || 'Brand Operator')
    // Falls back to the person's own name rather than this platform's domain: an empty brand
    // field should not silently name someone's workspace after us.
    const company = String(form.get('brand') || '').trim() || `${name}'s workspace`
    const password = String(form.get('password') || '')
    // The landing page's workspace-type radio. Absent on the login tab, and defaulted rather
    // than sent blank so the server's own default stays the single source of that rule.
    const accountType = String(form.get('accountType') || 'brand')
    // The consent checkbox, passed in by the caller rather than read from the form.
    //
    // It used to come from FormData, which worked only while the checkbox sat inside <form>. When
    // it moved below the form — to sit under BOTH sign-up paths, since it governs the social
    // buttons too — FormData stopped seeing it, so every signup was sent with acceptedTerms=false
    // and the server refused it with "You must accept the Terms of Service and Privacy Policy".
    // The page renders the box, the user ticks it, and the value never left the browser.
    //
    // The fallback keeps the old behaviour for any caller that still relies on the field being in
    // the form, so this cannot silently break a second entry point.
    const acceptedTerms = options.acceptedTerms ?? (form.get('acceptedTerms') === 'on')

    try {
      setAuthError('')
      setWorkspaceError('')

      const authResponse = isSignUp
        ? await signup({ email, password, brandName: company, accountType, acceptedTerms })
        : await login({ email, password })

      // One path establishes a session. This used to repeat establishSession's body line for
      // line, which is how the two drifted apart until only this copy was reachable at all.
      establishSession(authResponse, { userName: name, brandName: authResponse.brandName || company })

      if (isSignUp) {
        // The denominator for M4's activation metric — "% of signups that complete an import
        // within 24h" is meaningless without a reliable count of signups.
        track(EVENTS.SIGNUP, { accountType })
      }
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
    // Keep events attributed to the same tenant the requests go to. Analytics follows the brand
    // header for the same reason: one place decides the tenant.
    setAnalyticsBrand(nextBrandId)
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

  /**
   * Finishes a social sign-up by naming the workspace, and promoting it to an agency if asked.
   *
   * <p>The server re-mints the token for the same reason switching does: the current one carries
   * the provider-derived brandName the session was created with, so keeping it would leave the
   * header showing "Ari Rivera" after the workspace had been renamed.
   *
   * <p>Cookie sessions carry no bearer token, and `request` sends the cookie instead — passing an
   * empty authToken is correct there rather than a bug.
   */
  const handleCompleteOnboarding = async ({ workspaceName, accountType }) => {
    const updated = await completeOnboarding(authToken, { workspaceName, accountType })
    if (updated.accessToken) {
      setAuthToken(updated.accessToken)
    }
    applyBrandFromAuth(updated)
    setOnboarding({ open: false, accountType: 'brand' })
  }

  /**
   * Renames the workspace from settings.
   *
   * <p>The same endpoint the onboarding dialog posts to, which is idempotent and renames the brand
   * in the caller's own token — never one named in the body. accountType is omitted deliberately:
   * that endpoint only ever upgrades, and a rename must not quietly change what kind of account
   * this is. The response re-mints the session, so the sidebar and header follow immediately
   * instead of showing the old name until the next sign-in.
   */
  const renameWorkspace = async (name) => {
    const updated = await completeOnboarding(authToken, { workspaceName: name })
    if (updated.accessToken) {
      setAuthToken(updated.accessToken)
    }
    applyBrandFromAuth(updated)
  }

  /**
   * Creates a brand and switches into it.
   *
   * Switching is not a convenience — a brand created and left in the background looks like
   * nothing happened, and the account still appears to have one brand until the next reload.
   * Delegating to handleSwitchBrand rather than reimplementing it also means the token is
   * re-minted (role and permissions are per-brand) and the previous brand's cached rows are
   * cleared, both of which this path needs for exactly the same reasons.
   */
  const createBrandRecord = async (name) => {
    const created = await createBrand(authToken, name)

    // Refresh from the server rather than appending locally: listBrands is the authority on
    // which brands this user can reach, and the new row needs its server-assigned id anyway.
    try {
      const brands = await listBrands(authToken)
      setAvailableBrands(Array.isArray(brands) ? brands : brands?.items || [])
    } catch {
      // Non-fatal — the switch below refreshes the session, and a stale list self-corrects on
      // the next load. Failing here would strand a brand that was created successfully.
    }

    if (created?.brandId || created?.id) {
      await handleSwitchBrand(created.brandId || created.id)
    }
    return created
  }

  /**
   * The single place a session comes into existence.
   *
   * <p>Every caller goes through here — the sign-in form, sign-up, and any future restore path —
   * so there is one answer to "what does being logged in consist of". It previously existed
   * alongside a copy of itself inside handleAuthSubmit and was never called; keeping two of these
   * is what let them disagree, and the surviving copy is the one that sets every field.
   *
   * <p>Note the order: the token is set before isLoggedIn. The workspace-loading effect keys on
   * both, and flipping isLoggedIn first would give it one render with a stale token.
   *
   * @param overrides display values a caller knows better than the response does — the sign-up
   *                  form has the person's typed name, where the response has only an email
   */
  const establishSession = (authResponse, overrides = {}) => {
    const email = authResponse.email || ''
    const inferredName = email.includes('@') ? email.split('@')[0] : email
    setUserName(overrides.userName || inferredName || 'Brand Operator')
    setBrandName(overrides.brandName || authResponse.brandName || '')
    setUserId(authResponse.userId || '')
    setAuthToken(authResponse.accessToken || '')
    setRefreshToken(authResponse.refreshToken || '')
    applyBrandFromAuth(authResponse)
    setIsLoggedIn(true)
    // Identify before any event is tracked, so the first one carries its ids rather than arriving
    // anonymous and needing to be stitched later. Week-two retention (what M4 measures) is
    // entirely returning users, so this has to cover every sign-in path and not just sign-up.
    identify({
      userId: authResponse.userId,
      accountId: authResponse.accountId,
      brandId: authResponse.brandId,
    })
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
  /**
   * Connects a provider to the account already signed in.
   *
   * <p>A full-page navigation rather than a fetch, for the same reason sign-in is: the next thing
   * that happens is the provider's own consent screen, which cannot be rendered inside an XHR.
   *
   * <p>Routed through the DPS so the session cookie authenticates it. The BFF endpoint requires a
   * verified caller — that requirement is the entire security property of linking, since being
   * signed in is what proves the account belongs to the person attaching the provider to it.
   */
  const startProviderLink = (provider) => {
    window.location.assign(`${DPS_BASE_URL}/dps/auth/connected-accounts/${provider}/start`)
  }

  const handleSocialLogin = (
    provider,
    { brandName: socialBrandName = '', accountType = '', acceptedTerms = false } = {},
  ) => {
    setAuthError('')
    setWorkspaceError('')
    // Clear the previous refusal before leaving for the provider. Kept, it would still be on screen
    // when the browser returns, describing an attempt two redirects ago.
    setOauthErrorFromUrl('')

    // Agency used to be refused here, because the type cannot ride the provider redirect and a
    // federated signup always provisions a `brand` account. It is no longer refused: the workspace
    // is created as a brand and the post-OAuth onboarding step promotes it, which is also where the
    // workspace gets its real name. Remembering the selection across the redirect is what makes
    // that step arrive pre-filled rather than asking the same question twice.
    // Only on sign-up: accountType is blank when this handler is reused for sign-in, and someone
    // signing in already named their workspace.
    if (accountType) {
      try {
        window.sessionStorage.setItem(PENDING_SOCIAL_SIGNUP_KEY, '1')
        window.sessionStorage.setItem(PENDING_ACCOUNT_TYPE_KEY, accountType)
      } catch {
        // A blocked sessionStorage (private mode, hardened settings) costs a pre-filled radio
        // button, not the signup. Without the marker the step is skipped and the workspace keeps
        // its provider-derived name, which the user can still change in settings.
      }
    }

    // Both values ride the URL because this is a NAVIGATION, not a fetch — there is no body to
    // put them in. acceptedTerms is forwarded by the DPS to the BFF, which refuses to start the
    // flow without it, so the redirect cannot begin for someone who has not consented.
    const params = new URLSearchParams()
    if (socialBrandName) {
      params.set('brandName', socialBrandName)
    }
    if (acceptedTerms) {
      params.set('acceptedTerms', 'true')
    }
    // Says "Log in", not "Sign up". The server skips the consent question on this path — consent
    // belongs to registration, and the Log in tab has no checkbox to tick — and in exchange refuses
    // to CREATE an account, so this cannot become a way to register without agreeing to anything.
    if (!accountType) {
      params.set('signInOnly', 'true')
    }
    const query = params.toString() ? `?${params}` : ''
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
      // rows is a 5-row sample for the preview table, so it cannot be used as a count. The
      // import button promises a number to the user; it has to be the file's real total.
      totalRowCount: Number(batch?.rowCount) || cachedRows.length,
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
      // Hydration is where an import becomes creators in the workspace — the actual moment of
      // first value, and the numerator for M4's activation metric. Tracked here rather than at
      // upload, because an uploaded file that is never hydrated has activated nobody.
      track(EVENTS.IMPORT_COMPLETED, {
        createdCount: hydrateResult.createdCount || 0,
        updatedCount: hydrateResult.updatedCount || 0,
        skippedCount: hydrateResult.skippedCount || 0,
      })
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
    // Step four of the Act 1 demo script, and the point where attribution becomes possible.
    track(EVENTS.COUPON_CREATED, { bulk: false })
    return created
  }

  const generateCouponsBulkRecord = async (payload) => {
    setWorkspaceError('')
    const created = await generateCouponsBulk(authToken, { userId, ...payload })
    setCoupons((prev) => [...created, ...prev])
    // One event carrying a count, not N events. A bulk generate is one user action, and
    // emitting one per coupon would make the funnel's coupon step outrank its own signup step.
    track(EVENTS.COUPON_CREATED, { bulk: true, count: created?.length || 0 })
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
  // useCallback because DashboardPage lists this in a useEffect dependency chain — the date-range
  // refresh. A new function identity every render would refetch analytics on every render, which
  // is an infinite loop rather than merely wasteful. Keyed on the token, so a re-auth still
  // produces a fresh closure.
  const loadInfluencerRevenue = useCallback(
    async (range) => getInfluencerRevenue(authToken, range),
    [authToken],
  )

  const simulateOrderRecord = async (payload) => {
    const result = await simulateOrder(authToken, { userId, ...payload })
    // `simulated: true` is not decoration. This path is the debug-gated simulator, so without
    // the flag these events would inflate the same attribution metric that real Shopify orders
    // land in from M3 — and the number would look best exactly when it was least real.
    track(EVENTS.ORDER_ATTRIBUTED, { simulated: true, status: payload?.status || 'purchase' })
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

  // ---- account members & invitations (roadmap Stage 3) -----------------
  const loadMembers = async () => {
    const rows = await listAccountMembers(authToken)
    return Array.isArray(rows) ? rows : rows?.items || []
  }
  const loadInvitations = async () => listInvitations(authToken)
  const inviteMemberRecord = async (payload) => inviteMember(authToken, payload)
  const bulkInviteMemberRecords = async (rows) => bulkInviteMembers(authToken, rows)
  const resendInvitationRecord = async (id) => resendInvitation(authToken, id)
  // Read on each visit rather than held in session state: the server deliberately keeps the plan
  // out of the JWT so an upgrade takes effect immediately, and caching it here would put the
  // staleness back in a different place.
  const loadPlan = async () => loadPlanUsage(authToken)

  // ---- subscription & billing (roadmap M2.1/M2.2) ----------------------
  // Every one of these is re-read after an action rather than patched locally: pausing changes
  // both the subscription AND the account's effective plan, so a local edit would show one and
  // not the other.
  const loadSubscriptionRecord = async () => loadSubscription(authToken)
  const loadInvoicesRecord = async () => loadBillingInvoices(authToken)
  // A hosted-checkout provider answers with a URL and does NOT activate — the user has not paid
  // yet. Sending them there is the rest of the flow; without this the subscription would sit in
  // `trialing` forever and look like a bug. A provider that takes no money returns no URL, and
  // the page just refreshes.
  const subscribeRecord = async (plan) => {
    const result = await subscribeToPlan(authToken, plan)
    if (result?.checkoutUrl) {
      // assign, not replace: coming back from Stripe should land on the billing page, and
      // replace() would remove it from history so "back" left the workspace entirely.
      window.location.assign(result.checkoutUrl)
    }
    return result
  }
  const pauseSubscriptionRecord = async () => pauseSubscription(authToken)
  const resumeSubscriptionRecord = async () => resumeSubscription(authToken)
  const cancelSubscriptionRecord = async (options) => cancelSubscription(authToken, options)

  /**
   * Redeems an invitation and refreshes the brand list.
   *
   * The refresh is what makes acceptance visible: joining an account adds a brand the user can
   * reach, and without re-reading the list the switcher would not show it until the next reload.
   */
  const acceptInvitationRecord = async (invitationToken) => {
    const result = await acceptInvitation(authToken, invitationToken)
    try {
      const brands = await listBrands(authToken)
      setAvailableBrands(Array.isArray(brands) ? brands : brands?.items || [])
    } catch {
      // Non-fatal: the membership is granted server-side regardless, and the list self-corrects
      // on the next load. Failing here would report an error for an acceptance that succeeded.
    }
    return result
  }
  const revokeInvitationRecord = async (id) => revokeInvitation(authToken, id)
  const updateMemberRoleRecord = async (memberUserId, role) =>
    updateMemberRole(authToken, memberUserId, role)
  const removeMemberRecord = async (memberUserId) => removeMember(authToken, memberUserId)

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
  const loadAssetsRecord = async () => listAssets(authToken)
  const uploadAssetRecord = async (file) => uploadAsset(authToken, file)
  const loadLandingVersionsRecord = async (campaignId) => listLandingVersions(authToken, campaignId)
  const restoreLandingVersionRecord = async (campaignId, versionNo) =>
    restoreLandingVersion(authToken, campaignId, versionNo)

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

  /**
   * Look up a handle on its platform, for the create drawer (C.2).
   *
   * <p>Returns the preview rather than storing it. Nothing is persisted until someone presses
   * Add creator, which keeps looking and saving as two separate acts — a brand vetting ten
   * handles should not end up with ten rows in its CRM.
   *
   * <p>An unresolvable handle comes back as `resolved: false` with a reason and is NOT an error:
   * private accounts and typos are the common case, and the drawer stays open on the manual
   * fields. Only a failed request throws.
   */
  const lookupCreatorHandle = async ({ platform, handle }) => {
    return resolveCreatorHandle(authToken, { platform, handle })
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
        // null rather than '' when blank: the column is numeric and would reject an empty string,
        // and "no rate agreed" is a real state distinct from a rate of zero.
        preferredRate: String(creatorForm.preferredRate ?? '').trim() === ''
          ? null
          : Number(creatorForm.preferredRate),
        customAttributes,
        // Metrics from a handle lookup, when one resolved. Spread rather than set field by field
        // so an absent lookup contributes nothing at all: writing `followerCount: null` would
        // record "we looked and they have no audience", which is a different claim from "nobody
        // looked". `metricsSource` travels with the numbers — a follower count saved without it
        // renders as Unknown provenance forever, and a simulated figure would become
        // indistinguishable from one Instagram answered with.
        ...(creatorForm.resolvedMetrics || {}),
      })

      setCreators((prev) => [nextCreator, ...prev])
      setAssignmentForm((prev) => ({ ...prev, creatorId: nextCreator.id || prev.creatorId }))
      // resolvedMetrics cleared with the rest: carrying one creator's follower count into the next
      // creator's form would attribute a real audience to the wrong person.
      setCreatorForm({ name: '', handle: '', platform: 'instagram', email: '', preferredRate: '', customAttributes: [], resolvedMetrics: null })
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
      // Carried explicitly. `...existing` below would otherwise win and the optimistic row would
      // show the old rate until the next refetch — the field looking like it failed to save.
      preferredRate: payload.preferredRate ?? null,
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
      // After the server confirms, not on the optimistic update above. This placement rolls back
      // on failure, and an event emitted before that would count moves that never happened —
      // in the metric M4 uses to judge whether anyone came back in week two.
      track(EVENTS.CARD_MOVED, {
        // Whether the move crossed boards or only changed stage. Same gesture, different
        // meaning: stage progress is pipeline movement, a board change is reorganisation.
        changedBoard: (existing.boardId || null) !== (boardId || null),
      })
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
      if (isCookieSession()) {
        // The credential is the DPS cookie, so clearing local state alone would leave the session
        // alive server-side — and the next boot would silently restore the person who just asked
        // to be signed out. This is the call that actually ends it.
        //
        // The CSRF header is not optional here: without it the DPS answers 403, the session
        // survives, and the sign-out is a lie the user has no way to detect. Verified against the
        // live DPS — 403 without the header, 204 with it.
        await fetch(`${DPS_BASE_URL}/dps/auth/logout`, {
          method: 'POST',
          credentials: 'include',
          headers: dpsCsrfHeaders(),
        })
      } else if (refreshToken) {
        // Logout revokes the refresh token; the access token stays valid until it expires.
        await logout(refreshToken)
      }
    } catch {
      // Ignore logout API failures and clear local session state anyway.
    }

    // Back to bearer transport, or a subsequent password sign-in would keep routing through a
    // proxy whose session no longer exists.
    clearCookieSession()
    setCookiePermissions([])
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
    // Clear analytics identity with the rest of the session. On a shared browser, leaving it set
    // would attribute the next person's events to whoever signed in last.
    resetIdentity()
  }

  /**
   * "Continue working" — try the refresh token once more, on demand.
   *
   * <p>Worth offering rather than going straight to the login screen: the common causes of a
   * failed silent refresh are transient. Another tab may have rotated the token a moment earlier,
   * or the network may have dropped. On success the workspace resumes exactly where it was.
   */
  const handleSessionRetry = async () => {
    if (isCookieSession()) {
      // The DPS refreshes its own tokens server-side, so a 401 through the proxy means the session
      // itself is gone rather than an access token having expired. Re-asking /dps/session is the
      // only retry available, and it is worth one attempt: a restarted DPS or a rotated cookie in
      // another tab both recover here.
      setSessionRetrying(true)
      setSessionRetryError('')
      try {
        const session = await fetchDpsSession(DPS_BASE_URL)
        if (session?.authenticated) {
          setCookiePermissions(Array.isArray(session.permissions) ? session.permissions : [])
          establishSession(session, {
            userName: session.userName || '',
            brandName: session.brandName || '',
          })
          setSessionPrompt(false)
        } else {
          setSessionRetryError('This session has ended. Please sign in again.')
        }
      } finally {
        setSessionRetrying(false)
      }
      return
    }
    if (!refreshToken) {
      // Nothing left to retry with. Say so plainly rather than spinning on a doomed request.
      setSessionRetryError('This session cannot be restored. Please sign in again.')
      return
    }
    setSessionRetrying(true)
    setSessionRetryError('')
    try {
      const refreshed = await refreshSession(refreshToken)
      setAuthToken(refreshed?.accessToken || '')
      setRefreshToken(refreshed?.refreshToken || '')
      applyBrandFromAuth(refreshed)
      setSessionPrompt(false)
      // Deliberately not re-pulling the workspace here: refreshWorkspaceData() reads authToken
      // from closure, which at this point is still the expired one — calling it would fire a
      // batch of doomed requests. The state update above re-renders with the new token, and the
      // next interaction loads fresh data through it.
    } catch (error) {
      setSessionRetryError(
        error instanceof Error && error.message
          ? error.message
          : 'Could not restore the session. Please sign in again.',
      )
    } finally {
      setSessionRetrying(false)
    }
  }

  /** "Sign out" — the deliberate exit, as opposed to being evicted mid-task. */
  const handleSessionSignOut = async () => {
    setSessionPrompt(false)
    setSessionRetryError('')
    await handleLogout()
    setAuthError('Your session expired. Please sign in again.')
  }

  // Hold the first paint while the DPS is asked about an existing session, or the login page
  // renders and is then replaced by the workspace — a sign-in screen flashing past on every
  // reload, which reads as being logged out and back in.
  //
  // /accept-invitation is exempt: an invitee arrives with a token in the URL and no session at
  // all, so making them wait on a lookup that will say "anonymous" delays the one page they came
  // for. It renders signed-out regardless of the answer.
  if (restoringSession && window.location.pathname !== '/accept-invitation') {
    return (
      <div className="app-boot" role="status" aria-live="polite">
        <span className="app-boot-label">Restoring your session…</span>
      </div>
    )
  }

  return (
    <ToastProvider>
    <Routes>
      {!isLoggedIn ? (
        <>
          {/* Where the DPS sends a failed sign-in, carrying ?error= with the reason.

              Until this route existed the redirect landed on the catch-all, which rendered the
              signed-out landing page and dropped the message — so a refusal that had a specific,
              actionable explanation ("an account already exists for this email, sign in with your
              password then link facebook") was indistinguishable from being randomly logged out.
              The server was explaining itself to nobody.

              Rendered by LandingPage rather than a page of its own: the thing to do after a failed
              sign-in is sign in, and that form is already here. */}
          <Route
            path="/login"
            element={
              <LandingPage
                isSignUp={false}
                setIsSignUp={setIsSignUp}
                onAuthSubmit={handleAuthSubmit}
                onSocialLogin={handleSocialLogin}
                authError={oauthErrorFromUrl || authError}
              />
            }
          />
          {/* Declared BEFORE the catch-all, which would otherwise swallow it and drop the token
              from the URL. An invitee almost never has an account yet, so this route existing
              signed-out is the point rather than an edge case. */}
          <Route
            path="/accept-invitation"
            element={
              <AcceptInvitationPage
                isLoggedIn={false}
                onAccept={acceptInvitationRecord}
                onGoToSignIn={() => setIsSignUp(false)}
              />
            }
          />
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
                  onCreateBrand={createBrandRecord}
                  brandCapacity={brandCapacity}
                  role={role}
                  permissions={permissions}
                  progress={{
                    creatorCount: creators.length,
                    campaignCount: campaigns.length,
                    cardCount: workflowCards.length,
                    importedCount: importBatches.length,
                  }}
                />
              </SessionProvider>
            }
          >
            <Route index element={<Navigate to={DEFAULT_ROUTE} replace />} />
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
                  onLookupHandle={lookupCreatorHandle}
                />
              }
            />
            {/* The record page. Deliberately not in ROUTE_MANIFEST: the manifest drives the nav
                rail, and a detail route has no place there — it is reached from a row, or from a
                pasted link, which is the whole point of it having a URL at all. */}
            <Route
              path="creators/:creatorId"
              element={
                <CreatorRecordPage
                  creators={creators}
                  campaigns={campaigns}
                  // Workflow cards ARE the campaign-to-creator link: each card carries both
                  // campaignId and creatorId. There is no separate campaignCreators collection in
                  // the shell, which is why WorkflowPage receives these under that prop name too.
                  campaignCreators={workflowCards}
                  coupons={coupons}
                  workflowCards={workflowCards}
                  onLoadRevenue={loadInfluencerRevenue}
                  onLoadCommissions={loadCommissions}
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
              path="settings"
              element={
                <SettingsPage
                  onLoadConnectedAccounts={() => listConnectedAccounts(authToken)}
                  onConnect={startProviderLink}
                  onDisconnect={(id) => disconnectAccount(authToken, id)}
                  linkedNotice={linkedProviderNotice}
                  workspaceName={brandName}
                  onRenameWorkspace={renameWorkspace}
                />
              }
            />
            <Route
              path="members"
              element={
                <MembersPage
                  currentUserId={userId}
                  canManageMembers={permissions.includes('member:invite')}
                  onLoadMembers={loadMembers}
                  onLoadInvitations={loadInvitations}
                  onLoadPlan={loadPlan}
                  onInvite={inviteMemberRecord}
                  onBulkInvite={bulkInviteMemberRecords}
                  onResendInvitation={resendInvitationRecord}
                  onRevokeInvitation={revokeInvitationRecord}
                  onUpdateRole={updateMemberRoleRecord}
                  onRemoveMember={removeMemberRecord}
                />
              }
            />
            <Route
              path="billing"
              element={
                <BillingPage
                  // Reading needs account:billing:read (OWNER and ADMIN). Whether this caller may
                  // ACT comes from the server on the subscription payload, not from here — one
                  // authorization rule, checked in one place.
                  canViewBilling={permissions.includes('account:billing:read')}
                  onLoadSubscription={loadSubscriptionRecord}
                  onLoadInvoices={loadInvoicesRecord}
                  onLoadPlan={loadPlan}
                  onSubscribe={subscribeRecord}
                  onPause={pauseSubscriptionRecord}
                  onResume={resumeSubscriptionRecord}
                  onCancel={cancelSubscriptionRecord}
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
                  onLoadVersions={loadLandingVersionsRecord}
                  onRestoreVersion={restoreLandingVersionRecord}
                  onLoadAssets={loadAssetsRecord}
                  onUploadAsset={uploadAssetRecord}
                  can={(permission) => permissions.includes(permission)}
                />
              }
            />
            {/* Also before the catch-all, which redirects to the dashboard and would discard the
                token. This is the path taken when someone signs in first and then follows the
                link, or is already signed in when it arrives. */}
            <Route
              path="/accept-invitation"
              element={
                <AcceptInvitationPage
                  isLoggedIn
                  onAccept={acceptInvitationRecord}
                  onGoToSignIn={() => {}}
                />
              }
            />
            <Route path="*" element={<Navigate to={DEFAULT_ROUTE} replace />} />
          </Route>
        </>
      )}
    </Routes>
    {/* Outside <Routes> so it overlays whatever page is mounted rather than replacing it. That is
        the point: the workspace stays underneath, so a user mid-edit keeps their place while they
        decide. Gated on isLoggedIn so a stale flag can never cover the login screen itself. */}
    {isLoggedIn && sessionPrompt ? (
      <SessionExpiredDialog
        busy={sessionRetrying}
        error={sessionRetryError}
        onRetry={handleSessionRetry}
        onSignOut={handleSessionSignOut}
      />
    ) : null}
    {/* After sessionPrompt, so an expired session is never hidden behind a cosmetic question.
        The two cannot realistically coincide — this fires seconds after a fresh sign-in — but the
        ordering costs nothing and makes the precedence explicit. */}
    {isLoggedIn && !sessionPrompt && onboarding.open ? (
      <WorkspaceOnboardingDialog
        initialName={brandName}
        initialAccountType={onboarding.accountType}
        onSubmit={handleCompleteOnboarding}
      />
    ) : null}
    </ToastProvider>
  )
}

export default App
