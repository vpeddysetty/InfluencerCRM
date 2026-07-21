import { useState } from 'react'
import { MdsKicker, MdsSectionRule } from '../components/Mds'

function CampaignsPage({ campaigns, campaignForm, setCampaignForm, onCreateCampaign, onUpdateCampaign }) {
  const [editingId, setEditingId] = useState('')
  const [editDraft, setEditDraft] = useState({ name: '', budget: '', status: 'draft' })
  const [savingId, setSavingId] = useState('')
  const [rowFeedback, setRowFeedback] = useState({ id: '', type: '', message: '' })

  const startEdit = (campaign) => {
    setRowFeedback({ id: '', type: '', message: '' })
    setEditingId(campaign.id)
    setEditDraft({
      name: campaign.name || '',
      budget: campaign.budget == null ? '' : String(campaign.budget),
      status: campaign.status || 'draft',
    })
  }

  const cancelEdit = () => {
    setEditingId('')
    setSavingId('')
    setRowFeedback({ id: '', type: '', message: '' })
  }

  const saveEdit = async () => {
    if (!editingId || !editDraft.name.trim()) {
      return
    }

    try {
      setSavingId(editingId)
      await onUpdateCampaign(editingId, {
        name: editDraft.name.trim(),
        budget: editDraft.budget.trim(),
        status: editDraft.status,
      })
      setRowFeedback({ id: editingId, type: 'success', message: 'Campaign updated.' })
      setEditingId('')
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
        <button type="submit" className="primary-btn">
          Add campaign
        </button>
      </form>
      <ul className="simple-list">
        {campaigns.map((campaign) => (
          <li key={campaign.id}>
            {editingId === campaign.id ? (
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
                <div className="row-actions">
                  <button type="button" className="ghost-btn" onClick={cancelEdit} disabled={savingId === campaign.id}>
                    Cancel
                  </button>
                  <button type="button" className="primary-btn" onClick={saveEdit} disabled={savingId === campaign.id}>
                    {savingId === campaign.id ? 'Saving...' : 'Save'}
                  </button>
                </div>
              </div>
            ) : (
              <>
                <strong>{campaign.name}</strong>
                <span>{campaign.status}</span>
                <span>{campaign.budget ? `$${campaign.budget}` : 'Budget tbd'}</span>
                <div className="row-actions">
                  <button type="button" className="ghost-btn" onClick={() => startEdit(campaign)}>
                    Edit
                  </button>
                </div>
              </>
            )}
            {rowFeedback.id === campaign.id && rowFeedback.message ? (
              <p className={`row-save-feedback ${rowFeedback.type === 'error' ? 'error' : 'success'}`}>{rowFeedback.message}</p>
            ) : null}
          </li>
        ))}
      </ul>
    </article>
  )
}

export default CampaignsPage
