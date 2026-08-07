import { useMemo, useState } from 'react'
import CustomAttributesEditor from '../components/CustomAttributesEditor'
import { exportCsv } from '../api/csv'
import {
  Badge,
  ConfirmDialog,
  DataTable,
  Drawer,
  EmptyState,
  Field,
  FilterBar,
  PageHeader,
  useToast,
} from '../components/ui'

function sanitizePairs(pairs) {
  return Array.isArray(pairs)
    ? pairs.filter((pair) => String(pair?.key || '').trim() || String(pair?.value || '').trim())
    : []
}

function humanizeCampaignType(value) {
  return String(value || '')
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

/** Draft is a working state, active is live, completed is history — three tones, three meanings. */
const STATUS_TONE = {
  draft: 'neutral',
  active: 'success',
  completed: 'info',
}

const STATUS_OPTIONS = [
  { value: 'draft', label: 'Draft' },
  { value: 'active', label: 'Active' },
  { value: 'completed', label: 'Completed' },
]

function buildSnapshot(draft) {
  return JSON.stringify({
    name: String(draft?.name || '').trim(),
    budget: String(draft?.budget || '').trim(),
    status: String(draft?.status || 'draft').trim(),
    campaignType: String(draft?.campaignType || 'paid').trim().toLowerCase(),
    customAttributes: sanitizePairs(draft?.customAttributes).map((pair) => ({
      key: String(pair?.key || '').trim(),
      value: pair?.value,
      type: String(pair?.type || 'text'),
    })),
  })
}

function CampaignsPage({
  campaigns,
  campaignForm,
  setCampaignForm,
  campaignTypeOptions,
  customAttributesToPairs,
  normalizeCampaignTypeForPayload,
  onCreateCampaign,
  onUpdateCampaign,
}) {
  const toast = useToast()

  const [drawerMode, setDrawerMode] = useState('')
  const [editingId, setEditingId] = useState('')
  const [editDraft, setEditDraft] = useState({
    name: '',
    budget: '',
    status: 'draft',
    campaignType: campaignTypeOptions?.[0]?.value || 'product seeding',
    customAttributes: [],
  })
  const [editSnapshot, setEditSnapshot] = useState('')
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState('')
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const [createAttrValidation, setCreateAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })
  const [editAttrValidation, setEditAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [sortBy, setSortBy] = useState('name')
  const [sortDir, setSortDir] = useState('asc')

  const campaignTypeLabelMap = (campaignTypeOptions || []).reduce((acc, option) => {
    acc[String(option.value || '').trim().toLowerCase()] = option.label || humanizeCampaignType(option.value)
    return acc
  }, {})

  const formatCampaignTypeLabel = (value) => {
    const normalized = normalizeCampaignTypeForPayload(value)
    return campaignTypeLabelMap[normalized] || humanizeCampaignType(normalized)
  }

  const visibleCampaigns = useMemo(() => {
    const query = search.trim().toLowerCase()
    const direction = sortDir === 'asc' ? 1 : -1
    return (campaigns || [])
      .filter((campaign) => {
        if (!query) {
          return true
        }
        const haystack = [
          campaign?.name,
          campaign?.status,
          campaign?.campaignType,
          ...customAttributesToPairs(campaign?.customAttributes).flatMap((pair) => [pair?.key, pair?.value]),
        ].filter(Boolean).join(' ').toLowerCase()
        return haystack.includes(query)
      })
      .filter((campaign) => !statusFilter || String(campaign?.status || '').toLowerCase() === statusFilter)
      .sort((a, b) => {
        // Budget is the one numeric column; comparing it as text would order 9 above 100.
        if (sortBy === 'budget') {
          return ((Number(a?.budget) || 0) - (Number(b?.budget) || 0)) * direction
        }
        const text = (value) => String(value || '').toLowerCase()
        return (text(a?.[sortBy]).localeCompare(text(b?.[sortBy])) * direction)
          || text(a?.name).localeCompare(text(b?.name))
      })
  }, [campaigns, search, statusFilter, sortBy, sortDir, customAttributesToPairs])

  const totalCount = (campaigns || []).length
  const isFiltered = Boolean(search.trim() || statusFilter)

  const clearFilters = () => {
    setSearch('')
    setStatusFilter('')
  }

  const toggleSort = (key) => {
    if (key === sortBy) {
      setSortDir((prev) => (prev === 'asc' ? 'desc' : 'asc'))
      return
    }
    setSortBy(key)
    setSortDir('asc')
  }

  const openCreate = () => {
    setFormError('')
    setEditingId('')
    setCampaignForm({
      name: '',
      budget: '',
      status: 'draft',
      campaignType: campaignTypeOptions?.[0]?.value || 'product seeding',
      customAttributes: [],
    })
    setDrawerMode('create')
  }

  const openEdit = (campaign) => {
    setFormError('')
    setEditingId(campaign.id)
    const nextDraft = {
      name: campaign.name || '',
      budget: campaign.budget == null ? '' : String(campaign.budget),
      status: campaign.status || 'draft',
      campaignType: normalizeCampaignTypeForPayload(campaign.campaignType) || (campaignTypeOptions?.[0]?.value || 'product seeding'),
      customAttributes: customAttributesToPairs(campaign.customAttributes),
    }
    setEditDraft(nextDraft)
    setEditSnapshot(buildSnapshot(nextDraft))
    setDrawerMode('edit')
  }

  const closeDrawer = () => {
    setDrawerMode('')
    setEditingId('')
    setEditSnapshot('')
    setFormError('')
    setConfirmDiscard(false)
  }

  const requestClose = () => {
    if (saving) {
      return
    }
    if (drawerMode === 'edit' && editSnapshot && editSnapshot !== buildSnapshot(editDraft)) {
      setConfirmDiscard(true)
      return
    }
    closeDrawer()
  }

  const submitCreate = async (event) => {
    event.preventDefault()
    if (createAttrValidation.hasDuplicateKeys || createAttrValidation.hasMissingKeys) {
      setFormError('Give every custom attribute a unique name before saving.')
      return
    }
    try {
      setSaving(true)
      setFormError('')
      await onCreateCampaign(event)
      toast.success(`${campaignForm.name.trim() || 'Campaign'} created.`)
      closeDrawer()
    } catch (error) {
      setFormError(error instanceof Error ? error.message : 'Unable to create campaign.')
    } finally {
      setSaving(false)
    }
  }

  const submitEdit = async () => {
    if (!editingId || !editDraft.name.trim()) {
      setFormError('A campaign name is required.')
      return
    }
    if (editAttrValidation.hasDuplicateKeys || editAttrValidation.hasMissingKeys) {
      setFormError('Give every custom attribute a unique name before saving.')
      return
    }

    try {
      setSaving(true)
      setFormError('')
      await onUpdateCampaign(editingId, {
        name: editDraft.name.trim(),
        budget: editDraft.budget.trim(),
        status: editDraft.status,
        campaignType: normalizeCampaignTypeForPayload(editDraft.campaignType),
        customAttributes: sanitizePairs(editDraft.customAttributes),
      })
      // Toast rather than in-drawer text, which closeDrawer() would unmount before it rendered.
      toast.success(`${editDraft.name.trim()} updated.`)
      closeDrawer()
    } catch (error) {
      setFormError(error instanceof Error ? error.message : 'Unable to update campaign.')
    } finally {
      setSaving(false)
    }
  }

  const columns = [
    {
      key: 'name',
      header: 'Campaign',
      sortable: true,
      render: (campaign) => <span className="identity-name">{campaign.name}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      render: (campaign) => {
        const status = String(campaign.status || 'draft').toLowerCase()
        return <Badge tone={STATUS_TONE[status] || 'neutral'}>{humanizeCampaignType(status)}</Badge>
      },
    },
    {
      key: 'campaignType',
      header: 'Type',
      sortable: true,
      render: (campaign) => formatCampaignTypeLabel(campaign.campaignType),
    },
    {
      key: 'budget',
      header: 'Budget',
      sortable: true,
      align: 'right',
      render: (campaign) => (campaign.budget
        ? `$${Number(campaign.budget).toLocaleString()}`
        : <span className="identity-sub">—</span>),
    },
    {
      key: 'attributes',
      header: 'Attributes',
      render: (campaign) => {
        const pairs = customAttributesToPairs(campaign.customAttributes)
        if (!pairs.length) {
          return <span className="identity-sub">—</span>
        }
        return (
          <div className="custom-attributes-readonly">
            {pairs.slice(0, 2).map((pair) => (
              <span key={`${campaign.id}-${pair.key}`} className="custom-attribute-pill">
                <strong>{pair.key}:</strong> {pair.value}
              </span>
            ))}
            {pairs.length > 2 ? <span className="identity-sub">+{pairs.length - 2}</span> : null}
          </div>
        )
      },
    },
  ]

  const activeDraft = drawerMode === 'edit' ? editDraft : campaignForm
  const setActiveDraft = drawerMode === 'edit' ? setEditDraft : setCampaignForm
  const activeValidationSetter = drawerMode === 'edit' ? setEditAttrValidation : setCreateAttrValidation

  const exportCampaigns = () => {
    exportCsv({
      prefix: 'campaigns',
      source: 'campaigns',
      columns: [
        { key: 'name', header: 'Campaign' },
        { key: 'status', header: 'Status' },
        { key: 'campaignType', header: 'Type', value: (c) => campaignTypeLabel(c.campaignType) },
        { key: 'budget', header: 'Budget' },
        { key: 'createdAt', header: 'Created' },
        {
          key: 'customAttributes',
          header: 'Attributes',
          value: (campaign) => customAttributesToPairs(campaign.customAttributes)
            .map((pair) => `${pair.key}: ${pair.value}`)
            .join('; '),
        },
      ],
      rows: visibleCampaigns,
    })
  }

  return (
    <>
      <PageHeader
        title="Campaigns"
        count={isFiltered ? `${visibleCampaigns.length} of ${totalCount}` : totalCount}
        description="What you tie creators to. Budget and status drive payouts and reporting."
        action={
          <>
            <button
              type="button"
              className="ghost-btn"
              onClick={exportCampaigns}
              disabled={visibleCampaigns.length === 0}
            >
              Export CSV
            </button>
            <button type="button" className="primary-btn" onClick={openCreate}>
              New campaign
            </button>
          </>
        }
      />

      {totalCount > 0 ? (
        <FilterBar>
          <input
            type="search"
            value={search}
            placeholder="Search by name, type, or attribute…"
            onChange={(event) => setSearch(event.target.value)}
            aria-label="Search campaigns"
          />
          <select
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value)}
            aria-label="Filter by status"
          >
            <option value="">All statuses</option>
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
          {isFiltered ? (
            <button type="button" className="ghost-btn" onClick={clearFilters}>Clear</button>
          ) : null}
        </FilterBar>
      ) : null}

      <DataTable
        caption="Campaigns"
        columns={columns}
        rows={visibleCampaigns}
        rowKey={(campaign) => campaign.id}
        onRowClick={openEdit}
        sortBy={sortBy}
        sortDir={sortDir}
        onSort={toggleSort}
        emptyState={
          totalCount === 0 ? (
            <EmptyState
              icon="◈"
              title="No campaigns yet"
              description="A campaign is what creators get assigned to — start with the one you are running now."
              action={
                <button type="button" className="primary-btn" onClick={openCreate}>
                  Create your first campaign
                </button>
              }
            />
          ) : (
            <EmptyState
              title="No campaigns match this filter"
              description={search.trim() ? `Nothing found for "${search.trim()}".` : 'Try a different status.'}
              action={<button type="button" className="ghost-btn" onClick={clearFilters}>Clear filters</button>}
            />
          )
        }
      />

      {drawerMode ? (
        <Drawer
          title={drawerMode === 'create' ? 'New campaign' : 'Edit campaign'}
          onClose={requestClose}
        >
          <form
            className="drawer-form"
            onSubmit={drawerMode === 'create' ? submitCreate : (event) => { event.preventDefault(); submitEdit() }}
          >
            <Field label="Campaign name" htmlFor="campaign-name" required>
              <input
                id="campaign-name"
                type="text"
                value={activeDraft.name}
                placeholder="Q3 Gifting"
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, name: event.target.value }))}
                required
              />
            </Field>

            <Field label="Budget" htmlFor="campaign-budget" hint="Total spend. Used to calculate ROI on the revenue dashboard.">
              <input
                id="campaign-budget"
                type="number"
                min="0"
                value={activeDraft.budget}
                placeholder="0"
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, budget: event.target.value }))}
              />
            </Field>

            <Field label="Status" htmlFor="campaign-status">
              <select
                id="campaign-status"
                value={activeDraft.status}
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, status: event.target.value }))}
              >
                {STATUS_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </Field>

            <Field label="Campaign type" htmlFor="campaign-type">
              <select
                id="campaign-type"
                value={activeDraft.campaignType}
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, campaignType: event.target.value }))}
              >
                {(campaignTypeOptions || []).map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </Field>

            <Field label="Custom attributes" hint="Anything else you track — region, product line, owner.">
              <CustomAttributesEditor
                pairs={activeDraft.customAttributes}
                onChange={(pairs) => setActiveDraft((prev) => ({ ...prev, customAttributes: pairs }))}
                onValidationChange={activeValidationSetter}
              />
            </Field>

            {formError ? <p className="field-error" role="alert">{formError}</p> : null}

            <div className="drawer-actions">
              <button type="button" className="ghost-btn" onClick={requestClose} disabled={saving}>
                Cancel
              </button>
              <button type="submit" className="primary-btn" disabled={saving}>
                {saving ? 'Saving…' : drawerMode === 'create' ? 'Create campaign' : 'Save changes'}
              </button>
            </div>
          </form>
        </Drawer>
      ) : null}

      {confirmDiscard ? (
        <ConfirmDialog
          title="Discard your changes?"
          consequence={`Edits to ${editDraft.name || 'this campaign'} have not been saved and will be lost.`}
          confirmLabel="Discard"
          cancelLabel="Keep editing"
          onConfirm={closeDrawer}
          onCancel={() => setConfirmDiscard(false)}
        />
      ) : null}
    </>
  )
}

export default CampaignsPage
