import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'
import LandingBuilder from '../components/LandingBuilder'
import SectionEditor from '@influencer/ui/SectionEditor.jsx'
import { applyTemplate, stripForTemplate, templateById, templateForCampaignType, PAGE_TEMPLATES } from '@influencer/ui/pageTemplates.js'
import { blankSection } from '@influencer/ui/sectionTypes.js'
import CampaignPageGenerator from '../components/CampaignPageGenerator'
import CollaboratorPanel from '../components/CollaboratorPanel'
import { publicPageUrl } from '../api/core'

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
  onGeneratePage,
  onRewriteSection,
  onRegenerateVariant,
  onLoadEditorMode,
  onLoadPageTemplates,
  onSavePageTemplate,
  onDeletePageTemplate,
  onSchedulePublish,
  onCancelSchedule,
  onPublishNow,
  // PR-42. Optional: a caller that does not wire collaboration simply gets no
  // panel rather than a crash. Two different shells load the two copies of this
  // page, and only one of them is served in production.
  onLoadCollaborators,
  onHandOff,
  onTakeBack,
  onRevokeCollaborator,
  onInviteCreator,
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
  // PR-39. Which editor this DEPLOYMENT serves, read from the server rather than compiled in —
  // the flag's whole value is that flipping it is a variable change and an instance refresh.
  // Starts as null (unknown) rather than 'builder' so the editor does not flash the old one and
  // then swap while the answer is in flight.
  const [serverEditor, setServerEditor] = useState(null)
  const [sections, setSections] = useState([])
  const [savedTemplates, setSavedTemplates] = useState([])
  const [versions, setVersions] = useState([])
  const [mediaAssets, setMediaAssets] = useState([])
  // Scheduled publish (PR-35). The input is a local-time `datetime-local` string; the server
  // takes UTC, so the conversion happens at the boundary below rather than in state.
  // A draft the user just selected, before it has been saved. Held separately from
  // `currentTemplate.document` because nothing is persisted until they press save — the builder
  // must show it, and a reload must not resurrect a draft they walked away from.
  const [generatedDocument, setGeneratedDocument] = useState(null)
  // Which draft is on the canvas. Part of the builder's `key`, because the builder snapshots its
  // initial document at mount: without a per-draft key, switching from one draft to another keeps
  // the first one on screen and the second selection appears to do nothing.
  const [generatedDraftId, setGeneratedDraftId] = useState('')
  const [scheduleAt, setScheduleAt] = useState('')
  const [scheduling, setScheduling] = useState(false)
  const [publishing, setPublishing] = useState(false)

  const currentBrief = useMemo(
    () => briefs.find((b) => b.campaignId === campaignId) || null,
    [briefs, campaignId],
  )

  const [collaborators, setCollaborators] = useState([])

  const currentTemplate = useMemo(
    () => templates.find((t) => t.campaignId === campaignId) || null,
    [templates, campaignId],
  )

  // PR-42. Reloaded whenever the page changes or an action completes, and deliberately not cached
  // across pages: a stale collaborator list is how a brand hands a page to somebody whose access
  // was revoked while they were looking at it.
  const refreshCollaborators = useCallback(async () => {
    if (!currentTemplate?.id || typeof onLoadCollaborators !== 'function') {
      setCollaborators([])
      return
    }
    try {
      setCollaborators((await onLoadCollaborators(currentTemplate.id)) || [])
    } catch {
      // A failed load must not break the editor around it. The panel then shows nobody, which is
      // also what it shows before anyone is invited — a safe reading either way.
      setCollaborators([])
    }
  }, [currentTemplate?.id, onLoadCollaborators])

  useEffect(() => { refreshCollaborators() }, [refreshCollaborators])


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
    // An unsaved draft belongs to the campaign it was generated for. Clearing it here stops it
    // following the user to the next campaign, where it would look like that campaign's page.
    setGeneratedDocument(null)
    setGeneratedDraftId('')
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
   * Load a generated draft into the block editor (PR-35).
   *
   * Deliberately does NOT save. The design spec requires a review step before publishing, and a
   * draft that wrote itself to the campaign's page on selection would overwrite an existing page
   * with one the user had only glanced at. It also switches to the block list rather than the
   * visual builder: the builder renders `document`, which a generated draft has no value for, so
   * landing there would show an empty canvas beside a page that does exist.
   */
  /**
   * Schedule this page to publish itself.
   *
   * The browser gives a local wall-clock string with no zone; `new Date(...)` interprets it in the
   * user's zone and `toISOString()` converts to the UTC instant the server stores. Sending the raw
   * string would schedule at the wrong hour for anyone not on UTC.
   */
  /**
   * Publish immediately.
   *
   * <p>Goes through POST /publish rather than setting the status select to "Published" and
   * saving. Those are not the same operation: the select writes `status` only, leaving `stage`
   * behind, so the page goes live while the board still shows it in Draft and the free-hosting
   * clock never starts. The endpoint walks the stage machine, which is where all of that lives.
   */
  const publishNow = async () => {
    if (!currentTemplate || typeof onPublishNow !== 'function') return
    setPublishing(true)
    setTemplateFeedback({ type: '', message: '' })
    try {
      await onPublishNow(currentTemplate.id)
      await refreshTemplates()
      setTemplateStatus('published')
      setTemplateFeedback({
        type: 'success',
        message: 'Published. The page is live at the links below.',
      })
    } catch (error) {
      setTemplateFeedback({
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to publish.',
      })
    } finally {
      setPublishing(false)
    }
  }

  const schedulePublish = async () => {
    if (!currentTemplate || !scheduleAt) return
    setScheduling(true)
    setTemplateFeedback({ type: '', message: '' })
    try {
      const saved = await onSchedulePublish(currentTemplate.id, new Date(scheduleAt).toISOString())
      // Refresh from the server rather than patching local state: the response is the page as it
      // now is, and guessing would drift from it.
      await refreshTemplates()
      setScheduleAt('')
      setTemplateFeedback({
        type: 'success',
        message: `Scheduled to publish on ${new Date(saved.scheduledPublishAt).toLocaleString()}.`,
      })
    } catch (error) {
      setTemplateFeedback({
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to schedule.',
      })
    } finally {
      setScheduling(false)
    }
  }

  const cancelScheduledPublish = async () => {
    if (!currentTemplate) return
    setScheduling(true)
    try {
      await onCancelSchedule(currentTemplate.id)
      await refreshTemplates()
      setTemplateFeedback({ type: 'success', message: 'Scheduled publish cancelled.' })
    } catch (error) {
      setTemplateFeedback({
        type: 'error',
        message: error instanceof Error ? error.message : 'Unable to cancel.',
      })
    } finally {
      setScheduling(false)
    }
  }

  /**
   * The generator's sections -> the curated editor's sections.
   *
   * <p>The two vocabularies overlap but are not identical: the generator speaks
   * {@code {type, title, body}} using the RENDERER's block names, while the editor speaks
   * {@code {type, variant, fields}}. Mapping here rather than changing either contract, because
   * the generator's shape is also what the draft-comparison screen and the rewrite endpoint use.
   *
   * <p>A generated type with no curated equivalent becomes a Text section rather than being
   * dropped: losing a paragraph the model wrote is worse than showing it in a plainer section
   * than intended, and the brand can retype or delete it.
   */
  const sectionsFromVariant = (variant) => {
    const generated = Array.isArray(variant?.sections) ? variant.sections : []
    if (generated.length === 0) return []

    const mapped = generated.map((s) => {
      // Only `body` is copy. `s.title` is the draft list's display label and is deliberately
      // not read here — see the hero case.
      const body = String(s.body || '')
      switch (s.type) {
        case 'hero': {
          // `title` is a generic LABEL ("Hero", "Offer") from the draft-comparison list, not copy.
          // An earlier version used it as the headline and published "Hero" as an h1 on a real
          // page. The model's actual words are always in `body`; the variant's own headline is the
          // better short form when it has one.
          const hero = blankSection('hero')
          return {
            ...hero,
            fields: { ...hero.fields, headline: String(variant.headline || '').trim() || body, subheadline: variant.headline ? body : '' },
          }
        }
        case 'couponBlock': {
          // The code itself is rendered per creator by the server, so only the words around it
          // carry over. `{{coupon.code}}` stays a token for the same reason.
          const offer = blankSection('offer')
          return {
            ...offer,
            fields: { ...offer.fields, headline: String(variant.offerText || '').trim(), supporting: body },
          }
        }
        case 'productCta': {
          // A button label is two or three words. The model's `body` here is a full sentence, so
          // it becomes the headline and the LABEL comes from ctaText — which is the field the
          // brief's "call to action" input feeds. Putting the sentence on the button produced a
          // four-line block of white text on terracotta.
          const signup = blankSection('signup')
          const label = String(variant.ctaText || '').trim() || 'Shop now'
          return {
            ...signup,
            fields: { ...signup.fields, headline: body || 'Ready when you are', ctaLabel: label },
          }
        }
        case 'legal': {
          const legal = blankSection('legal')
          return { ...legal, fields: { ...legal.fields, body } }
        }
        case 'image':
        case 'video': {
          const media = blankSection('media')
          return { ...media, fields: { ...media.fields, asset: s.mediaUrl || '', caption: body, altText: body } }
        }
        default: {
          // No headline: `title` is a label, and inventing one from the body would put the first
          // sentence on the page twice.
          const text = blankSection('text')
          return { ...text, fields: { ...text.fields, body } }
        }
      }
    }).filter(Boolean)

    // Every published page needs a disclosure; the generator does not always produce one.
    if (!mapped.some((s) => s.type === 'legal')) mapped.push(blankSection('legal'))
    return mapped
  }

  const useGeneratedDraft = (variant) => {
    // BOTH representations are kept. The visual builder reads `document` and never looks at
    // `blocks`; the block editor is the reverse. Loading only one meant "Use this draft" appeared
    // to do nothing for anyone whose editor was the other one — which is what happened when the
    // draft arrived as blocks and the builder showed an empty canvas.
    setBlocks(Array.isArray(variant.blocks) ? variant.blocks : [])
    setGeneratedDocument(variant.document || null)
    setGeneratedDraftId(variant.id || '')
    // PR-39. The generator already returns TYPED sections; until now they were flattened into
    // html on the way in and the typing was thrown away, which is exactly why `rewriteSection`
    // had nothing to point at once a draft was open. Map them onto the curated section list so an
    // AI draft opens in the section editor as sections rather than as an opaque document.
    setSections(sectionsFromVariant(variant))
    // Stay in the visual builder, which is the default and where most people already are. The
    // block editor remains one click away and now holds the same draft.
    setEditorMode('visual')
    setTemplateStatus('draft')
    setTemplateFeedback({
      type: 'success',
      message: 'Draft loaded into the builder. Edit it below, then save — nothing is published yet.',
    })
  }

  /**
   * Save from the visual builder. Sends `document` (GrapesJS html/css) instead of `blocks`;
   * the server keeps both columns and renders whichever is present, so switching editors
   * never destroys the other representation.
   */
  useEffect(() => {
    let cancelled = false
    if (typeof onLoadEditorMode !== 'function') return undefined
    Promise.resolve(onLoadEditorMode())
      .then((mode) => { if (!cancelled) setServerEditor(mode === 'sections' ? 'sections' : 'builder') })
      // A failed read falls back to the builder: it is what production serves today, so an
      // unreachable config endpoint must not strand the user in the newer editor by accident.
      .catch(() => { if (!cancelled) setServerEditor('builder') })
    return () => { cancelled = true }
  }, [onLoadEditorMode])

  useEffect(() => {
    let cancelled = false
    if (typeof onLoadPageTemplates !== 'function' || serverEditor !== 'sections') return undefined
    Promise.resolve(onLoadPageTemplates())
      .then((list) => { if (!cancelled && Array.isArray(list)) setSavedTemplates(list) })
      .catch(() => { /* the picker degrades to built-ins only */ })
    return () => { cancelled = true }
  }, [onLoadPageTemplates, serverEditor])

  // Seed the section list ONCE per campaign, not on every render.
  //
  // `seededFor` is the guard, and it is load-bearing. This effect necessarily depends on values
  // whose identity changes on a parent render (`campaigns` is a prop array, `currentTemplate` is
  // derived), and its body builds a NEW array. Without the guard it re-ran constantly and replaced
  // `sections` with a fresh array each time — which restarted the preview debounce, so the
  // in-flight preview was cancelled before it could ever land and the canvas stayed blank. It
  // would also have discarded whatever the user had just typed.
  //
  // Found in production on the first real page. A local harness never showed it because nothing
  // was re-rendering the parent.
  //
  // KEYED ON THE PAGE'S VERSION, NOT JUST THE CAMPAIGN. Keying on campaignId alone meant the
  // editor seeded once and then ignored every later fetch of the same campaign -- so when a
  // creator handed their work back and the brand took the page, refreshTemplates() loaded the new
  // content into `currentTemplate` and the editor kept rendering what it had seeded minutes
  // earlier. The creator's edits were saved, returned by the API and sitting in state; they were
  // simply never shown, which reads exactly like losing them.
  //
  // Keyed on `turnChangedAt` and NOT on `updatedAt`. updatedAt moves on every save including the
  // brand's own -- and saveSections puts the returned row straight into `templates` -- so keying on
  // it would re-seed the editor from the server in the middle of someone typing, which is the very
  // thing the original guard was written to prevent. turnChangedAt moves only when the page
  // actually changes hands, which is exactly when the content on screen is stale.
  const seededFor = useRef(null)
  useEffect(() => {
    if (!campaignId) return
    const seedKey = `${campaignId}:${currentTemplate?.turnChangedAt || ''}`
    if (seededFor.current === seedKey) return
    seededFor.current = seedKey

    const stored = currentTemplate?.sections
    if (Array.isArray(stored) && stored.length > 0) {
      setSections(stored)
      return
    }
    // A campaign with no page yet starts from the template matching its type, so choosing a
    // campaign type and choosing a page shape stay one decision rather than two.
    const campaign = campaigns.find((c) => c.id === campaignId)
    const matched = templateForCampaignType(campaign?.campaignType)
    setSections(matched ? applyTemplate(matched, []).sections : [])
  }, [campaignId, currentTemplate, campaigns])

  const saveSections = async (next) => {
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
        sections: next,
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

  /** Server-rendered preview of the current sections — the real renderer, not an approximation. */
  const previewSections = async (next) => {
    if (!campaignId) return ''
    return onPreviewLanding({
      campaignId,
      name: templateName.trim() || 'Landing page',
      sections: next,
      couponId: previewCouponId || undefined,
    })
  }

  /**
   * Switch templates, keeping the words.
   *
   * The warning is shown BEFORE applying rather than after, because "three sections were
   * discarded" is not information anyone can act on once it has happened.
   */
  const applyPageTemplate = (kind, id) => {
    const template = kind === 'saved'
      ? savedTemplates.find((t) => t.id === id)
      : templateById(id)
    if (!template) return
    if (kind === 'saved') {
      setSections(Array.isArray(template.sections) ? template.sections : [])
      return
    }
    const { sections: next, discarded } = applyTemplate(template, sections)
    if (discarded.length > 0) {
      const names = discarded.map((d) => d.type).join(', ')
      if (!window.confirm(
        `This template has no place for what you wrote in: ${names}. That text will be lost. Continue?`)) return
    }
    setSections(next)
  }

  const savePageAsTemplate = async (name) => {
    if (typeof onSavePageTemplate !== 'function') return
    const { sections: stripped, assets } = stripForTemplate(sections)
    try {
      const saved = await onSavePageTemplate({ name, sections: stripped })
      setSavedTemplates((prev) => [saved, ...prev.filter((t) => t.id !== saved.id)])
      setTemplateFeedback({
        type: 'success',
        message: assets.length > 0
          // Naming the count matters: deleting one of these images later leaves a hole in every
          // page made from this template, and this is the only moment the link is visible.
          ? `Saved as “${name}”. It uses ${assets.length} image${assets.length === 1 ? '' : 's'} from your library — deleting them will affect pages made from it.`
          : `Saved as “${name}”.`,
      })
    } catch (error) {
      setTemplateFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to save the template.' })
    }
  }

  const deletePageTemplate = async (id) => {
    if (typeof onDeletePageTemplate !== 'function') return
    const target = savedTemplates.find((t) => t.id === id)
    if (target && !window.confirm(`Delete the template “${target.name}”? Pages already made from it are unaffected.`)) return
    try {
      await onDeletePageTemplate(id)
      setSavedTemplates((prev) => prev.filter((t) => t.id !== id))
    } catch (error) {
      setTemplateFeedback({ type: 'error', message: error instanceof Error ? error.message : 'Unable to delete the template.' })
    }
  }

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
                {drafting ? 'Drafting…' : 'Draft with AI'}
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

          {/* Generation sits above the editors, because the whole point of PR-35 is that the
              blank canvas is no longer the first thing a non-designer meets. The builder is
              still right below, unchanged, for anyone who would rather start from scratch. */}
          {onGeneratePage ? (
            <details className="page-stack">
              <summary>Start from a campaign goal</summary>
              <MdsNote>
                Describe the campaign and get drafts to compare. You can edit whichever you pick
                in the builder below before anything is saved.
              </MdsNote>
              <CampaignPageGenerator
                onGenerate={onGeneratePage}
                onUseDraft={useGeneratedDraft}
                onRewriteSection={onRewriteSection}
                onRegenerate={onRegenerateVariant}
                busy={savingTemplate}
                can={can}
              />
            </details>
          ) : null}

          {/* PR-39. The section editor REPLACES the builder rather than joining it as a third
              mode: offering both would mean a page could be authored two ways and the precedence
              rule (sections -> document -> blocks) would become a thing users have to reason
              about. Which one a deployment serves is the server's answer, not a user preference.

              `serverEditor === null` means the answer is still in flight; rendering nothing for
              that moment avoids showing the builder and then swapping it out underneath someone
              who has started typing. */}
          {serverEditor === null ? (
            <p className="mds-note">Loading the editor…</p>
          ) : serverEditor === 'sections' ? (
            <>
              {currentTemplate && typeof onHandOff === 'function' ? (
                <CollaboratorPanel
                  page={currentTemplate}
                  collaborators={collaborators}
                  can={can}
                  onHandOff={(payload) => onHandOff(currentTemplate.id, payload)}
                  onTakeBack={() => onTakeBack(currentTemplate.id)}
                  onRevoke={(collaboratorId) => onRevokeCollaborator(collaboratorId)}
                  onInvite={(payload) => onInviteCreator({
                    ...payload,
                    landingTemplateId: currentTemplate.id,
                  })}
                  onRefresh={async () => {
                    // BOTH, and the templates matter more. The panel shows whose turn it is from
                    // page.turn, and a handoff changes that on the SERVER -- so refreshing only the
                    // collaborator list left the button still reading "Hand over to creator" after
                    // a handoff that had returned 200. The state was right and the screen was a
                    // version behind, until the user navigated away and back.
                    await Promise.all([refreshCollaborators(), refreshTemplates()])
                  }}
                />
              ) : null}
              <label className="auth-label">Status</label>
              <select value={templateStatus} onChange={(e) => setTemplateStatus(e.target.value)}>
                <option value="draft">Draft</option>
                <option value="published">Published</option>
              </select>
              <SectionEditor
                sections={sections}
                onChange={setSections}
                onPreview={previewSections}
                onSave={saveSections}
                onRewrite={onRewriteSection}
                assets={mediaAssets}
                onUploadAsset={onUploadAsset}
                busy={savingTemplate}
                templates={PAGE_TEMPLATES}
                savedTemplates={savedTemplates}
                onApplyTemplate={applyPageTemplate}
                onSaveAsTemplate={savePageAsTemplate}
                onDeleteTemplate={deletePageTemplate}
              />
              {currentTemplate ? (
                <p className="mds-note">
                  Brand page:{' '}
                  <a href={publicPageUrl(`/s/${currentTemplate.publicSlug}`)} target="_blank" rel="noreferrer">
                    {publicPageUrl(`/s/${currentTemplate.publicSlug}`)}
                  </a>
                </p>
              ) : null}
            </>
          ) : (
          <>
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
                // `key` forces a remount when a draft is chosen: the builder snapshots its initial
                // document in a ref, so without a new key it keeps showing the old canvas.
                key={generatedDocument ? `gen-${campaignId}-${generatedDraftId}` : campaignId}
                initialDocument={generatedDocument || currentTemplate?.document || null}
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
                  <a href={publicPageUrl(`/s/${currentTemplate.publicSlug}`)} target="_blank" rel="noreferrer">
                    {publicPageUrl(`/s/${currentTemplate.publicSlug}`)}
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
              {previewing ? 'Rendering…' : 'Preview'}
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
          </>
          )}

          {/* Page-level, deliberately: a schedule applies to the page, not to whichever editor
              happens to be open. Only shown once the page exists — there is nothing to schedule
              until it has been saved and has a slug. */}
          {/* Publish now. Deliberately its own control rather than the status select: setting
              the select to Published writes `status` and leaves `stage` behind, which puts a live
              page in the board's Draft column and never starts the hosting clock. */}
          {currentTemplate && onPublishNow ? (
            <div className="page-stack">
              <MdsSectionRule />
              <label className="auth-label">Publish</label>
              {currentTemplate.status === 'published' ? (
                <MdsNote>
                  This page is <strong>live</strong>. Saving further edits updates it in place.
                </MdsNote>
              ) : (
                <>
                  <MdsNote>
                    Makes the page live immediately at the links below. Save your edits first —
                    publishing does not save unsaved changes.
                  </MdsNote>
                  <div className="row-actions">
                    <button
                      type="button"
                      className="primary-btn"
                      disabled={publishing || !can('content:write')}
                      onClick={publishNow}
                    >
                      {publishing ? 'Publishing…' : 'Publish now'}
                    </button>
                  </div>
                </>
              )}
            </div>
          ) : null}

          {currentTemplate && onSchedulePublish ? (
            <div className="page-stack">
              <MdsSectionRule />
              <label className="auth-label" htmlFor="cpg-schedule">Schedule publish</label>
              {currentTemplate.scheduledPublishAt ? (
                <>
                  <MdsNote>
                    This page publishes automatically on{' '}
                    <strong>{new Date(currentTemplate.scheduledPublishAt).toLocaleString()}</strong>.
                  </MdsNote>
                  <div className="row-actions">
                    <button
                      type="button"
                      className="ghost-btn"
                      disabled={scheduling}
                      onClick={cancelScheduledPublish}
                    >
                      {scheduling ? 'Cancelling…' : 'Cancel scheduled publish'}
                    </button>
                  </div>
                </>
              ) : (
                <>
                  <input
                    id="cpg-schedule"
                    type="datetime-local"
                    value={scheduleAt}
                    onChange={(e) => setScheduleAt(e.target.value)}
                    disabled={scheduling || !can('content:write')}
                  />
                  <MdsNote>
                    Your local time. The page must have content, and the time must be in the
                    future — to publish immediately, use <strong>Publish now</strong> above.
                  </MdsNote>
                  <div className="row-actions">
                    <button
                      type="button"
                      className="ghost-btn"
                      disabled={scheduling || !scheduleAt || !can('content:write')}
                      onClick={schedulePublish}
                    >
                      {scheduling ? 'Scheduling…' : 'Schedule publish'}
                    </button>
                  </div>
                </>
              )}
            </div>
          ) : null}

          {campaignCoupons.length ? (
            <>
              <label className="auth-label">Personalized pages (one per creator)</label>
              <ul className="simple-list">
                {campaignCoupons.map((c) => {
                  const url = currentTemplate ? publicPageUrl(`/s/${currentTemplate.publicSlug}/${c.publicSlug}`) : ''
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
