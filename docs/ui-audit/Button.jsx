import { joinClassNames } from './Primitives'

/**
 * The app's button.
 *
 * <p>Proposed for components/ui/. Today there is no Button component: pages write
 * `<button className="primary-btn" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>`,
 * which is 143 call sites each re-deciding what a busy button looks like and each
 * reflowing its own width when the label swaps mid-submit.
 *
 * <p>Three things this centralises that the raw element cannot:
 * <ul>
 *   <li><b>type="button" by default.</b> A bare <button> inside a form defaults to
 *       type="submit", so every non-submit button in a drawer form silently submits
 *       it. This is the single most common bug the component prevents.</li>
 *   <li><b>Loading is a state, not a label swap.</b> `loading` keeps the label for the
 *       accessible name, hides it visually, and overlays a spinner — the width never
 *       changes, and aria-busy announces the state rather than relying on a user
 *       noticing an ellipsis.</li>
 *   <li><b>Disabled and loading are the same guard.</b> A loading button that is not
 *       also disabled is a double-submit waiting to happen.</li>
 * </ul>
 */
function Button({
  tone = 'ghost',
  size = 'md',
  loading = false,
  disabled = false,
  block = false,
  iconOnly = false,
  type = 'button',
  className,
  children,
  ...rest
}) {
  const isDisabled = disabled || loading

  return (
    <button
      type={type}
      className={joinClassNames(
        'btn',
        `btn-${tone}`,
        size === 'sm' && 'btn-sm',
        block && 'btn-block',
        iconOnly && 'btn-icon',
        className,
      )}
      disabled={isDisabled}
      data-loading={loading ? 'true' : undefined}
      aria-busy={loading || undefined}
      {...rest}
    >
      {children}
    </button>
  )
}

/**
 * A link that looks like a button.
 *
 * <p>Separate from Button on purpose. The app currently styles anchors with
 * `.primary-btn` and patches the colour back in App.css:2721 because the button
 * colour rules do not apply to <a>. Navigation is an anchor — it must keep
 * middle-click, right-click-copy, and Ctrl+click. An action is a button. Choosing
 * between them by which one looks right is how you end up with a "Save" anchor that
 * a screen reader announces as a link to nowhere.
 *
 * <p>There is no `loading` prop: a navigation does not have a pending state, and
 * `disabled` does not exist on <a>. A disabled-looking link is `aria-disabled` plus a
 * removed href, which is almost always a sign the wrong element was chosen.
 */
function ButtonLink({ tone = 'ghost', size = 'md', block = false, className, children, ...rest }) {
  return (
    <a
      className={joinClassNames(
        'btn',
        `btn-${tone}`,
        size === 'sm' && 'btn-sm',
        block && 'btn-block',
        className,
      )}
      {...rest}
    >
      {children}
    </a>
  )
}

/**
 * Icon-only button.
 *
 * <p>Requires `label`. An icon button with no accessible name is a control that
 * screen readers announce as "button" — the existing `.icon-btn` call sites pass a
 * bare "×" as their whole content, which is announced as "times". Making the label a
 * required-by-convention prop is what stops that recurring.
 */
function IconButton({ label, tone = 'ghost', children, ...rest }) {
  return (
    <Button tone={tone} iconOnly aria-label={label} title={label} {...rest}>
      <span aria-hidden="true">{children}</span>
    </Button>
  )
}

export { Button, ButtonLink, IconButton }
export default Button
