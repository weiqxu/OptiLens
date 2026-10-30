const { chromium, expect } = require('@playwright/test');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const base = process.env.OPTILENS_TEST_URL || 'http://127.0.0.1:8080';
    const page = await browser.newPage();
    const library = await (
      await page.request.get(base + '/api/rule-examples?database=calcite')
    ).json();
    const report = await (
      await page.request.get(base + '/api/examples/verification?database=calcite')
    ).json();
    assert.equal(report.cases.filter((c) => c.status === 'verified').length, 135);
    await page.goto(base + '/rules?database=calcite');
    await expect(page.locator('#catalog-status')).toContainText('135 verified examples');
    await page.check('#examples-only');
    await expect(page.locator('.rule-item:visible')).toHaveCount(135);

    let status = 'unverified';
    await page.route('**/api/rule-examples?database=calcite', (route) =>
      route.fulfill({
        json: {
          ...library,
          coverage: library.coverage.map((c) => ({
            ...c,
            status,
            reason: 'Report requires verification.',
          })),
        },
      }),
    );
    await page.reload();
    const target = page.locator('[data-rule="CoreRules.PROJECT_REDUCE_EXPRESSIONS"]');
    await expect(target).toContainText('Example · unverified');
    await expect(page.locator('#catalog-status')).toContainText('0 verified examples');
    await expect(page.locator('#example-status')).toContainText('Completed', { timeout: 40000 });
    await page.check('#examples-only');
    await expect(page.locator('.rule-item:visible')).toHaveCount(0);
    await expect(page.locator('#list-empty')).toBeVisible();

    status = 'stale';
    await page.reload();
    await expect(target).toContainText('Example · stale');
    await expect(page.locator('#catalog-status')).toContainText('0 verified examples');
    console.log(
      'Report-backed badges/counts, unverified example execution and stale-report filtering passed.',
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
