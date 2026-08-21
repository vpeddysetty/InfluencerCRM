import { useEffect, useRef, useState } from 'react'

/**
 * Visual landing page builder — GrapesJS embedded in the existing React shell
 * (roadmap Phase A, A.1–A.4).
 *
 * Two things about this component are load-bearing:
 *
 * 1. **GrapesJS is not a React component.** It owns its own DOM subtree and mutates it
 *    outside React's knowledge. It is therefore mounted imperatively against a ref and
 *    destroyed explicitly on unmount. Skipping the teardown leaks an editor instance per
 *    route change — the documented failure mode when embedding it in a React shell.
 *
 * 2. **React must never re-render the canvas.** The mount effect deliberately runs once
 *    (empty dep array) and reads the initial document through a ref rather than a prop,
 *    so a parent re-render cannot blow away in-progress edits.
 */

const DEVICES = [
  { id: 'Desktop', label: 'Desktop', width: '' },
  { id: 'Tablet', label: 'Tablet', width: '820px' },
  { id: 'Mobile', label: 'Mobile', width: '390px' },
]

// Starter content for a page that has never been opened in the builder. A blank canvas
// gives a user nothing to react to; a hero + CTA is immediately recognisable as a landing
// page and shows where the personalization tokens go.
const STARTER_HTML = `
<section class="lp-hero">
  <h1>Your headline here</h1>
  <p>Hi {{creator.name}} — tell your audience why they should care.</p>
  <a class="lp-cta" href="#">Shop now</a>
</section>`.trim()

const STARTER_CSS = `
.lp-hero { padding: 48px 24px; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; text-align: center; }
.lp-hero h1 { font-size: 2rem; margin: 0 0 .5rem; color: #0f172a; }
.lp-hero p { color: #334155; margin: 0 0 1.25rem; }
.lp-cta { display: inline-block; background: #4f46e5; color: #fff; text-decoration: none; padding: .7rem 1.2rem; border-radius: 10px; }
@media (max-width: 640px) { .lp-hero { padding: 32px 16px; } .lp-hero h1 { font-size: 1.5rem; } }`.trim()

/**
 * Blocks offered in the palette.
 *
 * `permission` gates a block behind the caller's `can()` (A.3). A block whose content the
 * user could not publish should not be offered in the first place — hiding it here is a
 * UX affordance, not a security control; the server still enforces on save.
 */
const BLOCKS = [
  {
    id: 'lp-hero',
    label: 'Hero',
    content: `<section class="lp-hero"><h1>Your headline</h1><p>Supporting line</p><a class="lp-cta" href="#">Shop now</a></section>`,
  },
  {
    id: 'lp-text',
    label: 'Text',
    content: `<div class="lp-text"><p>Write something worth reading.</p></div>`,
  },
  {
    id: 'lp-image',
    label: 'Image',
    content: `<img class="lp-image" src="https://placehold.co/600x300" alt=""/>`,
  },
  {
    id: 'lp-coupon',
    label: 'Coupon code',
    // The token is substituted server-side at render; the builder shows it literally so
    // the user can see exactly where the code will land.
    content: `<div class="lp-coupon"><p>Use code <strong>{{coupon.code}}</strong> for {{discount}}</p></div>`,
  },
  {
    id: 'lp-cta',
    label: 'Shop CTA',
    content: `<a class="lp-cta" href="#">Shop now</a>`,
  },
  {
    id: 'lp-signup',
    label: 'Creator signup',
    permission: 'creator:write',
    content: `<div class="lp-signup"><h3>Work with us</h3><p>Creators — apply to join this campaign.</p><a class="lp-cta" href="#signup">Apply now</a></div>`,
  },
  {
    id: 'lp-legal',
    label: 'Legal / disclosure',
    content: `<p class="lp-legal">#ad — paid partnership. Terms apply.</p>`,
  },
]

function LandingBuilder({
  initialDocument,
  onSave,
  onPreview,
  can,
  busy,
  versions = [],
  onRestore,
  assets = [],
  onUploadAsset,
}) {
  const hostRef = useRef(null)
  const editorRef = useRef(null)
  // The initial document is read through a ref so the mount effect can stay dependency-free
  // without capturing a stale prop.
  const initialRef = useRef(initialDocument)
  // Same reasoning as initialRef: the mount effect must not re-run when the asset list
  // changes, or the canvas would be rebuilt and in-progress edits lost. New assets are
  // pushed into the live editor by the effect below instead.
  const assetsRef = useRef(assets)
  const [ready, setReady] = useState(false)
  const [device, setDevice] = useState('Desktop')
  const [error, setError] = useState('')
  const [uploading, setUploading] = useState(false)

  useEffect(() => {
    let cancelled = false
    let editor = null

    // Dynamic import: GrapesJS is large and only this route needs it, so it should not
    // sit in the shared micro-frontend bundle every other page pays for.
    import('grapesjs')
      .then(async (module) => {
        if (cancelled || !hostRef.current) return
        const grapesjs = module.default ?? module
        await import('grapesjs/dist/css/grapes.min.css').catch(() => {
          // Style import failing must not stop the editor mounting — an unstyled but
          // working builder beats a blank panel.
        })

        const doc = initialRef.current
        editor = grapesjs.init({
          container: hostRef.current,
          height: '560px',
          width: 'auto',
          // No storage manager: saving goes through this app's own API so the document
          // lands in the same transaction path as everything else.
          storageManager: false,
          // Undo/redo, and nothing that phones home.
          plugins: [],
          // Uploads go through this app's own endpoint (which validates and tenants them),
          // so GrapesJS's built-in uploader is off and the list is supplied from our library.
          assetManager: {
            assets: (assetsRef.current || []).map((a) => ({
              type: 'image',
              src: a.url,
              name: a.fileName,
            })),
            upload: false,
            autoAdd: true,
          },
          deviceManager: {
            devices: DEVICES.map((d) => ({ id: d.id, name: d.label, width: d.width })),
          },
          blockManager: {
            blocks: BLOCKS
              .filter((b) => !b.permission || (typeof can === 'function' ? can(b.permission) : true))
              .map((b) => ({ id: b.id, label: b.label, content: b.content, category: 'Landing' })),
          },
        })

        if (doc && (doc.html || doc.css)) {
          editor.setComponents(doc.html || '')
          editor.setStyle(doc.css || '')
        } else {
          editor.setComponents(STARTER_HTML)
          editor.setStyle(STARTER_CSS)
        }

        editorRef.current = editor
        if (!cancelled) setReady(true)
      })
      .catch((e) => {
        if (!cancelled) setError(e?.message || 'Could not load the visual builder')
      })

    return () => {
      cancelled = true
      // Explicit teardown — without this every visit to the builder leaves an editor
      // (and its listeners and iframe) behind.
      try {
        editorRef.current?.destroy()
      } catch {
        // destroy() can throw if the container was already removed; nothing to recover.
      }
      editorRef.current = null
    }
  }, [can])

  // Keep the mounted editor's asset manager in step with the library without remounting.
  useEffect(() => {
    assetsRef.current = assets
    const editor = editorRef.current
    if (!editor || !ready) return
    try {
      const manager = editor.AssetManager
      manager.getAll().reset()
      manager.add(assets.map((a) => ({ type: 'image', src: a.url, name: a.fileName })))
    } catch {
      // A GrapesJS internal shape change should degrade the picker, not break the builder.
    }
  }, [assets, ready])

  async function handleUpload(file) {
    if (!file || typeof onUploadAsset !== 'function') return
    setUploading(true)
    setError('')
    try {
      await onUploadAsset(file)
    } catch (e) {
      setError(e?.message || 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  function currentDocument() {
    const editor = editorRef.current
    if (!editor) return null
    return { html: editor.getHtml(), css: editor.getCss() }
  }

  function handleDevice(id) {
    setDevice(id)
    try {
      editorRef.current?.setDevice(id)
    } catch {
      // A device the manager does not know is a no-op rather than a crash.
    }
  }

  return (
    <div className="landing-builder">
      {error ? <p className="mds-note" role="alert">{error}</p> : null}

      <div className="landing-builder__toolbar" style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', margin: '8px 0' }}>
        <span style={{ fontSize: '.85rem', color: '#475569' }}>Preview at</span>
        {DEVICES.map((d) => (
          <button
            key={d.id}
            type="button"
            onClick={() => handleDevice(d.id)}
            aria-pressed={device === d.id}
            disabled={!ready}
            style={{
              padding: '.35rem .7rem',
              borderRadius: 8,
              border: device === d.id ? '1px solid #4f46e5' : '1px solid #cbd5e1',
              background: device === d.id ? '#eef2ff' : '#fff',
              cursor: ready ? 'pointer' : 'default',
            }}
          >
            {d.label}
          </button>
        ))}

        <span style={{ flex: 1 }} />

        {typeof onUploadAsset === 'function' ? (
          <label
            style={{
              padding: '.35rem .7rem',
              borderRadius: 8,
              border: '1px solid #cbd5e1',
              cursor: uploading ? 'default' : 'pointer',
              background: '#fff',
            }}
          >
            {uploading ? 'Uploading…' : 'Upload image'}
            <input
              type="file"
              accept="image/png,image/jpeg,image/gif,image/webp,image/avif"
              style={{ display: 'none' }}
              disabled={uploading}
              onChange={(e) => {
                const file = e.target.files?.[0]
                // Reset so re-picking the same file fires change again.
                e.target.value = ''
                handleUpload(file)
              }}
            />
          </label>
        ) : null}

        <button
          type="button"
          disabled={!ready || busy}
          onClick={() => {
            const doc = currentDocument()
            if (doc) onPreview?.(doc)
          }}
        >
          Preview
        </button>
        <button
          type="button"
          disabled={!ready || busy}
          onClick={() => {
            const doc = currentDocument()
            if (doc) onSave?.(doc)
          }}
        >
          {busy ? 'Saving…' : 'Save page'}
        </button>
      </div>

      <div ref={hostRef} className="landing-builder__canvas" />
      {!ready && !error ? <p className="mds-note">Loading the visual builder…</p> : null}

      {versions.length > 0 ? (
        <details style={{ marginTop: 12 }}>
          <summary style={{ cursor: 'pointer' }}>Version history ({versions.length})</summary>
          <ul style={{ listStyle: 'none', padding: 0, margin: '8px 0 0' }}>
            {versions.map((v) => (
              <li
                key={v.id ?? v.versionNo}
                style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '4px 0' }}
              >
                <strong style={{ minWidth: 40 }}>v{v.versionNo}</strong>
                <span style={{ flex: 1 }}>{v.name}</span>
                <span style={{ color: '#64748b', fontSize: '.8rem' }}>{v.stage}</span>
                <button type="button" disabled={busy} onClick={() => onRestore?.(v.versionNo)}>
                  Restore
                </button>
              </li>
            ))}
          </ul>
          <p className="mds-note" style={{ marginTop: 4 }}>
            Restoring brings a version back as a new draft. Nothing in the history is erased.
          </p>
        </details>
      ) : null}
    </div>
  )
}

export default LandingBuilder
