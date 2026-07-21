import { useState } from 'react'
import { MdsKicker, MdsSectionRule } from '../components/Mds'

function CreatorsPage({ creators, creatorForm, setCreatorForm, onCreateCreator, onUpdateCreator }) {
  const [editingId, setEditingId] = useState('')
  const [editDraft, setEditDraft] = useState({ name: '', handle: '', platform: 'Instagram', email: '' })
  const [savingId, setSavingId] = useState('')
  const [rowFeedback, setRowFeedback] = useState({ id: '', type: '', message: '' })

  const startEdit = (creator) => {
    setRowFeedback({ id: '', type: '', message: '' })
    setEditingId(creator.id)
    setEditDraft({
      name: creator.name || '',
      handle: creator.handle || '',
      platform: creator.platform || 'Instagram',
      email: creator.email || '',
    })
  }

  const cancelEdit = () => {
    setEditingId('')
    setSavingId('')
    setRowFeedback({ id: '', type: '', message: '' })
  }

  const saveEdit = async () => {
    if (!editingId || !editDraft.name.trim() || !editDraft.handle.trim()) {
      return
    }

    try {
      setSavingId(editingId)
      await onUpdateCreator(editingId, {
        name: editDraft.name.trim(),
        handle: editDraft.handle.trim(),
        platform: editDraft.platform,
        email: editDraft.email.trim(),
      })
      setRowFeedback({ id: editingId, type: 'success', message: 'Creator updated.' })
      setEditingId('')
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
      <h3>3. Add creator</h3>
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
          <option>Instagram</option>
          <option>TikTok</option>
          <option>YouTube</option>
          <option>Other</option>
        </select>
        <input
          type="email"
          value={creatorForm.email}
          placeholder="Email"
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, email: event.target.value }))}
        />
        <button type="submit" className="primary-btn">
          Add creator
        </button>
      </form>
      <ul className="simple-list">
        {creators.map((creator) => (
          <li key={creator.id}>
            {editingId === creator.id ? (
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
                  <option>Instagram</option>
                  <option>TikTok</option>
                  <option>YouTube</option>
                  <option>Other</option>
                </select>
                <input
                  type="email"
                  value={editDraft.email}
                  onChange={(event) => setEditDraft((prev) => ({ ...prev, email: event.target.value }))}
                />
                <div className="row-actions">
                  <button type="button" className="ghost-btn" onClick={cancelEdit} disabled={savingId === creator.id}>
                    Cancel
                  </button>
                  <button type="button" className="primary-btn" onClick={saveEdit} disabled={savingId === creator.id}>
                    {savingId === creator.id ? 'Saving...' : 'Save'}
                  </button>
                </div>
              </div>
            ) : (
              <>
                <strong>{creator.name}</strong>
                <span>{creator.handle}</span>
                <span>{creator.platform}</span>
                <div className="row-actions">
                  <button type="button" className="ghost-btn" onClick={() => startEdit(creator)}>
                    Edit
                  </button>
                </div>
              </>
            )}
            {rowFeedback.id === creator.id && rowFeedback.message ? (
              <p className={`row-save-feedback ${rowFeedback.type === 'error' ? 'error' : 'success'}`}>{rowFeedback.message}</p>
            ) : null}
          </li>
        ))}
      </ul>
    </article>
  )
}

export default CreatorsPage
