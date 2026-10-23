const {chromium,expect}=require('@playwright/test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1700,height:1100},locale:'zh-CN'}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const base=process.env.OPTILENS_TEST_URL||'http://127.0.0.1:8080';
  const ready=()=>expect(page.locator('#lab-status')).toContainText('Ready');
  const completed=()=>expect(page.locator('#lab-status')).toHaveText(/^Completed/,{timeout:40000});
  const draft=async()=>{await page.selectOption('#lab-mode','json');const result=JSON.parse(await page.inputValue('#lab-json'));await page.selectOption('#lab-mode','canvas');return result;};
  const apply=async value=>{await page.selectOption('#lab-mode','json');await page.fill('#lab-json',JSON.stringify(value));await page.click('#lab-json-apply');await page.selectOption('#lab-mode','canvas');};
  await page.goto(base+'/plan-lab');await ready();await expect(page.locator('#lab-run')).toBeEnabled();
  const original=await draft();assert.equal(original.inputMode,'plan');
  const historyBefore=await (await page.request.get(base+'/api/runs')).json();
  await page.click('#lab-validate');await expect(page.locator('#lab-status')).toContainText('Valid plan',{timeout:40000});
  assert.equal((await (await page.request.get(base+'/api/runs')).json()).length,historyBefore.length);
  await page.click('#lab-run');await completed();
  const exported=await (await page.request.get(base+await page.locator('#lab-download').getAttribute('href'))).json();
  assert.equal(exported.formatVersion,2);assert.equal(exported.input.inputMode,'plan');assert.ok(exported.stages[0].events.length>0);assert.ok(exported.editableResult.plan,JSON.stringify(exported.editableResult.reasons));
  await expect(page.locator('#lab-use-result')).toBeEnabled();await page.click('#lab-use-result');
  const continued=await draft();assert.deepEqual(continued.rules,[]);assert.equal(continued.scopeNodeId,null);assert.match(JSON.stringify(continued.plan),/200/);
  await page.click('#lab-undo');assert.deepEqual((await draft()).rules,original.rules);await page.click('#lab-redo');assert.deepEqual((await draft()).rules,[]);
  await page.click('#lab-new');
  // Structured field editing updates the expression AST, not a SQL string.
  await page.getByLabel('Output 1 argument 2 value',{exact:true}).fill('30');await page.getByLabel('Output 1 argument 2 value',{exact:true}).press('Tab');
  assert.equal((await draft()).plan.nodes.find(n=>n.op==='Project').attributes.projects[1].expr.args[1].value,'30');await page.click('#lab-undo');
  // Connect via real pointer dragging and preserve port order.
  await page.locator('[data-operator="Filter"]').click();await expect(page.locator('#lab-run')).toBeDisabled();
  await page.locator('[data-output="n2"]').dragTo(page.locator('[data-parent="n3"][data-slot="0"]'));
  await page.click('#lab-root');await expect(page.locator('#lab-run')).toBeEnabled();assert.equal((await draft()).plan.rootId,'n3');
  const beforePosition=(await draft()).plan.layout.n3;
  const node=page.locator('[data-node="n3"] rect'),box=await node.boundingBox();
  await page.mouse.move(box.x+80,box.y+35);await page.mouse.down();await page.mouse.move(box.x+190,box.y+100,{steps:8});await page.mouse.up();
  assert.notDeepEqual((await draft()).plan.layout.n3,beforePosition);await page.click('#lab-undo');assert.deepEqual((await draft()).plan.layout.n3,beforePosition);
  await page.click('#lab-copy');assert.equal((await draft()).plan.nodes.length,6);await page.click('#lab-undo');assert.equal((await draft()).plan.nodes.length,3);
  await page.click('#lab-delete');await expect(page.locator('#lab-run')).toBeEnabled();await page.click('#lab-undo');
  // Invalid JSON must not replace the working canvas.
  const saved=await draft();await page.selectOption('#lab-mode','json');await page.fill('#lab-json','{broken');await page.click('#lab-json-apply');await expect(page.locator('#lab-status')).toHaveClass(/error/);await page.selectOption('#lab-mode','canvas');assert.deepEqual((await draft()).plan,saved.plan);
  const dual={...structuredClone(original),sql:'SELECT 1'};await apply(dual);await expect(page.locator('#lab-status')).toContainText('must not also contain SQL');assert.deepEqual((await draft()).plan,saved.plan);
  const malformed=structuredClone(original);malformed.plan.nodes[1].attributes.projects[1].expr.args=null;await apply(malformed);await expect(page.locator('#lab-status')).toHaveClass(/error/);assert.deepEqual((await draft()).plan,saved.plan);
  const bad=structuredClone(original);bad.plan.nodes[1].attributes.projects[0].expr.index=999;await apply(bad);await page.click('#lab-validate');await expect(page.locator('#lab-status')).toContainText('Unknown field',{timeout:40000});await expect(page.locator('#lab-node-title')).toContainText('n2');
  // Optimize only one of two similar branches; then continue with the whole result.
  const ref=()=>({kind:'ref',input:0,index:0}),constant=()=>({kind:'literal',type:'INTEGER',value:1});
  const scan=id=>({id,op:'TableScan',inputs:[],attributes:{table:'customers'}});
  const project=(id,input)=>({id,op:'Project',inputs:[input],attributes:{projects:[{name:'x',expr:{kind:'call',op:'+',args:[ref(),constant()]}}]}});
  const scoped={...original,rules:['CoreRules.PROJECT_MERGE'],scopeNodeId:'l2',plan:{planVersion:1,rootId:'u',layout:{},nodes:[scan('ls'),project('l1','ls'),project('l2','l1'),scan('rs'),project('r1','rs'),project('r2','r1'),{id:'u',op:'Union',inputs:['l2','r2'],attributes:{all:true}}]}};
  await apply(scoped);await page.click('#lab-run');await completed();await page.selectOption('#lab-stage','0');await page.selectOption('#lab-view','scope');await expect(page.locator('#lab-result-scope')).toHaveText('Only the selected optimization subtree.');
  const scopeRun=await (await page.request.get(base+await page.locator('#lab-download').getAttribute('href'))).json();assert.equal(scopeRun.finalPlan.nodes.filter(n=>n.type==='LogicalProject').length,3);
  await page.selectOption('#lab-view','event:0');await expect(page.locator('#lab-result-scope')).toContainText('Local expressions');
  await page.click('#lab-use-result');await page.fill('#lab-rule-search','CoreRules.PROJECT_MERGE');await page.selectOption('#lab-rule-picker','CoreRules.PROJECT_MERGE');await page.click('#lab-add-rule');await page.click('#lab-run');await completed();
  await page.setInputFiles('#lab-file',{name:'scoped-run.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(scopeRun))});await expect(page.locator('#lab-status')).toContainText('Imported run');assert.equal((await draft()).scopeNodeId,'l2');
  // Reorder actual canvas inputs and rule stages, then restore the experiment.
  await page.locator('[data-node="u"] rect').click();await page.getByRole('button',{name:'Move input up',exact:true}).click();assert.deepEqual((await draft()).plan.nodes.find(n=>n.id==='u').inputs,['r2','l2']);await page.click('#lab-undo');
  await page.fill('#lab-rule-search','CoreRules.PROJECT_REMOVE');await page.selectOption('#lab-rule-picker','CoreRules.PROJECT_REMOVE');await page.click('#lab-add-rule');await page.getByRole('button',{name:'Move rule up',exact:true}).click();assert.deepEqual((await draft()).rules,['CoreRules.PROJECT_REMOVE','CoreRules.PROJECT_MERGE']);await page.click('#lab-undo');await page.click('#lab-undo');
  // Round-trip a standalone plan document, including editor layout.
  const downloadEvent=page.waitForEvent('download');await page.click('#lab-export');const download=await downloadEvent;const contents=await fs.readFile(await download.path(),'utf8');await page.click('#lab-new');await page.setInputFiles('#lab-file',{name:'plan.json',mimeType:'application/json',buffer:Buffer.from(contents)});await expect(page.locator('#lab-status')).toContainText('Plan imported');assert.deepEqual((await draft()).plan,JSON.parse(contents).plan);
  await page.goto(base+'/rules?rule=CoreRules.UNION_REMOVE');await expect(page.locator('#example-status')).toHaveText(/^Completed/,{timeout:40000});await page.click('#open-lab');await ready();await expect(page.locator('#lab-example')).toHaveValue('CoreRules.UNION_REMOVE');
  await page.click('#lab-run');await completed();await expect(page.locator('#lab-use-result')).toBeEnabled();
  // Unsupported live operators remain readable with an explicit explanation.
  const sqlLibrary=await (await page.request.get(base+'/api/rule-examples')).json();const correlate=sqlLibrary.examples.find(e=>e.id==='CoreRules.JOIN_TO_CORRELATE');const submitted=await (await page.request.post(base+'/api/runs',{data:correlate.input})).json();let readOnly;
  await expect.poll(async()=>{readOnly=await (await page.request.get(base+'/api/runs/'+submitted.id)).json();return readOnly.status;},{timeout:40000}).toBe('succeeded');
  await page.goto(base+'/plan-lab?run='+submitted.id);await ready();await expect(page.locator('#lab-use-result')).toBeDisabled();await expect(page.locator('#lab-editable-note')).toContainText('Correlate');
  await page.click('#lab-new');await page.click('#lab-run');await completed();
  if(process.env.OPTILENS_SCREENSHOT_DIR){await fs.mkdir(process.env.OPTILENS_SCREENSHOT_DIR,{recursive:true});await page.screenshot({path:process.env.OPTILENS_SCREENSHOT_DIR+'/plan-lab-desktop.png',fullPage:true});}
  await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  if(process.env.OPTILENS_SCREENSHOT_DIR)await page.screenshot({path:process.env.OPTILENS_SCREENSHOT_DIR+'/plan-lab-mobile.png',fullPage:true});
  assert.doesNotMatch(await page.locator('body').textContent(),/\p{Script=Han}/u);assert.deepEqual(errors,[]);
  console.log('Plan Lab browser checks passed: typed expressions, drag connections, layout, undo/redo, subtree isolation, live events, editable continuation, JSON preservation, validation errors, imports and unsupported results.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
