/**
 * The curated section editor (roadmap PR-39, piece C).
 *
 * <p>THIS IS A COPY of `InfluencerUI/src/components/SectionEditor.jsx`, and this is the one
 * production actually serves (`VITE_USE_REMOTES=true`). Guarded by
 * `InfluencerUI/src/shell/contentRemoteCopies.test.mjs` — copy changes across rather than relaxing it.
 */

/**
 * Preview widths.
 *
 * Laptop is the addition the plan calls for. It matters because 1024 is where a two-column
 * layout has to survive on the narrowest real desktop, and it is the width least likely to be
 * checked by someone working on a large monitor.
 */
const WIDTHS = [
  { id: 'desktop', label: 'Desktop', width: null },
  { id: 'laptop', label: 'Laptop', width: 1024 },
  { id: 'tablet', label: 'Tablet', width: 820 },
  { id: 'phone', label: 'Phone', width: 390 },
]

/** The rewrite instructions offered per section, in the user's own vocabulary. */
const REWRITES = [
  { id: 'shorter', label: 'Shorter', instruction: 'Make this shorter and tighter.' },
  { id: 'warmer', label: 'Warmer', instruction: 'Make this warmer and more personal.' },
  { id: 'offer-first', label: 'Lead with the offer', instruction: 'Rewrite so the offer comes first.' },
]

/** Debounce so every keystroke does not re-render the page server-side. */
function useDebounced(value, ms) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return debounced
}

export default function SectionEditor({
  sections = [],
  onChange,
  onPreview,
  onSave,
  onRewrite,
  assets = [],
  onUploadAsset,
  busy = false,
  templates = [],
  savedTemplates = [],
  onApplyTemplate,
  onSaveAsTemplate,
  onDeleteTemplate,
}) {
  const [selected, setSelected] = useState(0)
  const [width, setWidth] = useState('desktop')
  const [previewHtml, setPreviewHtml] = useState('')
  const [rewriting, setRewriting] = useState(null)
  const [rewriteNote, setRewriteNote] = useState(null)
  const [narrow, setNarrow] = useState(false)
  const shellRef = useRef(null)

  // The panel moves below the canvas on a narrow screen. Measured from the EDITOR's own width,
  // not the viewport: it also sits inside a page with its own chrome, so a viewport query would
  // move the panel at the wrong moment on a tablet.
  useEffect(() => {
    const el = shellRef.current
    if (!el || typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(([entry]) => setNarrow(entry.contentRect.width < 900))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const debouncedSections = useDebounced(sections, 400)

  // `onPreview` IS a dependency, and the ref that used to stand in for it was a mistake worth
  // recording. React runs child effects before parent ones, so `useRef(onPreview)` captured the
  // callback as it existed at mount — closing over `campaignId === ''`. That closure hits its own
  // `if (!campaignId) return ''` guard and resolves to an empty string, which is still a string,
  // so it was written into the iframe as an empty document: a permanently blank canvas with no
  // error anywhere. Depending on the prop directly means the effect re-runs with the CURRENT
  // closure once the campaign is known.
  //
  // Re-running on every parent render is not a problem here: `debouncedSections` changes rarely,
  // and a duplicate render request is idempotent and cheap next to silently showing nothing.
  useEffect(() => {
    let cancelled = false
    if (typeof onPreview !== 'function') return undefined
    Promise.resolve(onPreview(debouncedSections))
      // An empty string means the caller declined (no campaign yet) — keep whatever is on screen
      // rather than blanking it, which is what made this failure invisible.
      .then((html) => { if (!cancelled && typeof html === 'string' && html) setPreviewHtml(html) })
      .catch(() => { /* a failed preview leaves the last good one on screen */ })
    return () => { cancelled = true }
  }, [debouncedSections, onPreview])

  const current = sections[selected] || null
  const spec = current ? sectionType(current.type) : null

  const issues = useMemo(() => sections.map((s) => sectionIssues(s)), [sections])
  const totalErrors = issues.flat().filter((i) => i.level === 'error').length

  const update = (next) => { if (typeof onChange === 'function') onChange(next) }

  const setField = (name, value) => {
    const next = sections.map((s, i) => (i === selected ? { ...s, fields: { ...s.fields, [name]: value } } : s))
    update(next)
  }

  const setItemField = (fieldName, index, key, value) => {
    const items = [...(current.fields[fieldName] || [])]
    items[index] = { ...items[index], [key]: value }
    setField(fieldName, items)
  }

  const addItem = (fieldName) => setField(fieldName, [...(current.fields[fieldName] || []), {}])
  const removeItem = (fieldName, index) =>
    setField(fieldName, (current.fields[fieldName] || []).filter((_, i) => i !== index))

  const addSection = (type) => {
    const section = blankSection(type)
    if (!section) return
    update([...sections, section])
    setSelected(sections.length)
  }

  const move = (index, delta) => {
    const target = index + delta
    if (target < 0 || target >= sections.length) return
    const next = [...sections]
    const [moved] = next.splice(index, 1)
    next.splice(target, 0, moved)
    update(next)
    setSelected(target)
  }

  const remove = (index) => {
    // Only warn when there is something to lose. Confirming the removal of an empty section the
    // user just added is the kind of prompt people learn to click through, which is what makes
    // the prompt that matters ineffective.
    const hasContent = Object.values(sections[index]?.fields || {}).some((v) =>
      Array.isArray(v) ? v.some((i) => Object.values(i || {}).some(Boolean)) : String(v || '').trim())
    if (hasContent && !window.confirm(
      `Remove the ${sectionLabel(sections[index].type)} section? The words in it will be lost.`)) return
    update(sections.filter((_, i) => i !== index))
    setSelected((s) => Math.max(0, Math.min(s, sections.length - 2)))
  }

  /**
   * Ask the model to reword this section.
   *
   * <p>The endpoint speaks `{type, title, body}` — the generator's vocabulary — while a section
   * here is `{type, variant, fields}`. The mapping is done at this boundary rather than by
   * changing the endpoint, because the endpoint is also used by the draft-generation flow, and
   * one caller's convenience is not a reason to reshape a contract another caller depends on.
   */
  const rewrite = async (option) => {
    if (typeof onRewrite !== 'function' || !current) return
    const titleField = spec.fields.find((f) => f.name === 'headline' || f.name === 'quote')
    const bodyField = spec.fields.find((f) =>
      f.name === 'subheadline' || f.name === 'supporting' || f.name === 'body')
    setRewriting(option.id)
    setRewriteNote(null)
    try {
      const result = await onRewrite({
        section: {
          type: current.type,
          title: titleField ? current.fields[titleField.name] || '' : '',
          body: bodyField ? current.fields[bodyField.name] || '' : '',
        },
        instruction: option.instruction,
      })
      if (!result || result.rewritten !== true) {
        // "No suggestion" is an answer, not an error — say so plainly and leave their words be.
        setRewriteNote(result?.detail || 'No suggestion was available. Your text is unchanged.')
        return
      }
      const fields = { ...current.fields }
      if (titleField && result.section?.title) fields[titleField.name] = result.section.title
      if (bodyField && result.section?.body) fields[bodyField.name] = result.section.body
      update(sections.map((s, i) => (i === selected ? { ...s, fields } : s)))
    } catch (e) {
      setRewriteNote('The rewrite could not be reached. Your text is unchanged.')
    } finally {
      setRewriting(null)
    }
  }

  const previewWidth = WIDTHS.find((w) => w.id === width)?.width

  return (
    <div className="section-editor" ref={shellRef} style={S.shell(narrow)}>
      {/* ---- canvas ---- */}
      <div style={S.canvasCol}>
        <div style={S.toolbar}>
          <span style={S.toolbarLabel}>Preview at</span>
          {WIDTHS.map((w) => (
            <button
              key={w.id}
              type="button"
              className={width === w.id ? 'primary-btn' : 'ghost-btn'}
              aria-pressed={width === w.id}
              onClick={() => setWidth(w.id)}
              style={S.chip}
            >
              {w.label}
            </button>
          ))}
          <span style={{ flex: 1 }} />
          {typeof onSave === 'function' ? (
            <button type="button" className="primary-btn" onClick={() => onSave(sections)} disabled={busy}>
              {busy ? 'Saving…' : 'Save page'}
            </button>
          ) : null}
        </div>

        {totalErrors > 0 ? (
          <p className="mds-note" style={S.warn} role="status">
            {totalErrors === 1
              ? 'One section is missing something it needs and will not appear on the page.'
              : `${totalErrors} sections are missing something they need and will not appear on the page.`}
          </p>
        ) : null}

        <div style={S.canvasFrame}>
          <div style={{ ...S.canvasInner, width: previewWidth ? `${previewWidth}px` : '100%' }}>
            {sections.length === 0 ? (
              <div style={S.empty}>
                <p style={{ margin: 0, fontWeight: 600 }}>Nothing on this page yet.</p>
                <p style={{ margin: '.35rem 0 0', color: '#5C554E' }}>
                  Start from a template, or add a section below.
                </p>
              </div>
            ) : (
              <>
                <iframe
                  title="Page preview"
                  srcDoc={previewHtml}
                  sandbox=""
                  style={S.frame}
                />
                {/* The clickable overlay maps a click on the preview to a section. The iframe is
                    sandboxed and cannot talk to us, so selection is driven from the list below
                    rather than from inside the page — which also keeps the preview honest, since
                    nothing is injected into it to make selection work. */}
              </>
            )}
          </div>
        </div>

        <ol style={S.list}>
          {sections.map((section, index) => {
            const sectionErrors = issues[index] || []
            const isSelected = index === selected
            return (
              <li key={index} style={S.listItem(isSelected)}>
                <button
                  type="button"
                  onClick={() => setSelected(index)}
                  aria-current={isSelected}
                  style={S.listButton}
                >
                  <span style={S.listName}>{sectionLabel(section.type)}</span>
                  <span style={S.listMeta}>
                    {sectionType(section.type)?.variants.find((v) => v.id === section.variant)?.label || ''}
                  </span>
                  {sectionErrors.some((i) => i.level === 'error') ? (
                    <span style={S.badgeError} title="This section will not appear">Incomplete</span>
                  ) : null}
                </button>
                <div className="row-actions">
                  <button type="button" className="ghost-btn" onClick={() => move(index, -1)}
                          disabled={index === 0} aria-label={`Move ${sectionLabel(section.type)} up`}>↑</button>
                  <button type="button" className="ghost-btn" onClick={() => move(index, 1)}
                          disabled={index === sections.length - 1} aria-label={`Move ${sectionLabel(section.type)} down`}>↓</button>
                  <button type="button" className="ghost-btn" onClick={() => remove(index)}
                          aria-label={`Remove ${sectionLabel(section.type)}`}>Remove</button>
                </div>
              </li>
            )
          })}
        </ol>

        <div style={S.addRow}>
          <span style={S.toolbarLabel}>Add a section</span>
          {SECTION_TYPES.map((t) => (
            <button key={t.type} type="button" className="ghost-btn" style={S.chip}
                    onClick={() => addSection(t.type)} title={t.hint}>
              + {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* ---- context panel ---- */}
      <aside style={S.panel(narrow)}>
        {!current ? (
          <p className="mds-note">Select a section to edit it.</p>
        ) : (
          <>
            <h4 style={S.panelTitle}>{spec.label}</h4>
            <p className="mds-note" style={{ marginTop: 0 }}>{spec.hint}</p>

            {spec.variants.length > 0 ? (
              <>
                <label className="auth-label">Layout</label>
                <select
                  value={current.variant || spec.variants[0].id}
                  onChange={(e) => update(sections.map((s, i) =>
                    (i === selected ? { ...s, variant: e.target.value } : s)))}
                >
                  {spec.variants.map((v) => <option key={v.id} value={v.id}>{v.label}</option>)}
                </select>
              </>
            ) : null}

            {spec.fields.map((field) => (
              <FieldInput
                key={field.name}
                field={field}
                value={current.fields[field.name]}
                assets={assets}
                onUploadAsset={onUploadAsset}
                onChange={(v) => setField(field.name, v)}
                onItemChange={(idx, key, v) => setItemField(field.name, idx, key, v)}
                onAddItem={() => addItem(field.name)}
                onRemoveItem={(idx) => removeItem(field.name, idx)}
              />
            ))}

            <TokenHint section={current} />

            {typeof onRewrite === 'function' ? (
              <div style={{ marginTop: 16 }}>
                <label className="auth-label">Rewrite this section</label>
                <div className="row-actions">
                  {REWRITES.map((r) => (
                    <button key={r.id} type="button" className="ghost-btn"
                            onClick={() => rewrite(r)} disabled={rewriting !== null}>
                      {rewriting === r.id ? '…' : r.label}
                    </button>
                  ))}
                </div>
                {rewriteNote ? <p className="mds-note">{rewriteNote}</p> : null}
              </div>
            ) : null}

            {(issues[selected] || []).length > 0 ? (
              <ul style={S.issues}>
                {(issues[selected] || []).map((issue, i) => (
                  <li key={i} style={{ color: issue.level === 'error' ? '#9C2B12' : '#6E655E' }}>
                    {issue.message}
                  </li>
                ))}
              </ul>
            ) : null}
          </>
        )}

        <TemplatePicker
          templates={templates}
          savedTemplates={savedTemplates}
          onApply={onApplyTemplate}
          onSaveAs={onSaveAsTemplate}
          onDelete={onDeleteTemplate}
          hasSections={sections.length > 0}
        />
      </aside>
    </div>
  )
}

/** One field's input, chosen by kind. */
function FieldInput({ field, value, assets, onUploadAsset, onChange, onItemChange, onAddItem, onRemoveItem }) {
  if (field.kind === FIELD_KINDS.ITEMS) {
    const items = Array.isArray(value) ? value : []
    return (
      <div style={{ marginTop: 12 }}>
        <label className="auth-label">{field.label}</label>
        {items.map((item, i) => (
          <div key={i} style={S.itemBox}>
            {field.itemFields.map((sub) => (
              sub.kind === FIELD_KINDS.TEXTAREA ? (
                <textarea key={sub.name} rows={2} placeholder={sub.placeholder || sub.label}
                          value={item[sub.name] || ''} onChange={(e) => onItemChange(i, sub.name, e.target.value)} />
              ) : (
                <input key={sub.name} type="text" placeholder={sub.placeholder || sub.label}
                       value={item[sub.name] || ''} onChange={(e) => onItemChange(i, sub.name, e.target.value)} />
              )
            ))}
            <button type="button" className="ghost-btn" onClick={() => onRemoveItem(i)}
                    disabled={items.length <= (field.min || 0)}>Remove</button>
          </div>
        ))}
        <button type="button" className="ghost-btn" onClick={onAddItem}
                disabled={items.length >= (field.max || 99)}>
          Add {field.label.toLowerCase().replace(/s$/, '')}
        </button>
      </div>
    )
  }

  if (field.kind === FIELD_KINDS.ASSET) {
    return (
      <div style={{ marginTop: 12 }}>
        <label className="auth-label">{field.label}</label>
        {value ? (
          <img src={value} alt="" style={S.thumb} />
        ) : null}
        <select value={value || ''} onChange={(e) => onChange(e.target.value)}>
          <option value="">— none —</option>
          {assets.map((a) => <option key={a.url} value={a.url}>{a.fileName || a.url}</option>)}
        </select>
        {typeof onUploadAsset === 'function' ? (
          <input type="file" accept="image/*" onChange={async (e) => {
            const file = e.target.files?.[0]
            if (!file) return
            const uploaded = await onUploadAsset(file)
            if (uploaded?.url) onChange(uploaded.url)
          }} />
        ) : null}
      </div>
    )
  }

  const Input = field.kind === FIELD_KINDS.TEXTAREA ? 'textarea' : 'input'
  return (
    <div style={{ marginTop: 12 }}>
      <label className="auth-label">{field.label}</label>
      <Input
        {...(field.kind === FIELD_KINDS.TEXTAREA ? { rows: 3 } : { type: 'text' })}
        value={value || ''}
        placeholder={field.placeholder || ''}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  )
}

/**
 * Shows which personalization tokens this section uses, and flags ones that look like typos.
 *
 * <p>The unknown-token warning is the load-bearing half. `fill()` is a plain string replace, so
 * `{{coupon.cod}}` does not error anywhere — it publishes as literal text on every creator's
 * page, and nothing else in the stack will ever mention it.
 */
function TokenHint({ section }) {
  const { used, unknown } = tokensIn(section)
  if (used.length === 0 && unknown.length === 0) {
    return (
      <p className="mds-note" style={{ marginTop: 12 }}>
        Tip: type {TOKENS[0].token} and each creator sees {TOKENS[0].describes}.
      </p>
    )
  }
  return (
    <div style={{ marginTop: 12 }}>
      {used.length > 0 ? (
        <p className="mds-note" style={{ margin: 0 }}>
          Personalized:{' '}
          {used.map((t) => (
            <span key={t} style={S.token}>{t}</span>
          ))}
        </p>
      ) : null}
      {unknown.map((t) => (
        <p key={t} className="mds-note" style={S.tokenBad}>
          {t} is not a token we recognise — it will publish exactly as written.
        </p>
      ))}
    </div>
  )
}

/** Built-in and saved templates. */
function TemplatePicker({ templates, savedTemplates, onApply, onSaveAs, onDelete, hasSections }) {
  const [name, setName] = useState('')
  if (typeof onApply !== 'function') return null
  return (
    <div style={S.templates}>
      <label className="auth-label">Start from a template</label>
      <select
        value=""
        onChange={(e) => {
          const chosen = e.target.value
          if (!chosen) return
          const [kind, id] = chosen.split(':')
          onApply(kind, id)
          e.target.value = ''
        }}
      >
        <option value="">— choose —</option>
        <optgroup label="Built in">
          {templates.map((t) => <option key={t.id} value={`builtin:${t.id}`}>{t.name}</option>)}
        </optgroup>
        {savedTemplates.length > 0 ? (
          <optgroup label="Saved by your team">
            {savedTemplates.map((t) => <option key={t.id} value={`saved:${t.id}`}>{t.name}</option>)}
          </optgroup>
        ) : null}
      </select>

      {typeof onSaveAs === 'function' ? (
        <div style={{ marginTop: 12 }}>
          <label className="auth-label">Save this page as a template</label>
          <input type="text" value={name} placeholder="Template name"
                 onChange={(e) => setName(e.target.value)} />
          <button type="button" className="ghost-btn" disabled={!name.trim() || !hasSections}
                  onClick={async () => { await onSaveAs(name.trim()); setName('') }}>
            Save as template
          </button>
          <p className="mds-note" style={{ marginTop: 4 }}>
            The creator’s name, handle and portrait are cleared — they belong to one campaign.
          </p>
        </div>
      ) : null}

      {savedTemplates.length > 0 && typeof onDelete === 'function' ? (
        <ul className="simple-list" style={{ marginTop: 8 }}>
          {savedTemplates.map((t) => (
            <li key={t.id}>
              <span>{t.name}</span>
              <button type="button" className="ghost-btn" onClick={() => onDelete(t.id)}>Delete</button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}

/** Inline styles, for the same reason LandingBuilder uses them: the remote has no stylesheet. */
const S = {
  shell: (narrow) => ({
    display: narrow ? 'block' : 'grid',
    gridTemplateColumns: narrow ? undefined : 'minmax(0,1fr) 340px',
    gap: 20,
    alignItems: 'start',
  }),
  canvasCol: { minWidth: 0 },
  toolbar: { display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', margin: '8px 0' },
  toolbarLabel: { fontSize: '.85rem', color: '#5C554E' },
  chip: { padding: '.35rem .7rem', fontSize: '.85rem' },
  canvasFrame: {
    background: '#F2EDE7', borderRadius: 8, padding: 12,
    display: 'flex', justifyContent: 'center', overflowX: 'auto',
  },
  canvasInner: { maxWidth: '100%', transition: 'width .2s ease' },
  frame: { width: '100%', height: 560, border: '1px solid #E2DAD1', borderRadius: 4, background: '#fff' },
  empty: { padding: '48px 24px', textAlign: 'center', background: '#fff', borderRadius: 4 },
  list: { listStyle: 'none', margin: '12px 0 0', padding: 0, display: 'grid', gap: 6 },
  listItem: (selected) => ({
    display: 'flex', alignItems: 'center', gap: 8, padding: '6px 8px', borderRadius: 6,
    background: selected ? '#F6E9E4' : 'transparent',
    outline: selected ? '1px solid #A84A32' : '1px solid #E2DAD1',
  }),
  listButton: {
    flex: 1, display: 'flex', alignItems: 'baseline', gap: 8, background: 'none',
    border: 0, textAlign: 'left', cursor: 'pointer', padding: '.25rem 0', font: 'inherit',
  },
  listName: { fontWeight: 600 },
  listMeta: { fontSize: '.8rem', color: '#6E655E' },
  badgeError: { fontSize: '.7rem', color: '#9C2B12', border: '1px solid #9C2B12', borderRadius: 4, padding: '0 .35rem' },
  addRow: { display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginTop: 12 },
  panel: (narrow) => ({
    minWidth: 0, marginTop: narrow ? 20 : 0,
    background: '#FAF8F5', border: '1px solid #E2DAD1', borderRadius: 8, padding: 16,
  }),
  panelTitle: { margin: '0 0 .25rem', fontSize: '1rem' },
  itemBox: { display: 'grid', gap: 4, padding: 8, border: '1px solid #E2DAD1', borderRadius: 6, marginBottom: 6 },
  thumb: { width: '100%', maxHeight: 120, objectFit: 'cover', borderRadius: 4, marginBottom: 6 },
  token: { display: 'inline-block', fontFamily: 'ui-monospace, Menlo, monospace', background: '#F6E9E4', color: '#A84A32', borderRadius: 3, padding: '0 .3rem', marginRight: 4 },
  tokenBad: { color: '#9C2B12', margin: '.25rem 0 0' },
  issues: { margin: '12px 0 0', paddingLeft: '1.1rem', fontSize: '.85rem' },
  templates: { marginTop: 20, paddingTop: 16, borderTop: '1px solid #E2DAD1' },
  warn: { color: '#9C2B12' },
}
