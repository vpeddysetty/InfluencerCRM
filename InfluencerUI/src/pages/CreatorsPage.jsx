import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import CustomAttributesEditor from '../components/CustomAttributesEditor'
import { exportCsv } from '../api/csv'
import {
  Badge,
  BulkActionBar,
  ConfirmDialog,
  DataTable,
  Drawer,
  EmptyState,
  Field,
  FilterBar,
  MetricsProvenance,
  MetricsSourceBadge,
  PageHeader,
  useToast,
} from '../components/ui'
import { useUrlFilters } from '../shell/useUrlFilters'
import { lookupMatchesHandle, metricsFromLookup } from '../shell/handleLookup'

function sanitizePairs(pairs) {
  return Array.isArray(pairs)
    ? pairs.filter((pair) => String(pair?.key || '').trim() || String(pair?.value || '').trim())
    : []
}

const PLATFORM_OPTIONS = [
  { value: 'instagram', label: 'Instagram' },
  { value: 'tiktok', label: 'TikTok' },
  { value: 'youtube', label: 'YouTube' },
  { value: 'other', label: 'Other' },
]

const EMPTY_DRAFT = { name: '', handle: '', platform: 'instagram', email: '', preferredRate: '', customAttributes: [], resolvedMetrics: null }

/**
 * Formats the per-brand negotiated rate for display.
 *
 * Empty rather than `0` when unset: an unset rate and a rate genuinely negotiated to zero are
 * different facts, and showing `$0` for "we haven't agreed a rate" is a number someone will quote
 * back in a negotiation. Same reasoning as C8c, where an unresolvable handle leaves follower count
 * absent rather than writing 0 and silently failing every `< 5000` vetting rule.
 */
function formatRate(value) {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  const amount = Number(value)
  if (!Number.isFinite(amount)) {
    return ''
  }
  return amount.toLocaleString(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })
}

/** Two initials, for the row avatar. A list of names is far easier to scan with a shape beside it. */
function initialsOf(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean)
  if (!parts.length) {
    return '—'
  }
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase()
  }
  return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase()
}

function platformLabel(platform) {
  const normalized = String(platform || '').toLowerCase()
  return PLATFORM_OPTIONS.find((option) => option.value === normalized)?.label
    || (normalized ? normalized.charAt(0).toUpperCase() + normalized.slice(1) : '—')
}

/**
 * Free-text match across the fields someone would reach for Ctrl+F to find: name, handle,
 * email, platform, and any custom attribute they added themselves. Custom attributes are
 * included deliberately — a brand that imported a "Tier" or "Agency" column expects to be
 * able to search it, and excluding them would make the box feel broken for exactly the
 * users who invested most in their sheet.
 */
function matchesQuery(creator, query, pairsOf) {
  if (!query) {
    return true
  }
  const haystack = [
    creator?.name,
    creator?.handle,
    creator?.email,
    creator?.platform,
    ...pairsOf(creator?.customAttributes).flatMap((pair) => [pair?.key, pair?.value]),
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()

  return haystack.includes(query)
}

function buildSnapshot(draft) {
  return JSON.stringify({
    name: String(draft?.name || '').trim(),
    handle: String(draft?.handle || '').trim(),
    platform: String(draft?.platform || 'instagram').trim(),
    email: String(draft?.email || '').trim(),
    customAttributes: sanitizePairs(draft?.customAttributes).map((pair) => ({
      key: String(pair?.key || '').trim(),
      value: pair?.value,
      type: String(pair?.type || 'text'),
    })),
  })
}

function CreatorsPage({
  creators,
  creatorForm,
  setCreatorForm,
  customAttributesToPairs,
  onCreateCreator,
  onUpdateCreator,
  onLookupHandle,
  onDeleteCreator,
  canDeleteCreator = false,
}) {
  const toast = useToast()
  const navigate = useNavigate()

  // '' (closed) | 'create' | 'edit'. One drawer serves both: creation moved off the page so the
  // directory owns the first screen, and an edit is the same form with different initial values.
  const [drawerMode, setDrawerMode] = useState('')
  const [editingId, setEditingId] = useState('')
  const [editDraft, setEditDraft] = useState(EMPTY_DRAFT)
  const [editSnapshot, setEditSnapshot] = useState('')
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState('')
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  // Separate from confirmDiscard: discarding edits and deleting a record are different questions
  // and must never share a dialog, because the wrong one answered destroys data.
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [createAttrValidation, setCreateAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })
  const [editAttrValidation, setEditAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })

  // Handle lookup (C.2). `lookup` holds the last preview: null before anyone has looked,
  // `{ resolved: false, reason }` for a handle the platform would not answer for, and the
  // metrics object when it did. Kept out of the draft so that typing in the form never
  // silently invalidates numbers already on screen — `lookupHandle` records which handle the
  // preview belongs to, and the panel hides itself once the field no longer matches.
  const [lookingUp, setLookingUp] = useState(false)
  const [lookup, setLookup] = useState(null)
  const [lookupHandle, setLookupHandle] = useState('')

  // Filters live in the URL, so a filtered directory is a link someone can send. See
  // useUrlFilters — this is also the groundwork for saved views.
  const [filters, setFilters, clearUrlFilters] = useUrlFilters({
    q: '',
    platform: '',
    sort: 'name',
    dir: 'asc',
  })
  const { q: search, platform: platformFilter, sort: sortBy, dir: sortDir } = filters

  // Selection is a Set of creator ids. Deliberately NOT in the URL: a link should carry which
  // rows someone is looking at, not which they had ticked.
  const [selectedIds, setSelectedIds] = useState(() => new Set())

  // Only offer platforms actually present, so the filter never lists an option that yields nothing.
  const availablePlatforms = useMemo(() => {
    const seen = new Set()
    ;(creators || []).forEach((creator) => {
      const platform = String(creator?.platform || '').trim().toLowerCase()
      if (platform) {
        seen.add(platform)
      }
    })
    return [...seen].sort()
  }, [creators])

  const visibleCreators = useMemo(() => {
    const query = search.trim().toLowerCase()
    const direction = sortDir === 'asc' ? 1 : -1
    return (creators || [])
      .filter((creator) => matchesQuery(creator, query, customAttributesToPairs))
      .filter((creator) => !platformFilter || String(creator?.platform || '').toLowerCase() === platformFilter)
      .sort((a, b) => {
        const text = (value) => String(value || '').toLowerCase()
        const primary = text(a?.[sortBy]).localeCompare(text(b?.[sortBy])) * direction
        // Ties fall back to name, so repeated sorts on a low-cardinality column such as
        // platform stay stable rather than reshuffling rows on every click.
        return primary || text(a?.name).localeCompare(text(b?.name))
      })
  }, [creators, search, platformFilter, sortBy, sortDir, customAttributesToPairs])

  const totalCount = (creators || []).length
  const isFiltered = Boolean(search.trim() || platformFilter)

  const clearFilters = () => {
    clearUrlFilters()
  }

  const toggleSort = (key) => {
    if (key === sortBy) {
      setFilters({ dir: sortDir === 'asc' ? 'desc' : 'asc' })
      return
    }
    setFilters({ sort: key, dir: 'asc' })
  }

  // Selected rows that survived the current filter. Acting on a selection the user can no longer
  // see is how bulk operations go wrong — narrowing the filter after selecting must narrow what
  // an action applies to.
  const selectedVisible = useMemo(
    () => visibleCreators.filter((creator) => selectedIds.has(creator.id)),
    [visibleCreators, selectedIds],
  )

  const clearSelection = () => setSelectedIds(new Set())

  const exportSelected = () => {
    exportCreators(selectedVisible)
    toast.success(`Exported ${selectedVisible.length} creator${selectedVisible.length === 1 ? '' : 's'}.`)
  }

  const clearLookup = () => {
    setLookup(null)
    setLookupHandle('')
    setLookingUp(false)
  }

  const openCreate = () => {
    setFormError('')
    setEditingId('')
    setCreatorForm({ ...EMPTY_DRAFT })
    clearLookup()
    setDrawerMode('create')
  }

  /**
   * Ask the platform about this handle, and keep the answer as a preview (C.2).
   *
   * <p>Nothing is saved here. The numbers sit in the drawer until someone presses Add creator,
   * so vetting several handles leaves no trail of half-added creators.
   *
   * <p>An unresolved handle is a normal outcome, not an error — private accounts, typos and
   * deleted profiles all land there — so it renders as a note above the still-editable form
   * rather than as a failure. Only a broken request becomes an error.
   *
   * <p>The metrics are stored on the draft exactly as the BFF returned them, `metricsSource`
   * included. That field is what later renders as "Platform verified" or "Simulated", and
   * dropping it here would make the two indistinguishable on the record forever.
   */
  const runLookup = async () => {
    const handle = String(activeDraft.handle || '').trim()
    if (!handle || lookingUp) {
      return
    }
    const platform = String(activeDraft.platform || 'instagram').toLowerCase()
    try {
      setLookingUp(true)
      setFormError('')
      const result = await onLookupHandle({ platform, handle })
      setLookup(result)
      setLookupHandle(handle)

      const metrics = metricsFromLookup(result)
      if (metrics) {
        setActiveDraft((prev) => ({
          ...prev,
          // A display name only when the form is still empty: someone who typed a name meant it,
          // and overwriting it with the platform's version would discard a deliberate choice.
          // `displayName` is the only name resolveHandle returns — it has no `name` field, which
          // is why this silently populated nothing until the BFF started sending it.
          name: String(prev.name || '').trim() ? prev.name : (result.displayName || prev.name),
          resolvedMetrics: metrics,
        }))
      } else {
        // A failed lookup must clear any earlier success, or the previous handle's audience
        // would be saved against this one.
        setActiveDraft((prev) => ({ ...prev, resolvedMetrics: null }))
      }
    } catch (error) {
      setLookup(null)
      setLookupHandle('')
      setFormError(error instanceof Error ? error.message : 'Unable to look up that handle.')
    } finally {
      setLookingUp(false)
    }
  }

  const openEdit = (creator) => {
    setFormError('')
    setEditingId(creator.id)
    const nextDraft = {
      name: creator.name || '',
      handle: creator.handle || '',
      platform: String(creator.platform || 'instagram').toLowerCase(),
      email: creator.email || '',
      // `?? ''` rather than `|| ''`: a rate of 0 is a real negotiated value and must survive into
      // the form. `||` would replace it with an empty field and silently discard it on save.
      preferredRate: creator.preferredRate ?? '',
      customAttributes: customAttributesToPairs(creator.customAttributes),
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
    clearLookup()
  }

  const requestClose = () => {
    if (saving) {
      return
    }
    if (drawerMode === 'edit') {
      const hasUnsavedChanges = editSnapshot && editSnapshot !== buildSnapshot(editDraft)
      if (hasUnsavedChanges) {
        setConfirmDiscard(true)
        return
      }
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
      await onCreateCreator(event)
      // Toast rather than in-drawer text: the drawer closes on the next line, and a message
      // rendered inside it would be unmounted before anyone could read it.
      toast.success(`${creatorForm.name.trim() || 'Creator'} added.`)
      closeDrawer()
    } catch (error) {
      setFormError(error instanceof Error ? error.message : 'Unable to add creator.')
    } finally {
      setSaving(false)
    }
  }

  const submitEdit = async () => {
    if (!editingId || !editDraft.name.trim() || !editDraft.handle.trim()) {
      setFormError('Name and handle are required.')
      return
    }
    if (editAttrValidation.hasDuplicateKeys || editAttrValidation.hasMissingKeys) {
      setFormError('Give every custom attribute a unique name before saving.')
      return
    }

    try {
      setSaving(true)
      setFormError('')
      await onUpdateCreator(editingId, {
        name: editDraft.name.trim(),
        handle: editDraft.handle.trim(),
        platform: editDraft.platform,
        email: editDraft.email.trim(),
        // null, not '' — clearing the field means "no agreed rate", and the column is a numeric
        // that would reject an empty string.
        preferredRate: String(editDraft.preferredRate).trim() === '' ? null : Number(editDraft.preferredRate),
        customAttributes: sanitizePairs(editDraft.customAttributes),
      })
      toast.success(`${editDraft.name.trim()} updated.`)
      closeDrawer()
    } catch (error) {
      setFormError(error instanceof Error ? error.message : 'Unable to update creator.')
    } finally {
      setSaving(false)
    }
  }

  // Defaults to everything currently visible; the bulk bar passes the selected subset instead.
  const exportCreators = (rows = visibleCreators) => {
    exportCsv({
      prefix: 'creators',
      source: 'creators',
      // Deliberately wider than the table. The table is optimised for scanning; the export is
      // what an agency hands a client, so it carries the fields a spreadsheet reader expects —
      // including preferredRate, which is the whole point of M1.1.
      columns: [
        { key: 'name', header: 'Name' },
        { key: 'handle', header: 'Handle' },
        { key: 'platform', header: 'Platform' },
        { key: 'email', header: 'Email' },
        { key: 'preferredRate', header: 'Preferred rate' },
        { key: 'status', header: 'Status' },
        { key: 'followerCount', header: 'Followers' },
        { key: 'engagementRate', header: 'Engagement rate' },
        // Provenance travels with the metric, for the same reason the API exposes them together:
        // a follower count without its source cannot be told apart from a simulated one, and
        // this file is going to someone outside the company.
        { key: 'metricsSource', header: 'Metrics source' },
        { key: 'vettingStatus', header: 'Vetting status' },
        {
          key: 'customAttributes',
          header: 'Attributes',
          value: (creator) => customAttributesToPairs(creator.customAttributes)
            .map((pair) => `${pair.key}: ${pair.value}`)
            .join('; '),
        },
      ],
      rows,
    })
  }

  const runDelete = async () => {
    if (!editingId || deleting) {
      return
    }
    try {
      setDeleting(true)
      setFormError('')
      await onDeleteCreator(editingId)
      // Read before the state clears — closeDrawer resets editDraft and the toast would name nobody.
      const name = editDraft.name.trim() || 'Creator'
      setConfirmDelete(false)
      closeDrawer()
      toast.success(`${name} deleted.`)
    } catch (error) {
      // Kept open on failure, with the dialog dismissed: the row still exists and the drawer is
      // where someone would retry or give up. A closed drawer would imply it worked.
      setConfirmDelete(false)
      setFormError(error instanceof Error ? error.message : 'Unable to delete creator.')
    } finally {
      setDeleting(false)
    }
  }

  const columns = [
    {
      key: 'name',
      header: 'Creator',
      sortable: true,
      render: (creator) => (
        <div className="identity-cell">
          <span className="avatar" aria-hidden="true">{initialsOf(creator.name)}</span>
          <span className="identity-text">
            <span className="identity-name">{creator.name}</span>
            {creator.email ? <span className="identity-sub">{creator.email}</span> : null}
          </span>
        </div>
      ),
    },
    {
      key: 'handle',
      header: 'Handle',
      sortable: true,
      render: (creator) => creator.handle || '—',
    },
    {
      key: 'platform',
      header: 'Platform',
      sortable: true,
      render: (creator) => <Badge tone="info">{platformLabel(creator.platform)}</Badge>,
    },
    {
      key: 'preferredRate',
      header: 'Rate',
      sortable: true,
      // An em dash, not $0 — see formatRate. "No rate agreed" and "agreed at zero" are different
      // facts, and a demo that shows $0 for every unset creator makes the differentiator look
      // broken rather than unset.
      render: (creator) => formatRate(creator.preferredRate) || <span className="identity-sub">—</span>,
    },
    {
      key: 'followerCount',
      header: 'Audience',
      sortable: true,
      align: 'right',
      // The number and its provenance in one cell, because neither is safe to read alone.
      // Influencer fraud is a top-three buyer objection, and until now the only place a brand
      // could tell a measured follower count from a generated one was the CSV export — a file
      // that goes to their client.
      render: (creator) => {
        if (creator.followerCount === null || creator.followerCount === undefined) {
          // Em dash, not 0. "Nobody has looked this creator up" and "this creator has no
          // audience" are different facts, and 0 silently fails every `followers < 5000` rule.
          return <span className="identity-sub">—</span>
        }
        return (
          <span className="audience-cell">
            <span className="audience-count">{Number(creator.followerCount).toLocaleString()}</span>
            <MetricsSourceBadge source={creator.metricsSource} />
          </span>
        )
      },
    },
    {
      key: 'attributes',
      header: 'Attributes',
      render: (creator) => {
        const pairs = customAttributesToPairs(creator.customAttributes)
        if (!pairs.length) {
          return <span className="identity-sub">—</span>
        }
        // Two pills plus a count: the full set is in the drawer. Printing every attribute on
        // every row is what made the old list unscannable.
        return (
          <div className="custom-attributes-readonly">
            {pairs.slice(0, 2).map((pair) => (
              <span key={`${creator.id}-${pair.key}`} className="custom-attribute-pill">
                <strong>{pair.key}:</strong> {pair.value}
              </span>
            ))}
            {pairs.length > 2 ? <span className="identity-sub">+{pairs.length - 2}</span> : null}
          </div>
        )
      },
    },
    {
      key: 'edit',
      header: '',
      width: '1%',
      // Editing kept reachable now that the row itself navigates to the record. stopPropagation
      // so the button does not also fire the row's navigation — the same rule the selection
      // checkbox follows.
      render: (creator) => (
        <button
          type="button"
          className="ghost-btn ghost-btn-sm"
          onClick={(event) => {
            event.stopPropagation()
            openEdit(creator)
          }}
        >
          Edit
        </button>
      ),
    },
  ]

  // The full record behind the drawer. The draft carries only editable fields; metrics are read
  // from the platform and are not editable here, so they have to come from the row itself.
  const editingCreator = drawerMode === 'edit'
    ? (creators || []).find((creator) => creator.id === editingId)
    : null

  const activeDraft = drawerMode === 'edit' ? editDraft : creatorForm
  const setActiveDraft = drawerMode === 'edit' ? setEditDraft : setCreatorForm
  const activeValidationSetter = drawerMode === 'edit' ? setEditAttrValidation : setCreateAttrValidation

  return (
    <>
      <PageHeader
        title="Creators"
        count={isFiltered ? `${visibleCreators.length} of ${totalCount}` : totalCount}
        description="Everyone you work with, and the details campaigns and payouts are tied to."
        action={
          <>
            {/* Exports what is on screen, filters included — the count in the header says how
                many rows that is. An export that ignored the active filter would quietly
                disagree with the number the user is looking at. */}
            <button
              type="button"
              className="ghost-btn"
              onClick={exportCreators}
              disabled={visibleCreators.length === 0}
            >
              Export CSV
            </button>
            <button type="button" className="primary-btn" onClick={openCreate}>
              New creator
            </button>
          </>
        }
      />

      {totalCount > 0 ? (
        <FilterBar>
          <input
            type="search"
            value={search}
            placeholder="Search by name, handle, email, or attribute…"
            onChange={(event) => setFilters({ q: event.target.value })}
            aria-label="Search creators"
          />
          <select
            value={platformFilter}
            onChange={(event) => setFilters({ platform: event.target.value })}
            aria-label="Filter by platform"
          >
            <option value="">All platforms</option>
            {availablePlatforms.map((platform) => (
              <option key={platform} value={platform}>{platformLabel(platform)}</option>
            ))}
          </select>
          {isFiltered ? (
            <button type="button" className="ghost-btn" onClick={clearFilters}>Clear</button>
          ) : null}
        </FilterBar>
      ) : null}

      <BulkActionBar count={selectedVisible.length} onClear={clearSelection}>
        <button type="button" className="ghost-btn" onClick={exportSelected}>
          Export selected
        </button>
      </BulkActionBar>

      <DataTable
        caption="Creators"
        columns={columns}
        rows={visibleCreators}
        rowKey={(creator) => creator.id}
        // A row now opens the record, not the edit form. Finding and reading is the daily loop;
        // editing is occasional, and it stays one click away as an explicit action in the row.
        // This is also what makes a creator linkable — the reason the record page exists.
        onRowClick={(creator) => navigate(`/creators/${creator.id}`)}
        selectedKeys={selectedIds}
        onSelectionChange={setSelectedIds}
        selectionLabel={(creator) => `Select ${creator.name || creator.handle || 'creator'}`}
        sortBy={sortBy}
        sortDir={sortDir}
        onSort={toggleSort}
        emptyState={
          totalCount === 0 ? (
            <EmptyState
              icon="◍"
              title="No creators yet"
              description="Import the spreadsheet you already keep them in, or add the first one by hand."
              action={
                <button type="button" className="primary-btn" onClick={openCreate}>
                  Add your first creator
                </button>
              }
            />
          ) : (
            <EmptyState
              title="No creators match this filter"
              description={search.trim() ? `Nothing found for "${search.trim()}".` : 'Try a different platform.'}
              action={
                <button type="button" className="ghost-btn" onClick={clearFilters}>Clear filters</button>
              }
            />
          )
        }
      />

      {drawerMode ? (
        <Drawer
          title={drawerMode === 'create' ? 'New creator' : 'Edit creator'}
          onClose={requestClose}
        >
          {/* Read-only, and above the form on purpose: these are the numbers a brand decides on,
              and they are the only ones on this screen nobody on the team can type. Shown only
              when someone has actually been looked up — an empty panel would imply a failed
              lookup rather than one that never happened. */}
          {editingCreator?.followerCount !== null && editingCreator?.followerCount !== undefined ? (
            <section className="audience-panel">
              <h3 className="audience-panel-title">Audience</h3>
              <dl className="audience-stats">
                <div>
                  <dt>Followers</dt>
                  <dd>{Number(editingCreator.followerCount).toLocaleString()}</dd>
                </div>
                {editingCreator.engagementRate !== null && editingCreator.engagementRate !== undefined ? (
                  <div>
                    <dt>Engagement</dt>
                    <dd>{Number(editingCreator.engagementRate).toFixed(2)}%</dd>
                  </div>
                ) : null}
                {editingCreator.averageViews !== null && editingCreator.averageViews !== undefined ? (
                  <div>
                    <dt>Avg. views</dt>
                    <dd>{Number(editingCreator.averageViews).toLocaleString()}</dd>
                  </div>
                ) : null}
              </dl>
              <MetricsProvenance
                source={editingCreator.metricsSource}
                fetchedAt={editingCreator.metricsFetchedAt}
              />
            </section>
          ) : null}

          <form
            className="drawer-form"
            onSubmit={drawerMode === 'create' ? submitCreate : (event) => { event.preventDefault(); submitEdit() }}
          >
            <Field label="Name" htmlFor="creator-name" required>
              <input
                id="creator-name"
                type="text"
                value={activeDraft.name}
                placeholder="Ari Rivera"
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, name: event.target.value }))}
                required
              />
            </Field>

            <Field
              label="Handle"
              htmlFor="creator-handle"
              required
              hint={drawerMode === 'create'
                ? 'Look up the handle to read this creator’s audience from the platform before you add them.'
                : undefined}
            >
              <div className="handle-lookup">
                <input
                  id="creator-handle"
                  type="text"
                  value={activeDraft.handle}
                  placeholder="@aririvera"
                  onChange={(event) => setActiveDraft((prev) => ({ ...prev, handle: event.target.value }))}
                  required
                />
                {/* Create only. On an existing creator the audience panel above already shows what
                    was read and when, and re-reading it belongs with the record's refresh rather
                    than in a form whose job is editing the fields a human owns. */}
                {drawerMode === 'create' ? (
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={runLookup}
                    disabled={lookingUp || !String(activeDraft.handle || '').trim()}
                  >
                    {lookingUp ? 'Looking up…' : 'Look up'}
                  </button>
                ) : null}
              </div>
            </Field>

            {/* The preview. Shown only while it still describes what is in the field: editing the
                handle after a lookup makes these numbers someone else's, and leaving them on
                screen would invite adding a creator with another account's audience. */}
            {drawerMode === 'create' && lookup && lookupMatchesHandle(lookupHandle, activeDraft.handle) ? (
              lookup.resolved ? (
                <section className="audience-panel" aria-live="polite">
                  <h3 className="audience-panel-title">
                    Audience for @{lookup.handle || lookupHandle}
                  </h3>
                  <dl className="audience-stats">
                    {lookup.followerCount !== null && lookup.followerCount !== undefined ? (
                      <div>
                        <dt>Followers</dt>
                        <dd>{Number(lookup.followerCount).toLocaleString()}</dd>
                      </div>
                    ) : null}
                    {lookup.engagementRate !== null && lookup.engagementRate !== undefined ? (
                      <div>
                        <dt>Engagement</dt>
                        <dd>{Number(lookup.engagementRate).toFixed(2)}%</dd>
                      </div>
                    ) : null}
                    {lookup.averageViews !== null && lookup.averageViews !== undefined ? (
                      <div>
                        <dt>Avg. views</dt>
                        <dd>{Number(lookup.averageViews).toLocaleString()}</dd>
                      </div>
                    ) : null}
                  </dl>
                  {/* The same badge the record page uses, from the same `metricsSource` the BFF
                      stamped. This is the line that keeps a simulated figure legible as one. */}
                  <MetricsProvenance
                    source={lookup.metricsSource}
                    fetchedAt={lookup.metricsFetchedAt}
                  />
                  <p className="audience-panel-note">
                    Saved with this creator when you add them.
                  </p>
                </section>
              ) : (
                <p className="field-hint" role="status">
                  {lookup.reason || 'The handle could not be resolved. Enter the details manually.'}
                </p>
              )
            ) : null}

            <Field label="Platform" htmlFor="creator-platform">
              <select
                id="creator-platform"
                value={activeDraft.platform}
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, platform: event.target.value }))}
              >
                {PLATFORM_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </Field>

            <Field label="Email" htmlFor="creator-email" hint="Used for outreach and payout notices.">
              <input
                id="creator-email"
                type="email"
                value={activeDraft.email}
                placeholder="ari@example.com"
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, email: event.target.value }))}
              />
            </Field>

            {/* The per-brand negotiated rate. Placed with the identity fields rather than buried
                under custom attributes: it is the one capability MARKET-ANALYSIS.md §4 finds no
                documented competitor equivalent for, and it was invisible in the product until
                now. A differentiator nobody can see is not a differentiator. */}
            <Field
              label="Preferred rate"
              htmlFor="creator-preferred-rate"
              hint="What this brand pays this creator. Each brand negotiates its own — the same creator can hold a different rate under another brand."
            >
              <input
                id="creator-preferred-rate"
                type="number"
                min="0"
                step="1"
                inputMode="decimal"
                value={activeDraft.preferredRate}
                placeholder="2500"
                onChange={(event) => setActiveDraft((prev) => ({ ...prev, preferredRate: event.target.value }))}
              />
            </Field>

            <Field label="Custom attributes" hint="Anything from your own spreadsheet — tier, agency, rate.">
              <CustomAttributesEditor
                pairs={activeDraft.customAttributes}
                onChange={(pairs) => setActiveDraft((prev) => ({ ...prev, customAttributes: pairs }))}
                onValidationChange={activeValidationSetter}
              />
            </Field>

            {formError ? <p className="field-error" role="alert">{formError}</p> : null}

            <div className="drawer-actions">
              {/* Edit only, and only for a role that may delete. Pushed to the far left by
                  `drawer-actions-danger` so it is nowhere near Save changes — the two are one
                  mis-click apart otherwise, and only one of them is reversible. */}
              {drawerMode === 'edit' && canDeleteCreator && onDeleteCreator ? (
                <button
                  type="button"
                  className="danger-btn drawer-actions-danger"
                  onClick={() => setConfirmDelete(true)}
                  disabled={saving || deleting}
                >
                  {deleting ? 'Deleting…' : 'Delete'}
                </button>
              ) : null}
              <button type="button" className="ghost-btn" onClick={requestClose} disabled={saving || deleting}>
                Cancel
              </button>
              <button type="submit" className="primary-btn" disabled={saving || deleting}>
                {saving ? 'Saving…' : drawerMode === 'create' ? 'Add creator' : 'Save changes'}
              </button>
            </div>
          </form>
        </Drawer>
      ) : null}

      {confirmDelete ? (
        <ConfirmDialog
          title={`Delete ${editDraft.name.trim() || 'this creator'}?`}
          consequence="Their campaign assignments, coupons and workflow cards for this brand go with them. This cannot be undone."
          confirmLabel={deleting ? 'Deleting…' : 'Delete'}
          cancelLabel="Keep creator"
          onConfirm={runDelete}
          onCancel={() => setConfirmDelete(false)}
        />
      ) : null}

      {confirmDiscard ? (
        <ConfirmDialog
          title="Discard your changes?"
          consequence={`Edits to ${editDraft.name || 'this creator'} have not been saved and will be lost.`}
          confirmLabel="Discard"
          cancelLabel="Keep editing"
          onConfirm={closeDrawer}
          onCancel={() => setConfirmDiscard(false)}
        />
      ) : null}
    </>
  )
}

export default CreatorsPage
