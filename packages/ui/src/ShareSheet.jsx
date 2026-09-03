import { useState } from 'react'

/**
 * What a creator copies to post a page on their own handle (roadmap PR-45).
 *
 * <p>The share kit is assembled server-side by `ShareKitService` — captions from the page's own
 * words, the tracked link, the disclosure, the assets. This is the surface that hands it over, and
 * its whole job is to remove steps: on a phone, `navigator.share()` opens the system sheet straight
 * into Instagram or TikTok; everywhere else, one button per thing worth copying.
 *
 * <p><b>The disclosure is rendered separately and is not editable.</b> It arrives as its own field
 * for exactly this reason — an editable caption box containing it is one someone trims, and the
 * FTC obligation goes with the trim. Copying a caption always copies the disclosure with it; the
 * two are joined at the copy, never in a box someone can edit them out of.
 *
 * <p><b>No QR code yet, deliberately.</b> A correct QR needs Reed-Solomon error correction — not
 * something to hand-roll, because the failure mode is a code that scans on the author's phone and
 * not on anyone else's — and the smallest credible library is a new dependency in a bundle `PR-39`
 * just cut 951 KB from. The desktop-to-phone gap it would close is real but narrow: the link copies
 * in one click, and most creators are already on the phone they will post from.
 */
export default function ShareSheet({ kit, platform = 'instagram', onPosted }) {
  const [copied, setCopied] = useState('')
  const [posting, setPosting] = useState(false)

  if (!kit) return null

  const caption = (kit.captions || []).find((c) => c.platform === platform)
    || (kit.captions || []).find((c) => c.platform === 'other')
  // Joined HERE, at the point of copying, so what reaches the clipboard always carries it.
  const fullCaption = [caption?.body, kit.disclosure].filter(Boolean).join('\n\n')

  const copy = async (id, text) => {
    if (!text) return
    try {
      await navigator.clipboard.writeText(text)
      setCopied(id)
      setTimeout(() => setCopied((prev) => (prev === id ? '' : prev)), 1500)
    } catch {
      // Same failure the coupons page already handles: clipboard access is denied outside a
      // secure context and in some embedded webviews. Saying so beats a button that looks broken.
      setCopied('error')
    }
  }

  const nativeShare = async () => {
    if (typeof navigator === 'undefined' || !navigator.share) return
    try {
      await navigator.share({ text: fullCaption, url: kit.link || undefined })
    } catch {
      // Includes the user simply dismissing the sheet, which is not an error worth reporting.
    }
  }

  const canNativeShare = typeof navigator !== 'undefined' && Boolean(navigator.share)

  return (
    <section className="sharekit" aria-labelledby="sharekit-heading">
      <h3 id="sharekit-heading">Share this page</h3>
      <p className="sharekit__sub">
        Everything below is ready to post. The disclosure is included automatically when you copy.
      </p>

      {canNativeShare ? (
        <button type="button" className="primary-btn sharekit__native" onClick={nativeShare}>
          Share…
        </button>
      ) : null}

      <div className="sharekit__field">
        <label className="auth-label" htmlFor="sharekit-caption">Caption</label>
        {/* readOnly, not disabled: a disabled textarea is unselectable, and someone who wants to
            edit before posting should be able to select and copy their own version out. */}
        <textarea id="sharekit-caption" className="sharekit__caption" readOnly rows={6} value={fullCaption} />
        <button type="button" className="ghost-btn" onClick={() => copy('caption', fullCaption)}>
          {copied === 'caption' ? 'Copied' : 'Copy caption'}
        </button>
      </div>

      {kit.code ? (
        <div className="sharekit__field">
          <label className="auth-label" htmlFor="sharekit-code">Your code</label>
          <input id="sharekit-code" className="sharekit__code" readOnly value={kit.code} />
          <button type="button" className="ghost-btn" onClick={() => copy('code', kit.code)}>
            {copied === 'code' ? 'Copied' : 'Copy code'}
          </button>
          {/* Said out loud because it is counter-intuitive: the link is the thing that looks
              important, and on Instagram it is the code that does the work. */}
          <p className="helper">
            On Instagram the link is not clickable — the code is what credits the sale to you.
          </p>
        </div>
      ) : null}

      {kit.link ? (
        <div className="sharekit__field">
          <label className="auth-label" htmlFor="sharekit-link">Link</label>
          <input id="sharekit-link" className="sharekit__link" readOnly value={kit.link} />
          <button type="button" className="ghost-btn" onClick={() => copy('link', kit.link)}>
            {copied === 'link' ? 'Copied' : 'Copy link'}
          </button>
        </div>
      ) : null}

      {(kit.assets || []).length > 0 ? (
        <div className="sharekit__field">
          <span className="auth-label">Images from the page</span>
          <ul className="sharekit__assets">
            {kit.assets.map((asset) => (
              <li key={asset.url}>
                {/* Opens rather than downloads: a download attribute on a cross-origin URL is
                    ignored by every browser, so it would look broken. Long-press to save is what
                    a creator does on a phone anyway. */}
                <a href={asset.url} target="_blank" rel="noreferrer">
                  <img src={asset.url} alt={asset.altText || ''} loading="lazy" />
                </a>
              </li>
            ))}
          </ul>
          <p className="helper">
            These are the page&rsquo;s own images at their original size — check the crop before
            posting to a square feed.
          </p>
        </div>
      ) : null}

      {copied === 'error' ? (
        <p className="row-save-feedback error" role="alert">
          Clipboard unavailable here — select the text and copy it manually.
        </p>
      ) : null}

      {typeof onPosted === 'function' ? (
        <div className="sharekit__posted">
          {/* Closes the loop back to the brand, who otherwise has no idea whether the creator
              posted. Deliberately the creator's own claim rather than anything measured: nothing
              here can verify a post, and a button that says "posted" while meaning "we think so"
              would be worse than asking. */}
          <button
            type="button"
            className="primary-btn"
            disabled={posting}
            onClick={async () => {
              setPosting(true)
              try { await onPosted() } finally { setPosting(false) }
            }}
          >
            {posting ? 'Saving…' : 'I posted this'}
          </button>
          <p className="helper">Lets the brand know it went live. Nothing is checked automatically.</p>
        </div>
      ) : null}
    </section>
  )
}
