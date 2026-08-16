import { useMemo, useState } from 'react'
import './components/ui/ui.css'
import CustomAttributesEditor from './components/CustomAttributesEditor'
import {
  Badge,
  ConfirmDialog,
  DataTable,
  Drawer,
  EmptyState,
  Field,
  FilterBar,
  MetricsProvenance,
  PageHeader,
  useToast,
} from './components/ui'
import { lookupMatchesHandle, metricsFromLookup } from './handleLookup'

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

const EMPTY_DRAFT = { name: '', handle: '', platform: 'instagram', email: '', customAttributes: [], resolvedMetrics: null }

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
}) {
  const toast = useToast()

  // '' (closed) | 'create' | 'edit'. One drawer serves both: creation moved off the page so the
  // directory owns the first screen, and an edit is the same form with different initial values.
  const [drawerMode, setDrawerMode] = useState('')
  const [editingId, setEditingId] = useState('')
  const [editDraft, setEditDraft] = useState(EMPTY_DRAFT)
  const [editSnapshot, setEditSnapshot] = useState('')
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState('')
  const [confirmDiscard, setConfirmDiscard] = useState(false)
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

  const [search, setSearch] = useState('')
  const [platformFilter, setPlatformFilter] = useState('')
  const [sortBy, setSortBy] = useState('name')
  const [sortDir, setSortDir] = useState('asc')

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
    setSearch('')
    setPlatformFilter('')
  }

  const toggleSort = (key) => {
    if (key === sortBy) {
      setSortDir((prev) => (prev === 'asc' ? 'desc' : 'asc'))
      return
    }
    setSortBy(key)
    setSortDir('asc')
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
   * <p>`onLookupHandle` comes from the gateway with the session token already bound, the same way
   * `onCreateCreator` does. The remote holds no token of its own and must not acquire one.
   */
  const runLookup = async () => {
    const handle = String(activeDraft.handle || '').trim()
    if (!handle || lookingUp || !onLookupHandle) {
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
          name: String(prev.name || '').trim() ? prev.name : (result.name || result.displayName || prev.name),
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
  ]

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
          <button type="button" className="primary-btn" onClick={openCreate}>
            New creator
          </button>
        }
      />

      {totalCount > 0 ? (
        <FilterBar>
          <input
            type="search"
            value={search}
            placeholder="Search by name, handle, email, or attribute…"
            onChange={(event) => setSearch(event.target.value)}
            aria-label="Search creators"
          />
          <select
            value={platformFilter}
            onChange={(event) => setPlatformFilter(event.target.value)}
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

      <DataTable
        caption="Creators"
        columns={columns}
        rows={visibleCreators}
        rowKey={(creator) => creator.id}
        onRowClick={openEdit}
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
              hint={drawerMode === 'create' && onLookupHandle
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
                {/* Create only, and only when the gateway supplied the handler — the standalone
                    harness renders this page with no props at all, and a button that throws on
                    click would make the dev entry point useless. */}
                {drawerMode === 'create' && onLookupHandle ? (
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
                  {/* Reads the same `metricsSource` the BFF stamped. This is the line that keeps a
                      simulated figure legible as one. */}
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

            <Field label="Custom attributes" hint="Anything from your own spreadsheet — tier, agency, rate.">
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
                {saving ? 'Saving…' : drawerMode === 'create' ? 'Add creator' : 'Save changes'}
              </button>
            </div>
          </form>
        </Drawer>
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
