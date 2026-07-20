import { Navigate, Route, Routes } from 'react-router-dom'
import { useEffect, useMemo, useState } from 'react'
import './App.css'
import LandingPage from './pages/LandingPage'
import ImportPage from './pages/ImportPage'
import CampaignsPage from './pages/CampaignsPage'
import CreatorsPage from './pages/CreatorsPage'
import WorkflowPage from './pages/WorkflowPage'
import WorkspaceLayout from './components/WorkspaceLayout'
import { STAGES, starterAssignments, starterCampaigns, starterCreators, parseCsv } from './constants'

const STORAGE_KEY = 'tejdux_ui_state_v1'

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

function App() {
  const persistedState = loadPersistedState()
  const initialCampaigns = persistedState?.campaigns?.length ? persistedState.campaigns : starterCampaigns
  const initialCreators = persistedState?.creators?.length ? persistedState.creators : starterCreators
  const initialAssignments = persistedState?.assignments?.length ? persistedState.assignments : starterAssignments
  const defaultCampaignId = initialCampaigns[0]?.id || ''
  const defaultCreatorId = initialCreators[0]?.id || ''

  const [isSignUp, setIsSignUp] = useState(persistedState?.isSignUp ?? true)
  const [isLoggedIn, setIsLoggedIn] = useState(persistedState?.isLoggedIn ?? false)
  const [brandName, setBrandName] = useState(persistedState?.brandName ?? 'tejdux.io')
  const [userName, setUserName] = useState(persistedState?.userName ?? '')

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
    persistedState?.importSummary ?? {
      filename: '',
      type: '',
      headers: [],
      rows: [],
      message: 'Upload CSV, XLS, or XLSX to preview mapped source columns.',
    },
  )

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

  useEffect(() => {
    const snapshot = {
      isSignUp,
      isLoggedIn,
      brandName,
      userName,
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
    campaigns,
    creators,
    assignments,
    campaignForm,
    creatorForm,
    assignmentForm,
    importSummary,
  ])

  const handleAuthSubmit = (event) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const name = form.get('fullName') || 'Brand Operator'
    const company = form.get('brand') || 'tejdux.io'
    setUserName(String(name))
    setBrandName(String(company))
    setIsLoggedIn(true)
  }

  const handleImport = (event) => {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }

    const extension = file.name.split('.').pop()?.toLowerCase()

    if (extension === 'csv') {
      const reader = new FileReader()
      reader.onload = () => {
        const text = typeof reader.result === 'string' ? reader.result : ''
        const parsed = parseCsv(text)
        setImportSummary({
          filename: file.name,
          type: 'CSV',
          headers: parsed.headers,
          rows: parsed.rows.slice(0, 5),
          message: `Parsed ${parsed.rows.length} data rows from ${file.name}.`,
        })
      }
      reader.readAsText(file)
      return
    }

    if (extension === 'xls' || extension === 'xlsx') {
      setImportSummary({
        filename: file.name,
        type: extension.toUpperCase(),
        headers: ['Campaign Name', 'Creator Handle', 'Platform', 'Stage', 'Agreed Fee'],
        rows: [
          ['Holiday Spark', '@brightriver', 'Instagram', 'outreach', '1200'],
          ['Holiday Spark', '@alexocean', 'TikTok', 'agreed', '900'],
        ],
        message:
          'Spreadsheet binary parsing is mocked in this version. Import mapping preview is represented with sample columns for UI validation.',
      })
      return
    }

    setImportSummary({
      filename: file.name,
      type: 'Unsupported',
      headers: [],
      rows: [],
      message: 'Unsupported file type. Please upload CSV, XLS, or XLSX.',
    })
  }

  const createCampaign = (event) => {
    event.preventDefault()
    if (!campaignForm.name.trim()) {
      return
    }

    const nextCampaign = {
      id: `c-${Date.now()}`,
      name: campaignForm.name.trim(),
      budget: campaignForm.budget.trim(),
      status: campaignForm.status,
    }

    setCampaigns((prev) => [nextCampaign, ...prev])
    setAssignmentForm((prev) => ({ ...prev, campaignId: nextCampaign.id || defaultCampaignId }))
    setCampaignForm({ name: '', budget: '', status: 'draft' })
  }

  const createCreator = (event) => {
    event.preventDefault()
    if (!creatorForm.name.trim() || !creatorForm.handle.trim()) {
      return
    }

    const nextCreator = {
      id: `r-${Date.now()}`,
      name: creatorForm.name.trim(),
      handle: creatorForm.handle.trim(),
      platform: creatorForm.platform,
      email: creatorForm.email.trim(),
    }

    setCreators((prev) => [nextCreator, ...prev])
    setAssignmentForm((prev) => ({ ...prev, creatorId: nextCreator.id || defaultCreatorId }))
    setCreatorForm({ name: '', handle: '', platform: 'Instagram', email: '' })
  }

  const tieCreatorToCampaign = (event) => {
    event.preventDefault()
    if (!assignmentForm.campaignId || !assignmentForm.creatorId) {
      return
    }

    const nextAssignment = {
      id: `a-${Date.now()}`,
      campaignId: assignmentForm.campaignId,
      creatorId: assignmentForm.creatorId,
      stage: assignmentForm.stage,
      fee: assignmentForm.fee.trim(),
      notes: assignmentForm.notes.trim(),
      dueDate: assignmentForm.dueDate,
      tags: assignmentForm.tags
        .split(',')
        .map((tag) => tag.trim())
        .filter(Boolean),
    }

    setAssignments((prev) => [nextAssignment, ...prev])
    setAssignmentForm((prev) => ({ ...prev, fee: '', notes: '', stage: 'outreach', dueDate: '', tags: '' }))
  }

  const updateCardStage = (id, nextStage) => {
    setAssignments((prev) => prev.map((item) => (item.id === id ? { ...item, stage: nextStage } : item)))
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
              />
            }
          />
        </>
      ) : (
        <>
          <Route
            path="/"
            element={<WorkspaceLayout brandName={brandName} userName={userName} onLogout={() => setIsLoggedIn(false)} />}
          >
            <Route index element={<Navigate to="/import" replace />} />
            <Route
              path="import"
              element={
                <ImportPage
                  importSummary={importSummary}
                  onImport={handleImport}
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
                  onCreateCampaign={createCampaign}
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
                  onCreateCreator={createCreator}
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
