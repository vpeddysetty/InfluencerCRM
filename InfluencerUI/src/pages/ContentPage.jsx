import { useEffect, useMemo, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'
import LandingBuilder from '../components/LandingBuilder'

const EMPTY_CONTENT = { summary: '', goals: '', dos: '', donts: '', talkingPoints: '' }

const BLOCK_TYPES = [
  { value: 'hero', label: 'Hero heading' },
  { value: 'richText', label: 'Text' },
  { value: 'image', label: 'Image' },
  { value: 'couponBlock', label: 'Coupon code' },
  { value: 'productCta', label: 'Shop CTA' },
  { value: 'legal', label: 'Legal / disclosure' },
]

function defaultBlock(type) {
  if (type === 'image') return { type, url: '' }
  if (type === 'couponBlock') return { type }
  if (type === 'productCta') return { type, label: 'Shop now' }
  if (type === 'hero') return { type, text: '{{creator.name}}’s exclusive {{discount}}' }
  return { type, text: '' }
}

function ContentPage({
  campaigns = [],
  coupons = [],
  onLoadBriefs,
  onSaveBrief,
  onLoadTemplates,
  onSaveTemplate,
  onReloadCoupons,
  onDraftContent,
  onPreviewLanding,
  onLoadVersions,
  onRestoreVersion,
  onLoadAssets,
  onUploadAsset,
  can,
}) {
  const [campaignId, setCampaignId] = useState('')
  const [briefs, setBriefs] = useState([])
  const [content, setContent] = useState(EMPTY_CONTENT)
  const [hashtags, setHashtags] = useState('')
  const [assets, setAssets] = useState('') // "label|url" per line
  const [disclosure, setDisclosure] = useState('')
  const [status, setStatus] = useState('draft')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState({ type: '', message: '' })

  // Landing template state (Content Phase 2)
  const [templates, setTemplates] = useState([])
  const [templateName, setTemplateName] = useState('Landing page')
  const [blocks, setBlocks] = useState([])
  const [templateStatus, setTemplateStatus] = useState('draft')
  const [newBlockType, setNewBlockType] = useState('hero')
  const [savingTemplate, setSavingTemplate] = useState(false)
  const [templateFeedback, setTemplateFeedback] = useState({ type: '', message: '' })
  const [pageCoupons, setPageCoupons] = useState(coupons)
  const [previewHtml, setPreviewHtml] = useState('')
  const [previewCouponId, setPreviewCouponId] = useState('')
  const [previewing, setPreviewing] = useState(false)
  // Visual builder (Phase A). `visual` is the default for new pages; a page saved by the
  // old block editor opens in `blocks` so nobody's existing work silently changes shape.
  const [editorMode, setEditorMode] = useState('visual')
  const [versions, setVersions] = useState([])
  const [mediaAssets, setMediaAssets] = useState([])

  const currentBrief = useMemo(
    () => briefs.find((b) => b.campaignId === campaignId) || null,
    [briefs, campaignId],
  )

  const currentTemplate = useMemo(
    () => templates.find((t) => t.campaignId === campaignId) || null,
    [templates, campaignId],
  )

  const refresh = async () => {
    setLoading(true)
    try {
      const list = await onLoadBriefs()
      setBriefs(Array.isArray(list) ? list : [])
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to load briefs.' })
    } finally {
      setLoading(false)
    }
  }

  const refreshTemplates = async () => {
    try {
      const list = await onLoadTemplates()
      setTemplates(Array.isArray(list) ? list : [])
    } catch {
      // non-fatal
    }
  }

  useEffect(() => {
    refresh()
    refreshTemplates()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Hydrate the template builder when the campaign / its template changes.
  useEffect(() => {
    const t = templates.find((x) => x.campaignId === campaignId) || null
    if (t) {
      setTemplateName(t.name || 'Landing page')
      setBlocks(Array.isArray(t.blocks) ? t.blocks : [])
      setTemplateStatus(t.status || 'draft')
      // Open a page in the editor that produced it. A page with a GrapesJS document opens
      // visually; one with only typed blocks opens in the block editor, so existing work
      // never appears to have changed shape on its own.
      const hasDocument = Boolean(t.document && (t.document.html || t.document.css))
      const hasBlocks = Array.isArray(t.blocks) && t.blocks.length > 0
      setEditorMode(hasDocument ? 'visual' : hasBlocks ? 'blocks' : 'visual')
    } else {
      setTemplateName('Landing page')
      setBlocks([])
      setTemplateStatus('draft')
      setEditorMode('visual')
    }
  }, [campaignId, templates])

  // Version history follows the selected campaign.
  useEffect(() => {
    if (!campaignId) {
      setVersions([])
      return
    }
    refreshVersions()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [campaignId])

  // The asset library is brand-wide, not per campaign, so it loads once.
  useEffect(() => {
    refreshAssets()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // When the selected campaign (or its brief) changes, hydrate the form.
  useEffect(() => {
    const b = briefs.find((x) => x.campaignId === campaignId) || null
    if (b) {
      setContent({ ...EMPTY_CONTENT, ...(b.content || {}) })
      setHashtags((Array.isArray(b.hashtags) ? b.hashtags : []).join(' '))
      setAssets((Array.isArray(b.assets) ? b.assets : []).map((a) => `${a.label || ''}|${a.url || ''}`).join('\n'))
      setDisclosure(b.disclosureText || '')
      setStatus(b.status || 'draft')
    } else {
      setContent(EMPTY_CONTENT)
      setHashtags('')
      setAssets('')
      setDisclosure('')
      setStatus('draft')
    }
  }, [campaignId, briefs])

  const [drafting, setDrafting] = useState(false)

  const draftBrief = async () => {
    if (!campaignId) {
      setFeedback({ type: 'error', message: 'Pick a campaign first.' })
      return
    }
    setDrafting(true)
    setFeedback({ type: '', message: '' })
    try {
      const campaign = campaigns.find((c) => c.id === campaignId)
      const res = await onDraftContent({ kind: 'brief', campaignName: campaign?.name || '', campaign_name: campaign?.name || '' })
      const d = res?.draft || {}
      setContent((prev) => ({
        summary: d.summary || prev.summary,
        goals: d.goals || prev.goals,
        dos: d.dos || prev.dos,
        donts: d.donts || prev.donts,
        talkingPoints: d.talkingPoints || prev.talkingPoints,
      }))
      if (Array.isArray(d.hashtags) && d.hashtags.length) setHashtags(d.hashtags.join(' '))
      setFeedback({ type: 'success', message: `Draft generated (${d.source || 'ai'}). Review and edit before saving.` })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to draft.' })
    } finally {
      setDrafting(false)
    }
  }

  const parseHashtags = (raw) =>
    raw.split(/[\s,]+/).map((t) => t.trim()).filter(Boolean).map((t) => (t.startsWith('#') ? t : `#${t}`))

  const parseAssets = (raw) =>
    raw.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
      const [label, url] = line.split('|')
      return { label: (label || '').trim(), url: (url || label || '').trim() }
    })

  const save = async (event) => {
    event.preventDefault()
    if (!campaignId) {
      setFeedback({ type: 'error', message: 'Pick a campaign first.' })
      return
    }
    setSaving(true)
    setFeedback({ type: '', message: '' })
    try {
      const payload = {
        campaignId,
        content,
        hashtags: parseHashtags(hashtags),
        assets: parseAssets(assets),
        disclosureText: disclosure.trim(),
        status,
      }
      const saved = await onSaveBrief(currentBrief?.id || null, payload)
      // Merge saved brief into local list.
      setBriefs((prev) => {
        const others = prev.filter((b) => b.id !== saved.id && b.campaignId !== saved.campaignId)
        return [saved, ...others]
      })
      setFeedback({ type: 'success', message: 'Brief saved.' })
    } catch (error) {
      setFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to save brief.' })
    } finally {
      setSaving(false)
    }
  }

  const addBlock = () => setBlocks((prev) => [...prev, defaultBlock(newBlockType)])
  const removeBlock = (i) => setBlocks((prev) => prev.filter((_, idx) => idx !== i))
  const moveBlock = (i, dir) => setBlocks((prev) => {
    const next = [...prev]
    const j = i + dir
    if (j < 0 || j >= next.length) return prev
    ;[next[i], next[j]] = [next[j], next[i]]
    return next
  })
  const editBlock = (i, field, value) => setBlocks((prev) => prev.map((b, idx) => (idx === i ? { ...b, [field]: value } : b)))

  /**
   * Save from the visual builder. Sends `document` (GrapesJS html/css) instead of `blocks`;
   * the server keeps both columns and renders whichever is present, so switching editors
   * never destroys the other representation.
   */
  const saveBuilderDocument = async (document) => {
    if (!campaignId) {
      setTemplateFeedback({ type: 'error', message: 'Pick a campaign first.' })
      return
    }
    setSavingTemplate(true)
    setTemplateFeedback({ type: '', message: '' })
    try {
      const saved = await onSaveTemplate({
        campaignId,
        name: templateName.trim() || 'Landing page',
        document,
        status: templateStatus,
      })
      setTemplates((prev) => {
        const others = prev.filter((t) => t.id !== saved.id && t.campaignId !== saved.campaignId)
        return [saved, ...others]
      })
      try {
        const reloaded = await onReloadCoupons()
        if (Array.isArray(reloaded)) setPageCoupons(reloaded)
      } catch { /* non-fatal */ }
      await refreshVersions()
      setTemplateFeedback({ type: 'success', message: `Landing page saved (slug: ${saved.publicSlug}).` })
    } catch (error) {
      setTemplateFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to save landing page.' })
    } finally {
      setSavingTemplate(false)
    }
  }

  const previewBuilderDocument = async (document) => {
    if (!campaignId) return
    setPreviewing(true)
    try {
      const html = await onPreviewLanding({
        campaignId,
        name: templateName.trim() || 'Landing page',
        document,
        couponId: previewCouponId || undefined,
      })
      setPreviewHtml(html)
    } catch (error) {
      setTemplateFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to preview.' })
    } finally {
      setPreviewing(false)
    }
  }

  const refreshAssets = async () => {
    if (typeof onLoadAssets !== 'function') return
    try {
      const list = await onLoadAssets()
      setMediaAssets(Array.isArray(list) ? list : [])
    } catch {
      // The picker degrades to "no assets yet" rather than breaking the builder.
    }
  }

  const uploadAsset = async (file) => {
    if (typeof onUploadAsset !== 'function') return
    const saved = await onUploadAsset(file)
    // Prepend rather than refetch: the new asset appears in the picker immediately, and the
    // server already returned the row we would have re-read.
    if (saved) setMediaAssets((prev) => [saved, ...prev])
    return saved
  }

  const refreshVersions = async () => {
    if (!campaignId || typeof onLoadVersions !== 'function') return
    try {
      const list = await onLoadVersions(campaignId)
      setVersions(Array.isArray(list) ? list : [])
    } catch {
      // History is auxiliary — failing to load it must not break the builder.
    }
  }

  const restoreVersion = async (versionNo) => {
    if (!campaignId || typeof onRestoreVersion !== 'function') return
    setSavingTemplate(true)
    try {
      const restored = await onRestoreVersion(campaignId, versionNo)
      setTemplates((prev) => {
        const others = prev.filter((t) => t.id !== restored.id && t.campaignId !== restored.campaignId)
        return [restored, ...others]
      })
      await refreshVersions()
      setTemplateFeedback({
        type: 'success',
        message: `Restored v${versionNo} as a new draft. Reopen the builder to edit it.`,
      })
    } catch (error) {
      setTemplateFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to restore.' })
    } finally {
      setSavingTemplate(false)
    }
  }

  const saveTemplate = async () => {
    if (!campaignId) {
      setTemplateFeedback({ type: 'error', message: 'Pick a campaign first.' })
      return
    }
    setSavingTemplate(true)
    setTemplateFeedback({ type: '', message: '' })
    try {
      const saved = await onSaveTemplate({ campaignId, name: templateName.trim() || 'Landing page', blocks, status: templateStatus })
      setTemplates((prev) => {
        const others = prev.filter((t) => t.id !== saved.id && t.campaignId !== saved.campaignId)
        return [saved, ...others]
      })
      // Coupons just got public slugs assigned — reload so preview links appear.
      try {
        const reloaded = await onReloadCoupons()
        if (Array.isArray(reloaded)) setPageCoupons(reloaded)
      } catch { /* non-fatal */ }
      setTemplateFeedback({ type: 'success', message: `Landing page saved (slug: ${saved.publicSlug}).` })
    } catch (error) {
      setTemplateFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to save landing page.' })
    } finally {
      setSavingTemplate(false)
    }
  }

  const previewLanding = async () => {
    if (!campaignId) {
      setTemplateFeedback({ type: 'error', message: 'Pick a campaign first.' })
      return
    }
    setPreviewing(true)
    setTemplateFeedback({ type: '', message: '' })
    try {
      const html = await onPreviewLanding({
        campaignId,
        name: templateName.trim() || 'Landing page',
        blocks,
        couponId: previewCouponId || undefined,
      })
      setPreviewHtml(html)
    } catch (error) {
      setTemplateFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to preview.' })
    } finally {
      setPreviewing(false)
    }
  }

  // Coupons on this campaign (for the preview creator picker + share links).
  const allCampaignCoupons = (pageCoupons.length ? pageCoupons : coupons).filter((c) => c.campaignId === campaignId)
  const campaignCoupons = allCampaignCoupons.filter((c) => c.publicSlug)

  return (
    <article className="card mds-surface mds-prose form-card page-stack">
      <MdsKicker>Content</MdsKicker>
      <h3>Campaign brief</h3>
      <MdsSectionRule />
      <p>Author the brief creators execute against — goals, dos &amp; don'ts, talking points, required hashtags, and disclosure text. The brand owns the message; creators bring the voice.</p>

      {feedback.message ? (
        <p className={`row-save-feedback ${feedback.type === 'error' ? 'error' : 'success'}`}>{feedback.message}</p>
      ) : null}

      <form onSubmit={save} className="page-stack">
        <label className="auth-label">Campaign</label>
        <select value={campaignId} onChange={(e) => setCampaignId(e.target.value)} required>
          <option value="">Select campaign…</option>
          {campaigns.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>

        {campaignId ? (
          <>
            <MdsNote>{currentBrief ? 'Editing existing brief.' : 'No brief yet — this will create one.'}</MdsNote>

            <label className="auth-label">Summary</label>
            <textarea rows={2} value={content.summary} placeholder="One-line campaign summary" onChange={(e) => setContent((p) => ({ ...p, summary: e.target.value }))} />

            <label className="auth-label">Goals</label>
            <textarea rows={2} value={content.goals} placeholder="What success looks like" onChange={(e) => setContent((p) => ({ ...p, goals: e.target.value }))} />

            <label className="auth-label">Do's</label>
            <textarea rows={2} value={content.dos} placeholder="Encouraged messaging, must-mentions" onChange={(e) => setContent((p) => ({ ...p, dos: e.target.value }))} />

            <label className="auth-label">Don'ts</label>
            <textarea rows={2} value={content.donts} placeholder="Off-limits claims, competitors, etc." onChange={(e) => setContent((p) => ({ ...p, donts: e.target.value }))} />

            <label className="auth-label">Talking points</label>
            <textarea rows={3} value={content.talkingPoints} placeholder="Key product benefits to highlight" onChange={(e) => setContent((p) => ({ ...p, talkingPoints: e.target.value }))} />

            <label className="auth-label">Required hashtags (space or comma separated)</label>
            <input type="text" value={hashtags} placeholder="#summer #ad #brandpartner" onChange={(e) => setHashtags(e.target.value)} />

            <label className="auth-label">Brand assets (one per line, format: label|url)</label>
            <textarea rows={2} value={assets} placeholder={'Logo|https://cdn.example.com/logo.png\nProduct shot|https://cdn.example.com/hero.jpg'} onChange={(e) => setAssets(e.target.value)} />

            <label className="auth-label">Disclosure text (FTC / ASA)</label>
            <input type="text" value={disclosure} placeholder="#ad — paid partnership with Brand" onChange={(e) => setDisclosure(e.target.value)} />

            <label className="auth-label">Status</label>
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="draft">Draft</option>
              <option value="published">Published (shared with creators)</option>
            </select>

            <div className="row-actions">
              <button type="button" className="ghost-btn" onClick={draftBrief} disabled={drafting}>
                {drafting ? 'Drafting…' : '✨ Draft with AI'}
              </button>
              <button type="submit" className="primary-btn" disabled={saving}>
                {saving ? 'Saving…' : currentBrief ? 'Update brief' : 'Create brief'}
              </button>
            </div>
          </>
        ) : null}
      </form>

      <MdsSectionRule />
      <h3>Landing page builder</h3>
      <p>Build one hosted landing page per campaign. It’s auto-personalized per creator — tokens like <code>{'{{creator.name}}'}</code>, <code>{'{{coupon.code}}'}</code>, and <code>{'{{discount}}'}</code> are filled in per coupon.</p>
      {templateFeedback.message ? (
        <p className={`row-save-feedback ${templateFeedback.type === 'error' ? 'error' : 'success'}`}>{templateFeedback.message}</p>
      ) : null}
      {!campaignId ? (
        <MdsNote>Pick a campaign above to build its landing page.</MdsNote>
      ) : (
        <div className="page-stack">
          <MdsNote>{currentTemplate ? `Editing landing page (slug: ${currentTemplate.publicSlug}).` : 'No landing page yet — this will create one.'}</MdsNote>
          <label className="auth-label">Page name</label>
          <input type="text" value={templateName} onChange={(e) => setTemplateName(e.target.value)} />

          <div className="row-actions" role="group" aria-label="Editor mode">
            <button
              type="button"
              className={editorMode === 'visual' ? 'primary-btn' : 'ghost-btn'}
              aria-pressed={editorMode === 'visual'}
              onClick={() => setEditorMode('visual')}
            >
              Visual builder
            </button>
            <button
              type="button"
              className={editorMode === 'blocks' ? 'primary-btn' : 'ghost-btn'}
              aria-pressed={editorMode === 'blocks'}
              onClick={() => setEditorMode('blocks')}
            >
              Block list
            </button>
          </div>

          {editorMode === 'visual' ? (
            <>
              <label className="auth-label">Status</label>
              <select value={templateStatus} onChange={(e) => setTemplateStatus(e.target.value)}>
                <option value="draft">Draft</option>
                <option value="published">Published</option>
              </select>
              <MdsNote>
                Drag blocks onto the canvas. Preview at three widths with the buttons above the
                canvas. A page must be <strong>Published</strong> before it is reachable at its
                public link.
              </MdsNote>
              <LandingBuilder
                key={campaignId}
                initialDocument={currentTemplate?.document || null}
                onSave={saveBuilderDocument}
                onPreview={previewBuilderDocument}
                can={can}
                busy={savingTemplate || previewing}
                versions={versions}
                onRestore={restoreVersion}
                assets={mediaAssets}
                onUploadAsset={uploadAsset}
              />
              {currentTemplate ? (
                <p className="mds-note">
                  Brand page:{' '}
                  <a href={`/s/${currentTemplate.publicSlug}`} target="_blank" rel="noreferrer">
                    /s/{currentTemplate.publicSlug}
                  </a>
                </p>
              ) : null}
              {previewHtml ? (
                <div className="landing-preview">
                  <iframe title="Landing preview" className="landing-preview-frame" srcDoc={previewHtml} sandbox="" />
                </div>
              ) : null}
            </>
          ) : (
          <>
          <label className="auth-label">Blocks</label>
          {blocks.length === 0 ? <p className="custom-attributes-empty">No blocks yet. Add one below.</p> : null}
          <ul className="simple-list">
            {blocks.map((block, i) => (
              <li key={i}>
                <strong>{BLOCK_TYPES.find((t) => t.value === block.type)?.label || block.type}</strong>
                {block.type === 'image' ? (
                  <input type="url" value={block.url || ''} placeholder="Image URL" onChange={(e) => editBlock(i, 'url', e.target.value)} />
                ) : block.type === 'couponBlock' ? (
                  <span>Renders the creator’s code + discount</span>
                ) : block.type === 'productCta' ? (
                  <input type="text" value={block.label || ''} placeholder="Button label" onChange={(e) => editBlock(i, 'label', e.target.value)} />
                ) : (
                  <input type="text" value={block.text || ''} placeholder="Text (tokens allowed)" onChange={(e) => editBlock(i, 'text', e.target.value)} />
                )}
                <div className="row-actions">
                  <button type="button" className="ghost-btn" onClick={() => moveBlock(i, -1)} disabled={i === 0}>↑</button>
                  <button type="button" className="ghost-btn" onClick={() => moveBlock(i, 1)} disabled={i === blocks.length - 1}>↓</button>
                  <button type="button" className="ghost-btn" onClick={() => removeBlock(i)}>Remove</button>
                </div>
              </li>
            ))}
          </ul>

          <div className="row-actions">
            <select value={newBlockType} onChange={(e) => setNewBlockType(e.target.value)}>
              {BLOCK_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
            <button type="button" className="ghost-btn" onClick={addBlock}>Add block</button>
          </div>

          <label className="auth-label">Status</label>
          <select value={templateStatus} onChange={(e) => setTemplateStatus(e.target.value)}>
            <option value="draft">Draft</option>
            <option value="published">Published</option>
          </select>

          <div className="row-actions">
            <button type="button" className="primary-btn" onClick={saveTemplate} disabled={savingTemplate}>
              {savingTemplate ? 'Saving…' : currentTemplate ? 'Update landing page' : 'Create landing page'}
            </button>
            <button type="button" className="ghost-btn" onClick={previewLanding} disabled={previewing}>
              {previewing ? 'Rendering…' : '👁 Preview'}
            </button>
          </div>

          <label className="auth-label">Preview as creator</label>
          <select value={previewCouponId} onChange={(e) => setPreviewCouponId(e.target.value)}>
            <option value="">{allCampaignCoupons.length ? 'Auto (first coupon on campaign)' : 'Sample data'}</option>
            {allCampaignCoupons.map((c) => (
              <option key={c.id} value={c.id}>{c.code}</option>
            ))}
          </select>
          <MdsNote>Preview shows the page exactly as a visitor sees it — tokens like {'{{creator.name}}'} and {'{{discount}}'} are filled in. It reflects your current unsaved edits and does not record a visit.</MdsNote>

          {previewHtml ? (
            <div className="landing-preview">
              <iframe title="Landing preview" className="landing-preview-frame" srcDoc={previewHtml} sandbox="" />
            </div>
          ) : null}
          </>
          )}

          {campaignCoupons.length ? (
            <>
              <label className="auth-label">Personalized pages (one per creator)</label>
              <ul className="simple-list">
                {campaignCoupons.map((c) => {
                  const url = currentTemplate ? `/s/${currentTemplate.publicSlug}/${c.publicSlug}` : ''
                  return (
                    <li key={c.id}>
                      <strong className="mds-inline-code">{c.code}</strong>
                      {url ? <a href={url} target="_blank" rel="noreferrer">{url}</a> : <span>Save the page to get a link</span>}
                    </li>
                  )
                })}
              </ul>
            </>
          ) : null}
        </div>
      )}

      <MdsSectionRule />
      <h4>Briefs ({briefs.length})</h4>
      {loading ? (
        <p className="custom-attributes-empty">Loading…</p>
      ) : briefs.length === 0 ? (
        <p className="custom-attributes-empty">No briefs yet.</p>
      ) : (
        <ul className="simple-list">
          {briefs.map((b) => {
            const camp = campaigns.find((c) => c.id === b.campaignId)
            return (
              <li key={b.id}>
                <strong>{camp?.name || 'Campaign'}</strong>
                <span>{b.status}</span>
                <span>{b.content?.summary || 'No summary'}</span>
                <span>{(b.hashtags || []).join(' ')}</span>
              </li>
            )
          })}
        </ul>
      )}
    </article>
  )
}

export default ContentPage
