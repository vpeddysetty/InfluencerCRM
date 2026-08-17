/**
 * What a handle lookup contributes to the creator about to be saved.
 *
 * <p>Plain `.js` beside `provenance.js` and for the same reason: this decides which platform-reported
 * fields become columns on a creator row, and getting it wrong either drops a follower count or
 * detaches it from the stamp saying where it came from. The repo's test runner is bare `node --test`
 * with no JSX loader, so the rule has to live somewhere Node can import.
 */

/**
 * Fields carried from a resolved lookup onto the creator payload.
 *
 * <p>An allow-list rather than a spread of the whole response. `resolveHandle` also returns
 * `resolved`, `reason`, `handle` and `platform` — request bookkeeping and fields the form already
 * owns — and forwarding those would post columns the DAO does not have.
 *
 * <p>`metricsSource` and `metricsFetchedAt` are on this list deliberately and must stay: they are
 * what later renders as "Platform verified" or "Simulated" next to the numbers. A follower count
 * saved without its source is indistinguishable from a measured one forever after.
 */
export const CARRIED_FIELDS = Object.freeze([
  'followerCount',
  'engagementRate',
  'averageViews',
  'lastActiveAt',
  // Serialized on the way out, NOT passed through — see metricsFromLookup. `resolveHandle`
  // returns this as an object because a preview wants one, but `audience_demographics` is jsonb
  // mapped to a Java String on the DAO entity, so it must arrive as JSON text. Sending the object
  // fails the whole save with a 400 naming only "String from Object value".
  'audienceDemographics',
  'metricsPlatformVerified',
  'metricsSource',
  'metricsFetchedAt',
  // Classification travels too. It is derived from the same fetch, and the BFF stamps it with its
  // own `classificationSource` so a model's guess never reads as a platform's answer.
  'niche',
  'contentThemes',
  'contentCategories',
  'riskFlags',
  'safetyNotes',
])

/**
 * Reduce a lookup response to the fields worth saving.
 *
 * <p>Returns null for anything unresolved, so the caller stores nothing rather than storing blanks:
 * absent metrics and zeroed metrics are different claims, and a `followerCount: 0` written because
 * nobody could be found would pass every vetting rule expressed as `followers < 5000`.
 *
 * <p>Null and undefined members are skipped for the same reason — `demographics` is legitimately
 * null for a discovered creator, since the insights edge answers only for the connected account,
 * and writing that null would overwrite anything already known.
 */
export function metricsFromLookup(result) {
  if (!result || !result.resolved) {
    return null
  }
  const metrics = {}
  CARRIED_FIELDS.forEach((field) => {
    const value = result[field]
    if (value !== null && value !== undefined) {
      metrics[field] = value
    }
  })

  // jsonb-as-String: the DAO entity maps audience_demographics with @JdbcTypeCode(SqlTypes.JSON)
  // over a String field, so it expects the JSON as TEXT. The preview shape is an object, and
  // forwarding it rejects the entire creator with a 400 — the same trap CreatorOnboardingService
  // documents on its own persistence path, which this route bypasses by saving through /api/creators.
  //
  // Serialization is deliberately allowed to fail quietly: demographics are enrichment, and losing
  // them must not cost the creator. Dropping the key entirely is right — an unparseable string
  // written to a jsonb column would fail the save just as hard.
  if (metrics.audienceDemographics !== undefined && typeof metrics.audienceDemographics !== 'string') {
    try {
      metrics.audienceDemographics = JSON.stringify(metrics.audienceDemographics)
    } catch {
      delete metrics.audienceDemographics
    }
  }

  return metrics
}

/**
 * Whether a preview still describes what is in the handle field.
 *
 * <p>Editing the handle after a lookup makes those numbers someone else's. Comparison is trimmed and
 * case-insensitive with a leading `@` ignored, because `@Ari` and `ari` are the same account and
 * hiding a valid preview over punctuation would just make the button look broken.
 */
export function lookupMatchesHandle(lookupHandle, currentHandle) {
  const normalize = (value) => String(value || '').trim().replace(/^@/, '').toLowerCase()
  const looked = normalize(lookupHandle)
  return Boolean(looked) && looked === normalize(currentHandle)
}
