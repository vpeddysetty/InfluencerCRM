import { useEffect, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'
import { ConfirmDialog, PlanUsage } from '../components/ui'
import { describeUsage } from '../shell/plan'
import BulkInvitePanel from './BulkInvitePanel'

// OWNER is absent on purpose. Ownership carries billing and the right to delete the account,
// so transferring it should be a deliberate, separately-confirmed act rather than a dropdown
// selection — the server refuses it here too.
const INVITABLE_ROLES = [
  { value: 'ADMIN', label: 'Admin', hint: 'Full brand control plus member and brand administration.' },
  { value: 'MANAGER', label: 'Manager', hint: 'Full brand control and commission approval. Cannot create payouts.' },
  { value: 'MARKETER', label: 'Marketer', hint: 'Day-to-day campaign and creator work. No financial approval.' },
  { value: 'ANALYST', label: 'Analyst', hint: 'Read-only across the brands they can reach.' },
  { value: 'FINANCE', label: 'Finance', hint: 'Owns the payout chain. Cannot edit campaign or creator data.' },
]

/**
 * Builds the full accept link from a raw token.
 *
 * A bare token is not actionable — the invitee has to be told which page to paste it into, which
 * is the step that made the old flow feel improvised. Built from window.location so it is correct
 * on localhost, a preview deploy, and production without configuration.
 */
function inviteLinkFor(token) {
  const base = typeof window !== 'undefined' && window.location ? window.location.origin : ''
  return `${base}/accept-invitation?token=${encodeURIComponent(token)}`
}

function MembersPage({
  currentUserId = '',
  canManageMembers = false,
  onLoadMembers,
  onLoadInvitations,
  onLoadPlan,
  onInvite,
  onBulkInvite,
  onResendInvitation,
  onRevokeInvitation,
  onUpdateRole,
  onRemoveMember,
}) {
  const [members, setMembers] = useState([])
  const [invitations, setInvitations] = useState([])
  const [plan, setPlan] = useState(null)
  const [planError, setPlanError] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState('')
  const [feedback, setFeedback] = useState({ type: '', message: '' })
  const [pendingRemove, setPendingRemove] = useState(null)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('MARKETER')
  // Shown once, after inviting. There is no way to retrieve it later because only its hash is
  // stored, so the UI has to make copying it feel deliberate rather than incidental.
  const [issuedToken, setIssuedToken] = useState('')
  // Whether the server actually handed the invitation to a mail provider. With the log-only
  // provider it did not, and saying "sent" would leave the inviter waiting on a reply to an
  // email that was never delivered.
  const [emailDelivered, setEmailDelivered] = useState(false)

  const refresh = async () => {
    setLoading(true)
    try {
      // The plan read is allowed to fail on its own. Members are the point of this page, and
      // losing the whole list because a secondary panel could not load would be the worse
      // outcome — the server enforces the limits either way.
      const [memberRows, inviteRows, planPayload] = await Promise.all([
        onLoadMembers(),
        onLoadInvitations().catch(() => []),
        onLoadPlan ? onLoadPlan().catch(() => null) : Promise.resolve(null),
      ])
      setMembers(Array.isArray(memberRows) ? memberRows : [])
      setInvitations(Array.isArray(inviteRows) ? inviteRows : [])
      setPlan(planPayload)
      setPlanError(Boolean(onLoadPlan) && !planPayload)
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to load members.' })
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const invite = async (event) => {
    event.preventDefault()
    const trimmed = email.trim()
    if (!trimmed) {
      setFeedback({ type: 'error', message: 'Enter an email address to invite.' })
      return
    }
    setBusyId('invite')
    setFeedback({ type: '', message: '' })
    setIssuedToken('')
    try {
      const created = await onInvite({ email: trimmed, role })
      const delivered = Boolean(created?.emailDelivered)
      setEmailDelivered(delivered)
      // The token is only worth showing when nothing was actually sent. When the email went out,
      // putting a one-time credential on screen invites copying it into a second channel for no
      // reason.
      setIssuedToken(delivered ? '' : created?.token || '')
      setEmail('')
      setFeedback({
        type: 'success',
        message: delivered
          ? `Invitation emailed to ${trimmed}.`
          : `Invitation created for ${trimmed}.`,
      })
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to create the invitation.' })
    } finally {
      setBusyId('')
    }
  }

  /**
   * Issues a fresh link for an invitation that was never delivered.
   *
   * Reuses the same token note the single invite uses, so there is exactly one place in this page
   * that puts a credential on screen and exactly one set of warnings around it.
   */
  const resend = async (invitation) => {
    setBusyId(`resend-${invitation.id}`)
    setIssuedToken('')
    try {
      const resent = await onResendInvitation(invitation.id)
      const delivered = Boolean(resent?.emailDelivered)
      setEmailDelivered(delivered)
      setIssuedToken(delivered ? '' : resent?.token || '')
      setFeedback({
        type: 'success',
        message: delivered
          ? `A new invitation was emailed to ${invitation.email}.`
          : `A new link was created for ${invitation.email}. The previous one no longer works.`,
      })
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to resend the invitation.' })
    } finally {
      setBusyId('')
    }
  }

  const revoke = async (invitation) => {
    setBusyId(`revoke-${invitation.id}`)
    try {
      await onRevokeInvitation(invitation.id)
      setFeedback({ type: 'success', message: `Invitation to ${invitation.email} revoked.` })
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to revoke the invitation.' })
    } finally {
      setBusyId('')
    }
  }

  const changeRole = async (member, nextRole) => {
    setBusyId(`role-${member.userId}`)
    try {
      await onUpdateRole(member.userId, nextRole)
      setFeedback({ type: 'success', message: 'Role updated.' })
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to change the role.' })
    } finally {
      setBusyId('')
    }
  }

  const confirmRemove = async () => {
    const member = pendingRemove
    if (!member) {
      return
    }
    setBusyId(`remove-${member.userId}`)
    try {
      await onRemoveMember(member.userId)
      setFeedback({ type: 'success', message: 'Member removed.' })
      setPendingRemove(null)
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to remove the member.' })
      setPendingRemove(null)
    } finally {
      setBusyId('')
    }
  }

  const pending = invitations.filter((row) => row.status === 'pending')

  // The member row from the plan payload, if it loaded. The server counts pending invitations
  // against this limit too — an invitation is a seat already committed — so the count shown here
  // and the one enforced agree without the page having to add them up itself.
  const memberUsage = plan?.usage
    ? plan.usage.map(describeUsage).find((row) => row.resource === 'member')
    : null
  const atMemberLimit = Boolean(memberUsage?.atLimit)

  if (!canManageMembers) {
    return (
      <section className="page-section">
        <MdsKicker>Members</MdsKicker>
        <h2>Team access</h2>
        <MdsNote>
          Only account owners and admins can manage members.
        </MdsNote>
      </section>
    )
  }

  return (
    <section className="page-section">
      <MdsKicker>Members</MdsKicker>
      <h2>Team access</h2>
      <p className="helper">
        Invite people onto this account and choose what they can reach. Roles are per account —
        someone can own their own workspace and still be a marketer here.
      </p>

      {feedback.message ? (
        <MdsNote className={feedback.type === 'error' ? 'auth-error-note' : ''}>{feedback.message}</MdsNote>
      ) : null}

      <MdsSectionRule />

      <PlanUsage plan={plan?.plan} usage={plan?.usage} loading={loading} error={planError} />

      <MdsSectionRule />

      <form className="inline-form members-invite-form" onSubmit={invite}>
        <label>
          <span className="auth-label">Email</span>
          <input
            type="email"
            value={email}
            placeholder="teammate@agency.com"
            disabled={atMemberLimit}
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>
        <label>
          <span className="auth-label">Role</span>
          <select
            value={role}
            disabled={atMemberLimit}
            onChange={(event) => setRole(event.target.value)}
          >
            {INVITABLE_ROLES.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        {/* Disabled at the limit rather than left to fail: the server returns 402 either way, but
            a form that accepts an email, sends it, and then reports failure wastes the user's
            attention on a request that could never have succeeded. */}
        <button type="submit" className="primary-btn" disabled={busyId === 'invite' || atMemberLimit}>
          {busyId === 'invite' ? 'Inviting…' : 'Send invitation'}
        </button>
      </form>
      <p className="helper">
        {atMemberLimit
          ? 'Your plan has no seats left. Pending invitations count as seats, so revoking one below frees a seat immediately.'
          : INVITABLE_ROLES.find((r) => r.value === role)?.hint}
      </p>

      {issuedToken ? (
        <MdsNote className="members-token-note">
          <strong>No email was sent — send this link yourself.</strong>
          {/* The fallback, and labelled as one. Previously this was the ONLY outcome and read as
              though handing someone a bare token were the intended design. */}
          <code className="members-token">{inviteLinkFor(issuedToken)}</code>
          Email delivery is not configured on this environment, so nothing was sent. The link works
          once and expires in 7 days. It is stored only as a hash and cannot be shown again — if it
          is lost, invite them again.
        </MdsNote>
      ) : emailDelivered ? (
        <MdsNote className="members-token-note">
          <strong>Invitation emailed.</strong>
          The invitee has a link that works once and expires in 7 days. Nothing further to do —
          if it does not arrive, resend it below.
        </MdsNote>
      ) : null}

      {onBulkInvite ? (
        <>
          <MdsSectionRule />
          {/* Seats remaining rather than a boolean at-limit: a batch has to be sized against a
              number, and the server refuses an over-capacity batch whole rather than filling it
              partway. Null on an unlimited plan, which the panel renders as "unlimited" instead of
              a cap. */}
          <BulkInvitePanel
            roles={INVITABLE_ROLES.map((option) => option.value)}
            defaultRole="MARKETER"
            remainingSeats={memberUsage ? memberUsage.remaining : null}
            busy={Boolean(busyId)}
            onBulkInvite={onBulkInvite}
            onDone={refresh}
          />
        </>
      ) : null}

      <MdsSectionRule />

      <h3>Members ({members.length})</h3>
      {loading ? (
        <MdsNote>Loading…</MdsNote>
      ) : members.length === 0 ? (
        <MdsNote>No members yet.</MdsNote>
      ) : (
        <ul className="members-list">
          {members.map((member) => {
            const isSelf = member.userId === currentUserId
            return (
              <li key={member.userId} className="members-row">
                <div className="members-identity">
                  <strong>{member.email || member.userId}</strong>
                  {isSelf ? <span className="members-self"> (you)</span> : null}
                </div>
                <select
                  value={member.role}
                  // Changing your own role could demote the last owner and lock the account
                  // out of its own administration; the server refuses it too.
                  disabled={isSelf || busyId === `role-${member.userId}`}
                  onChange={(event) => changeRole(member, event.target.value)}
                >
                  <option value="OWNER">Owner</option>
                  {INVITABLE_ROLES.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
                <button
                  type="button"
                  className="ghost-btn"
                  disabled={isSelf || busyId === `remove-${member.userId}`}
                  onClick={() => setPendingRemove(member)}
                >
                  {busyId === `remove-${member.userId}` ? 'Removing…' : 'Remove'}
                </button>
              </li>
            )
          })}
        </ul>
      )}

      <MdsSectionRule />

      <h3>Pending invitations ({pending.length})</h3>
      {pending.length === 0 ? (
        <MdsNote>No invitations outstanding.</MdsNote>
      ) : (
        <ul className="members-list">
          {pending.map((invitation) => (
            <li key={invitation.id} className="members-row">
              <div className="members-identity">
                <strong>{invitation.email}</strong>
                <span className="members-meta"> · {invitation.role}</span>
              </div>
              <span className="members-meta">
                expires {new Date(invitation.expiresAt).toLocaleDateString()}
              </span>
              {onResendInvitation ? (
                // The only way back to a token. Only the hash is stored, so an invitation created
                // in bulk — where no tokens are returned at all — would otherwise be unrecoverable
                // and the sole remedy would be to revoke it and start over.
                <button
                  type="button"
                  className="ghost-btn"
                  disabled={busyId === `resend-${invitation.id}`}
                  onClick={() => resend(invitation)}
                >
                  {busyId === `resend-${invitation.id}` ? 'Resending…' : 'Resend'}
                </button>
              ) : null}
              <button
                type="button"
                className="ghost-btn"
                disabled={busyId === `revoke-${invitation.id}`}
                onClick={() => revoke(invitation)}
              >
                {busyId === `revoke-${invitation.id}` ? 'Revoking…' : 'Revoke'}
              </button>
            </li>
          ))}
        </ul>
      )}

      {pendingRemove ? (
        <ConfirmDialog
          title={`Remove ${pendingRemove.email || pendingRemove.name || 'this member'}?`}
          consequence="They lose access to this workspace immediately. Campaigns, creators, and boards they created stay in place."
          confirmLabel="Remove member"
          busy={busyId === `remove-${pendingRemove.userId}`}
          onConfirm={confirmRemove}
          onCancel={() => setPendingRemove(null)}
        />
      ) : null}
    </section>
  )
}

export default MembersPage
