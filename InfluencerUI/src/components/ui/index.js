/**
 * The shell's shared UI layer.
 *
 * <p>Every page imports its primitives from here rather than re-implementing them. The four
 * `Mds*` wrappers were the whole component library before this, so each page hand-rolled its
 * own drawer, its own feedback line, and its own idea of what a list row looks like — and each
 * fix had to be made once per page. Building the drawer once is what makes the accessibility
 * work in findings 03 and 07 a single change instead of seven.
 *
 * <p>Next step is extracting this directory to `@influencer/ui` and federating it as a shared
 * singleton, the way React already is, so the six remotes stop depending on the shell's
 * stylesheet to look right. Keeping the imports pointed at one barrel now is what makes that
 * a package move rather than a rewrite.
 */
export { default as Drawer } from './Drawer'
export { default as ConfirmDialog } from './ConfirmDialog'
export { default as SessionExpiredDialog } from './SessionExpiredDialog'
export { default as WorkspaceOnboardingDialog } from './WorkspaceOnboardingDialog'
export { default as DataTable } from './DataTable'
export { default as PageHeader } from './PageHeader'
export { default as ThemeToggle } from './ThemeToggle'
export { ToastProvider, useToast } from './ToastProvider'
export { Field, Badge, EmptyState, FilterBar, BulkActionBar } from './Primitives'
export {
  MetricsProvenance,
  MetricsSourceBadge,
  formatFetchedAt,
  isPlatformVerified,
} from './MetricsProvenance'
export { default as PlanUsage } from './PlanUsage'
