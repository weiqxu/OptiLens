const { chromium, expect } = require('@playwright/test');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    const base = process.env.OPTILENS_TEST_URL || 'http://127.0.0.1:8080';
    const response = await page.request.get(base + '/api/catalog');
    assert.equal(response.status(), 200);
    const catalog = await response.json();
    for (const [path, id] of [
      ['/', 'database'],
      ['/plan-lab', 'lab-database'],
      ['/rules', 'explorer-database'],
    ]) {
      await page.goto(base + path);
      await expect(page.locator('#' + id)).toBeEnabled();
      const select = page.locator('#' + id);
      await expect(select).toHaveValue('calcite');
      for (const database of catalog.databases) {
        const option = select.locator(`option[value="${database.id}"]`);
        if (database.available) {
          await expect(option).toHaveJSProperty('disabled', false);
          await expect(option).not.toContainText('Not installed');
        } else {
          await expect(option).toHaveJSProperty('disabled', true);
          await expect(option).toContainText('Not installed');
          assert.equal(
            await option.evaluate((e) => getComputedStyle(e).color),
            'rgb(101, 118, 131)',
          );
        }
      }
      if (catalog.databases.filter((d) => d.available).length === 1) {
        await select.focus();
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');
        await expect(select).toHaveValue('calcite');
      }
      if (path === '/rules')
        await expect(page.locator('#example-status')).toHaveText(/^Completed/, { timeout: 40000 });
    }
    const missing = catalog.databases.find((d) => d.id === 'starrocks' && !d.available);
    if (missing) {
      let submitted = 0;
      page.on('request', (r) => {
        if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/runs') submitted++;
      });
      await page.goto(base + '/?database=starrocks');
      await expect(page.locator('#database')).toBeEnabled();
      await expect(page.locator('#run')).toBeDisabled();
      await page.goto(base + '/plan-lab?database=starrocks');
      await expect(page.locator('#lab-database')).toBeEnabled();
      await expect(page.locator('#lab-run')).toBeDisabled();
      await expect(page.locator('#lab-validate')).toBeDisabled();
      await page.goto(base + '/rules?database=starrocks');
      await expect(page.locator('#explorer-database')).toBeEnabled();
      await expect(page.locator('#rerun-example')).toBeDisabled();
      await expect(page.locator('#example-status')).toContainText(missing.message);
      await page.waitForTimeout(350);
      assert.equal(submitted, 0, 'Unavailable runtimes must not submit example jobs.');
    }
    assert.deepEqual(errors, []);
    console.log('Database availability verified on all three pages.');
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
