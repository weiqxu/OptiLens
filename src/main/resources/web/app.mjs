import {diffPlans,drawPlan} from './graph.mjs';
const $=id=>document.getElementById(id);
let catalog,current,selection={kind:'overview'},epoch=0,busy=false;
const zoom={before:1,after:1};
const text=(id,value)=>{$(id).textContent=value;};
const status=(message,error=false)=>{text('status',message);$('status').className='status'+(error?' error':busy?' busy':'');};
async function api(path,options={}){
  const response=await fetch(path,{...options,headers:{'Content-Type':'application/json',...options.headers}});
  const data=await response.json();if(!response.ok)throw new Error(data.error||`请求失败 (${response.status})`);return data;
}
function button(label,handler,className=''){const b=document.createElement('button');b.type='button';b.textContent=label;b.className=className;b.addEventListener('click',handler);return b;}
function setBusy(value){busy=value;$('run').disabled=value||!catalog;$('import-button').disabled=value;$('history').disabled=value;for(const id of ['sql','schema','examples','database','rule-search','rule-category','rule-recommended','rule-clear'])$(id).disabled=value;for(const input of $('rule-options').querySelectorAll('input'))input.disabled=value||input.dataset.runnable!=='true';}
function loadExample(example){
  $('sql').value=example.input.sql;$('schema').value=JSON.stringify(example.input.schema,null,2);text('example-note',example.note);
  selectRules(example.input);
}
function applyRequest(input){$('sql').value=input.sql;$('schema').value=JSON.stringify(input.schema,null,2);selectRules(input);$('examples').value='';text('example-note','已载入该记录的 SQL 和表结构；重新运行会生成新的本地证据。');}
function selectRules(input){
  $('database').value=input.database||'calcite';
  for(const c of $('rule-options').querySelectorAll('input'))c.checked=c.dataset.runnable==='true'&&input.rules.includes(c.value);
  updateRules();
}
function renderRules(){
  $('rule-options').replaceChildren();
  $('rule-search').value='';
  $('rule-category').replaceChildren(new Option('全部分类',''),...Array.from(new Set(catalog.rules.map(r=>r.category)),c=>new Option(c,c)));
  text('rule-scope',catalog.scope);
  for(const rule of catalog.rules){
    const row=document.createElement('div');row.className='rule-row';row.dataset.category=rule.category;row.dataset.search=[rule.title,rule.description,...rule.implementations,rule.category].join(' ').toLowerCase();
    const label=document.createElement('label');label.className='rule-option';
    const input=document.createElement('input');input.type='checkbox';input.value=rule.id;input.dataset.runnable=String(rule.runnable);input.disabled=!rule.runnable;
    const info=document.createElement('span');const title=document.createElement('strong');title.textContent=rule.title;
    const description=document.createElement('small');description.textContent=rule.category+' · '+rule.description;
    const availability=document.createElement('small');availability.textContent=rule.runnable?'可选择运行':rule.unavailableReason;availability.className=rule.runnable?'rule-available':'muted';
    info.append(title,description,availability);label.append(input,info);
    const source=document.createElement('a');source.href=rule.source;source.target='_blank';source.rel='noopener noreferrer';source.textContent='源码 ↗';source.setAttribute('aria-label',rule.title+' 源码');
    row.append(label,source);$('rule-options').append(row);
  }
}
function updateRules(){
  const query=$('rule-search').value.trim().toLowerCase(),category=$('rule-category').value;
  let visible=0;
  for(const row of $('rule-options').children){row.hidden=!(row.dataset.search.includes(query)&&(!category||row.dataset.category===category));if(!row.hidden)visible++;}
  const selected=$('rule-options').querySelectorAll('input:checked').length;
  const presets=catalog.rules.filter(r=>r.category==='常用规则组').length;
  text('rule-count',`${catalog.rules.length-presets} 个内置规则条目 + ${presets} 个常用组合 · 已选 ${selected} / ${catalog.maxSelected} · 显示 ${visible} 项`);
  $('rule-empty').hidden=visible!==0;
}
async function refreshHistory(){
  const list=await api('/api/runs');const select=$('history');select.replaceChildren(new Option('选择历史运行',''));
  for(const item of list)select.add(new Option(`${new Date(item.submittedAt).toLocaleString()} · ${item.imported?'导入':({succeeded:'已完成',failed:'失败',queued:'排队',running:'运行中'}[item.status]||item.status)}`,item.id));
  if(current)select.value=current.id;
}
function showJob(job){
  current=job;$('empty').hidden=true;$('workspace').hidden=false;
  const link=$('export');link.href=`/api/runs/${job.id}/download`;link.classList.remove('disabled');link.setAttribute('aria-disabled','false');
  selection={kind:'overview'};renderSteps();renderSelection();
  status(job.imported?'已导入记录。结构已校验，内容来源未经重新执行验证。':`已完成 · ${job.result.stages.reduce((n,s)=>n+s.events.length,0)} 个局部变换事件 · ${job.result.stages.length} 个规则阶段`);
}
function selected(key){return JSON.stringify(selection)===JSON.stringify(key);}
function choose(key){selection=key;zoom.before=zoom.after=1;renderSteps();renderSelection();}
function renderSteps(){
  const nav=$('steps');nav.replaceChildren();
  function add(label,key,sub,cls){const b=button(label,()=>choose(key),cls);b.setAttribute('aria-pressed',selected(key));if(sub){const s=document.createElement('small');s.textContent=sub;b.append(s);}nav.append(b);}
  add('初始 → 最终',{kind:'overview'},'完整逻辑计划');
  current.result.stages.forEach((s,i)=>{
    add(`${i+1} · ${s.title}`,{kind:'stage',i},`${s.attempts.length} 次尝试 · ${s.events.length} 个变换`);
    s.events.forEach((e,j)=>add(`↳ ${j+1}. ${e.rule}`,{kind:'event',i,j},'局部表达式','event-button'));
  });
}
function renderSelection(){
  if(!current)return;
  const run=current.result;
  let before=run.initial,after=run.finalPlan,title='初始与最终计划',description='比较本次受控优化程序的整体结果。阶段间可能存在相互影响，不能将整体差异归因于某一条规则。',conditions='',source='',attempts=[],scope='完整计划 · 总览';
  if(selection.kind!=='overview'){
    const stage=run.stages[selection.i];({before,after,description,conditions,source,attempts}=stage);title=stage.title;scope='完整计划 · 阶段边界';
    if(selection.kind==='event'){const e=stage.events[selection.j];({before,after}=e);title=e.rule;scope='局部表达式 · 规则事件';description=`规则调用 #${e.callId} 产生的等价表达式。该快照不代表完整查询计划，最终采用关系未采集。`;}
    else if(!stage.events.length)description+=' 本阶段未记录到产生新表达式的事件；未采集具体拒绝原因。';
  }
  text('scope',scope);text('selection-title',title);text('description',description);text('conditions',conditions);
  text('before-title',selection.kind==='overview'?'初始计划（SQL 转换后）':'应用前');text('after-title',selection.kind==='overview'?'最终逻辑计划':'应用后');
  text('provenance',current.imported?'导入记录 · 未重新执行验证来源':'本地 Calcite 真实运行 · 阶段顺序由所选规则程序决定');
  const link=$('source');link.hidden=!source;
  // Imported records cannot inject arbitrary link schemes or external destinations.
  if(source?.startsWith('https://github.com/apache/calcite/blob/'))link.href=source;else{link.removeAttribute('href');link.hidden=true;}
  const diff=diffPlans(before,after);
  text('change-title',diff.left.size||diff.right.size?`差异节点：应用前 ${diff.left.size} · 应用后 ${diff.right.size}`:'未观察到结构或属性差异');
  const inspect=(node,mark,side)=>{text('node-label',`${side==='before'?'应用前':'应用后'} · ${node.type} #${node.id}`);text('node-content',JSON.stringify({difference:mark??'未标记差异',...node},null,2));$('node-details').open=true;};
  drawPlan($('before'),before,diff.left,'before',inspect,zoom.before);drawPlan($('after'),after,diff.right,'after',inspect,zoom.after);
  text('node-label','点击图中的算子');text('node-content','请选择一个节点。');$('node-details').open=false;
  text('raw-before',before.raw);text('raw-after',after.raw);text('attempts',selection.kind==='overview'?'选择某个规则阶段查看原始尝试记录。':JSON.stringify(attempts,null,2));
  $('limitations').replaceChildren(...run.limitations.map(s=>{const li=document.createElement('li');li.textContent=s;return li;}));
  text('metadata',JSON.stringify({id:current.id,engine:run.engine,engineVersion:run.engineVersion,createdAt:run.createdAt,formatVersion:run.formatVersion,input:run.input,provenance:current.imported?'imported-unverified':'local-execution'},null,2));
}
async function poll(id,token){
  for(;;){if(token!==epoch)return;const job=await api(`/api/runs/${id}`);if(token!==epoch)return;
    if(job.status==='succeeded'){setBusy(false);showJob(job);await refreshHistory();return;}
    if(job.status==='failed')throw new Error(job.error||'优化失败');
    status(job.status==='queued'?'已提交，等待优化工作进程…':'Calcite 正在解析与优化；首次运行可能需要几秒…');
    await new Promise(r=>setTimeout(r,500));
  }
}
$('run').addEventListener('click',async()=>{
  if(busy)return;setBusy(true);const token=++epoch;status('正在提交优化任务…');
  try{const schema=JSON.parse($('schema').value);const rules=[...$('rule-options').querySelectorAll('input:checked')].map(i=>i.value);
    const job=await api('/api/runs',{method:'POST',body:JSON.stringify({database:$('database').value,sql:$('sql').value,schema,rules})});await poll(job.id,token);
  }catch(e){if(token===epoch){setBusy(false);status(e.message+(current?'（下方保留上次成功运行的结果）':''),true);refreshHistory().catch(()=>{});}}
});
function inputChanged(){if(current)status('输入已修改；下方仍为上次运行结果。点击“运行优化”生成新结果。');}
$('sql').addEventListener('input',inputChanged);$('schema').addEventListener('input',inputChanged);$('rule-options').addEventListener('change',e=>{
  if($('rule-options').querySelectorAll('input:checked').length>catalog.maxSelected){e.target.checked=false;status(`每次最多选择 ${catalog.maxSelected} 项规则`,true);}
  else inputChanged();
  updateRules();
});
$('rule-search').addEventListener('input',updateRules);
$('rule-category').addEventListener('change',updateRules);
$('rule-recommended').addEventListener('click',()=>{selectRules(catalog.examples[0].input);inputChanged();});
$('rule-clear').addEventListener('click',()=>{selectRules({database:$('database').value,rules:[]});inputChanged();});
$('database').addEventListener('change',()=>{renderRules();selectRules(catalog.examples[0].input);inputChanged();});
$('examples').addEventListener('change',e=>{const ex=catalog.examples.find(x=>x.id===e.target.value);if(ex)loadExample(ex);inputChanged();});
$('history').addEventListener('change',async e=>{if(!e.target.value)return;const id=e.target.value;const token=++epoch;setBusy(true);try{const job=await api(`/api/runs/${id}`);if(job.status==='succeeded'){setBusy(false);applyRequest(job.result.input);showJob(job);}else if(job.status==='failed'){setBusy(false);status(job.error,true);}else await poll(id,token);}catch(error){setBusy(false);status(error.message,true);}});
$('import-button').addEventListener('click',()=>$('import-file').click());
$('import-file').addEventListener('change',async e=>{const file=e.target.files[0];if(!file)return;setBusy(true);++epoch;status('正在校验导入记录…');try{if(file.size>8_000_000)throw new Error('记录超过 8 MB 上限');const job=await api('/api/import',{method:'POST',body:await file.text()});setBusy(false);applyRequest(job.result.input);showJob(job);await refreshHistory();}catch(error){setBusy(false);status(error.message,true);}finally{e.target.value='';}});
document.querySelectorAll('[data-zoom]').forEach(b=>b.addEventListener('click',()=>{const [side,delta]=b.dataset.zoom.split(':');zoom[side]=Number(delta)===0?1:Math.min(1.6,Math.max(.65,zoom[side]+Number(delta)*.15));renderSelection();}));
try{
  catalog=await api('/api/catalog');
  $('examples').replaceChildren(...catalog.examples.map(e=>new Option(e.title,e.id)));
  $('database').replaceChildren(...catalog.databases.map(d=>new Option(`${d.title} · ${d.version}`,d.id)));
  renderRules();
  loadExample(catalog.examples[0]);setBusy(false);status('准备就绪 · 选择案例或输入自己的查询');await refreshHistory();
}catch(error){status('无法加载服务：'+error.message,true);}
