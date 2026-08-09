/**
 * The people these journeys are about, and the helpers every journey shares.
 *
 * <p>Personas rather than "user1/user2" because the point of a recorded journey is that someone
 * can watch it and recognise their own job. A test named `agencyOwner` that creates two client
 * brands says what the product is for; `testUserB` says nothing.
 */

/** Password used by every generated account. Meets the server's policy; nothing here is real. */
export const PASSWORD = 'Password123!'

/**
 * A unique email per run.
 *
 * <p>These journeys write to a shared local database, so a fixed address would collide with the
 * previous run and fail on "email already registered" — a failure that looks like a product bug
 * and is not. The timestamp also makes it obvious in the database which rows a given run created.
 */
export function uniqueEmail(prefix) {
  const stamp = Date.now().toString(36)
  const noise = Math.random().toString(36).slice(2, 6)
  return `${prefix}-${stamp}${noise}@e2e.example`
}

export const PERSONAS = {
  /** Runs one brand alone. The free tier is built for exactly this person. */
  soloBrandOwner: {
    name: 'Ari Rivera',
    workspace: 'Rivera Coffee',
    accountType: 'brand',
    emailPrefix: 'solo-owner',
  },
  /** Manages several client brands from one login — what the agency tier exists for. */
  agencyOwner: {
    name: 'Morgan Vale',
    workspace: 'Northstar Agency',
    accountType: 'agency',
    emailPrefix: 'agency-owner',
  },
  /** Evaluating the product. Never signs in — the signed-out story matters as much. */
  prospect: {
    name: 'Visitor',
  },
}

/**
 * Signs a new persona up and waits for the workspace to be usable.
 *
 * <p>Waits on the navigation rail rather than a fixed timeout: signup does a round trip and then
 * loads the workspace, and a sleep long enough to be reliable is also long enough to pad every
 * video by several seconds.
 */
export async function signUp(page, persona, email) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Sign up' }).click()

  await page.fill('input[name="fullName"]', persona.name)
  if (persona.accountType) {
    // Clicked on the styled label, not the input. The input sits under its own <span>, so a
    // direct click is intercepted — which is not a bug, it is how the control is built, and the
    // label is what a real user clicks. Using { force: true } would have suppressed the
    // interception rather than reproducing the interaction, and would still pass if the label
    // ever stopped being wired to the input.
    await page
      .locator('.auth-accounttype-option')
      .filter({ has: page.locator(`input[value="${persona.accountType}"]`) })
      .click()

    // The class only lands once React has re-rendered from state, so this proves the click was
    // received rather than merely dispatched.
    await page.waitForSelector(
      `.auth-accounttype-option.selected input[value="${persona.accountType}"]`,
      { timeout: 10_000 },
    )
  }
  await page.fill('input[name="brand"]', persona.workspace)
  await page.fill('input[name="email"]', email)
  await page.fill('input[name="password"]', PASSWORD)

  await page.getByRole('button', { name: /Create workspace/i }).click()

  // The rail only renders inside the authenticated shell, so its presence IS "signed in".
  await page.waitForSelector('nav, [class*="rail"]', { timeout: 60_000 })
}

/**
 * Moves to a workspace section by its rail label.
 *
 * <p>By visible text rather than a URL: navigating with page.goto() would reload the SPA and prove
 * only that a route renders. Clicking proves the link a user actually uses is present, enabled,
 * and wired — and it is what makes the video look like someone using the product.
 */
export async function navigate(page, label) {
  await page.getByRole('link', { name: label, exact: false }).first().click()
  await page.waitForLoadState('networkidle')
}

/**
 * Holds the frame briefly so a viewer can register what just happened.
 *
 * <p>Video is the deliverable, and a correct run that flashes through six screens in two seconds
 * is not watchable. This is presentation, not synchronisation — every assertion still waits on a
 * real condition, never on this.
 */
export async function beat(page, ms = 900) {
  await page.waitForTimeout(ms)
}
