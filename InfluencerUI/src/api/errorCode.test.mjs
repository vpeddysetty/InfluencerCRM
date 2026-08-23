import test from 'node:test'
import assert from 'node:assert/strict'

/**
 * The server's errorCode has to survive being thrown.
 *
 * readResponse used to flatten every failure into `new Error(message)`, so a caller could only
 * tell one failure from another by matching prose. That is fine for displaying a message and
 * useless for branching: a sign-in refused because the address is unconfirmed needs a resend
 * button, and a sign-in refused because the password is wrong must not offer one.
 *
 * The message is also the wrong thing to match on here, because both cases are deliberately
 * vague — "Invalid credentials" must not reveal whether an address is registered.
 */

// Mirrors readResponse's failure path in src/api/core.js.
function throwFromResponse(status, data) {
  const message = data?.message || data?.error || `Request failed with status ${status}`
  const error = new Error(message)
  error.errorCode = data?.errorCode || null
  error.status = status
  error.details = data?.details || null
  throw error
}

test('an EMAIL_NOT_VERIFIED refusal carries its code and the address', () => {
  try {
    throwFromResponse(403, {
      errorCode: 'EMAIL_NOT_VERIFIED',
      message: 'Confirm your email address to finish signing in.',
      details: { email: 'a+tag@example.com' },
    })
    assert.fail('should have thrown')
  } catch (error) {
    assert.equal(error.errorCode, 'EMAIL_NOT_VERIFIED')
    assert.equal(error.status, 403)
    // Carried so the resend can name the address without the client having to guess which of
    // several typed values the server actually matched.
    assert.equal(error.details.email, 'a+tag@example.com')
  }
})

test('an ordinary bad password carries no code, so it cannot offer a resend', () => {
  try {
    throwFromResponse(400, { errorCode: 'BAD_REQUEST', message: 'Invalid credentials' })
    assert.fail('should have thrown')
  } catch (error) {
    assert.notEqual(error.errorCode, 'EMAIL_NOT_VERIFIED')
    assert.equal(error.message, 'Invalid credentials')
  }
})

test('a body with no errorCode still throws a usable Error', () => {
  try {
    throwFromResponse(500, null)
    assert.fail('should have thrown')
  } catch (error) {
    assert.ok(error instanceof Error)
    assert.equal(error.errorCode, null)
    assert.match(error.message, /500/)
  }
})

test('the two sign-in failures are distinguishable by code and not by message', () => {
  // The point of the whole change. Both messages are deliberately unhelpful - one must not reveal
  // whether an address is registered - so the code is the only safe thing to branch on.
  const codes = []
  for (const body of [
    { errorCode: 'EMAIL_NOT_VERIFIED', message: 'Confirm your email address to finish signing in.' },
    { errorCode: 'BAD_REQUEST', message: 'Invalid credentials' },
  ]) {
    try {
      throwFromResponse(body.errorCode === 'EMAIL_NOT_VERIFIED' ? 403 : 400, body)
    } catch (error) {
      codes.push(error.errorCode)
    }
  }
  assert.deepEqual(codes, ['EMAIL_NOT_VERIFIED', 'BAD_REQUEST'])
})
