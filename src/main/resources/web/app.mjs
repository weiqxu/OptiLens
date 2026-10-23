import {mountAssistant,highlightNode} from './assistant.mjs';
import {diffPlans,drawPlan} from './graph.mjs';
const $=id=>document.getElementById(id);
let catalog,current,selection={kind:'overview'},epoch=0,busy=false,ruleOrder=[];
let assistantSelectedResult=null,assistantRevision=0;
const zoom={before:1,after:1};
const counted=(count,singular,plural=singular+'s')=>`${count} ${count===1?singular:plural}`;
const text=(id,value)=>{$(id).textContent=value;};
const status=(message,error=false)=>{text('status',message);$('status').className='status'+(error?' error':busy?' busy':'');};
async function api(path,options={}){
  const response=await fetch(path,{...options,headers:{'Content-Type':'application/json',...options.headers}});
  const data=await response.json();if(!response.ok)throw new Error(data.error||`Request failed (${response.status})`);return data;
}
function button(label,handler,className=''){const b=document.createElement('button');b.type='button';b.textContent=label;b.className=className;b.addEventListener('click',handler);return b;}
function setBusy(value){busy=value;$('run').disabled=value||!catalog;$('import-button').disabled=value;$('history').disabled=value;for(const id of ['sql','schema','examples','database','rule-search','rule-category','rule-recommended','rule-clear','match-limit','expand-subqueries'])$(id).disabled=value;for(const input of $('rule-options').querySelectorAll('input'))input.disabled=value||input.dataset.runnable!=='true';}
function loadExample(example){assistantRevision++;
  if(example.input.inputMode==='plan'){location.href='/plan-lab?database='+example.input.database+'&rule='+encodeURIComponent(example.id);return;}
  $('sql').value=example.input.sql;$('schema').value=JSON.stringify(example.input.schema,null,2);text('example-note',example.note);
  selectRules(example.input);
}
async function applyRequest(input){assistantRevision++;if(catalog.database!==(input.database||'calcite'))await loadEngine(input.database||'calcite');$('sql').value=input.sql;$('schema').value=JSON.stringify(input.schema,null,2);selectRules(input);$('examples').value='';text('example-note','Loaded the SQL and schema from this record. Run again to collect new local evidence.');}
function selectRules(input){
  ruleOrder=[...input.rules];
  $('match-limit').value=input.options?.matchLimit??100;
  $('expand-subqueries').checked=input.options?.expandSubqueries!==false;
  $('database').value=input.database||'calcite';
  for(const c of $('rule-options').querySelectorAll('input'))c.checked=c.dataset.runnable==='true'&&input.rules.includes(c.value);
  updateRules();
}
function renderRules(){
  $('rule-options').replaceChildren();
  $('rule-search').value='';
  $('rule-category').replaceChildren(new Option('All categories',''),...Array.from(new Set(catalog.rules.map(r=>r.category)),c=>new Option(c,c)));
  text('rule-scope',catalog.scope);
  for(const rule of catalog.rules){
    const row=document.createElement('div');row.className='rule-row';row.dataset.category=rule.category;row.dataset.search=[rule.title,rule.description,...rule.implementations,rule.category].join(' ').toLowerCase();
    const label=document.createElement('label');label.className='rule-option';
    const input=document.createElement('input');input.type='checkbox';input.value=rule.id;input.dataset.runnable=String(rule.runnable);input.disabled=!rule.runnable;
    const info=document.createElement('span');const title=document.createElement('strong');title.textContent=rule.title;
    const description=document.createElement('small');description.textContent=rule.category+' · '+rule.description;
    const availability=document.createElement('small');availability.textContent=rule.runnable?'Available to run':rule.unavailableReason;availability.className=rule.runnable?'rule-available':'muted';
    info.append(title,description,availability);label.append(input,info);
    const source=document.createElement('a');source.href=rule.source;source.target='_blank';source.rel='noopener noreferrer';source.textContent='Source ↗';source.setAttribute('aria-label',rule.title+' source');
    row.append(label,source);$('rule-options').append(row);
  }
}
function updateRules(){
  const query=$('rule-search').value.trim().toLowerCase(),category=$('rule-category').value;
  let visible=0;
  for(const row of $('rule-options').children){row.hidden=!(row.dataset.search.includes(query)&&(!category||row.dataset.category===category));if(!row.hidden)visible++;}
  const checked=[...$('rule-options').querySelectorAll('input:checked')].map(input=>input.value);
  ruleOrder=ruleOrder.filter(id=>checked.includes(id));
  for(const id of checked)if(!ruleOrder.includes(id))ruleOrder.push(id);
  const selected=checked.length;
  $('rule-program').replaceChildren(...ruleOrder.map(id=>{const li=document.createElement('li');li.textContent=id;return li;}));
  const presets=catalog.rules.filter(r=>r.category==='Common rule groups').length;
  text('rule-count',`${catalog.rules.length-presets} built-in entries + ${presets} common groups · Selected ${selected} / ${catalog.maxSelected} · Showing ${counted(visible,'item')}`);
  $('rule-empty').hidden=visible!==0;
}
async function refreshHistory(){
  const list=await api('/api/runs');const select=$('history');select.replaceChildren(new Option('Select a previous run',''));
  for(const item of list)select.add(new Option(`${new Date(item.submittedAt).toLocaleString('en-US')} · ${item.imported?'Imported':({succeeded:'Completed',failed:'Failed',queued:'Queued',running:'Running'}[item.status]||item.status)}`,item.id));
  if(current)select.value=current.id;
}
function showJob(job){
  current=job;$('empty').hidden=true;$('workspace').hidden=false;
  const link=$('export');link.href=`/api/runs/${job.id}/download`;link.classList.remove('disabled');link.setAttribute('aria-disabled','false');
  selection={kind:'overview'};renderSteps();renderSelection();
  status(job.imported?'Imported record. Structure validated; provenance has not been verified by re-execution.':`Completed · ${counted(job.result.stages.reduce((n,s)=>n+s.events.length,0),'local transformation event')} · ${counted(job.result.stages.length,'rule stage')}`);
}
function selected(key){return JSON.stringify(selection)===JSON.stringify(key);}
function choose(key){selection=key;zoom.before=zoom.after=1;renderSteps();renderSelection();}
function renderSteps(){
  const nav=$('steps');nav.replaceChildren();
  function add(label,key,sub,cls){const b=button(label,()=>choose(key),cls);b.setAttribute('aria-pressed',selected(key));if(sub){const s=document.createElement('small');s.textContent=sub;b.append(s);}nav.append(b);}
  add('Initial → Final',{kind:'overview'},'Complete logical plan');
  current.result.stages.forEach((s,i)=>{
    add(`${i+1} · ${s.title}`,{kind:'stage',i},`${counted(s.attempts.length,'attempt')} · ${counted(s.events.length,'transformation')}`);
    s.events.forEach((e,j)=>add(`↳ ${j+1}. ${e.rule}`,{kind:'event',i,j},'Local expression','event-button'));
  });
}
function renderSelection(){
  if(!current)return;
  const run=current.result;
  let before=run.initial,after=run.finalPlan,title='Initial and final plans',description='Compare the overall result of this controlled optimization program. Stages can interact, so the overall difference cannot be attributed to one rule.',conditions='',source='',attempts=[],scope='Complete plan · Overview';
  if(selection.kind!=='overview'){
    const stage=run.stages[selection.i];({before,after,description,conditions,source,attempts}=stage);title=stage.title;scope='Complete plan · Stage boundary';
    if(selection.kind==='event'){const e=stage.events[selection.j];({before,after}=e);title=e.rule;scope='Local expression · Rule event';description=`Rule invocation #${e.callId} produced a changed expression. This snapshot is not a complete query plan; final candidate selection was not collected.`;}
    else if(!stage.events.length)description+=' No expression production events were recorded in this stage. Specific rejection reasons were not collected.';
  }
  text('scope',scope);text('selection-title',title);text('description',description);text('conditions',conditions);
  text('before-title',selection.kind==='overview'?'Initial plan (after SQL conversion)':'Before');text('after-title',selection.kind==='overview'?'Final logical plan':'After');
  text('provenance',current.imported?'Imported record · Provenance not verified by re-execution':`Real local ${run.engine} run · Stage order follows the selected rule program`);
  const link=$('source');link.hidden=!source;
  // Imported records cannot inject arbitrary link schemes or external destinations.
  if((source?.startsWith('https://github.com/apache/calcite/blob/')||source?.startsWith('https://github.com/StarRocks/starrocks/blob/')))link.href=source;else{link.removeAttribute('href');link.hidden=true;}
  const diff=diffPlans(before,after);
  text('change-title',diff.left.size||diff.right.size?`Changed nodes: before ${diff.left.size} · after ${diff.right.size}`:'No structural or attribute differences observed');
  assistantSelectedResult=null;const inspect=(node,mark,side)=>{assistantSelectedResult={kind:'result',side,nodeId:node.id};text('node-label',`${side==='before'?'Before':'After'} · ${node.type} #${node.id}`);text('node-content',JSON.stringify({difference:mark??'No marked difference',...node},null,2));$('node-details').open=true;};
  drawPlan($('before'),before,diff.left,'before',inspect,zoom.before);drawPlan($('after'),after,diff.right,'after',inspect,zoom.after);
  text('node-label','Select an operator in the graph');text('node-content','Select a node.');$('node-details').open=false;
  text('raw-before',before.raw);text('raw-after',after.raw);text('attempts',selection.kind==='overview'?'Select a rule stage to inspect its raw attempts.':JSON.stringify(attempts,null,2));
  $('limitations').replaceChildren(...run.limitations.map(s=>{const li=document.createElement('li');li.textContent=s;return li;}));
  text('metadata',JSON.stringify({id:current.id,engine:run.engine,engineVersion:run.engineVersion,createdAt:run.createdAt,formatVersion:run.formatVersion,input:run.input,provenance:current.imported?'imported-unverified':'local-execution'},null,2));
}
async function poll(id,token){
  for(;;){if(token!==epoch)return;const job=await api(`/api/runs/${id}`);if(token!==epoch)return;
    if(job.status==='succeeded'){setBusy(false);showJob(job);await refreshHistory();return;}
    if(job.status==='failed')throw new Error(job.error||'Optimization failed');
    status(job.status==='queued'?'Submitted. Waiting for an optimization worker…':`${catalog.database} is parsing and optimizing. The first run may take a few seconds…`);
    await new Promise(r=>setTimeout(r,500));
  }
}
$('run').addEventListener('click',async()=>{
  if(busy)return;setBusy(true);const token=++epoch;status('Submitting optimization job…');
  try{const schema=JSON.parse($('schema').value);const rules=[...ruleOrder];
    const options={matchLimit:Number($('match-limit').value),expandSubqueries:$('expand-subqueries').checked};
    const job=await api('/api/runs',{method:'POST',body:JSON.stringify({database:$('database').value,sql:$('sql').value,schema,rules,options})});await poll(job.id,token);
  }catch(e){if(token===epoch){setBusy(false);status(e.message+(current?' (The last successful result remains below.)':''),true);refreshHistory().catch(()=>{});}}
});
function inputChanged(){assistantRevision++;if(current)status('Inputs changed. The previous result is still shown below. Select Run optimization to generate a new result.');}
$('match-limit').addEventListener('input',inputChanged);$('expand-subqueries').addEventListener('change',inputChanged);
$('sql').addEventListener('input',inputChanged);$('schema').addEventListener('input',inputChanged);$('rule-options').addEventListener('change',e=>{
  if($('rule-options').querySelectorAll('input:checked').length>catalog.maxSelected){e.target.checked=false;status(`Select at most ${catalog.maxSelected} rules per run`,true);}
  else inputChanged();
  updateRules();
});
$('rule-search').addEventListener('input',updateRules);
$('rule-category').addEventListener('change',updateRules);
$('rule-recommended').addEventListener('click',()=>{selectRules(catalog.examples.find(e=>e.input.inputMode!=='plan')?.input||{database:catalog.database,rules:[]});inputChanged();});
$('rule-clear').addEventListener('click',()=>{selectRules({database:$('database').value,rules:[]});inputChanged();});
$('database').addEventListener('change',async()=>{setBusy(true);try{await loadEngine($('database').value);status(catalog.runtime.message);}catch(error){status(error.message,true);}finally{setBusy(false);}});
$('examples').addEventListener('change',e=>{const ex=catalog.examples.find(x=>x.id===e.target.value);if(ex)loadExample(ex);inputChanged();});
$('history').addEventListener('change',async e=>{if(!e.target.value)return;const id=e.target.value;const token=++epoch;setBusy(true);try{const job=await api(`/api/runs/${id}`);if(job.status==='succeeded'){setBusy(false);if(job.result.input.inputMode==='plan'){location.href='/plan-lab?run='+job.id;return;}await applyRequest(job.result.input);showJob(job);}else if(job.status==='failed'){setBusy(false);status(job.error,true);}else await poll(id,token);}catch(error){setBusy(false);status(error.message,true);}});
$('import-button').addEventListener('click',()=>$('import-file').click());
$('import-file').addEventListener('change',async e=>{const file=e.target.files[0];if(!file)return;setBusy(true);++epoch;status('Validating imported record…');try{if(file.size>8_000_000)throw new Error('Record exceeds the 8 MB limit');const job=await api('/api/import',{method:'POST',body:await file.text()});setBusy(false);if(job.result.input.inputMode==='plan'){location.href='/plan-lab?run='+job.id;return;}await applyRequest(job.result.input);showJob(job);await refreshHistory();}catch(error){setBusy(false);status(error.message,true);}finally{e.target.value='';}});
document.querySelectorAll('[data-zoom]').forEach(b=>b.addEventListener('click',()=>{const [side,delta]=b.dataset.zoom.split(':');zoom[side]=Number(delta)===0?1:Math.min(1.6,Math.max(.65,zoom[side]+Number(delta)*.15));renderSelection();}));
async function loadEngine(database){
 document.querySelectorAll('.page-nav a').forEach(link=>{const url=new URL(link.href);url.searchParams.set('database',database);link.href=url.pathname+url.search;});

  catalog=await api('/api/catalog?database='+database);
  const sqlExamples=catalog.examples.filter(e=>e.input.inputMode!=='plan');
  $('examples').replaceChildren(new Option('Select an example',''),...sqlExamples.map(e=>new Option(e.title,e.id)));
  $('database').replaceChildren(...catalog.databases.map(d=>new Option(`${d.title} · ${d.version}`,d.id)));$('database').value=database;
  renderRules();
  if(sqlExamples.length)loadExample(sqlExamples[0]);else {if(!$('schema').value)$('schema').value=JSON.stringify(catalog.examples[0]?.input.schema||[{name:'customers',columns:[{name:'id',type:'INTEGER'}]}],null,2);selectRules({database,rules:[]});if(!$('sql').value)$('sql').value='SELECT 1 + 2 AS total';text('example-note','Choose a native rule and enter a query. Hand-built examples are available in Plan lab.');}
  $('expand-subqueries').closest('p').hidden=database!=='calcite';
}
try{
  await loadEngine(new URL(location.href).searchParams.get('database')||'calcite');
  const requestedRule=new URL(location.href).searchParams.get('rule');
  if(requestedRule){
    const rule=catalog.rules.find(r=>r.id===requestedRule&&r.runnable);
    if(!rule)throw new Error('The requested rule is unknown or unavailable.');
    const examples=await api('/api/rule-examples?database='+catalog.database);
    const example=examples.examples.find(e=>e.id===requestedRule);
    if(example?.input.inputMode==='plan'){location.href='/plan-lab?database='+catalog.database+'&rule='+encodeURIComponent(requestedRule);throw new Error('Opening this hand-built example in Plan lab…');}
    loadExample(example||{input:{...(catalog.examples.find(e=>e.input.inputMode!=='plan')?.input||{database:catalog.database,sql:$('sql').value,schema:JSON.parse($('schema').value)}),rules:[requestedRule]},note:'Only the selected rule is enabled. Edit the SQL to match its pattern; this query is not a verified example for that rule.'});
    $('examples').value='';
  }
  setBusy(false);status('Ready · Select an example or enter a query');await refreshHistory();
}catch(error){status('Unable to load the service: '+error.message,true);}

if(catalog)await mountAssistant({
 page:'query-lab',
 context(){
  let schema;try{schema=JSON.parse($('schema').value);}catch{schema=$('schema').value;}
  const sameEngine=current?.result.input.database===catalog.database;
  return {page:'query-lab',database:catalog.database,engineVersion:catalog.engineVersion,
   input:{inputMode:'sql',database:catalog.database,sql:$('sql').value,schema,rules:[...ruleOrder],options:{matchLimit:Number($('match-limit').value),expandSubqueries:$('expand-subqueries').checked}},
   stamp:assistantRevision,selectedNode:assistantSelectedResult,validationMessage:$('status').textContent,runId:sameEngine?current.id:null,
   stage:sameEngine&&selection.kind!=='overview'?selection.i:-1,event:sameEngine&&selection.kind==='event'?selection.j:-1};
 },
 async apply(input){if(busy)throw Error('Wait for the current run to finish.');await applyRequest(input);inputChanged();status('Assistant suggestion applied. Run to verify SQL and collect optimizer evidence.');},
 async focus(ref){
  if(ref.kind!=='result')throw Error('Only result nodes can be selected in Query lab.');
  if(current?.id!==ref.runId)showJob(await api('/api/runs/'+encodeURIComponent(ref.runId)));
  choose(ref.stage<0?{kind:'overview'}:ref.event<0?{kind:'stage',i:ref.stage}:{kind:'event',i:ref.stage,j:ref.event});highlightNode($(ref.side),ref.nodeId);
 }
});
