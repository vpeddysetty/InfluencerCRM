import { useEffect, useMemo, useState } from 'react'
import { MdsKicker, MdsNote, MdsSectionRule } from './components/Mds'
import { MAX_WORKFLOW_BOARDS, DEFAULT_BOARD_STAGES } from './constants'

const EMPTY_DRAFT = {
  name: '',
  startDate: '',
  endDate: '',
  stages: [{ stageName: '' }],
}

const EMPTY_CARD_DRAFT = {
  campaignId: '',
  creatorId: '',
  name: '',
  status: 'todo',
  agreedFee: '',
  feeCurrency: 'USD',
  notes: '',
  tags: '',
}

function WorkflowPage({
  boards,
  boardStages,
  activeBoardId,
  onSelectBoard,
  onCreateBoard,
  onCreateDefaultBoard,
  onDeleteBoard,
  onUpdateBoard,
  onSaveBoardStages,
  campaigns = [],
  creators = [],
  cards = [],
  onCreateCard,
  onPlaceCard,
  onDeleteCard,
}) {
  const [creating, setCreating] = useState(false)
  const [notice, setNotice] = useState('')

  // Slider drawer: mode is '' (closed), 'create', or 'edit'.
  const [drawerMode, setDrawerMode] = useState('')
  const [drawerBoardId, setDrawerBoardId] = useState('')
  const [draft, setDraft] = useState(EMPTY_DRAFT)
  const [saving, setSaving] = useState(false)
  const [drawerNotice, setDrawerNotice] = useState('')

  // Card creation drawer + search.
  const [cardDrawerOpen, setCardDrawerOpen] = useState(false)
  const [cardDraft, setCardDraft] = useState(EMPTY_CARD_DRAFT)
  const [savingCard, setSavingCard] = useState(false)
  const [cardDrawerNotice, setCardDrawerNotice] = useState('')
  const [cardSearch, setCardSearch] = useState('')
  const [dragCardId, setDragCardId] = useState('')
  const [dropStageId, setDropStageId] = useState('')
  const [cardNotice, setCardNotice] = useState('')

  const sortedBoards = useMemo(() => {
    return [...(boards || [])].sort((a, b) => {
      const byPos = Number(a?.position || 0) - Number(b?.position || 0)
      if (byPos !== 0) return byPos
      return String(a?.createdAt || '').localeCompare(String(b?.createdAt || ''))
    })
  }, [boards])

  const stagesByBoard = useMemo(() => {
    return (boardStages || []).reduce((acc, stage) => {
      const key = stage?.boardId
      if (!key) return acc
      if (!acc[key]) acc[key] = []
      acc[key].push(stage)
      return acc
    }, {})
  }, [boardStages])

  const stagesForBoard = (boardId) =>
    [...(stagesByBoard[boardId] || [])].sort((a, b) => Number(a?.position || 0) - Number(b?.position || 0))

  const activeBoard = useMemo(
    () => (boards || []).find((b) => b.id === activeBoardId) || null,
    [boards, activeBoardId],
  )

  const atBoardLimit = sortedBoards.length >= MAX_WORKFLOW_BOARDS
  const drawerOpen = drawerMode === 'create' || drawerMode === 'edit'

  const campaignById = useMemo(
    () => (campaigns || []).reduce((acc, c) => { acc[c.id] = c; return acc }, {}),
    [campaigns],
  )
  const creatorById = useMemo(
    () => (creators || []).reduce((acc, c) => { acc[c.id] = c; return acc }, {}),
    [creators],
  )

  const cardSearchBlob = (card) => {
    const campaign = campaignById[card.campaignId]
    const creator = creatorById[card.creatorId]
    return [
      card.name,
      campaign?.name,
      creator?.name,
      creator?.handle,
      card.status,
      card.notes,
      ...(Array.isArray(card.tags) ? card.tags : []),
    ].filter(Boolean).join(' ').toLowerCase()
  }

  // Unassigned cards (no board), filtered by the search box.
  const unassignedCards = useMemo(() => {
    const term = cardSearch.trim().toLowerCase()
    return (cards || [])
      .filter((c) => !c.boardId)
      .filter((c) => !term || cardSearchBlob(c).includes(term))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cards, cardSearch, campaignById, creatorById])

  const cardsForStage = (stageId) =>
    (cards || []).filter((c) => c.boardId === activeBoardId && c.stageId === stageId)

  // Lock body scroll + close on Escape while the drawer is open.
  useEffect(() => {
    if (!drawerOpen) return undefined
    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        closeDrawer()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      document.body.style.overflow = originalOverflow
      window.removeEventListener('keydown', onKeyDown)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drawerOpen])

  const openCreateDrawer = () => {
    if (atBoardLimit) {
      setNotice(`Board limit reached (${MAX_WORKFLOW_BOARDS}). Delete a board to add another.`)
      return
    }
    setDrawerNotice('')
    setDrawerBoardId('')
    setDraft({
      name: '',
      startDate: '',
      endDate: '',
      stages: DEFAULT_BOARD_STAGES.map((stageName) => ({ stageName })),
    })
    setDrawerMode('create')
  }

  const openEditDrawer = (board) => {
    setDrawerNotice('')
    setDrawerBoardId(board.id)
    const stages = stagesForBoard(board.id).map((s) => ({ stageName: s.stageName }))
    setDraft({
      name: board.name || '',
      startDate: board.startDate || '',
      endDate: board.endDate || '',
      stages: stages.length ? stages : [{ stageName: '' }],
    })
    setDrawerMode('edit')
  }

  const closeDrawer = () => {
    if (saving) return
    setDrawerMode('')
    setDrawerBoardId('')
    setDraft(EMPTY_DRAFT)
    setDrawerNotice('')
  }

  const createDefault = async () => {
    if (atBoardLimit) {
      setNotice(`Board limit reached (${MAX_WORKFLOW_BOARDS}).`)
      return
    }
    try {
      setCreating(true)
      setNotice('')
      await onCreateDefaultBoard()
      setNotice('Default template board created.')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'Unable to create default board.')
    } finally {
      setCreating(false)
    }
  }

  const removeBoard = async (board) => {
    if (!window.confirm(`Delete board "${board.name}"? Its stages will be removed.`)) {
      return
    }
    try {
      await onDeleteBoard(board.id)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : 'Unable to delete board.')
    }
  }

  // ---- drawer stage editor ----
  const addStageDraft = () => setDraft((prev) => ({ ...prev, stages: [...prev.stages, { stageName: '' }] }))
  const updateStageDraft = (index, value) =>
    setDraft((prev) => ({
      ...prev,
      stages: prev.stages.map((row, i) => (i === index ? { stageName: value } : row)),
    }))
  const removeStageDraft = (index) =>
    setDraft((prev) => ({
      ...prev,
      stages: prev.stages.length <= 1 ? prev.stages : prev.stages.filter((_, i) => i !== index),
    }))
  const moveStageDraft = (index, dir) =>
    setDraft((prev) => {
      const next = [...prev.stages]
      const target = index + dir
      if (target < 0 || target >= next.length) return prev
      const [moved] = next.splice(index, 1)
      next.splice(target, 0, moved)
      return { ...prev, stages: next }
    })

  const saveDrawer = async () => {
    const name = draft.name.trim()
    if (!name) {
      setDrawerNotice('Enter a board name.')
      return
    }
    const stageNames = draft.stages.map((s) => s.stageName.trim()).filter(Boolean)
    if (!stageNames.length) {
      setDrawerNotice('Add at least one stage.')
      return
    }

    try {
      setSaving(true)
      setDrawerNotice('')
      if (drawerMode === 'create') {
        await onCreateBoard({
          name,
          startDate: draft.startDate || null,
          endDate: draft.endDate || null,
          stages: stageNames.map((stageName) => ({ stageName })),
        })
        setNotice(`Board "${name}" created.`)
      } else {
        await onUpdateBoard(drawerBoardId, {
          name,
          startDate: draft.startDate || null,
          endDate: draft.endDate || null,
        })
        await onSaveBoardStages(drawerBoardId, stageNames.map((stageName) => ({ stageName })))
        setNotice(`Board "${name}" updated.`)
      }
      setDrawerMode('')
      setDrawerBoardId('')
      setDraft(EMPTY_DRAFT)
    } catch (error) {
      setDrawerNotice(error instanceof Error ? error.message : 'Unable to save board.')
    } finally {
      setSaving(false)
    }
  }

  // ---- card creation drawer ----
  const openCardDrawer = () => {
    setCardDrawerNotice('')
    setCardDraft({
      ...EMPTY_CARD_DRAFT,
      campaignId: campaigns[0]?.id || '',
      creatorId: creators[0]?.id || '',
    })
    setCardDrawerOpen(true)
  }

  const closeCardDrawer = () => {
    if (savingCard) return
    setCardDrawerOpen(false)
    setCardDraft(EMPTY_CARD_DRAFT)
    setCardDrawerNotice('')
  }

  const saveCard = async () => {
    const name = cardDraft.name.trim()
    if (!cardDraft.campaignId || !cardDraft.creatorId) {
      setCardDrawerNotice('Select both a campaign and a creator.')
      return
    }
    if (!name) {
      setCardDrawerNotice('Enter a card name.')
      return
    }
    const tags = cardDraft.tags.split(',').map((t) => t.trim()).filter(Boolean)
    try {
      setSavingCard(true)
      setCardDrawerNotice('')
      await onCreateCard({
        campaignId: cardDraft.campaignId,
        creatorId: cardDraft.creatorId,
        name,
        status: cardDraft.status,
        agreedFee: cardDraft.agreedFee,
        feeCurrency: cardDraft.feeCurrency,
        notes: cardDraft.notes,
        tags,
      })
      setCardNotice(`Card "${name}" added to the pool.`)
      setCardDrawerOpen(false)
      setCardDraft(EMPTY_CARD_DRAFT)
    } catch (error) {
      setCardDrawerNotice(error instanceof Error ? error.message : 'Unable to create card.')
    } finally {
      setSavingCard(false)
    }
  }

  const removeCard = async (card) => {
    if (!window.confirm(`Delete card "${card.name}"?`)) return
    try {
      await onDeleteCard(card.id)
    } catch (error) {
      setCardNotice(error instanceof Error ? error.message : 'Unable to delete card.')
    }
  }

  // ---- drag a card onto a board stage ----
  const onCardDragStart = (event, cardId) => {
    event.dataTransfer.setData('text/plain', cardId)
    event.dataTransfer.effectAllowed = 'move'
    setDragCardId(cardId)
  }

  const dropCardOnStage = async (event, stageId) => {
    event.preventDefault()
    const cardId = event.dataTransfer.getData('text/plain') || dragCardId
    setDropStageId('')
    setDragCardId('')
    if (!cardId || !activeBoard) return
    try {
      setCardNotice('')
      await onPlaceCard(cardId, { boardId: activeBoard.id, stageId })
    } catch (error) {
      setCardNotice(error instanceof Error ? error.message : 'Unable to place card.')
    }
  }

  const detachCard = async (card) => {
    try {
      setCardNotice('')
      await onPlaceCard(card.id, { boardId: null, stageId: null })
    } catch (error) {
      setCardNotice(error instanceof Error ? error.message : 'Unable to remove card from board.')
    }
  }

  return (
    <section className="page-stack">
      <article className="card mds-surface mds-prose relationship-card">
        <MdsKicker>Workflow Boards</MdsKicker>
        <h3>Manage the creator relationship over a campaign lifecycle</h3>
        <MdsSectionRule />
        <p>
          Create up to {MAX_WORKFLOW_BOARDS} kanban-style boards with your own stages. Boards are not tied to a campaign.
          Select the board you are working on with its radio button.
        </p>

        <div className="row-actions">
          <button type="button" className="primary-btn" onClick={openCreateDrawer} disabled={creating || atBoardLimit}>
            Add board
          </button>
          <button type="button" className="ghost-btn" onClick={createDefault} disabled={creating || atBoardLimit}>
            + Default template
          </button>
        </div>
        {notice ? <MdsNote className="board-notice">{notice}</MdsNote> : null}
        {atBoardLimit ? (
          <MdsNote className="board-notice">You have reached the {MAX_WORKFLOW_BOARDS}-board limit. Delete a board to add another.</MdsNote>
        ) : null}

        {sortedBoards.length ? (
          <ul className="board-list">
            {sortedBoards.map((board) => {
              const stages = stagesForBoard(board.id)
              const isActive = board.id === activeBoardId
              return (
                <li key={board.id} className={`board-row${isActive ? ' selected' : ''}`}>
                  <label className="board-select">
                    <input
                      type="radio"
                      name="active-board"
                      checked={isActive}
                      onChange={() => onSelectBoard(board.id)}
                    />
                    <span className="board-name">{board.name}</span>
                    <span className="board-dates">
                      {board.startDate || '—'} → {board.endDate || '—'}
                    </span>
                    <span className="board-stage-count">{stages.length} stages</span>
                  </label>
                  <div className="row-actions">
                    <button type="button" className="ghost-btn" onClick={() => openEditDrawer(board)}>Edit</button>
                    <button type="button" className="ghost-btn" onClick={() => removeBoard(board)}>Remove</button>
                  </div>
                </li>
              )
            })}
          </ul>
        ) : (
          <MdsNote className="board-notice">No boards yet. Add one above or start from the default template.</MdsNote>
        )}
      </article>

      {activeBoard ? (
        <>
          <article className="card mds-surface mds-prose relationship-card">
            <MdsKicker>Associate Campaigns to Creators</MdsKicker>
            <h3>Relationship cards</h3>
            <MdsSectionRule />
            <p>
              Each card is a campaign-to-creator relationship. Create cards here, then search and drag them onto a stage of
              <strong> {activeBoard.name}</strong>.
            </p>

            <div className="row-actions">
              <button
                type="button"
                className="primary-btn"
                onClick={openCardDrawer}
                disabled={!campaigns.length || !creators.length}
              >
                Add relationship card
              </button>
            </div>
            {!campaigns.length || !creators.length ? (
              <MdsNote className="board-notice">Create at least one campaign and one creator before adding cards.</MdsNote>
            ) : null}
            {cardNotice ? <MdsNote className="board-notice">{cardNotice}</MdsNote> : null}

            <div className="card-pool-toolbar">
              <input
                type="search"
                value={cardSearch}
                placeholder="Search cards by name, campaign, creator, tag..."
                onChange={(event) => setCardSearch(event.target.value)}
              />
              <span className="card-pool-count">{unassignedCards.length} unassigned</span>
            </div>

            <div className="card-thumb-grid">
              {unassignedCards.length ? (
                unassignedCards.map((card) => {
                  const campaign = campaignById[card.campaignId]
                  const creator = creatorById[card.creatorId]
                  return (
                    <div
                      key={card.id}
                      className="card-thumb"
                      draggable
                      onDragStart={(event) => onCardDragStart(event, card.id)}
                      onDragEnd={() => setDragCardId('')}
                      title="Drag onto a board stage"
                    >
                      <p className="card-thumb-name">{card.name}</p>
                      <p className="card-thumb-assoc">{campaign?.name || 'Unknown campaign'} × {creator?.name || 'Unknown creator'}</p>
                      <div className="card-thumb-meta">
                        <span className={`status-chip status-${String(card.status || 'todo').toLowerCase()}`}>{card.status || 'todo'}</span>
                        {card.agreedFee ? <span className="card-thumb-fee">{card.feeCurrency || 'USD'} {card.agreedFee}</span> : null}
                      </div>
                      {(card.tags || []).length ? (
                        <div className="tag-row">
                          {card.tags.map((tag) => <span key={`${card.id}-${tag}`} className="tag-chip">{tag}</span>)}
                        </div>
                      ) : null}
                      <div className="card-thumb-actions">
                        <button type="button" className="ghost-btn" onClick={() => removeCard(card)}>Delete</button>
                      </div>
                    </div>
                  )
                })
              ) : (
                <MdsNote className="board-notice">
                  {cardSearch.trim() ? 'No cards match your search.' : 'No unassigned cards. Add one to get started.'}
                </MdsNote>
              )}
            </div>
          </article>

          <article className="card mds-surface mds-prose workflow-board">
            <MdsKicker>Board</MdsKicker>
            <h3>{activeBoard.name} — kanban</h3>
            <MdsSectionRule />
            <p className="helper">
              {activeBoard.startDate || '—'} → {activeBoard.endDate || '—'} · drag cards from the pool above onto a stage.
            </p>
            <div className="columns">
              {stagesForBoard(activeBoard.id).length ? (
                stagesForBoard(activeBoard.id).map((stage) => {
                  const stageCards = cardsForStage(stage.id)
                  return (
                    <article
                      key={stage.id}
                      className={`kanban-column${dropStageId === stage.id ? ' drop-target' : ''}`}
                      onDragOver={(event) => { event.preventDefault(); setDropStageId(stage.id) }}
                      onDragLeave={() => setDropStageId('')}
                      onDrop={(event) => dropCardOnStage(event, stage.id)}
                    >
                      <header>
                        <h4>{stage.stageName}</h4>
                        <span>{stageCards.length}</span>
                      </header>
                      <div className="cards">
                        {stageCards.length ? (
                          stageCards.map((card) => {
                            const campaign = campaignById[card.campaignId]
                            const creator = creatorById[card.creatorId]
                            return (
                              <div
                                key={card.id}
                                className="kanban-card"
                                draggable
                                onDragStart={(event) => onCardDragStart(event, card.id)}
                                onDragEnd={() => setDragCardId('')}
                              >
                                <p className="card-campaign">{card.name}</p>
                                <p className="card-creator">{campaign?.name || 'Unknown'} × {creator?.name || 'Unknown'}</p>
                                <div className="card-actions">
                                  <button type="button" onClick={() => detachCard(card)}>Remove from board</button>
                                </div>
                              </div>
                            )
                          })
                        ) : (
                          <p className="helper">Drop cards here.</p>
                        )}
                      </div>
                    </article>
                  )
                })
              ) : (
                <MdsNote className="board-notice">This board has no stages. Use Edit to add some.</MdsNote>
              )}
            </div>
          </article>
        </>
      ) : (
        <article className="card mds-surface mds-prose relationship-card">
          <MdsNote className="board-notice">Select a board with its radio button to view it.</MdsNote>
        </article>
      )}

      {drawerOpen ? (
        <div className="edit-drawer-overlay" onClick={closeDrawer} role="presentation">
          <aside className="edit-drawer" onClick={(event) => event.stopPropagation()} aria-label={drawerMode === 'create' ? 'Add board' : 'Edit board'}>
            <div className="edit-drawer-header">
              <h4>{drawerMode === 'create' ? 'Add board' : 'Edit board'}</h4>
              <button type="button" className="ghost-btn" onClick={closeDrawer} disabled={saving}>
                Close
              </button>
            </div>
            <div className="editable-row-form">
              <label className="field-block">
                <span>Board name</span>
                <input
                  type="text"
                  value={draft.name}
                  placeholder="Board name"
                  onChange={(event) => setDraft((prev) => ({ ...prev, name: event.target.value }))}
                />
              </label>
              <label className="field-block">
                <span>Start date</span>
                <input
                  type="date"
                  value={draft.startDate}
                  onChange={(event) => setDraft((prev) => ({ ...prev, startDate: event.target.value }))}
                />
              </label>
              <label className="field-block">
                <span>End date</span>
                <input
                  type="date"
                  value={draft.endDate}
                  onChange={(event) => setDraft((prev) => ({ ...prev, endDate: event.target.value }))}
                />
              </label>

              <p className="helper" style={{ marginTop: '6px' }}>Stages (customize name and order)</p>
              <div className="blueprint-stage-editor">
                {draft.stages.map((stage, index) => (
                  <div key={index} className="blueprint-stage-draft-row">
                    <span className="blueprint-stage-index">{index + 1}</span>
                    <input
                      type="text"
                      value={stage.stageName}
                      placeholder={`Stage ${index + 1} name`}
                      onChange={(event) => updateStageDraft(index, event.target.value)}
                    />
                    <button type="button" className="ghost-btn" onClick={() => moveStageDraft(index, -1)} disabled={index === 0} aria-label="Move up">↑</button>
                    <button type="button" className="ghost-btn" onClick={() => moveStageDraft(index, 1)} disabled={index === draft.stages.length - 1} aria-label="Move down">↓</button>
                    <button type="button" className="ghost-btn" onClick={() => removeStageDraft(index)} disabled={draft.stages.length <= 1} aria-label="Remove stage">✕</button>
                  </div>
                ))}
                <button type="button" className="ghost-btn" onClick={addStageDraft}>+ Add stage</button>
              </div>

              {drawerNotice ? <MdsNote className="board-notice">{drawerNotice}</MdsNote> : null}

              <div className="row-actions">
                <button type="button" className="ghost-btn" onClick={closeDrawer} disabled={saving}>
                  Cancel
                </button>
                <button type="button" className="primary-btn" onClick={saveDrawer} disabled={saving}>
                  {saving ? 'Saving...' : drawerMode === 'create' ? 'Create board' : 'Save changes'}
                </button>
              </div>
            </div>
          </aside>
        </div>
      ) : null}

      {cardDrawerOpen ? (
        <div className="edit-drawer-overlay" onClick={closeCardDrawer} role="presentation">
          <aside className="edit-drawer" onClick={(event) => event.stopPropagation()} aria-label="Add relationship card">
            <div className="edit-drawer-header">
              <h4>Add relationship card</h4>
              <button type="button" className="ghost-btn" onClick={closeCardDrawer} disabled={savingCard}>
                Close
              </button>
            </div>
            <div className="editable-row-form">
              <label className="field-block">
                <span>Card name</span>
                <input
                  type="text"
                  value={cardDraft.name}
                  placeholder="e.g. Q3 gifting - Lena"
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, name: event.target.value }))}
                />
              </label>
              <label className="field-block">
                <span>Campaign</span>
                <select
                  value={cardDraft.campaignId}
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, campaignId: event.target.value }))}
                >
                  <option value="">Select campaign</option>
                  {campaigns.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </label>
              <label className="field-block">
                <span>Creator</span>
                <select
                  value={cardDraft.creatorId}
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, creatorId: event.target.value }))}
                >
                  <option value="">Select creator</option>
                  {creators.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.handle})</option>)}
                </select>
              </label>
              <label className="field-block">
                <span>Status</span>
                <select
                  value={cardDraft.status}
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, status: event.target.value }))}
                >
                  <option value="todo">To do</option>
                  <option value="in_progress">In progress</option>
                  <option value="blocked">Blocked</option>
                  <option value="done">Done</option>
                </select>
              </label>
              <label className="field-block">
                <span>Agreed fee</span>
                <input
                  type="number"
                  min="0"
                  value={cardDraft.agreedFee}
                  placeholder="0.00"
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, agreedFee: event.target.value }))}
                />
              </label>
              <label className="field-block">
                <span>Currency</span>
                <input
                  type="text"
                  value={cardDraft.feeCurrency}
                  placeholder="USD"
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, feeCurrency: event.target.value }))}
                />
              </label>
              <label className="field-block">
                <span>Tags (comma separated)</span>
                <input
                  type="text"
                  value={cardDraft.tags}
                  placeholder="priority, instagram"
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, tags: event.target.value }))}
                />
              </label>
              <label className="field-block">
                <span>Notes</span>
                <textarea
                  value={cardDraft.notes}
                  placeholder="Relationship notes"
                  onChange={(event) => setCardDraft((prev) => ({ ...prev, notes: event.target.value }))}
                />
              </label>

              {cardDrawerNotice ? <MdsNote className="board-notice">{cardDrawerNotice}</MdsNote> : null}

              <div className="row-actions">
                <button type="button" className="ghost-btn" onClick={closeCardDrawer} disabled={savingCard}>
                  Cancel
                </button>
                <button type="button" className="primary-btn" onClick={saveCard} disabled={savingCard}>
                  {savingCard ? 'Saving...' : 'Create card'}
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
