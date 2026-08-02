function joinClassNames(...parts) {
  return parts.filter(Boolean).join(' ')
}

export function MdsKicker({ as = 'p', className = '', children }) {
  const Tag = as
  return <Tag className={joinClassNames('mds-kicker', className)}>{children}</Tag>
}

export function MdsSectionRule({ className = '' }) {
  return <div className={joinClassNames('mds-section-rule', className)} />
}

export function MdsNote({ className = '', children }) {
  return <p className={joinClassNames('mds-note', className)}>{children}</p>
}

export function MdsInlineCode({ className = '', children }) {
  return <span className={joinClassNames('mds-inline-code', className)}>{children}</span>
}
