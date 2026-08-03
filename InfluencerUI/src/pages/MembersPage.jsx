import { useEffect, useState } from 'react'
import { MdsKicker, MdsSectionRule, MdsNote } from '../components/Mds'

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

function MembersPage({
  currentUserId = '',
  canManageMembers = false,
  onLoadMembers,
  onLoadInvitations,
  onInvite,
  onRevokeInvitation,
  onUpdateRole,
  onRemoveMember,
}) {
  const [members, setMembers] = useState([])
  const [invitations, setInvitations] = useState([])
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState('')
  const [feedback, setFeedback] = useState({ type: '', message: '' })
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('MARKETER')
  // Shown once, after inviting. There is no way to retrieve it later because only its hash is
  // stored, so the UI has to make copying it feel deliberate rather than incidental.
  const [issuedToken, setIssuedToken] = useState('')

  const refresh = async () => {
    setLoading(true)
    try {
      const [memberRows, inviteRows] = await Promise.all([
        onLoadMembers(),
        onLoadInvitations().catch(() => []),
      ])
      setMembers(Array.isArray(memberRows) ? memberRows : [])
      setInvitations(Array.isArray(inviteRows) ? inviteRows : [])
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
      setIssuedToken(created?.token || '')
      setEmail('')
      setFeedback({ type: 'success', message: `Invitation created for ${trimmed}.` })
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to create the invitation.' })
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

  const remove = async (member) => {
    if (!window.confirm('Remove this member from the account? They lose access immediately.')) {
      return
    }
    setBusyId(`remove-${member.userId}`)
    try {
      await onRemoveMember(member.userId)
      setFeedback({ type: 'success', message: 'Member removed.' })
      await refresh()
    } catch (error) {
      setFeedback({ type: 'error', message: error?.message || 'Unable to remove the member.' })
    } finally {
      setBusyId('')
    }
  }

  const pending = invitations.filter((row) => row.status === 'pending')

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

      <form className="inline-form members-invite-form" onSubmit={invite}>
        <label>
          <span className="auth-label">Email</span>
          <input
            type="email"
            value={email}
            placeholder="teammate@agency.com"
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>
        <label>
          <span className="auth-label">Role</span>
          <select value={role} onChange={(event) => setRole(event.target.value)}>
            {INVITABLE_ROLES.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <button type="submit" className="primary-btn" disabled={busyId === 'invite'}>
          {busyId === 'invite' ? 'Inviting…' : 'Send invitation'}
        </button>
      </form>
      <p className="helper">{INVITABLE_ROLES.find((r) => r.value === role)?.hint}</p>

      {issuedToken ? (
        <MdsNote className="members-token-note">
          <strong>Invitation link token — shown once.</strong>
          <code className="members-token">{issuedToken}</code>
          Send this to the invitee. It is stored only as a hash, so it cannot be shown again;
          if it is lost, invite them again.
        </MdsNote>
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
                  onClick={() => remove(member)}
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
    </section>
  )
}

export default MembersPage
