import { useEffect, useState } from 'react'
import { MdsKicker, MdsSectionRule } from '../components/Mds'
import CustomAttributesEditor from '../components/CustomAttributesEditor'

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
  const [editingId, setEditingId] = useState('')
  const [editDraft, setEditDraft] = useState({
    name: '',
    budget: '',
    status: 'draft',
    campaignType: campaignTypeOptions?.[0]?.value || 'product seeding',
    customAttributes: [],
  })
  const [editSnapshot, setEditSnapshot] = useState('')
  const [savingId, setSavingId] = useState('')
  const [rowFeedback, setRowFeedback] = useState({ id: '', type: '', message: '' })
  const [createAttrValidation, setCreateAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })
  const [editAttrValidation, setEditAttrValidation] = useState({ hasDuplicateKeys: false, hasMissingKeys: false })
  const campaignTypeLabelMap = (campaignTypeOptions || []).reduce((acc, option) => {
    acc[String(option.value || '').trim().toLowerCase()] = option.label || humanizeCampaignType(option.value)
    return acc
  }, {})

  const formatCampaignTypeLabel = (value) => {
    const normalized = normalizeCampaignTypeForPayload(value)
    return campaignTypeLabelMap[normalized] || humanizeCampaignType(normalized)
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

  const startEdit = (campaign) => {
    setRowFeedback({ id: '', type: '', message: '' })
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
  }

  const saveEdit = async () => {
    if (!editingId || !editDraft.name.trim()) {
      return
    }

    if (editAttrValidation.hasDuplicateKeys || editAttrValidation.hasMissingKeys) {
      setRowFeedback({ id: editingId, type: 'error', message: 'Fix custom attributes before saving (unique names and no unnamed values).' })
      return
    }

    try {
      setSavingId(editingId)
      await onUpdateCampaign(editingId, {
        name: editDraft.name.trim(),
        budget: editDraft.budget.trim(),
        status: editDraft.status,
        campaignType: normalizeCampaignTypeForPayload(editDraft.campaignType),
        customAttributes: sanitizePairs(editDraft.customAttributes),
      })
      setRowFeedback({ id: editingId, type: 'success', message: 'Campaign updated.' })
      closeEdit()
    } catch (error) {
      setRowFeedback({
        id: editingId,
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to update campaign.',
      })
    } finally {
      setSavingId('')
    }
  }

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Campaign Editor</MdsKicker>
      <h3>2. Create campaign</h3>
      <MdsSectionRule />
      <p>Define campaign basics first, then move creators through workflow stages.</p>
      <form onSubmit={onCreateCampaign} className="inline-form page-form-grid">
        <input
          type="text"
          value={campaignForm.name}
          placeholder="Campaign name"
          onChange={(event) => setCampaignForm((prev) => ({ ...prev, name: event.target.value }))}
          required
        />
        <input
          type="number"
          value={campaignForm.budget}
          placeholder="Budget"
          onChange={(event) => setCampaignForm((prev) => ({ ...prev, budget: event.target.value }))}
        />
        <select
          value={campaignForm.status}
          onChange={(event) => setCampaignForm((prev) => ({ ...prev, status: event.target.value }))}
        >
          <option value="draft">Draft</option>
          <option value="active">Active</option>
          <option value="completed">Completed</option>
        </select>
        <select
          value={campaignForm.campaignType}
          onChange={(event) => setCampaignForm((prev) => ({ ...prev, campaignType: event.target.value }))}
        >
          {(campaignTypeOptions || []).map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <div className="custom-attributes-form-block">
          <p className="custom-attributes-label">Custom attributes</p>
          <CustomAttributesEditor
            pairs={campaignForm.customAttributes}
            onChange={(pairs) => setCampaignForm((prev) => ({ ...prev, customAttributes: pairs }))}
            onValidationChange={setCreateAttrValidation}
          />
        </div>
        <button
          type="submit"
          className="primary-btn"
          disabled={createAttrValidation.hasDuplicateKeys || createAttrValidation.hasMissingKeys}
        >
          Add campaign
        </button>
      </form>
      <ul className="simple-list">
        {campaigns.map((campaign) => (
          <li key={campaign.id}>
            <>
              <strong>{campaign.name}</strong>
              <span>{campaign.status}</span>
              <span>{formatCampaignTypeLabel(campaign.campaignType)}</span>
              <span>{campaign.budget ? `$${campaign.budget}` : 'Budget tbd'}</span>
              <p className="custom-attributes-label">Custom attributes</p>
              {customAttributesToPairs(campaign.customAttributes).length ? (
                <div className="custom-attributes-readonly">
                  {customAttributesToPairs(campaign.customAttributes).map((pair) => (
                    <span key={`${campaign.id}-${pair.key}`} className="custom-attribute-pill">
                      <strong>{pair.key}:</strong> {pair.value}
                    </span>
                  ))}
                </div>
              ) : (
                <p className="custom-attributes-empty">No custom attributes.</p>
              )}
              <div className="row-actions">
                <button type="button" className="ghost-btn" onClick={() => startEdit(campaign)}>
                  Edit
                </button>
              </div>
            </>
            {rowFeedback.id === campaign.id && rowFeedback.message ? (
              <p className={`row-save-feedback ${rowFeedback.type === 'error' ? 'error' : 'success'}`}>{rowFeedback.message}</p>
            ) : null}
          </li>
        ))}
      </ul>

      {editingId ? (
        <div className="edit-drawer-overlay" onClick={requestCloseEdit} role="presentation">
          <aside className="edit-drawer" onClick={(event) => event.stopPropagation()} aria-label="Edit campaign">
            <div className="edit-drawer-header">
              <h4>Edit campaign</h4>
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
                type="number"
                value={editDraft.budget}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, budget: event.target.value }))}
              />
              <select
                value={editDraft.status}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, status: event.target.value }))}
              >
                <option value="draft">Draft</option>
                <option value="active">Active</option>
                <option value="completed">Completed</option>
              </select>
              <select
                value={editDraft.campaignType}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, campaignType: event.target.value }))}
              >
                {(campaignTypeOptions || []).map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
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

export default CampaignsPage
