/**
 * Placeholder shapes for content that has not arrived yet.
 *
 * <p><b>Why this exists.</b> Every page tracked its loading state already, but rendered it as a
 * text swap — `{loading ? 'Loading…' : 'Refresh'}`, or a one-line `<p>Loading…</p>` standing in
 * for a table. Swapping a short string for tall content reflows the page the moment the fetch
 * lands, which is the layout shift users experience as the page "jumping" out from under a click.
 * A skeleton reserves the height the real content will occupy, so nothing moves when it arrives.
 *
 * <p>Sized in `em` rather than px so a skeleton inherits the type scale of wherever it is dropped;
 * a placeholder for a heading should be heading-sized without the caller doing arithmetic.
 */

/**
 * One placeholder bar.
 *
 * <p>`aria-hidden` throughout: a skeleton is decoration, and a screen reader announcing six empty
 * boxes is worse than silence. The *region* announces loading instead — see SkeletonText — which
 * is one clear message rather than one per shape.
 */
export function Skeleton({ width = '100%', height = '1em', radius = 'var(--radius-sm)', className = '' }) {
  return (
    <span
      className={`skeleton ${className}`.trim()}
      style={{ width, height, borderRadius: radius }}
      aria-hidden="true"
    />
  )
}

/**
 * A block of placeholder lines.
 *
 * <p>The last line is shortened, because real paragraphs do not end flush and a stack of
 * equal-width bars reads as a loading *graphic* rather than as text about to appear.
 *
 * <p>This is the piece that talks to assistive tech. `role="status"` with `aria-busy` announces
 * "Loading" once for the whole region; `aria-live="polite"` means it waits for a pause rather
 * than interrupting. The visually-hidden label is what actually gets read — without it the
 * region is an empty announcement.
 */
export function SkeletonText({ lines = 3, label = 'Loading' }) {
  return (
    <div className="skeleton-text" role="status" aria-busy="true" aria-live="polite">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} width={index === lines - 1 ? '60%' : '100%'} />
      ))}
    </div>
  )
}

/**
 * Placeholder rows shaped like a table.
 *
 * <p>Takes the column count so the placeholder matches the table it stands in for. A generic
 * block of bars under a six-column header is its own kind of layout shift — the eye settles on
 * the wrong shape, then has to re-read when the real rows land.
 */
export function SkeletonTable({ rows = 5, columns = 4, label = 'Loading rows' }) {
  return (
    <div className="skeleton-table" role="status" aria-busy="true" aria-live="polite">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, rowIndex) => (
        <div key={rowIndex} className="skeleton-table-row">
          {Array.from({ length: columns }, (_, columnIndex) => (
            <Skeleton
              key={columnIndex}
              // The first column is the identifying one and is usually widest; the rest read as
              // values. Uniform widths look like a grid, not like a table.
              width={columnIndex === 0 ? '80%' : '55%'}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

export default Skeleton
