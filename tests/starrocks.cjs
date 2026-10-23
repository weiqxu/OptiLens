const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const {expect}=require('@playwright/test');
(async()=>{
 const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1500,height:1000}});const failures=[];page.on('pageerror',e=>failures.push(e.message));
 const base=process.env.OPTILENS_TEST_URL;
 try {
  await page.goto(base+'/rules?database=starrocks&rule=StarRocks.MergeTwoFiltersRule');
  await expect(page.locator('#explorer-database')).toHaveValue('starrocks');
  await expect(page.locator('#detail-id')).toHaveText('StarRocks.MergeTwoFiltersRule');
  await expect(page.locator('#example-status')).toContainText('Completed',{timeout:60000});
  await expect(page.locator('#example-status')).toContainText('1 transformation');
  assert.ok((await page.locator('#open-lab').getAttribute('href')).includes('database=starrocks'));
  assert.equal(await page.locator('#verification-link').isVisible(),true);
  await page.click('#open-lab');
  await expect(page.locator('#lab-database')).toHaveValue('starrocks');
  assert.equal(await page.locator('[data-operator="Calc"]').count(),0);
  await page.click('#lab-validate');await expect(page.locator('#lab-status')).toContainText('Valid plan',{timeout:60000});
  await page.click('#lab-run');await expect(page.locator('#lab-status')).toContainText('Completed',{timeout:60000});
  await expect(page.locator('#lab-result-note')).toContainText('starrocks');
  await expect(page.locator('#lab-editable-note')).toContainText('Read-only');
  await page.selectOption('#lab-database','calcite');await expect(page.locator('[data-operator="Calc"]')).toBeVisible();
  await page.selectOption('#lab-database','starrocks');await expect(page.locator('#lab-database')).toHaveValue('starrocks');
  await page.goto(base+'/?database=starrocks');await expect(page.locator('#database')).toHaveValue('starrocks');
  await page.click('#rule-clear');await page.fill('#sql','SELECT id FROM customers WHERE id > 1');
  await page.locator('input[value="StarRocks.FilterImplementationRule"]').check();await page.click('#run');
  await expect(page.locator('#status')).toContainText('Completed',{timeout:60000});
  await expect(page.locator('#provenance')).toContainText('starrocks');await expect(page.locator('#raw-after')).toContainText('PHYSICAL_FILTER');
  await page.selectOption('#database','calcite');await expect(page.locator('input[value="CoreRules.PROJECT_REDUCE_EXPRESSIONS"]')).toBeAttached();
  assert.deepEqual(failures,[]);console.log('StarRocks browser checks passed: engine switching, native rule example, validation, Plan Lab run, SQL run and physical output.');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
