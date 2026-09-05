import { chromium } from 'playwright';
(async () => {
  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  page.on('console', msg => console.log('console:', msg.type(), msg.text()));
  page.on('requestfailed', req => console.log('requestfailed:', req.url(), req.failure()?.errorText));
  page.on('response', async res => {
    if (res.status() >= 400) {
      console.log('response', res.status(), res.url());
    }
  });
  try {
    await page.goto('https://www.tejdux.com/', { waitUntil: 'domcontentloaded' });
    console.log('title', await page.title());
    await page.getByRole('button', { name: 'Sign up' }).click();
    await page.fill('input[name="fullName"]', 'Debug User');
    await page.locator('.auth-accounttype-option').filter({ has: page.locator('input[value="brand"]') }).click();
    await page.fill('input[name="brand"]', 'Debug Workspace');
    await page.fill('input[name="email"]', 'debug-' + Date.now() + '@e2e.example');
    await page.fill('input[name="password"]', 'Password123!');
    await page.getByRole('button', { name: /Create workspace/i }).click();
    await page.waitForTimeout(20000);
    console.log('current url:', page.url());
    console.log('body text snippet:', (await page.locator('body').innerText()).slice(0, 2000));
  } catch (e) {
    console.error('ERR', e);
  } finally {
    await page.screenshot({ path: 'debug-signup.png', fullPage: true });
    await browser.close();
  }
})();
