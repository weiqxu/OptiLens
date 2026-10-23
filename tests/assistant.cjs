const {chromium,expect}=require('@playwright/test');
const http=require('node:http'),assert=require('node:assert/strict'),fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path'),{spawn}=require('node:child_process');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 let app,browser;const data=await fs.mkdtemp(path.join(os.tmpdir(),'optilens-assistant-browser-'));
 const gateway=http.createServer(async(req,res)=>{
  let input='';for await(const chunk of req)input+=chunk;const body=JSON.parse(input);
  assert.equal(req.headers.authorization,'Bearer mock-only');
  res.writeHead(200,{'Content-Type':'text/event-stream'});
  const emit=(delta,finish=null)=>res.write('data: '+JSON.stringify({choices:[{delta,finish_reason:finish}]})+'\n\n');
  const finish=reason=>{emit({},reason);res.end('data: [DONE]\n\n');};
  if(body.messages.at(-1).role==='tool'){emit({content:'待验证建议：请检查预览。 Source analysis is not observed execution. <script>window.assistantInjected=true</script>'});finish('stop');return;}
  const message=body.messages.at(-1).content,ctx=JSON.parse(message.slice(message.indexOf('{'),message.lastIndexOf('\n\nQuestion:'))),question=message.split('\n\nQuestion:')[1];
  if(question.includes('slow')){emit({content:'Partial answer…'});const timer=setTimeout(()=>finish('stop'),30000);res.on('close',()=>clearTimeout(timer));return;}
  if(question.includes('selected result node'))assert.equal(ctx.selectedNode.kind,'result');
  const calls=[];const source=ctx.sourceExcerpts[0];if(source)calls.push(['cite_source',{id:source.id}]);
  const keys=Object.keys(ctx.nodeReferences);const reference=keys.find(k=>k.startsWith('run:')&&k.includes(':after:'))||keys[0];if(reference)calls.push(['cite_node',{id:reference}]);
  const candidate=structuredClone(ctx.draft||ctx.examples[0]?.input);
  if(candidate){
   if(candidate.inputMode==='plan'){
    const project=candidate.plan.nodes.find(n=>n.op==='Project');
    if(question.includes('invalid')&&project)project.attributes.projects[0].expr={kind:'ref',input:0,index:999};
    else if(project?.attributes.projects[1]?.expr?.args?.[1])project.attributes.projects[1].expr.args[1].value=30;
    else candidate.options={...candidate.options,matchLimit:2};
   }else candidate.sql='SELECT 42 AS answer';
   calls.push(['propose_input',{title:'Review this candidate',inputJson:JSON.stringify(candidate)}]);
  }
  emit({content:'Actual execution evidence is limited to the recorded events. '});
  calls.forEach(([name,args],index)=>{const encoded=JSON.stringify(args),split=Math.floor(encoded.length/2);emit({tool_calls:[{index,id:'call'+index,function:{name,arguments:encoded.slice(0,split)}}]});emit({tool_calls:[{index,function:{arguments:encoded.slice(split)}}]});});finish('tool_calls');
 });
 await new Promise(r=>gateway.listen(0,'127.0.0.1',r));
 try{
  app=spawn('java',['-jar','target/optilens.jar'],{env:{...process.env,OPTILENS_PORT:'0',OPTILENS_DATA_DIR:data,OPTILENS_ASSISTANT_ENDPOINT:`http://127.0.0.1:${gateway.address().port}/chat`,OPTILENS_ASSISTANT_MODEL:'mock-model',OPTILENS_ASSISTANT_API_KEY:'mock-only'},stdio:['ignore','pipe','pipe']});
  let logs='';app.stderr.on('data',b=>logs+=b);const base=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Server startup timed out: '+logs)),20000);app.stdout.on('data',b=>{const m=String(b).match(/http:\/\/127\.0\.0\.1:\d+/);if(m){clearTimeout(timer);resolve(m[0]);}});app.on('exit',code=>{clearTimeout(timer);reject(Error('Server exited '+code+': '+logs));});});
  browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:1700,height:1100}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const open=async()=>{await expect(page.locator('#assistant-open')).toBeVisible();await page.click('#assistant-open');await expect(page.locator('#assistant-send')).toBeEnabled();};
  const ask=async text=>{await page.fill('#assistant-question',text);await page.click('#assistant-send');await expect(page.locator('#assistant-status')).toContainText('Answer complete',{timeout:40000});};
  const last=()=>page.locator('.assistant-proposal').last();
  await page.goto(base+'/plan-lab');await expect(page.locator('#lab-status')).toContainText('Ready');await open();
  await ask('Explain this rule and propose a change.');await expect(last()).toContainText('Review this candidate');
  await expect(page.locator('#assistant-messages')).toContainText('待验证');assert.equal(await page.evaluate(()=>window.assistantInjected),undefined);
  await page.locator('#assistant-messages button').filter({hasText:/Draft ·/}).first().click();await expect(page.locator('.assistant-highlight')).toHaveCount(1);
  await last().getByRole('button',{name:'Validate suggestion',exact:true}).click();await expect(last()).toContainText('Plan builds successfully',{timeout:40000});await expect(last().getByRole('button',{name:'Apply suggestion',exact:true})).toBeEnabled();
  await last().getByRole('button',{name:'Apply suggestion',exact:true}).click();await expect(last()).toContainText('Applied to the editor');
  await page.getByRole('button',{name:'Close',exact:true}).click();await page.click('#lab-undo');await page.click('#lab-redo');await page.click('#lab-run');await expect(page.locator('#lab-status')).toContainText('Completed',{timeout:40000});
  await page.selectOption('#lab-stage','0');await page.selectOption('#lab-view','event:0');await open();await ask('Explain the actual event.');
  await page.selectOption('#lab-stage','overview');await page.locator('#assistant-messages button').filter({hasText:/after · stage 1 · event 1/}).last().click();await expect(page.locator('#lab-stage')).toHaveValue('0');await expect(page.locator('#lab-view')).toHaveValue('event:0');await expect(page.locator('#lab-after .assistant-highlight')).toHaveCount(1);await ask('Explain the selected result node.');
  // A changed draft cannot be overwritten by an earlier proposal.
  await page.click('#lab-new');await last().getByRole('button',{name:'Validate suggestion',exact:true}).click();await expect(last()).toContainText('input has changed');
  await ask('Produce an invalid field candidate.');await last().getByRole('button',{name:'Validate suggestion',exact:true}).click();await expect(last()).toContainText('Unknown field',{timeout:40000});await expect(last().getByRole('button',{name:'Apply suggestion',exact:true})).toBeDisabled();
  // Cancellation preserves partial text and frees the request.
  await page.fill('#assistant-question','slow');await page.click('#assistant-send');await expect(page.locator('#assistant-messages')).toContainText('Partial answer');await page.click('#assistant-stop');await expect(page.locator('#assistant-status')).toHaveText('Stopped.');
  await page.goto(base+'/');await expect(page.locator('#status')).toContainText('Ready');await open();await expect(page.locator('#assistant-messages')).toContainText('Partial answer');
  const original=await page.inputValue('#sql');await ask('Suggest SQL.');await last().getByRole('button',{name:'Validate suggestion',exact:true}).click();await expect(last().getByRole('button',{name:'Apply suggestion',exact:true})).toBeEnabled();await last().getByRole('button',{name:'Apply suggestion',exact:true}).click();await expect(page.locator('#sql')).toHaveValue('SELECT 42 AS answer');await page.click('#assistant-restore');await expect(page.locator('#sql')).toHaveValue(original);
  await page.goto(base+'/rules?rule=CoreRules.UNION_REMOVE');await expect(page.locator('#example-status')).toContainText('Completed',{timeout:40000});await open();await ask('Make an experiment for this rule.');await last().getByRole('button',{name:'Validate suggestion',exact:true}).click();await expect(last().getByRole('button',{name:'Open in Lab',exact:true})).toBeEnabled({timeout:40000});
  const historyBefore=await (await page.request.get(base+'/api/runs')).json();await last().getByRole('button',{name:'Open in Lab',exact:true}).click();await page.waitForURL('**/plan-lab?database=calcite');await expect(page.locator('#lab-status')).toContainText('Assistant suggestion applied',{timeout:40000});assert.equal((await (await page.request.get(base+'/api/runs')).json()).length,historyBefore.length);
  if(process.env.OPTILENS_STARROCKS_HOME){
   await page.selectOption('#lab-database','starrocks');await expect(page.locator('#lab-status')).toContainText('StarRocks');await open();await ask('Explain StarRocks and propose an input.');await expect(page.locator('#assistant-context')).toContainText('4.0.14');await last().getByRole('button',{name:'Validate suggestion',exact:true}).click();await expect(last().getByRole('button',{name:'Apply suggestion',exact:true})).toBeEnabled({timeout:40000});await last().getByRole('button',{name:'Apply suggestion',exact:true}).click();await page.getByRole('button',{name:'Close',exact:true}).click();await page.click('#lab-run');await expect(page.locator('#lab-status')).toContainText('Completed',{timeout:40000});
  }
  await page.setViewportSize({width:390,height:844});if(await page.locator('#assistant-open').isVisible())await open();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  if(process.env.OPTILENS_SCREENSHOT_DIR){await fs.mkdir(process.env.OPTILENS_SCREENSHOT_DIR,{recursive:true});await page.screenshot({path:process.env.OPTILENS_SCREENSHOT_DIR+'/assistant-mobile.png',fullPage:true});await page.setViewportSize({width:1700,height:1100});await page.screenshot({path:process.env.OPTILENS_SCREENSHOT_DIR+'/assistant-desktop.png',fullPage:true});}
  await page.click('#assistant-clear');await expect(page.locator('.assistant-message')).toHaveCount(0);assert.deepEqual(errors,[]);
  console.log('Assistant browser checks passed: streaming, Unicode, source/node references, signed proposals, worker validation, apply/undo/restore, stale drafts, cross-page handoff, cancellation and both engines.');
 }finally{if(browser)await browser.close();if(app){app.kill();await new Promise(r=>{app.once('exit',r);setTimeout(r,10000);});}gateway.closeAllConnections();await new Promise(r=>gateway.close(r));await fs.rm(data,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
