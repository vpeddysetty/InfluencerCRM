import { useState } from 'react'
import { STAGES, stageLabels } from '../constants'

function WorkflowPage({
  campaigns,
  creators,
  assignments,
  assignmentForm,
  setAssignmentForm,
  onTieCreatorToCampaign,
  groupedAssignments,
  campaignById,
  creatorById,
  updateCardStage,
}) {
  const [dragOverStage, setDragOverStage] = useState('')
  const [searchText, setSearchText] = useState('')
  const [creatorFilter, setCreatorFilter] = useState('all')
  const [tagFilter, setTagFilter] = useState('all')
  const [wipLimits, setWipLimits] = useState({
    outreach: 8,
    agreed: 6,
    shipped: 6,
    posted: 8,
    paid: 10,
  })
  const [boardNotice, setBoardNotice] = useState('')

  const allTags = Array.from(new Set(assignments.flatMap((item) => item.tags || []))).sort((a, b) => a.localeCompare(b))

  const matchesFilters = (item) => {
    const campaign = campaignById[item.campaignId]
    const creator = creatorById[item.creatorId]
    const searchBlob = [
      campaign?.name || '',
      creator?.name || '',
      creator?.handle || '',
      item.notes || '',
      (item.tags || []).join(' '),
    ]
      .join(' ')
      .toLowerCase()

    const textPass = !searchText.trim() || searchBlob.includes(searchText.trim().toLowerCase())
    const creatorPass = creatorFilter === 'all' || item.creatorId === creatorFilter
    const tagPass = tagFilter === 'all' || (item.tags || []).includes(tagFilter)

    return textPass && creatorPass && tagPass
  }

  const filteredByStage = STAGES.reduce((acc, stage) => {
    acc[stage] = groupedAssignments[stage].filter(matchesFilters)
    return acc
  }, {})

  const canMoveToStage = (assignmentId, nextStage) => {
    const movingAssignment = assignments.find((item) => item.id === assignmentId)
    if (!movingAssignment || !nextStage) {
      return false
    }

    if (movingAssignment.stage === nextStage) {
      return true
    }

    return groupedAssignments[nextStage].length < (wipLimits[nextStage] || 0)
  }

  const tryUpdateStage = (assignmentId, nextStage) => {
    if (!canMoveToStage(assignmentId, nextStage)) {
      setBoardNotice(`Cannot move card. ${stageLabels[nextStage]} hit WIP limit (${wipLimits[nextStage]}).`)
      return
    }

    updateCardStage(assignmentId, nextStage)
    setBoardNotice('')
  }

  const moveByDirection = (assignmentId, direction) => {
    const movingAssignment = assignments.find((item) => item.id === assignmentId)
    if (!movingAssignment) {
      return
    }
    const currentIndex = STAGES.indexOf(movingAssignment.stage)
    const nextIndex = Math.min(Math.max(currentIndex + direction, 0), STAGES.length - 1)
    tryUpdateStage(assignmentId, STAGES[nextIndex])
  }

  const onCardDragStart = (event, assignmentId) => {
    event.dataTransfer.setData('text/plain', assignmentId)
    event.dataTransfer.effectAllowed = 'move'
  }

  const onColumnDrop = (event, stage) => {
    event.preventDefault()
    const assignmentId = event.dataTransfer.getData('text/plain')
    if (assignmentId) {
      tryUpdateStage(assignmentId, stage)
    }
    setDragOverStage('')
  }

  return (
    <section className="page-stack">
      <article className="card mdx-surface mdx-prose relationship-card">
        <p className="mdx-kicker">Relationship Mapping</p>
        <h3>4. Tie creators to campaigns</h3>
        <div className="mdx-section-rule" />
        <p>Create the active relationship record before managing progression in Kanban.</p>
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
            {STAGES.map((stage) => (
              <option key={stage} value={stage}>
                {stageLabels[stage]}
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
          <button type="submit" className="primary-btn">
            Tie to campaign
          </button>
        </form>
      </article>

      <article className="card mdx-surface mdx-prose kanban-board">
        <p className="mdx-kicker">Workflow Board</p>
        <h3>5. Creator-campaign relationship Kanban</h3>
        <div className="mdx-section-rule" />
        <p className="helper">Drag cards between columns to update stage. WIP limits prevent overloading a stage.</p>

        <div className="kanban-toolbar">
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
        </div>

        {boardNotice ? <p className="mdx-note board-notice">{boardNotice}</p> : null}

        <div className="columns">
          {STAGES.map((stage) => (
            <article
              key={stage}
              className={`kanban-column${dragOverStage === stage ? ' drop-target' : ''}`}
              onDragOver={(event) => {
                event.preventDefault()
                setDragOverStage(stage)
              }}
              onDragLeave={() => setDragOverStage('')}
              onDrop={(event) => onColumnDrop(event, stage)}
            >
              <header>
                <h4>{stageLabels[stage]}</h4>
                <span>
                  {filteredByStage[stage].length}/{wipLimits[stage]}
                </span>
              </header>

              <label className="wip-control" htmlFor={`wip-${stage}`}>
                WIP limit
                <input
                  id={`wip-${stage}`}
                  type="number"
                  min="1"
                  value={wipLimits[stage]}
                  onChange={(event) => {
                    const value = Number(event.target.value)
                    if (!Number.isNaN(value) && value > 0) {
                      setWipLimits((prev) => ({ ...prev, [stage]: value }))
                    }
                  }}
                />
              </label>

              <div className="cards">
                {filteredByStage[stage].map((item) => {
                  const campaign = campaignById[item.campaignId]
                  const creator = creatorById[item.creatorId]
                  return (
                    <div
                      key={item.id}
                      className="kanban-card"
                      draggable
                      onDragStart={(event) => onCardDragStart(event, item.id)}
                    >
                      <p className="card-campaign">{campaign?.name || 'Unknown campaign'}</p>
                      <p className="card-creator">{creator?.name || 'Unknown creator'}</p>
                      <p className="card-handle">{creator?.handle || 'No handle'}</p>
                      <p className="card-meta">Fee: {item.fee ? `$${item.fee}` : 'TBD'}</p>
                      <p className="card-meta">Due: {item.dueDate || 'No due date'}</p>
                      <p className="card-meta">{item.notes || 'No notes yet.'}</p>
                      <div className="tag-row">
                        {(item.tags || []).length ? (
                          (item.tags || []).map((tag) => (
                            <span key={`${item.id}-${tag}`} className="tag-chip">
                              {tag}
                            </span>
                          ))
                        ) : (
                          <span className="tag-chip muted">no tags</span>
                        )}
                      </div>

                      <select
                        value={item.stage}
                        onChange={(event) => tryUpdateStage(item.id, event.target.value)}
                      >
                        {STAGES.map((option) => (
                          <option key={option} value={option}>
                            {stageLabels[option]}
                          </option>
                        ))}
                      </select>

                      <div className="card-actions">
                        <button type="button" onClick={() => moveByDirection(item.id, -1)}>
                          Back
                        </button>
                        <button type="button" onClick={() => moveByDirection(item.id, 1)}>
                          Forward
                        </button>
                      </div>
                    </div>
                  )
                })}
              </div>
            </article>
          ))}
        </div>
      </article>
    </section>
  )
}

export default WorkflowPage
