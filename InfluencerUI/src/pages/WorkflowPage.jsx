import { useEffect, useMemo, useState } from 'react'
import { STAGES, stageLabels } from '../constants'
import { MdsKicker, MdsNote, MdsSectionRule } from '../components/Mds'

function normalizeCampaignType(value) {
  const normalized = String(value || '').trim().toLowerCase()
  return normalized || 'paid'
}

function labelForStage(stageKey) {
  return stageLabels[stageKey] || stageKey
}

function humanizeCampaignType(value) {
  return String(value || '')
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

function buildEditSnapshot(draft) {
  return JSON.stringify({
    fee: String(draft?.fee || '').trim(),
    dueDate: String(draft?.dueDate || '').trim(),
    notes: String(draft?.notes || '').trim(),
    tags: String(draft?.tags || '').trim(),
  })
}

function normalizeTagList(value) {
  if (Array.isArray(value)) {
    return value.map((tag) => String(tag || '').trim()).filter(Boolean)
  }

  return String(value || '')
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean)
}

function WorkflowPage({
  campaigns,
  creators,
  assignments,
  assignmentForm,
  setAssignmentForm,
  onTieCreatorToCampaign,
  campaignTypeOptions,
  campaignTypeWorkflowStages,
  getConfiguredStagesForCampaignType,
  onSaveCampaignTypeWorkflowSetup,
  campaignById,
  creatorById,
  updateCardStage,
  onUpdateAssignment,
}) {
  const [dragOverTarget, setDragOverTarget] = useState('')
  const [searchText, setSearchText] = useState('')
  const [creatorFilter, setCreatorFilter] = useState('all')
  const [tagFilter, setTagFilter] = useState('all')
  const [boardNotice, setBoardNotice] = useState('')
  const [boardCampaignTypeFilter, setBoardCampaignTypeFilter] = useState('all')
  const [editingId, setEditingId] = useState('')
  const [editSnapshot, setEditSnapshot] = useState('')
  const [savingId, setSavingId] = useState('')
  const [setupCampaignType, setSetupCampaignType] = useState('')
  const [setupDraftStages, setSetupDraftStages] = useState([])
  const [setupDragStageKey, setSetupDragStageKey] = useState('')
  const [savingWorkflowSetup, setSavingWorkflowSetup] = useState(false)
  const [quickNotes, setQuickNotes] = useState({})
  const [quickNoteSavingId, setQuickNoteSavingId] = useState('')
  const [quickTags, setQuickTags] = useState({})
  const [quickTagSavingId, setQuickTagSavingId] = useState('')
  const [editDraft, setEditDraft] = useState({
    fee: '',
    dueDate: '',
    notes: '',
    tags: '',
  })
  const [cardFeedback, setCardFeedback] = useState({ id: '', type: '', message: '' })

  const campaignTypes = useMemo(() => {
    const unique = new Set(campaigns.map((campaign) => normalizeCampaignType(campaign?.campaignType)))
    ;(campaignTypeOptions || []).forEach((option) => {
      unique.add(normalizeCampaignType(option.value))
    })
    unique.add('paid')
    return Array.from(unique).sort((a, b) => a.localeCompare(b))
  }, [campaigns, campaignTypeOptions])

  const campaignTypeLabelMap = useMemo(() => {
    return (campaignTypeOptions || []).reduce((acc, option) => {
      acc[normalizeCampaignType(option.value)] = option.label || humanizeCampaignType(option.value)
      return acc
    }, {})
  }, [campaignTypeOptions])

  const formatCampaignTypeLabel = (value) => {
    const normalized = normalizeCampaignType(value)
    return campaignTypeLabelMap[normalized] || humanizeCampaignType(normalized)
  }

  const workflowLabelByTypeStage = useMemo(() => {
    return (campaignTypeWorkflowStages || []).reduce((acc, item) => {
      const type = normalizeCampaignType(item?.campaignType)
      const stageKey = String(item?.stageKey || '').trim()
      const label = String(item?.stageLabel || '').trim()
      if (!type || !stageKey || !label) {
        return acc
      }
      if (!acc[type]) {
        acc[type] = {}
      }
      acc[type][stageKey] = label
      return acc
    }, {})
  }, [campaignTypeWorkflowStages])

  const stageLabelForType = (campaignType, stageKey) => {
    const type = normalizeCampaignType(campaignType)
    return workflowLabelByTypeStage[type]?.[stageKey] || labelForStage(stageKey)
  }

  const sortedSetupStages = useMemo(() => {
    return [...setupDraftStages].sort((a, b) => {
      const byPosition = Number(a?.position || 0) - Number(b?.position || 0)
      if (byPosition !== 0) {
        return byPosition
      }
      return String(a?.stageKey || '').localeCompare(String(b?.stageKey || ''))
    })
  }, [setupDraftStages])

  const selectedCampaign = campaignById[assignmentForm.campaignId]
  const selectedCampaignType = normalizeCampaignType(selectedCampaign?.campaignType)
  const selectedTypeConfiguredStages = getConfiguredStagesForCampaignType(selectedCampaignType)
  const editingItem = assignments.find((item) => item.id === editingId)
  const editingCampaign = editingItem ? campaignById[editingItem.campaignId] : null
  const editingCreator = editingItem ? creatorById[editingItem.creatorId] : null

  useEffect(() => {
    if (setupCampaignType) {
      return
    }
    setSetupCampaignType(campaignTypes[0] || 'paid')
  }, [setupCampaignType, campaignTypes])

  useEffect(() => {
    if (!setupCampaignType) {
      return
    }

    const existingRows = (campaignTypeWorkflowStages || [])
      .filter((item) => normalizeCampaignType(item?.campaignType) === normalizeCampaignType(setupCampaignType))

    if (existingRows.length) {
      const byStage = existingRows.reduce((acc, item) => {
        acc[item.stageKey] = item
        return acc
      }, {})

      setSetupDraftStages(STAGES.map((stageKey, index) => ({
        stageKey,
        stageLabel: byStage[stageKey]?.stageLabel || labelForStage(stageKey),
        position: Number.isFinite(Number(byStage[stageKey]?.position)) ? Number(byStage[stageKey]?.position) : index,
        isActive: byStage[stageKey]?.isActive !== false,
      })))
      return
    }

    setSetupDraftStages(STAGES.map((stageKey, index) => ({
      stageKey,
      stageLabel: labelForStage(stageKey),
      position: index,
      isActive: true,
    })))
  }, [setupCampaignType, campaignTypeWorkflowStages])

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
  }, [editingId, editDraft, editSnapshot, savingId])

  const allTags = Array.from(new Set(assignments.flatMap((item) => normalizeTagList(item.tags)))).sort((a, b) => a.localeCompare(b))

  const matchesFilters = (item) => {
    const campaign = campaignById[item.campaignId]
    const creator = creatorById[item.creatorId]
    const searchBlob = [
      campaign?.name || '',
      creator?.name || '',
      creator?.handle || '',
      item.notes || '',
      normalizeTagList(item.tags).join(' '),
    ]
      .join(' ')
      .toLowerCase()

    const textPass = !searchText.trim() || searchBlob.includes(searchText.trim().toLowerCase())
    const creatorPass = creatorFilter === 'all' || item.creatorId === creatorFilter
    const tagPass = tagFilter === 'all' || normalizeTagList(item.tags).includes(tagFilter)

    return textPass && creatorPass && tagPass
  }

  const filteredAssignments = assignments.filter(matchesFilters)

  const typeFilteredAssignments = filteredAssignments.filter((item) => {
    if (boardCampaignTypeFilter === 'all') {
      return true
    }
    const campaign = campaignById[item.campaignId]
    return normalizeCampaignType(campaign?.campaignType) === boardCampaignTypeFilter
  })

  const boardStageKeys = boardCampaignTypeFilter === 'all'
    ? STAGES
    : (getConfiguredStagesForCampaignType(boardCampaignTypeFilter).length
      ? getConfiguredStagesForCampaignType(boardCampaignTypeFilter)
      : STAGES)

  const stagesForAssignment = (item) => {
    const campaign = campaignById[item.campaignId]
    const campaignType = normalizeCampaignType(campaign?.campaignType)
    const configured = getConfiguredStagesForCampaignType(campaignType)
    return configured.length ? configured : STAGES
  }

  const itemsForCell = (stage) => typeFilteredAssignments.filter((item) => (item.stage || 'outreach') === stage)

  const tryMoveCard = async (assignmentId, nextStage) => {
    const movingAssignment = assignments.find((item) => item.id === assignmentId)
    if (!movingAssignment) {
      return
    }
    const allowedStages = stagesForAssignment(movingAssignment)
    if (!allowedStages.includes(nextStage)) {
      setBoardNotice('That stage is not enabled for this campaign type workflow.')
      return
    }

    try {
      await updateCardStage(assignmentId, nextStage)
      setBoardNotice('')
    } catch (error) {
      setBoardNotice(error instanceof Error ? error.message : 'Unable to move work item.')
    }
  }

  const onCardDragStart = (event, assignmentId) => {
    event.dataTransfer.setData('text/plain', assignmentId)
    event.dataTransfer.effectAllowed = 'move'
  }

  const onColumnDrop = (event, stage) => {
    event.preventDefault()
    const assignmentId = event.dataTransfer.getData('text/plain')
    if (assignmentId) {
      tryMoveCard(assignmentId, stage)
    }
    setDragOverTarget('')
  }

  const startCardEdit = (item) => {
    setCardFeedback({ id: '', type: '', message: '' })
    setEditingId(item.id)
    const nextDraft = {
      fee: item.fee == null ? '' : String(item.fee),
      dueDate: item.dueDate || '',
      notes: item.notes || '',
      tags: normalizeTagList(item.tags).join(', '),
    }
    setEditDraft(nextDraft)
    setEditSnapshot(buildEditSnapshot(nextDraft))
  }

  const cancelCardEdit = () => {
    setEditingId('')
    setEditSnapshot('')
    setSavingId('')
    setCardFeedback({ id: '', type: '', message: '' })
  }

  const requestCloseEdit = () => {
    if (!editingId || savingId === editingId) {
      return
    }

    const hasUnsavedChanges = editSnapshot && editSnapshot !== buildEditSnapshot(editDraft)
    if (hasUnsavedChanges && !window.confirm('You have unsaved changes. Discard them and close?')) {
      return
    }

    cancelCardEdit()
  }

  const saveCardEdit = async (itemId) => {
    const existing = assignments.find((item) => item.id === itemId)
    if (!existing) {
      return
    }

    try {
      setSavingId(itemId)
      await onUpdateAssignment(itemId, {
        stage: existing.stage,
        fee: editDraft.fee,
        dueDate: editDraft.dueDate,
        notes: editDraft.notes.trim(),
        tags: editDraft.tags,
      })
      setCardFeedback({ id: itemId, type: 'success', message: 'Workflow record updated.' })
      setEditingId('')
    } catch (error) {
      setCardFeedback({
        id: itemId,
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to update workflow record.',
      })
    } finally {
      setSavingId('')
    }
  }

  const saveWorkflowSetup = async () => {
    const activeStages = sortedSetupStages.filter((stage) => stage.isActive)
    if (!setupCampaignType || !activeStages.length) {
      setBoardNotice('Select a campaign type and keep at least one active stage.')
      return
    }

    try {
      setSavingWorkflowSetup(true)
      await onSaveCampaignTypeWorkflowSetup(
        setupCampaignType,
        sortedSetupStages.map((stage, index) => ({
          ...stage,
          position: index,
          stageLabel: String(stage.stageLabel || '').trim() || labelForStage(stage.stageKey),
        })),
      )
      setBoardNotice(`Workflow setup saved for campaign type "${setupCampaignType}".`)
      if (boardCampaignTypeFilter !== 'all') {
        setBoardCampaignTypeFilter(setupCampaignType)
      }
    } catch (error) {
      setBoardNotice(error instanceof Error ? error.message : 'Unable to save workflow setup.')
    } finally {
      setSavingWorkflowSetup(false)
    }
  }

  const reorderSetupStages = (fromStageKey, toStageKey) => {
    if (!fromStageKey || !toStageKey || fromStageKey === toStageKey) {
      return
    }

    const working = [...sortedSetupStages]
    const fromIndex = working.findIndex((row) => row.stageKey === fromStageKey)
    const toIndex = working.findIndex((row) => row.stageKey === toStageKey)
    if (fromIndex < 0 || toIndex < 0) {
      return
    }

    const [moved] = working.splice(fromIndex, 1)
    working.splice(toIndex, 0, moved)
    setSetupDraftStages(working.map((row, index) => ({ ...row, position: index })))
  }

  const startQuickNote = (item) => {
    setQuickNotes((prev) => ({
      ...prev,
      [item.id]: item.notes || '',
    }))
  }

  const cancelQuickNote = (itemId) => {
    setQuickNotes((prev) => {
      const next = { ...prev }
      delete next[itemId]
      return next
    })
  }

  const saveQuickNote = async (item) => {
    const draft = String(quickNotes[item.id] ?? '')
    try {
      setQuickNoteSavingId(item.id)
      await onUpdateAssignment(item.id, {
        stage: item.stage,
        fee: item.fee == null ? '' : String(item.fee),
        dueDate: item.dueDate || '',
        notes: draft.trim(),
        tags: item.tags || [],
      })
      setCardFeedback({ id: item.id, type: 'success', message: 'Note updated.' })
      cancelQuickNote(item.id)
    } catch (error) {
      setCardFeedback({
        id: item.id,
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to update note.',
      })
    } finally {
      setQuickNoteSavingId('')
    }
  }

  const startQuickTags = (item) => {
    setQuickTags((prev) => ({
      ...prev,
      [item.id]: (item.tags || []).join(', '),
    }))
  }

  const cancelQuickTags = (itemId) => {
    setQuickTags((prev) => {
      const next = { ...prev }
      delete next[itemId]
      return next
    })
  }

  const saveQuickTags = async (item) => {
    const draft = String(quickTags[item.id] ?? '')
    const parsedTags = draft
      .split(',')
      .map((tag) => tag.trim())
      .filter(Boolean)

    try {
      setQuickTagSavingId(item.id)
      await onUpdateAssignment(item.id, {
        stage: item.stage,
        fee: item.fee == null ? '' : String(item.fee),
        dueDate: item.dueDate || '',
        notes: item.notes || '',
        tags: parsedTags,
      })
      setCardFeedback({ id: item.id, type: 'success', message: 'Tags updated.' })
      cancelQuickTags(item.id)
    } catch (error) {
      setCardFeedback({
        id: item.id,
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to update tags.',
      })
    } finally {
      setQuickTagSavingId('')
    }
  }

  return (
    <section className="page-stack">
      <article className="card mds-surface mds-prose relationship-card">
        <MdsKicker>Workflow Setup</MdsKicker>
        <h3>4. Configure workflow by campaign type</h3>
        <MdsSectionRule />
        <p>Set enabled stages and labels for each campaign type before creating work items.</p>
        <div className="workflow-type-setup">
          <select value={setupCampaignType} onChange={(event) => setSetupCampaignType(event.target.value)}>
            {campaignTypes.map((campaignType) => (
              <option key={campaignType} value={campaignType}>
                {formatCampaignTypeLabel(campaignType)}
              </option>
            ))}
          </select>
          <button type="button" className="primary-btn" onClick={saveWorkflowSetup} disabled={savingWorkflowSetup}>
            {savingWorkflowSetup ? 'Saving...' : 'Save workflow setup'}
          </button>
        </div>
        <div className="workflow-stage-setup-grid">
          {sortedSetupStages.map((stage, index) => (
            <div
              key={stage.stageKey}
              className={`workflow-stage-setup-row${setupDragStageKey === stage.stageKey ? ' dragging' : ''}`}
              draggable
              onDragStart={() => setSetupDragStageKey(stage.stageKey)}
              onDragOver={(event) => event.preventDefault()}
              onDrop={() => {
                reorderSetupStages(setupDragStageKey, stage.stageKey)
                setSetupDragStageKey('')
              }}
              onDragEnd={() => setSetupDragStageKey('')}
            >
              <span className="workflow-stage-drag-handle" aria-hidden="true">::</span>
              <label>
                <input
                  type="checkbox"
                  checked={stage.isActive}
                  onChange={(event) => {
                    const checked = event.target.checked
                    setSetupDraftStages((prev) => prev.map((row) => (
                      row.stageKey === stage.stageKey ? { ...row, isActive: checked } : row
                    )))
                  }}
                />
                <span>{stage.stageKey}</span>
              </label>
              <input
                type="text"
                value={stage.stageLabel}
                onChange={(event) => {
                  const value = event.target.value
                  setSetupDraftStages((prev) => prev.map((row) => (
                    row.stageKey === stage.stageKey ? { ...row, stageLabel: value } : row
                  )))
                }}
              />
              <input
                type="number"
                min="0"
                value={stage.position}
                onChange={(event) => {
                  const value = Number(event.target.value)
                  setSetupDraftStages((prev) => prev.map((row) => (
                    row.stageKey === stage.stageKey ? { ...row, position: Number.isFinite(value) ? value : index } : row
                  )))
                }}
              />
            </div>
          ))}
        </div>
      </article>

      <article className="card mds-surface mds-prose relationship-card">
        <MdsKicker>Relationship Mapping</MdsKicker>
        <h3>5. Tie creators to campaigns</h3>
        <MdsSectionRule />
        <p>Create the active relationship record, then generate a workflow task that carries the stage on the board.</p>
        <form onSubmit={onTieCreatorToCampaign} className="inline-form assignment-form">
          <select
            value={assignmentForm.campaignId}
            onChange={(event) => setAssignmentForm((prev) => ({ ...prev, campaignId: event.target.value }))}
          >
            {campaigns.map((campaign) => (
              <option key={campaign.id} value={campaign.id}>
                {campaign.name}
              </option>
            ))}
          </select>

          <select
            value={assignmentForm.creatorId}
            onChange={(event) => setAssignmentForm((prev) => ({ ...prev, creatorId: event.target.value }))}
          >
            {creators.map((creator) => (
              <option key={creator.id} value={creator.id}>
                {creator.name} ({creator.handle})
              </option>
            ))}
          </select>

          <select
            value={assignmentForm.stage}
            onChange={(event) => setAssignmentForm((prev) => ({ ...prev, stage: event.target.value }))}
          >
            {(selectedTypeConfiguredStages.length ? selectedTypeConfiguredStages : STAGES).map((stage) => (
              <option key={stage} value={stage}>
                {stageLabelForType(selectedCampaignType, stage)}
              </option>
            ))}
          </select>

          <input
            type="number"
            value={assignmentForm.fee}
            placeholder="Agreed fee"
            onChange={(event) => setAssignmentForm((prev) => ({ ...prev, fee: event.target.value }))}
          />
          <input
            type="text"
            value={assignmentForm.notes}
            placeholder="Notes"
            onChange={(event) => setAssignmentForm((prev) => ({ ...prev, notes: event.target.value }))}
          />
          <input
            type="date"
            value={assignmentForm.dueDate}
            onChange={(event) => setAssignmentForm((prev) => ({ ...prev, dueDate: event.target.value }))}
          />
          <input
            type="text"
            value={assignmentForm.tags}
            placeholder="Assignee tags (comma separated)"
            onChange={(event) => setAssignmentForm((prev) => ({ ...prev, tags: event.target.value }))}
          />
          <button type="submit" className="primary-btn" disabled={!selectedTypeConfiguredStages.length}>
            Tie to campaign
          </button>
        </form>
        {!selectedTypeConfiguredStages.length ? (
          <MdsNote className="board-notice">Configure workflow stages for campaign type "{selectedCampaignType}" before creating work items.</MdsNote>
        ) : null}
      </article>

      <article className="card mds-surface mds-prose workflow-board">
        <MdsKicker>Workflow Board</MdsKicker>
        <h3>6. Creator-campaign workflow</h3>
        <MdsSectionRule />
        <p className="helper">Drag work items across stage columns to update each item stage.</p>

        <div className="workflow-toolbar">
          <input
            type="text"
            value={searchText}
            placeholder="Search campaign, creator, notes, or tags"
            onChange={(event) => setSearchText(event.target.value)}
          />
          <select value={creatorFilter} onChange={(event) => setCreatorFilter(event.target.value)}>
            <option value="all">All creators</option>
            {creators.map((creator) => (
              <option key={creator.id} value={creator.id}>
                {creator.name}
              </option>
            ))}
          </select>
          <select value={tagFilter} onChange={(event) => setTagFilter(event.target.value)}>
            <option value="all">All tags</option>
            {allTags.map((tag) => (
              <option key={tag} value={tag}>
                {tag}
              </option>
            ))}
          </select>
          <select value={boardCampaignTypeFilter} onChange={(event) => setBoardCampaignTypeFilter(event.target.value)}>
            <option value="all">All campaign types</option>
            {campaignTypes.map((campaignType) => (
              <option key={campaignType} value={campaignType}>
                {formatCampaignTypeLabel(campaignType)}
              </option>
            ))}
          </select>
        </div>

        {boardNotice ? <MdsNote className="board-notice">{boardNotice}</MdsNote> : null}

        <div className="columns">
          {boardStageKeys.map((stage) => {
                  const target = stage
                  const headerLabel = boardCampaignTypeFilter === 'all'
                    ? labelForStage(stage)
                    : stageLabelForType(boardCampaignTypeFilter, stage)
                  return (
                    <article
                      key={target}
                      className={`kanban-column${dragOverTarget === target ? ' drop-target' : ''}`}
                      onDragOver={(event) => {
                        event.preventDefault()
                        setDragOverTarget(target)
                      }}
                      onDragLeave={() => setDragOverTarget('')}
                      onDrop={(event) => onColumnDrop(event, stage)}
                    >
                      <header>
                        <h4>{headerLabel}</h4>
                        <span>{itemsForCell(stage).length}</span>
                      </header>

                      <div className="cards">
                        {itemsForCell(stage).map((item) => {
                          const campaign = campaignById[item.campaignId]
                          const creator = creatorById[item.creatorId]
                          const isEditing = editingId === item.id
                          const isQuickNoteOpen = quickNotes[item.id] != null
                          const isQuickTagsOpen = quickTags[item.id] != null
                          return (
                            <div
                              key={item.id}
                              className="kanban-card"
                              draggable={!editingId && !isQuickNoteOpen && !isQuickTagsOpen}
                              onDragStart={(event) => onCardDragStart(event, item.id)}
                            >
                              <p className="card-campaign">{campaign?.name || 'Unknown campaign'}</p>
                              <p className="card-creator">{creator?.name || 'Unknown creator'}</p>
                              <p className="card-handle">{creator?.handle || 'No handle'}</p>
                              <>
                                  <p className="card-meta">Fee: {item.fee ? `$${item.fee}` : 'TBD'}</p>
                                  <p className="card-meta">Due: {item.dueDate || 'No due date'}</p>
                                  <p className="card-meta">{item.notes || 'No notes yet.'}</p>
                                  <div className="tag-row">
                                    {(item.tags || []).length ? (
                                      normalizeTagList(item.tags).map((tag) => (
                                        <span key={`${item.id}-${tag}`} className="tag-chip">
                                          {tag}
                                        </span>
                                      ))
                                    ) : (
                                      <span className="tag-chip muted">no tags</span>
                                    )}
                                  </div>

                                  {isQuickNoteOpen ? (
                                    <div className="quick-note-editor">
                                      <textarea
                                        value={quickNotes[item.id]}
                                        placeholder="Add note"
                                        onChange={(event) => setQuickNotes((prev) => ({ ...prev, [item.id]: event.target.value }))}
                                      />
                                      <div className="row-actions">
                                        <button
                                          type="button"
                                          className="ghost-btn"
                                          onClick={() => cancelQuickNote(item.id)}
                                          disabled={quickNoteSavingId === item.id}
                                        >
                                          Cancel
                                        </button>
                                        <button
                                          type="button"
                                          className="primary-btn"
                                          onClick={() => saveQuickNote(item)}
                                          disabled={quickNoteSavingId === item.id}
                                        >
                                          {quickNoteSavingId === item.id ? 'Saving...' : 'Save note'}
                                        </button>
                                      </div>
                                    </div>
                                  ) : null}

                                  {isQuickTagsOpen ? (
                                    <div className="quick-tags-editor">
                                      <input
                                        type="text"
                                        value={quickTags[item.id]}
                                        placeholder="tag1, tag2"
                                        onChange={(event) => setQuickTags((prev) => ({ ...prev, [item.id]: event.target.value }))}
                                      />
                                      <div className="row-actions">
                                        <button
                                          type="button"
                                          className="ghost-btn"
                                          onClick={() => cancelQuickTags(item.id)}
                                          disabled={quickTagSavingId === item.id}
                                        >
                                          Cancel
                                        </button>
                                        <button
                                          type="button"
                                          className="primary-btn"
                                          onClick={() => saveQuickTags(item)}
                                          disabled={quickTagSavingId === item.id}
                                        >
                                          {quickTagSavingId === item.id ? 'Saving...' : 'Save tags'}
                                        </button>
                                      </div>
                                    </div>
                                  ) : null}
                                </>

                              <div className="card-actions">
                                <button type="button" onClick={() => startCardEdit(item)} disabled={Boolean(editingId)}>
                                  Edit
                                </button>
                                <button type="button" onClick={() => startQuickNote(item)} disabled={isEditing || isQuickNoteOpen}>
                                  Notes
                                </button>
                                <button type="button" onClick={() => startQuickTags(item)} disabled={isEditing || isQuickTagsOpen}>
                                  Tags
                                </button>
                              </div>
                              {cardFeedback.id === item.id && cardFeedback.message ? (
                                <p className={`row-save-feedback ${cardFeedback.type === 'error' ? 'error' : 'success'}`}>
                                  {cardFeedback.message}
                                </p>
                              ) : null}
                            </div>
                          )
                        })}
                      </div>
                    </article>
                  )
          })}
        </div>
      </article>

      {editingId ? (
        <div className="edit-drawer-overlay" onClick={requestCloseEdit} role="presentation">
          <aside className="edit-drawer" onClick={(event) => event.stopPropagation()} aria-label="Edit workflow item">
            <div className="edit-drawer-header">
              <h4>Edit work item</h4>
              <button type="button" className="ghost-btn" onClick={requestCloseEdit} disabled={savingId === editingId}>
                Close
              </button>
            </div>
            <div className="editable-row-form">
              <p className="card-campaign">{editingCampaign?.name || 'Unknown campaign'}</p>
              <p className="card-creator">{editingCreator?.name || 'Unknown creator'}</p>
              <input
                type="number"
                value={editDraft.fee}
                placeholder="Agreed fee"
                onChange={(event) => setEditDraft((prev) => ({ ...prev, fee: event.target.value }))}
              />
              <input
                type="date"
                value={editDraft.dueDate}
                onChange={(event) => setEditDraft((prev) => ({ ...prev, dueDate: event.target.value }))}
              />
              <input
                type="text"
                value={editDraft.tags}
                placeholder="Tags (comma separated)"
                onChange={(event) => setEditDraft((prev) => ({ ...prev, tags: event.target.value }))}
              />
              <textarea
                value={editDraft.notes}
                placeholder="Notes"
                onChange={(event) => setEditDraft((prev) => ({ ...prev, notes: event.target.value }))}
              />
              {cardFeedback.id === editingId && cardFeedback.message ? (
                <p className={`row-save-feedback ${cardFeedback.type === 'error' ? 'error' : 'success'}`}>
                  {cardFeedback.message}
                </p>
              ) : null}
              <div className="row-actions">
                <button type="button" className="ghost-btn" onClick={requestCloseEdit} disabled={savingId === editingId}>
                  Cancel
                </button>
                <button
                  type="button"
                  className="primary-btn"
                  onClick={() => saveCardEdit(editingId)}
                  disabled={savingId === editingId}
                >
                  {savingId === editingId ? 'Saving...' : 'Save'}
                </button>
              </div>
            </div>
          </aside>
        </div>
      ) : null}
    </section>
  )
}

export default WorkflowPage
