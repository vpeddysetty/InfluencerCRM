import { useState } from 'react'
import { MdsNote } from './Mds'

/**
 * Goal-first landing page authoring (roadmap PR-35).
 *
 * The builder is a blank canvas, which asks a brand manager to think like a designer before they
 * can launch a campaign. This panel inverts that: describe the campaign, compare drafts, then open
 * the winner in the builder to refine. The builder stays exactly where it was — it becomes the
 * second step rather than the first.
 *
 * Selecting a draft hands its blocks back to the page, which saves them through the ordinary
 * landing-template path. There is deliberately no separate "generated draft" record: a draft that
 * could not be edited and published like any other page would need its own editor and its own
 * renderer to reach the same public URL.
 */

const CAMPAIGN_TYPES = [
  { value: 'product_launch', label: 'Product launch' },
  { value: 'creator_takeover', label: 'Creator takeover' },
  { value: 'coupon_offer', label: 'Coupon offer' },
  { value: 'email_signup', label: 'Email signup' },
  { value: 'waitlist', label: 'Waitlist' },
  { value: 'affiliate', label: 'Affiliate campaign' },
]

const TONES = ['Premium', 'Playful', 'Bold', 'Clean', 'Warm']

// Mirrors the server's REQUIRED_SECTION_TYPES — these are the only labels a draft can carry.
const SECTION_LABELS = {
  hero: 'Hero',
  richText: 'Text',
  couponBlock: 'Coupon code',
  productCta: 'Call to action',
  legal: 'Disclosure',
}

const EMPTY_BRIEF = {
  campaignType: 'product_launch',
  goal: '',
  audience: '',
  offer: '',
  creatorHandle: '',
  brandTone: '',
  ctaPreference: '',
  proofPoints: '',
}

function CampaignPageGenerator({ onGenerate, onUseDraft, onRewriteSection, onRegenerate, busy = false, can = () => true }) {
  const [brief, setBrief] = useState(EMPTY_BRIEF)
  const [result, setResult] = useState(null)
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState('')
  const [expandedId, setExpandedId] = useState('')
  // Keyed by `${variantId}:${index}` so two sections of the same type in one draft, or the same
  // index across two drafts, never share a control.
  const [editing, setEditing] = useState('')
  const [instruction, setInstruction] = useState('')
  const [rewriting, setRewriting] = useState('')
  const [regenerating, setRegenerating] = useState('')
  const [notice, setNotice] = useState('')

  const editable = can('content:write')
  // Goal is the only required field: it is what the whole page is about, and the server rejects a
  // brief without one. Everything else degrades to an omitted section rather than a blocked form.
  const canGenerate = brief.goal.trim().length > 0 && !generating && !busy && editable

  const field = (name) => (event) => setBrief((prev) => ({ ...prev, [name]: event.target.value }))

  async function generate(event) {
    event.preventDefault()
    if (!canGenerate) return
    setGenerating(true)
    setError('')
    try {
      const generated = await onGenerate(briefPayload())
      setResult(generated)
      // Preserving the brief on failure is the point of the fallback: the user keeps their input
      // and gets an editable page either way, so there is never a dead end to back out of.
      setExpandedId(generated?.variants?.[0]?.id || '')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to generate drafts.')
    } finally {
      setGenerating(false)
    }
  }

  /** Ask the server to reword one section, and splice the answer into the draft in place. */
  async function rewrite(variantId, index) {
    const key = `${variantId}:${index}`
    const variant = (result?.variants || []).find((v) => v.id === variantId)
    const section = variant?.sections?.[index]
    if (!section || !onRewriteSection) return

    setRewriting(key)
    setNotice('')
    try {
      const answer = await onRewriteSection({ ...briefPayload(), section, instruction })
      if (!answer?.rewritten) {
        // Reported rather than swallowed: a button that silently does nothing reads as broken,
        // and the user would keep pressing it assuming they had phrased the request wrong.
        setNotice(answer?.detail || 'No rewrite was suggested. Your text is unchanged.')
        return
      }
      // Replaced in place. Only this section changes — that is the whole point of section-level
      // editing, so a reworded offer cannot silently restyle a headline the user had settled on.
      setResult((prev) => ({
        ...prev,
        variants: prev.variants.map((v) => v.id !== variantId ? v : {
          ...v,
          sections: v.sections.map((sec, i) => (i === index ? answer.section : sec)),
          blocks: v.blocks?.map((block, i) => (i === index
            ? withBlockText(block, answer.section.body)
            : block)),
        }),
      }))
      setInstruction('')
      setEditing('')
    } catch (e) {
      setNotice(e instanceof Error ? e.message : 'The rewrite could not be requested.')
    } finally {
      setRewriting('')
    }
  }

  /** Swap one card for a fresh draft, telling the server which headlines are already on screen. */
  async function regenerate(variantId) {
    if (!onRegenerate) return
    setRegenerating(variantId)
    setNotice('')
    try {
      const answer = await onRegenerate({
        ...briefPayload(),
        seenHeadlines: (result?.variants || []).map((v) => v.headline),
      })
      const replacement = answer?.variants?.[0]
      if (!replacement) {
        setNotice(answer?.detail || 'No new draft was available.')
        return
      }
      setResult((prev) => ({
        ...prev,
        // The replacement keeps the card's position so the list does not reshuffle under the
        // user's cursor while they are comparing.
        variants: prev.variants.map((v) => (v.id === variantId ? replacement : v)),
      }))
      setExpandedId(replacement.id)
    } catch (e) {
      setNotice(e instanceof Error ? e.message : 'A new draft could not be requested.')
    } finally {
      setRegenerating('')
    }
  }

  /**
   * The brief as the server wants it, shared by generate, rewrite and regenerate.
   *
   * Rewrite and regenerate both need the whole brief, not just the section or the seen headlines:
   * a rewrite with no offer or audience in view drifts away from the campaign, and a regenerate
   * without it would produce a draft for a different page.
   */
  function briefPayload() {
    return {
      ...brief,
      // The server takes a list; the form takes one line the user can type comma-separated.
      proofPoints: brief.proofPoints.split(/[,\n]/).map((p) => p.trim()).filter(Boolean),
    }
  }

  /**
   * Put rewritten text back into the builder-ready block beside the section.
   *
   * The renderer reads `label` on a productCta and `text` on everything else, so writing the wrong
   * key would leave the block rendering its old copy while the card showed the new — the two would
   * silently disagree until someone published.
   */
  function withBlockText(block, body) {
    if (!block) return block
    if (block.type === 'couponBlock') return block
    return block.type === 'productCta' ? { ...block, label: body } : { ...block, text: body }
  }

  /**
   * Render a draft as a GrapesJS document, so the visual builder can open it.
   *
   * The blocks array alone is not enough: the visual builder reads `document` (html/css) and never
   * looks at `blocks`, so a draft handed over as blocks only appears in the Block list editor. That
   * made "Use this draft" look broken to anyone who expected the builder they had been using.
   *
   * Both representations travel together — the server keeps both columns and the public renderer
   * prefers `document` — so a draft opens in either editor and publishes identically.
   *
   * Tokens are left as literal {{coupon.code}} text: they are substituted at request time against
   * the visiting creator's own coupon, so resolving them here would freeze one creator's code into
   * every visitor's page.
   */
  function toBuilderDocument(variant) {
    const esc = (v) => String(v ?? '')
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

    const body = variant.sections.map((section) => {
      const text = esc(section.body)
      switch (section.type) {
        case 'hero':
          return `<h1 class="cpg-hero">${text}</h1>`
        case 'couponBlock':
          // Rendered by the server per creator; the placeholder is what the builder shows.
          return '<p class="cpg-coupon">Use code <strong>{{coupon.code}}</strong> for {{discount}}</p>'
        case 'productCta':
          return `<a class="cpg-cta" href="{{coupon.landingUrl}}">${esc(section.body || variant.ctaText)}</a>`
        case 'legal':
          return `<p class="cpg-legal">${text}</p>`
        default:
          return `<p class="cpg-text">${text}</p>`
      }
    }).join('\n')

    const sub = variant.subheadline
      ? `<p class="cpg-sub">${esc(variant.subheadline)}</p>`
      : ''

    return {
      html: `<section class="cpg-page">\n${body.replace('</h1>', `</h1>\n${sub}`)}\n</section>`,
      // Deliberately plain: this is a starting point the user edits in the builder, not a finished
      // design. Opinionated styling here would be harder to undo than to add.
      css: [
        '.cpg-page{max-width:640px;margin:0 auto;padding:32px 24px}',
        '.cpg-hero{font-size:2rem;line-height:1.2;margin:0 0 .5rem}',
        '.cpg-sub{color:#475569;margin:0 0 1.5rem}',
        '.cpg-text{line-height:1.6;margin:0 0 1rem}',
        '.cpg-coupon{background:#eef2ff;color:#3730a3;padding:.75rem 1rem;border-radius:10px}',
        '.cpg-cta{display:inline-block;background:#4f46e5;color:#fff;text-decoration:none;',
        'padding:.75rem 1.25rem;border-radius:10px;margin:1rem 0}',
        '.cpg-legal{color:#64748b;font-size:.8rem;margin-top:2rem}',
      ].join(''),
    }
  }

  const variants = result?.variants || []

  return (
    <section className="page-stack">
      <form onSubmit={generate} className="page-stack">
        <label className="auth-label" htmlFor="cpg-type">Page type</label>
        <select id="cpg-type" value={brief.campaignType} onChange={field('campaignType')} disabled={!editable}>
          {CAMPAIGN_TYPES.map((type) => (
            <option key={type.value} value={type.value}>{type.label}</option>
          ))}
        </select>

        <label className="auth-label" htmlFor="cpg-goal">Goal</label>
        <input
          id="cpg-goal"
          type="text"
          value={brief.goal}
          onChange={field('goal')}
          placeholder="Launch the winter trail collection"
          disabled={!editable}
          required
        />

        <label className="auth-label" htmlFor="cpg-audience">Audience</label>
        <input
          id="cpg-audience"
          type="text"
          value={brief.audience}
          onChange={field('audience')}
          placeholder="Hikers 25–40, weekend trips"
          disabled={!editable}
        />

        <label className="auth-label" htmlFor="cpg-offer">Offer</label>
        <input
          id="cpg-offer"
          type="text"
          value={brief.offer}
          onChange={field('offer')}
          placeholder="15% off the first order"
          disabled={!editable}
        />

        <label className="auth-label" htmlFor="cpg-creator">Creator</label>
        <input
          id="cpg-creator"
          type="text"
          value={brief.creatorHandle}
          onChange={field('creatorHandle')}
          placeholder="@northbound"
          disabled={!editable}
        />

        <label className="auth-label" htmlFor="cpg-cta">Call to action</label>
        <input
          id="cpg-cta"
          type="text"
          value={brief.ctaPreference}
          onChange={field('ctaPreference')}
          placeholder="Shop the collection"
          disabled={!editable}
        />

        <label className="auth-label" htmlFor="cpg-tone">Brand tone</label>
        <input
          id="cpg-tone"
          type="text"
          value={brief.brandTone}
          onChange={field('brandTone')}
          placeholder="Clean, premium"
          list="cpg-tones"
          disabled={!editable}
        />
        <datalist id="cpg-tones">
          {TONES.map((tone) => <option key={tone} value={tone} />)}
        </datalist>

        <label className="auth-label" htmlFor="cpg-proof">Proof points (comma separated)</label>
        <input
          id="cpg-proof"
          type="text"
          value={brief.proofPoints}
          onChange={field('proofPoints')}
          placeholder="Recycled fabric, two-year guarantee"
          disabled={!editable}
        />

        <MdsNote>
          Only the goal is required. Anything you leave blank is left off the page rather than
          invented — a landing page carries the brand’s name, so nothing is claimed that you
          haven’t supplied.
        </MdsNote>

        <div className="row-actions">
          <button type="submit" className="primary-btn" disabled={!canGenerate}>
            {generating ? 'Writing drafts…' : 'Generate page drafts'}
          </button>
        </div>
      </form>

      {error ? <p className="row-save-feedback error">{error}</p> : null}
      {/* Distinct from `error`: a generator with no suggestion is an answer, not a failure, so it
          reads as information rather than something the user must fix. */}
      {notice ? <MdsNote>{notice}</MdsNote> : null}

      {result ? (
        <>
          {/* Stated plainly rather than implied: a template draft presented as an AI draft is
              worse than one that says what it is. */}
          {result.fallback ? (
            <MdsNote>
              These are template drafts, not AI-written ones
              {result.detail ? ` — ${result.detail}` : '.'} Your brief is unchanged, and the drafts
              below are editable exactly like any other page.
            </MdsNote>
          ) : null}

          <h4>Choose a draft</h4>
          {variants.length === 0 ? (
            <p className="custom-attributes-empty">No drafts came back. Try generating again.</p>
          ) : null}

          <ul className="simple-list">
            {variants.map((variant) => (
              <li key={variant.id}>
                <strong>{variant.headline}</strong>
                {variant.subheadline ? <span>{variant.subheadline}</span> : null}
                <span className="mds-note">
                  Completeness {variant.score}/100 · {variant.sections.length} sections · CTA “{variant.ctaText}”
                </span>

                {expandedId === variant.id ? (
                  <ul className="simple-list">
                    {variant.sections.map((section, index) => {
                      const key = `${variant.id}:${index}`
                      // The coupon block renders the creator's live code, so there is no authored
                      // text to reword — the server refuses it, and offering the control here
                      // would be a button that always errors.
                      const rewritable = section.type !== 'couponBlock' && Boolean(onRewriteSection)
                      return (
                        <li key={key}>
                          <strong>{SECTION_LABELS[section.type] || section.type}</strong>
                          <span>
                            {section.type === 'couponBlock'
                              ? 'Renders each creator’s own code'
                              : section.body}
                          </span>

                          {rewritable ? (
                            <div className="row-actions">
                              <button
                                type="button"
                                className="ghost-btn"
                                aria-expanded={editing === key}
                                disabled={!editable || busy}
                                onClick={() => {
                                  setEditing(editing === key ? '' : key)
                                  setInstruction('')
                                  setNotice('')
                                }}
                              >
                                {editing === key ? 'Cancel' : 'Rewrite'}
                              </button>
                            </div>
                          ) : null}

                          {editing === key ? (
                            <div className="page-stack">
                              <label className="auth-label" htmlFor={`cpg-instr-${key}`}>
                                What should change?
                              </label>
                              <input
                                id={`cpg-instr-${key}`}
                                type="text"
                                value={instruction}
                                onChange={(e) => setInstruction(e.target.value)}
                                placeholder="Make it shorter"
                                disabled={rewriting === key}
                              />
                              <div className="row-actions">
                                {/* Presets, because the useful instructions are a short list and
                                    typing them every time is friction for no gain. */}
                                {['Make it shorter', 'Lead with the offer', 'Warmer tone'].map((preset) => (
                                  <button
                                    key={preset}
                                    type="button"
                                    className="ghost-btn"
                                    disabled={rewriting === key}
                                    onClick={() => setInstruction(preset)}
                                  >
                                    {preset}
                                  </button>
                                ))}
                              </div>
                              <div className="row-actions">
                                <button
                                  type="button"
                                  className="primary-btn"
                                  disabled={rewriting === key || !instruction.trim()}
                                  onClick={() => rewrite(variant.id, index)}
                                >
                                  {rewriting === key ? 'Rewriting…' : 'Apply rewrite'}
                                </button>
                              </div>
                            </div>
                          ) : null}
                        </li>
                      )
                    })}
                  </ul>
                ) : null}

                <div className="row-actions">
                  <button
                    type="button"
                    className="ghost-btn"
                    aria-expanded={expandedId === variant.id}
                    onClick={() => setExpandedId(expandedId === variant.id ? '' : variant.id)}
                  >
                    {expandedId === variant.id ? 'Hide sections' : 'Preview sections'}
                  </button>
                  {onRegenerate ? (
                    <button
                      type="button"
                      className="ghost-btn"
                      disabled={!editable || busy || regenerating === variant.id}
                      onClick={() => regenerate(variant.id)}
                    >
                      {regenerating === variant.id ? 'Rewriting…' : 'Try another'}
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="primary-btn"
                    disabled={!editable || busy}
                    onClick={() => onUseDraft({ ...variant, document: toBuilderDocument(variant) })}
                  >
                    Use this draft
                  </button>
                </div>
              </li>
            ))}
          </ul>

          {variants.length > 0 ? (
            <MdsNote>
              Choosing a draft loads it into the builder below, where you can edit it before saving.
              Nothing is published until you set the page to <strong>Published</strong> and save.
            </MdsNote>
          ) : null}
        </>
      ) : null}
    </section>
  )
}

export default CampaignPageGenerator
