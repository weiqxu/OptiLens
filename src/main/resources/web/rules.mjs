import {diffPlans,drawPlan,layoutPlan} from './graph.mjs';
const $=id=>document.getElementById(id);
const text=(id,value)=>{$(id).textContent=value;};
const counted=(n,label)=>`${n} ${label}${n===1?'':'s'}`;
let catalog,rules=[],examples=new Map(),coverage=new Map(),selected=null,job=null,revision=0,startTimer;
const runs=new Map(),zoom={before:1,after:1};
async function api(path,options={}){
  const response=await fetch(path,{...options,headers:{'Content-Type':'application/json',...options.headers}});
  const data=await response.json();if(!response.ok)throw new Error(data.error||`Request failed (${response.status})`);return data;
}
function displayName(rule){
  const constant=rule.id.split('.').slice(1).join('.');
  return constant.includes('_')?constant.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase()):rule.title;
}
function renderList(){
  const query=$('explorer-search').value.trim().toLowerCase(),category=$('explorer-category').value,onlyExamples=$('examples-only').checked;
  let visible=0;
  for(const button of $('explorer-list').children){
    const rule=rules.find(r=>r.id===button.dataset.rule);
    button.hidden=!(button.dataset.search.includes(query)&&(!category||rule.category===category)&&(!onlyExamples||examples.has(rule.id)));
    button.setAttribute('aria-pressed',String(rule.id===selected?.id));
    if(!button.hidden)visible++;
  }
  text('list-count',`${visible} of ${rules.length} rules · ${examples.size} verified examples`);
  $('list-empty').hidden=visible>0;
  const active=[...$('explorer-list').children].find(button=>button.dataset.rule===selected?.id&&!button.hidden);
  if(active){
    const list=$('explorer-list'),row=active.getBoundingClientRect(),bounds=list.getBoundingClientRect();
    if(row.top<bounds.top)list.scrollTop-=bounds.top-row.top;
    else if(row.bottom>bounds.bottom)list.scrollTop+=row.bottom-bounds.bottom;
  }
}
function clearResult(){
  job=null;zoom.before=zoom.after=1;$('example-result').hidden=true;
  for(const id of ['example-before','example-after','example-node-content'])$(id).replaceChildren();
  $('example-node').open=false;
}
function choose(rule){
  clearTimeout(startTimer);const token=++revision;selected=rule;clearResult();renderList();
  const url=new URL(location.href);url.searchParams.set('rule',rule.id);history.replaceState(null,'',url);
  $('detail-empty').hidden=true;$('detail-content').hidden=false;
  text('detail-category',rule.category);text('detail-title',displayName(rule));text('detail-id',rule.id);
  text('detail-availability',rule.runnable?'Available in Query lab':'Requires setup');
  text('detail-description',rule.description);text('detail-conditions',rule.conditions);
  $('detail-source').href=rule.source;
  $('open-lab').hidden=!rule.runnable;$('open-lab').href=`/?rule=${encodeURIComponent(rule.id)}`;
  const example=examples.get(rule.id);
  $('example-section').hidden=!example;$('no-example').hidden=Boolean(example);
  if(!example){
    text('no-example-title',rule.runnable?'No verified example yet':'Additional environment required');
    text('no-example-note',coverage.get(rule.id)?.reason || (rule.runnable?'This rule is in the catalog, but a reproducible example has not been added. Try it with your own SQL in Query lab. No transformation is implied.':rule.unavailableReason+'. A runnable example is not available in this environment.'));
    return;
  }
  text('example-title',example.title);text('example-note',example.note);text('example-sql',example.input.sql);text('example-schema',JSON.stringify(example.input.schema,null,2));
  $('example-program').replaceChildren(...example.input.rules.map(id=>{const li=document.createElement('li');li.textContent=(id===example.id?'Target: ':'Preparation: ')+id;return li;}));
  const options=example.input.options;
  text('example-settings',`Match limit per stage: ${options?.matchLimit??100}. SQL subquery expansion: ${options?.expandSubqueries===false?'disabled, preserving subqueries for the target rule':'enabled'}. Planner time zone: UTC. A bounded demonstration does not imply a fixed point or a globally optimal plan.`);
  $('rerun-example').disabled=true;setStatus('Preparing the example…');
  startTimer=setTimeout(()=>showExample(example,token),200);
}
function setStatus(message,error=false){text('example-status',message);$('example-status').className='status'+(error?' error':'');}
async function execute(example){
  const submitted=await api('/api/runs',{method:'POST',body:JSON.stringify(example.input)});
  const deadline=Date.now()+180000;
  while(Date.now()<deadline){
    const result=await api(`/api/runs/${submitted.id}`);
    if(result.status==='succeeded')return result;
    if(result.status==='failed')throw new Error(result.error||'The example failed.');
    await new Promise(resolve=>setTimeout(resolve,500));
  }
  throw new Error('The example is still pending. Check Recent runs in Query lab.');
}
async function showExample(example,token){
  if(token!==revision)return;
  setStatus('Running this rule in Calcite…');
  let pending=runs.get(example.id);
  if(!pending){pending=execute(example);runs.set(example.id,pending);}
  try{
    const result=await pending;if(token!==revision)return;
    job=result;const stage=targetStage();
    $('example-view').replaceChildren(new Option('Complete plan · stage boundary','stage'),...stage.events.map((event,index)=>new Option(`Local event ${index+1} · ${event.rule}`,String(index))));
    $('download-example').href=`/api/runs/${job.id}/download`;
    $('example-result').hidden=false;
    setStatus(stage.events.length?`Completed · ${counted(stage.events.length,'transformation')} · ${counted(stage.attempts.length,'attempt')} · Calcite ${job.result.engineVersion}`:'Completed without a recorded transformation. Inspect the plans and attempts; no rewrite is claimed.');
    $('example-limitations').replaceChildren(...job.result.limitations.map(value=>{const li=document.createElement('li');li.textContent=value;return li;}));
    renderPlans(true);
  }catch(error){
    if(runs.get(example.id)===pending)runs.delete(example.id);
    if(token===revision)setStatus(error.message,true);
  }finally{if(token===revision)$('rerun-example').disabled=false;}
}
function targetStage(){return job.result.stages.find(stage=>stage.id===selected.id);}
function renderPlans(fit=false){
  if(!job)return;
  const stage=targetStage(),value=$('example-view').value;
  const pair=value==='stage'?stage:stage.events[Number(value)];
  text('example-scope',value==='stage'?`Complete plans immediately before and after ${selected.id}. ${job.result.stages.length-1} preparation stages precede the target; their transformations are excluded from this comparison. Local events are available in the View menu.`:'Local expressions from one rule invocation. This event does not represent the entire query plan or prove final candidate selection.');
  const diff=diffPlans(pair.before,pair.after);
  if(fit)for(const side of ['before','after'])zoom[side]=Math.min(1,Math.max(.65,($('example-'+side).clientWidth-16)/layoutPlan(pair[side]).width));
  const inspect=(node,mark,side)=>{text('example-node-content',JSON.stringify({side,difference:mark||'No marked difference',...node},null,2));$('example-node').open=true;};
  drawPlan($('example-before'),pair.before,diff.left,'before',inspect,zoom.before);
  drawPlan($('example-after'),pair.after,diff.right,'after',inspect,zoom.after);
  text('difference-summary',`${diff.left.size} before / ${diff.right.size} after nodes marked`);
  text('example-raw-before',pair.before.raw);text('example-raw-after',pair.after.raw);text('example-attempts',JSON.stringify(stage.attempts,null,2));
  $('example-node').open=false;text('example-node-content','Select an operator to inspect its expressions and attributes.');
}
$('explorer-search').addEventListener('input',renderList);$('explorer-category').addEventListener('change',renderList);$('examples-only').addEventListener('change',renderList);
$('explorer-database').addEventListener('change',()=>{ $('explorer-search').value='';$('explorer-category').value='';$('examples-only').checked=false;choose(rules.find(r=>examples.has(r.id))); });
$('example-view').addEventListener('change',()=>renderPlans(true));
$('rerun-example').addEventListener('click',()=>{const example=examples.get(selected.id);runs.delete(example.id);clearResult();$('rerun-example').disabled=true;showExample(example,++revision);});
document.querySelectorAll('[data-zoom]').forEach(button=>button.addEventListener('click',()=>{const [side,delta]=button.dataset.zoom.split(':');zoom[side]=Number(delta)===0?1:Math.min(1.6,Math.max(.65,zoom[side]+Number(delta)*.15));renderPlans();}));
try{
  const [loaded,library]=await Promise.all([api('/api/catalog'),api('/api/rule-examples')]);catalog=loaded;
  rules=catalog.rules.filter(rule=>rule.category!=='Common rule groups');examples=new Map(library.examples.map(example=>[example.id,example]));coverage=new Map(library.coverage.map(item=>[item.id,item]));
  $('explorer-database').replaceChildren(...catalog.databases.map(database=>new Option(`${database.title} · ${database.version}`,database.id)));
  $('explorer-category').replaceChildren(new Option('All categories',''),...Array.from(new Set(rules.map(rule=>rule.category)),category=>new Option(category,category)));
  text('catalog-total',rules.length);text('catalog-scope',catalog.scope);
  for(const rule of rules){
    const button=document.createElement('button');button.type='button';button.className='rule-item';button.dataset.rule=rule.id;
    button.dataset.search=[rule.id,rule.description,rule.category,...rule.implementations].join(' ').toLowerCase();
    const title=document.createElement('strong');title.textContent=displayName(rule);
    const id=document.createElement('small');id.textContent=rule.id;
    const tag=document.createElement('small');tag.textContent=examples.has(rule.id)?'● Verified example':rule.runnable?'No example yet':'Requires setup';if(examples.has(rule.id))tag.className='example-tag';
    button.append(title,id,tag);button.addEventListener('click',()=>choose(rule));$('explorer-list').append(button);
  }
  for(const id of ['explorer-database','explorer-search','explorer-category','examples-only'])$(id).disabled=false;
  const requested=new URL(location.href).searchParams.get('rule');
  const initial=rules.find(rule=>rule.id===requested)||rules.find(rule=>rule.id==='CoreRules.PROJECT_REDUCE_EXPRESSIONS');
  text('catalog-status',`${rules.length} catalog rules · ${examples.size} verified SQL examples / ${rules.filter(rule=>rule.runnable).length} runnable rules · Select a rule to explore. Examples run locally and appear in Query lab history.`);
  if(requested&&!rules.some(rule=>rule.id===requested))text('catalog-status','The requested rule was not found. Showing the constant projection example.');
  choose(initial);
}catch(error){text('catalog-status','Unable to load the rule explorer: '+error.message);$('catalog-status').className='status error';}
