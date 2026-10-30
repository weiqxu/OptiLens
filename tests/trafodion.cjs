const { chromium, expect } = require('@playwright/test');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const base = process.env.OPTILENS_TEST_URL || 'http://127.0.0.1:8080';
    const page = await browser.newPage({ viewport: { width: 1500, height: 1050 } });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    const catalog = await (await page.request.get(base + '/api/catalog?database=trafodion')).json();
    assert.equal(catalog.runtime.available, true, catalog.runtime.message);
    await page.goto(base + '/?database=trafodion');
    await expect(page.locator('#database')).toHaveValue('trafodion');
    await expect(page.locator('#run')).toBeEnabled();
    await page.click('#run');
    await expect(page.locator('#status')).toContainText('Completed', { timeout: 40000 });
    await expect(page.locator('#limitations')).toContainText('complete native');
    assert.ok(await page.locator('#before svg').count());
    assert.ok(await page.locator('#after svg').count());
    await page.goto(base + '/rules?database=trafodion&rule=Trafodion.HashJoinRule');
    await expect(page.locator('#example-status')).toContainText('Completed', { timeout: 40000 });
    await expect(page.locator('#example-status')).toContainText('candidate');
    await expect(page.locator('#detail-source')).toHaveAttribute(
      'href',
      /github\.com\/weiqxu\/Trafodion-Lite\/blob\/.*#L/,
    );
    await expect(page.locator('#example-settings')).toContainText('Full native SQL optimization');
    await page.goto(base + '/plan-lab?database=trafodion');
    await expect(page.locator('#lab-database')).toHaveValue('trafodion');
    await expect(page.locator('#lab-run')).toBeEnabled();
    await page.click('#lab-validate');
    await expect(page.locator('#lab-status')).toContainText('Valid', { timeout: 40000 });
    await page.click('#lab-run');
    await expect(page.locator('#lab-native-experiment')).toBeVisible({ timeout: 40000 });
    await expect(page.locator('#lab-native-choose')).toBeEnabled();
    const recordUrl = await page.locator('#lab-download').getAttribute('href');
    const record = await (await page.request.get(base + recordUrl)).json();
    assert.equal(record.formatVersion, 3);
    assert.equal(record.stages.length, 0, 'Generating candidates must not adopt one');
    const runId = recordUrl.split('/')[3];
    const candidateId = record.nativeTrace.candidates[0].id;
    const invalid = await page.request.post(base + '/api/experiments/step', {
      data: { runId, candidateId: 'forged', input: record.input },
    });
    assert.equal(invalid.status(), 400);
    const stale = structuredClone(record.input);
    stale.rules.reverse();
    stale.options.matchLimit++;
    const staleResponse = await page.request.post(base + '/api/experiments/step', {
      data: { runId, candidateId, input: stale },
    });
    assert.equal(staleResponse.status(), 400);
    const imported = await (await page.request.post(base + '/api/import', { data: record })).json();
    const importedResponse = await page.request.post(base + '/api/experiments/step', {
      data: { runId: imported.id, candidateId, input: record.input },
    });
    assert.equal(importedResponse.status(), 400);
    await page.click('#lab-native-preview');
    await page.click('#lab-native-choose');
    await expect(page.locator('#lab-native-status')).toContainText('Step 2', { timeout: 40000 });
    await expect(page.locator('#lab-native-choose')).toBeEnabled();
    await page.click('#lab-native-choose');
    await expect(page.locator('#lab-native-status')).toContainText('complete', { timeout: 40000 });
    await page.selectOption('#lab-database', 'calcite');
    await expect(page.locator('#lab-run')).toBeEnabled();
    assert.deepEqual(errors, []);
    console.log(
      'Trafodion Query lab, rule candidates/source links, and manual construction, candidate selection and fresh-process replay verified.',
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
