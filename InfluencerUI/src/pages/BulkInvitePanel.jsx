import { useMemo, useRef, useState } from 'react'
import { MdsNote } from '../components/Mds'
import { Badge, BulkActionBar, DataTable, EmptyState } from '../components/ui'
import { exportCsv } from '../api/csv'
import { parseCsv } from '../constants'
import {
  MAX_BULK_INVITE,
  buildInviteRows,
  describeBatch,
  describeOutcome,
  formatBatchSummary,
  parseEmailList,
  rowsFromCsv,
  summarizeResult,
} from '../shell/bulkInvite'

/** The template column order, reused for the download so an exported file can be re-uploaded. */
const TEMPLATE_COLUMNS = [
  { key: 'email', header: 'email' },
  { key: 'role', header: 'role' },
  { key: 'brandId', header: 'brandId' },
]

/**
 * Inviting a whole team at once.
 *
 * <p><b>Everything is previewed before anything is sent.</b> The server refuses an over-capacity
 * batch whole rather than filling it partway, which is the right call — a half-applied upload
 * cannot be reasoned about — but it makes an unpreviewed submit an expensive way to discover a
 * problem. So the seat count, the duplicates, and the unreadable rows are all on screen first, and
 * the send button is disabled until the batch would actually be accepted.
 *
 * <p><b>Row selection is the escape hatch.</b> When a batch is over capacity, unticking rows is
 * what turns a hard refusal into a successful send — without the server ever having to choose which
 * rows to drop, which it could only do by position in a file.
 *
 * <p>Prop-driven like its parent: no API calls live here.
 */
function BulkInvitePanel({
  roles = [],
  defaultRole = 'MARKETER',
  remainingSeats = null,
  busy = false,
  onBulkInvite,
  onDone,
}) {
  const [mode, setMode] = useState('paste')
  const [pasted, setPasted] = useState('')
  const [fileRows, setFileRows] = useState(null)
  const [fileName, setFileName] = useState('')
  const [fileError, setFileError] = useState('')
  const [batchRole, setBatchRole] = useState(defaultRole)
  // Per-row role overrides, keyed by position in the input. Held apart from the parsed rows so that
  // editing a role does not mean re-parsing the file, and so a freshly pasted list cannot silently
  // inherit edits made to the previous one.
  const [roleOverrides, setRoleOverrides] = useState({})
  const [excluded, setExcluded] = useState(() => new Set())
  const [result, setResult] = useState(null)
  const [sending, setSending] = useState(false)
  const [dragging, setDragging] = useState(false)
  const fileInputRef = useRef(null)

  const rawRows = useMemo(() => {
    const source = mode === 'file'
      ? (fileRows || [])
      : parseEmailList(pasted).map((email) => ({ email }))
    return source.map((row, position) => (roleOverrides[position]
      ? { ...row, role: roleOverrides[position] }
      : row))
  }, [mode, pasted, fileRows, roleOverrides])

  const preview = useMemo(
    () => buildInviteRows(rawRows, { defaultRole: batchRole, roles }),
    [rawRows, batchRole, roles],
  )

  // Selection is stored as exclusions rather than inclusions so a newly pasted address arrives
  // ticked. Storing the positive set would mean every edit silently deselects everything added
  // since the last time the user touched a checkbox.
  const selectedKeys = useMemo(
    () => new Set(preview.rows.filter((row) => !excluded.has(row.index)).map((row) => row.index)),
    [preview.rows, excluded],
  )

  const selectedPreview = useMemo(() => ({
    ...preview,
    rows: preview.rows.filter((row) => selectedKeys.has(row.index)),
    invalid: preview.invalid.filter((row) => selectedKeys.has(row.index)),
    sendable: preview.sendable.filter((row) => selectedKeys.has(row.index)),
  }), [preview, selectedKeys])

  const batch = describeBatch(selectedPreview, remainingSeats)

  const handleFiles = async (files) => {
    const file = Array.from(files || [])[0]
    if (!file) {
      return
    }
    setFileError('')
    try {
      const text = await file.text()
      const rows = rowsFromCsv(parseCsv(text))
      if (!rows.length) {
        setFileError('That file has no rows in it.')
        return
      }
      setFileRows(rows)
      setFileName(file.name)
      setExcluded(new Set())
      // A new file means new positions; keeping the old overrides would apply row 3's role to a
      // different person entirely.
      setRoleOverrides({})
      setResult(null)
    } catch (error) {
      setFileError(error?.message || 'That file could not be read.')
    }
  }

  const send = async () => {
    setSending(true)
    try {
      const payload = selectedPreview.sendable.map((row) => ({
        email: row.email,
        role: row.role,
        brandId: row.brandId,
      }))
      const outcome = await onBulkInvite(payload)
      setResult(outcome)
      // Cleared on success so the panel cannot be submitted twice — the second send would come
      // back entirely "already invited", which reads as a failure rather than as a no-op.
      setPasted('')
      setFileRows(null)
      setFileName('')
      setExcluded(new Set())
      setRoleOverrides({})
      if (onDone) {
        await onDone()
      }
    } catch (error) {
      setResult({
        requested: selectedPreview.sendable.length,
        invited: 0,
        skipped: 0,
        failed: 0,
        rows: [],
        error: error?.message || 'The invitations could not be sent.',
      })
    } finally {
      setSending(false)
    }
  }

  const downloadTemplate = () => {
    exportCsv({
      prefix: 'member-invite-template',
      columns: TEMPLATE_COLUMNS,
      rows: [{ email: 'teammate@agency.com', role: defaultRole, brandId: '' }],
      source: 'members-bulk-invite-template',
    })
  }

  const downloadNotSent = () => {
    exportCsv({
      prefix: 'member-invites-not-sent',
      // Same column order as the template, so the downloaded file can be corrected and uploaded
      // again. A record that cannot be re-used is only half an affordance.
      columns: [...TEMPLATE_COLUMNS, { key: 'outcome', header: 'status' }, { key: 'reason', header: 'reason' }],
      rows: (result?.rows || []).filter((row) => row.outcome !== 'invited'),
      source: 'members-bulk-invite-failures',
    })
  }

  const previewColumns = [
    { key: 'email', header: 'Email' },
    {
      key: 'role',
      header: 'Role',
      render: (row) => (
        <select
          value={row.role}
          disabled={sending || busy}
          onChange={(event) => overrideRole(row.index, event.target.value)}
        >
          {roles.map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
      ),
    },
    {
      key: 'issue',
      header: 'Status',
      render: (row) => (row.issue
        ? <Badge tone={row.issue.kind === 'duplicate' ? 'neutral' : 'danger'}>{row.issue.message}</Badge>
        : <Badge tone="neutral">Ready</Badge>),
    },
  ]

  const overrideRole = (index, nextRole) => {
    const target = preview.rows.find((row) => row.index === index)
    if (target) {
      setRoleOverrides((current) => ({ ...current, [target.position]: nextRole }))
    }
  }

  const resultColumns = [
    { key: 'email', header: 'Email' },
    { key: 'role', header: 'Role' },
    {
      key: 'outcome',
      header: 'Result',
      render: (row) => {
        const described = describeOutcome(row.outcome)
        return <Badge tone={described.tone}>{described.label}</Badge>
      },
    },
    { key: 'reason', header: 'Detail', render: (row) => row.reason || '—' },
  ]

  if (result) {
    return (
      <div className="bulk-invite">
        <h3>Results</h3>
        {result.error ? (
          <MdsNote className="auth-error-note">{result.error}</MdsNote>
        ) : (
          <>
            <p className="helper">{summarizeResult(result)}</p>
            {/* The whole batch was created but nobody was told. Saying "invitations sent" here
                would leave an admin waiting for replies that cannot come. */}
            {result.invited > 0 && !result.emailDelivered ? (
              <MdsNote className="members-token-note">
                <strong>No emails were sent.</strong>
                Email delivery is not configured on this environment. Each invitation is valid for 7
                days but nobody has been notified — use <em>Resend</em> on each pending invitation
                below to get a link you can send yourself.
              </MdsNote>
            ) : null}
            <DataTable
              columns={resultColumns}
              rows={result.rows || []}
              rowKey={(row) => row.index}
              caption="Bulk invitation results"
              emptyState={<EmptyState title="Nothing to show" description="No rows were processed." />}
            />
          </>
        )}
        <div className="bulk-invite-actions">
          {(result.rows || []).some((row) => row.outcome !== 'invited') ? (
            <button type="button" className="ghost-btn" onClick={downloadNotSent}>
              Download rows that were not sent
            </button>
          ) : null}
          <button type="button" className="primary-btn" onClick={() => setResult(null)}>
            Invite more people
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="bulk-invite">
      <h3>Invite several people at once</h3>

      <div className="bulk-invite-modes" role="tablist" aria-label="How to add addresses">
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'paste'}
          className={mode === 'paste' ? 'primary-btn' : 'ghost-btn'}
          onClick={() => setMode('paste')}
        >
          Paste emails
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={mode === 'file'}
          className={mode === 'file' ? 'primary-btn' : 'ghost-btn'}
          onClick={() => setMode('file')}
        >
          Upload a file
        </button>
      </div>

      {mode === 'paste' ? (
        <label>
          <span className="auth-label">Email addresses</span>
          <textarea
            className="bulk-invite-textarea"
            rows={5}
            value={pasted}
            placeholder="ana@agency.com, bo@agency.com&#10;chris@agency.com"
            onChange={(event) => setPasted(event.target.value)}
          />
        </label>
      ) : (
        <>
          <div
            className={`file-drop${dragging ? ' is-dragging' : ''}`}
            onDragOver={(event) => { event.preventDefault(); setDragging(true) }}
            onDragLeave={() => setDragging(false)}
            onDrop={(event) => {
              event.preventDefault()
              setDragging(false)
              handleFiles(event.dataTransfer?.files)
            }}
            onClick={() => fileInputRef.current?.click()}
          >
            <p>{fileName || 'Drop a CSV here, or click to choose one.'}</p>
            <p className="helper">
              One address per line. A <code>role</code> column is optional — anything without one
              uses the role chosen below.
            </p>
            <input
              ref={fileInputRef}
              type="file"
              accept=".csv"
              onChange={(event) => handleFiles(event.target.files)}
            />
          </div>
          {fileError ? <MdsNote className="auth-error-note">{fileError}</MdsNote> : null}
          <button type="button" className="ghost-btn" onClick={downloadTemplate}>
            Download a template
          </button>
        </>
      )}

      <label>
        <span className="auth-label">Role for anyone without one</span>
        <select value={batchRole} onChange={(event) => setBatchRole(event.target.value)}>
          {roles.map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
      </label>

      {preview.rows.length ? (
        <>
          <div className="bulk-invite-summary">
            <Badge tone={batch.overCapacity ? 'danger' : 'neutral'}>
              {formatBatchSummary(batch)}
            </Badge>
            {preview.duplicates.length ? (
              <span className="helper">{preview.duplicates.length} duplicate rows will be skipped.</span>
            ) : null}
          </div>

          <BulkActionBar
            count={selectedKeys.size}
            onClear={() => setExcluded(new Set(preview.rows.map((row) => row.index)))}
          />

          <DataTable
            columns={previewColumns}
            rows={preview.rows}
            // Keyed on index, never on email: two rows can hold the same address, and a key
            // collision would fold them into one row that cannot be ticked independently.
            rowKey={(row) => row.index}
            caption="Invitations to send"
            selectedKeys={selectedKeys}
            onSelectionChange={(next) => {
              setExcluded(new Set(preview.rows
                .map((row) => row.index)
                .filter((index) => !next.has(index))))
            }}
            selectionLabel={(row) => `Include ${row.email}`}
          />

          {batch.blocker ? <p className="helper bulk-invite-blocker">{batch.blocker}</p> : null}

          <div className="bulk-invite-actions">
            {/* Disabled rather than left to fail, for the same reason the single invite form is:
                the server answers 402 either way, and a send that could never succeed spends the
                user's attention for nothing. Unticking rows is the way out, and it is right here. */}
            <button
              type="button"
              className="primary-btn"
              disabled={!batch.canSend || sending || busy}
              onClick={send}
            >
              {sending ? 'Sending…' : `Send ${batch.needed} ${batch.needed === 1 ? 'invitation' : 'invitations'}`}
            </button>
          </div>
        </>
      ) : (
        <p className="helper">
          Up to {MAX_BULK_INVITE} at a time. Nothing is sent until you have seen the list.
        </p>
      )}
    </div>
  )
}

export default BulkInvitePanel
