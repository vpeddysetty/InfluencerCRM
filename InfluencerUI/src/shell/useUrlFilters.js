import { useCallback, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * Filter state that lives in the URL rather than in component state.
 *
 * <p>Filters used to be `useState` inside each page, which meant "Instagram creators sorted by
 * rate" could not be linked, bookmarked, or restored by the back button — every session rebuilt
 * it by hand. Saved views are the feature this unlocks: once a filter set is expressible as a
 * URL, saving one is storing a string.
 *
 * <p>Defaults are omitted from the query string, so an unfiltered page keeps a clean URL and two
 * users describing "the default view" produce the same link.
 *
 * @param {Record<string, string>} defaults key → default value
 * @returns {[Record<string, string>, (patch: Record<string, string>) => void, () => void]}
 */
export function useUrlFilters(defaults) {
  const [searchParams, setSearchParams] = useSearchParams()

  // Serialised rather than the object itself: `defaults` is almost always an inline literal at
  // the call site, so a new identity every render would defeat every memo downstream.
  const defaultsKey = JSON.stringify(defaults)

  const values = useMemo(() => {
    const parsed = JSON.parse(defaultsKey)
    const next = {}
    Object.keys(parsed).forEach((key) => {
      const fromUrl = searchParams.get(key)
      next[key] = fromUrl === null ? parsed[key] : fromUrl
    })
    return next
  }, [searchParams, defaultsKey])

  const setValues = useCallback(
    (patch) => {
      const parsed = JSON.parse(defaultsKey)
      setSearchParams(
        (current) => {
          const next = new URLSearchParams(current)
          Object.entries(patch).forEach(([key, value]) => {
            // A value equal to its default is dropped, not written. Otherwise clearing a filter
            // would leave `?platform=` behind and the "is anything filtered?" test would have to
            // know about empty strings.
            if (value === parsed[key] || value === '' || value === null || value === undefined) {
              next.delete(key)
            } else {
              next.set(key, String(value))
            }
          })
          return next
        },
        // replace: typing in a search box would otherwise push one history entry per keystroke,
        // and the back button would walk the user backwards through their own query letter by
        // letter instead of leaving the page.
        { replace: true },
      )
    },
    [setSearchParams, defaultsKey],
  )

  const clear = useCallback(() => {
    const parsed = JSON.parse(defaultsKey)
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        Object.keys(parsed).forEach((key) => next.delete(key))
        return next
      },
      { replace: true },
    )
  }, [setSearchParams, defaultsKey])

  return [values, setValues, clear]
}

export default useUrlFilters
