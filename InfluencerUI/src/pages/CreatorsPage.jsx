import { useEffect, useMemo, useState } from 'react'
import { MdsKicker, MdsSectionRule } from '../components/Mds'
import CustomAttributesEditor from '../components/CustomAttributesEditor'

function sanitizePairs(pairs) {
  return Array.isArray(pairs)
    ? pairs.filter((pair) => String(pair?.key || '').trim() || String(pair?.value || '').trim())
    : []
}

const SORT_OPTIONS = [
  { value: 'name-asc', label: 'Name A–Z' },
  { value: 'name-desc', label: 'Name Z–A' },
  { value: 'handle-asc', label: 'Handle A–Z' },
  { value: 'platform-asc', label: 'Platform' },
]

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

function compareCreators(a, b, sortBy) {
  const text = (value) => String(value || '').toLowerCase()
  switch (sortBy) {
    case 'name-desc':
      return text(b?.name).localeCompare(text(a?.name))
    case 'handle-asc':
      return text(a?.handle).localeCompare(text(b?.handle))
    case 'platform-asc':
      return text(a?.platform).localeCompare(text(b?.platform)) || text(a?.name).localeCompare(text(b?.name))
    case 'name-asc':
    default:
      return text(a?.name).localeCompare(text(b?.name))
  }
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
}) {
  const [editingId, setEditingId] = useState('')
  const [editDraft, setEditDraft] = useState({ name: '', handle: '', platform: 'instagram', email: '', customAttributes: [] })
  const [editSnapshot, setEditSnapshot] = useState('')
  const [savingId, setSavingId] = useState('')
  const [rowFeedback, setRowFeedback] = useState({ id: '', type: '', message: '' })
  const [createAttrValidation, setCreateAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })
  const [editAttrValidation, setEditAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })
  const [search, setSearch] = useState('')
  const [platformFilter, setPlatformFilter] = useState('')
  const [sortBy, setSortBy] = useState('name-asc')

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
    return (creators || [])
      .filter((creator) => matchesQuery(creator, query, customAttributesToPairs))
      .filter((creator) => !platformFilter || String(creator?.platform || '').toLowerCase() === platformFilter)
      .sort((a, b) => compareCreators(a, b, sortBy))
  }, [creators, search, platformFilter, sortBy, customAttributesToPairs])

  const totalCount = (creators || []).length
  const isFiltered = Boolean(search.trim() || platformFilter)

  const clearFilters = () => {
    setSearch('')
    setPlatformFilter('')
  }

  useEffect(() => {
    if (!editingId) {
      return undefined
    }

    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    return () => {
      document.body.style.overflow = originalOverflow
    }
  }, [editingId])

  const closeEdit = () => {
    setEditingId('')
    setEditSnapshot('')
    setSavingId('')
    setRowFeedback({ id: '', type: '', message: '' })
  }

  const requestCloseEdit = () => {
    if (!editingId || savingId === editingId) {
      return
    }

    const hasUnsavedChanges = editSnapshot && editSnapshot !== buildSnapshot(editDraft)
    if (hasUnsavedChanges && !window.confirm('You have unsaved changes. Discard them and close?')) {
      return
    }

    closeEdit()
  }

  useEffect(() => {
    if (!editingId) {
      return undefined
    }

    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        requestCloseEdit()
      }
    }

    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [editingId, savingId, editDraft, editSnapshot])

  const startEdit = (creator) => {
    setRowFeedback({ id: '', type: '', message: '' })
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
  }

  const saveEdit = async () => {
    if (!editingId || !editDraft.name.trim() || !editDraft.handle.trim()) {
      return
    }

    if (editAttrValidation.hasDuplicateKeys || editAttrValidation.hasMissingKeys) {
      setRowFeedback({ id: editingId, type: 'error', message: 'Fix custom attributes before saving (unique names and no unnamed values).' })
      return
    }

    try {
      setSavingId(editingId)
      await onUpdateCreator(editingId, {
        name: editDraft.name.trim(),
        handle: editDraft.handle.trim(),
        platform: editDraft.platform,
        email: editDraft.email.trim(),
        customAttributes: sanitizePairs(editDraft.customAttributes),
      })
      setRowFeedback({ id: editingId, type: 'success', message: 'Creator updated.' })
      closeEdit()
    } catch (error) {
      setRowFeedback({
        id: editingId,
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to update creator.',
      })
    } finally {
      setSavingId('')
    }
  }

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Creator Directory</MdsKicker>
      <h3>Add creator</h3>
      <MdsSectionRule />
      <p>Store creator profile details so assignments can be tied and tracked accurately.</p>
      <form onSubmit={onCreateCreator} className="inline-form page-form-grid">
        <input
          type="text"
          value={creatorForm.name}
          placeholder="Creator name"
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, name: event.target.value }))}
          required
        />
        <input
          type="text"
          value={creatorForm.handle}
          placeholder="@handle"
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, handle: event.target.value }))}
          required
        />
        <select
          value={creatorForm.platform}
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, platform: event.target.value }))}
        >
          <option value="instagram">Instagram</option>
          <option value="tiktok">TikTok</option>
          <option value="youtube">YouTube</option>
          <option value="other">Other</option>
        </select>
        <input
          type="email"
          value={creatorForm.email}
          placeholder="Email"
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, email: event.target.value }))}
        />
        <div className="custom-attributes-form-block">
          <p className="custom-attributes-label">Custom attributes</p>
          <CustomAttributesEditor
            pairs={creatorForm.customAttributes}
            onChange={(pairs) => setCreatorForm((prev) => ({ ...prev, customAttributes: pairs }))}
            onValidationChange={setCreateAttrValidation}
          />
        </div>
        <button
          type="submit"
          className="primary-btn"
          disabled={createAttrValidation.hasDuplicateKeys || createAttrValidation.hasMissingKeys}
        >
          Add creator
        </button>
      </form>
      <MdsSectionRule />
      <div className="creator-directory-header">
        <h4>
          Your creators
          <span className="creator-count">
            {isFiltered ? `${visibleCreators.length} of ${totalCount}` : totalCount}
          </span>
        </h4>
      </div>

      {totalCount > 0 ? (
        <div className="creator-filter-bar">
          <label className="creator-search-field">
            <span className="visually-hidden">Search creators</span>
            <input
              type="search"
              value={search}
              placeholder="Search by name, handle, email, or attribute…"
              onChange={(event) => setSearch(event.target.value)}
              aria-label="Search creators"
            />
          </label>
          <label className="creator-filter-field">
            <span className="visually-hidden">Filter by platform</span>
            <select
              value={platformFilter}
              onChange={(event) => setPlatformFilter(event.target.value)}
              aria-label="Filter by platform"
            >
              <option value="">All platforms</option>
              {availablePlatforms.map((platform) => (
                <option key={platform} value={platform}>
                  {platform.charAt(0).toUpperCase() + platform.slice(1)}
                </option>
              ))}
            </select>
          </label>
          <label className="creator-filter-field">
            <span className="visually-hidden">Sort creators</span>
            <select value={sortBy} onChange={(event) => setSortBy(event.target.value)} aria-label="Sort creators">
              {SORT_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          {isFiltered ? (
            <button type="button" className="ghost-btn" onClick={clearFilters}>
              Clear
            </button>
          ) : null}
        </div>
      ) : null}

      {totalCount === 0 ? (
        <p className="custom-attributes-empty">
          No creators yet. Import your spreadsheet or add one above to get started.
        </p>
      ) : null}

      {totalCount > 0 && visibleCreators.length === 0 ? (
        <p className="custom-attributes-empty">
          No creators match {search.trim() ? `"${search.trim()}"` : 'this filter'}.{' '}
          <button type="button" className="link-btn" onClick={clearFilters}>
            Clear filters
          </button>
        </p>
      ) : null}

      <ul className="simple-list">
        {visibleCreators.map((creator) => (
          <li key={creator.id}>
            <>
              <strong>{creator.name}</strong>
              <span>{creator.handle}</span>
              <span>{creator.platform}</span>
              <p className="custom-attributes-label">Custom attributes</p>
              {customAttributesToPairs(creator.customAttributes).length ? (
                <div className="custom-attributes-readonly">
                  {customAttributesToPairs(creator.customAttributes).map((pair) => (
                    <span key={`${creator.id}-${pair.key}`} className="custom-attribute-pill">
                      <strong>{pair.key}:</strong> {pair.value}
                    </span>
                  ))}
                </div>
              ) : (
                <p className="custom-attributes-empty">No custom attributes.</p>
              )}
              <div className="row-actions">
                <button type="button" className="ghost-btn" onClick={() => startEdit(creator)}>
                  Edit
                </button>
              </div>
            </>
            {rowFeedback.id === creator.id && rowFeedback.message ? (
              <p className={`row-save-feedback ${rowFeedback.type === 'error' ? 'error' : 'success'}`}>{rowFeedback.message}</p>
            ) : null}
          </li>
        ))}
      </ul>

      {editingId ? (
        <div className="edit-drawer-overlay" onClick={requestCloseEdit} role="presentation">
          <aside className="edit-drawer" onClick={(event) => event.stopPropagation()} aria-label="Edit creator">
            <div className="edit-drawer-header">
              <h4>Edit creator</h4>
              <button type="button" className="ghost-btn" onClick={requestCloseEdit} disabled={savingId === editingId}>
                Close
              </button>
            </div>
            <div className="editable-row-form">
              <input
                type="text"
                value={editDraft.name}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, name: event.target.value }))}
              />
              <input
                type="text"
                value={editDraft.handle}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, handle: event.target.value }))}
              />
              <select
                value={editDraft.platform}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, platform: event.target.value }))}
              >
                <option value="instagram">Instagram</option>
                <option value="tiktok">TikTok</option>
                <option value="youtube">YouTube</option>
                <option value="other">Other</option>
              </select>
              <input
                type="email"
                value={editDraft.email}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, email: event.target.value }))}
              />
              <div className="custom-attributes-form-block">
                <p className="custom-attributes-label">Custom attributes</p>
                <CustomAttributesEditor
                  pairs={editDraft.customAttributes}
                  onChange={(pairs) => setEditDraft((prev) => ({ ...prev, customAttributes: pairs }))}
                  onValidationChange={setEditAttrValidation}
                />
              </div>
              {rowFeedback.id === editingId && rowFeedback.message ? (
                <p className={`row-save-feedback ${rowFeedback.type === 'error' ? 'error' : 'success'}`}>{rowFeedback.message}</p>
              ) : null}
              <div className="row-actions">
                <button type="button" className="ghost-btn" onClick={requestCloseEdit} disabled={savingId === editingId}>
                  Cancel
                </button>
                <button
                  type="button"
                  className="primary-btn"
                  onClick={saveEdit}
                  disabled={savingId === editingId || editAttrValidation.hasDuplicateKeys || editAttrValidation.hasMissingKeys}
                >
                  {savingId === editingId ? 'Saving...' : 'Save'}
                </button>
              </div>
            </div>
          </aside>
        </div>
      ) : null}
    </article>
  )
}

export default CreatorsPage
