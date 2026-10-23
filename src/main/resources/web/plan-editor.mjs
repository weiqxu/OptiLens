export const copy=value=>structuredClone(value);
export function scalar(value=1,type='INTEGER'){return {kind:'literal',type,nullable:value===null,value};}
export function ref(input=0,index=0){return {kind:'ref',input,index};}
export function defaults(op,schema){
  const truth=scalar(true,'BOOLEAN');
  return ({TableScan:{table:schema[0]?.name||'customers'},Values:{columns:[{name:'id',type:'INTEGER',nullable:false}],rows:[[1],[2]]},
    Filter:{condition:truth},Project:{projects:[{name:'id',expr:ref()}]},Calc:{projects:[{name:'id',expr:ref()}],condition:truth},
    Join:{joinType:'INNER',condition:truth},Aggregate:{groupKeys:[],calls:[{function:'COUNT',args:[],name:'count',distinct:false}]},
    Sort:{keys:[],offset:null,fetch:null},Union:{all:true},Intersect:{all:false},Minus:{all:false},
    Exchange:{distribution:{type:'HASH',keys:[0]}},SortExchange:{distribution:{type:'HASH',keys:[0]},keys:[]},MultiJoin:{condition:truth}})[op];
}
export function freshId(plan){let i=1;while(plan.nodes.some(n=>n.id==='n'+i))i++;return 'n'+i;}
export function remapPlan(plan){const p=copy(plan),ids=new Map(p.nodes.map((n,i)=>[n.id,'n'+(i+1)]));p.rootId=ids.get(p.rootId);p.nodes=p.nodes.map(n=>({...n,id:ids.get(n.id),inputs:n.inputs.map(id=>ids.get(id))}));p.layout={};return p;}
export function connect(plan,parentId,index,childId){
  const parent=plan.nodes.find(n=>n.id===parentId);if(!parent)throw Error('Unknown parent node.');
  if(childId){
    if(!plan.nodes.some(n=>n.id===childId))throw Error('Unknown input node.');
    const seen=new Set();function reaches(id){if(id===parentId)return true;if(seen.has(id))return false;seen.add(id);return (plan.nodes.find(n=>n.id===id)?.inputs||[]).filter(Boolean).some(reaches);}
    if(reaches(childId))throw Error('This connection would create a cycle.');
    if(plan.nodes.some(n=>n.inputs.some((id,i)=>id===childId&&(n.id!==parentId||i!==index))))throw Error('This input already has a parent. Duplicate its subtree to reuse it.');
  }
  parent.inputs[index]=childId||null;delete parent.attributes?.refCounts;if(parent.attributes?.projectedFields)parent.attributes.projectedFields[index]=null;
}
export function cloneSubtree(plan,id){
  const nodes=new Map(plan.nodes.map(n=>[n.id,n])),path=new Set();
  function clone(old){if(path.has(old))throw Error('Cannot copy a cyclic subtree.');path.add(old);const n=copy(nodes.get(old));if(!n)throw Error('Missing input.');n.id=freshId(plan);plan.nodes.push(n);n.inputs=n.inputs.map(child=>child?clone(child):null);const pos=plan.layout?.[old];plan.layout[n.id]={x:(pos?.x||80)+70,y:(pos?.y||80)+70};path.delete(old);return n.id;}
  if(plan.nodes.length>=250)throw Error('Plan node limit reached.');const result=clone(id);if(plan.nodes.length>250)throw Error('Copy exceeds the 250-node limit.');return result;
}
export function structuralErrors(plan,arity){
  const errors=[],nodes=new Map();if(!plan||!Array.isArray(plan.nodes))return ['A node array is required.'];
  if(!plan.nodes.length||plan.nodes.length>250)errors.push('Use 1–250 nodes.');
  for(const n of plan.nodes){if(!n||!n.id||nodes.has(n.id)){errors.push('Node IDs must be unique.');continue;}nodes.set(n.id,n);if(!(n.op in arity))errors.push(`${n.id}: unsupported operator.`);const count=arity[n.op];if(!Array.isArray(n.inputs)||(count>=0?n.inputs.length!==count:n.inputs.length<-count))errors.push(`${n.id}: incorrect number of inputs.`);}
  const seen=new Set(),path=new Set(),parents=new Set();
  for(const n of nodes.values())for(const id of n.inputs||[]){if(!nodes.has(id))errors.push(`${n.id}: missing input.`);if(parents.has(id))errors.push(`${id}: shared input; copy its subtree.`);parents.add(id);}
  function visit(id,depth){if(!nodes.has(id))return;if(path.has(id)){errors.push('Cycle detected.');return;}if(depth>40){errors.push('Tree exceeds 40 levels.');return;}if(seen.has(id))return;seen.add(id);path.add(id);for(const child of nodes.get(id).inputs||[])visit(child,depth+1);path.delete(id);}
  if(!nodes.has(plan.rootId))errors.push('Select a root node.');else if(parents.has(plan.rootId))errors.push('The root cannot be an input.');visit(plan.rootId,0);if(seen.size!==nodes.size)errors.push('Connect all nodes to the root.');return [...new Set(errors)];
}
export function autoLayout(plan){
  const depth=new Map(),path=new Set();function visit(id,d){if(d>40||path.has(id))return;if((depth.get(id)??-1)>=d)return;depth.set(id,d);path.add(id);for(const c of plan.nodes.find(n=>n.id===id)?.inputs||[])if(c)visit(c,d+1);path.delete(id);}
  visit(plan.rootId,0);for(const n of plan.nodes)if(!depth.has(n.id))visit(n.id,0);
  const rows=new Map();for(const n of plan.nodes){const d=depth.get(n.id)||0;if(!rows.has(d))rows.set(d,[]);rows.get(d).push(n.id);}
  plan.layout={};for(const [d,ids] of rows)ids.forEach((id,i)=>plan.layout[id]={x:40+i*240,y:40+d*155});
}
export class History {
  constructor(value){this.value=copy(value);this.past=[];this.future=[];}
  set(value){this.past.push(copy(this.value));if(this.past.length>80)this.past.shift();this.value=copy(value);this.future=[];}
  undo(){if(!this.past.length)return;this.future.push(this.value);this.value=this.past.pop();}
  redo(){if(!this.future.length)return;this.past.push(this.value);this.value=this.future.pop();}
}
export function subtree(snapshot,rootId){const map=new Map(snapshot.nodes.map(n=>[n.id,n])),seen=new Set();function walk(id){if(seen.has(id))return;seen.add(id);for(const child of map.get(id)?.inputs||[])walk(child);}walk(rootId);return {...snapshot,scope:'local-expression',rootId,nodes:snapshot.nodes.filter(n=>seen.has(n.id)),raw:'Selected subtree: '+rootId+'\n'+JSON.stringify(snapshot.nodes.filter(n=>seen.has(n.id)),null,2)};}

/** Check editor-readable shapes before replacing a working draft. Type validation stays in the worker. */
export function validateEditorShape(plan) {
  const object=(v,label)=>{if(!v||typeof v!=='object'||Array.isArray(v))throw Error(label+' must be an object.');};
  const array=(v,label)=>{if(!Array.isArray(v))throw Error(label+' must be an array.');};
  const expr=(v,label,depth=0)=>{
    object(v,label);if(depth>32)throw Error(label+' exceeds expression depth 32.');
    if(!['literal','ref','cast','call'].includes(v.kind))throw Error(label+' has an unsupported expression kind.');
    if(v.kind==='cast')expr(v.expr,label+'.expr',depth+1);
    if(v.kind==='call'){array(v.args,label+'.args');v.args.forEach((a,i)=>expr(a,label+'.args['+i+']',depth+1));}
  };
  const ids=new Set();
  for(const n of plan.nodes){
    if(!/^[A-Za-z0-9_-]{1,64}$/.test(n.id)||ids.has(n.id))throw Error('Node IDs must be unique ASCII identifiers.');ids.add(n.id);
    object(n.attributes,n.id+'.attributes');const a=n.attributes;
    if(n.inputs.some(id=>id!==null&&typeof id!=='string'))throw Error(n.id+': inputs must contain node IDs or null.');
    for(const key of ['condition','postFilter'])if(a[key]!=null)expr(a[key],n.id+'.'+key);
    for(const key of ['projects','columns','calls','keys'])if(a[key]!=null){array(a[key],n.id+'.'+key);a[key].forEach((v,i)=>object(v,n.id+'.'+key+'['+i+']'));}
    if(a.projects)a.projects.forEach((p,i)=>expr(p.expr,n.id+'.projects['+i+'].expr'));
    if(a.calls)a.calls.forEach((c,i)=>array(c.args,n.id+'.calls['+i+'].args'));
    if(a.rows){array(a.rows,n.id+'.rows');a.rows.forEach((r,i)=>array(r,n.id+'.rows['+i+']'));}
    if(['Exchange','SortExchange'].includes(n.op)){object(a.distribution,n.id+'.distribution');if(a.distribution.type==='HASH')array(a.distribution.keys,n.id+'.distribution.keys');}
    if(a.projectedFields!=null){array(a.projectedFields,n.id+'.projectedFields');a.projectedFields.forEach(f=>{if(f!=null)array(f,n.id+'.projectedFields');});}
  }
  const parents=new Set(),byId=new Map(plan.nodes.map(n=>[n.id,n])),visiting=new Set(),done=new Set();
  for(const n of plan.nodes)for(const child of n.inputs.filter(Boolean)){if(parents.has(child))throw Error('Shared inputs are not supported; copy the subtree.');parents.add(child);}
  function visit(id,depth){if(depth>40)throw Error('Tree exceeds 40 levels.');if(visiting.has(id))throw Error('Cycle detected.');if(done.has(id)||!byId.has(id))return;visiting.add(id);for(const child of byId.get(id).inputs.filter(Boolean))visit(child,depth+1);visiting.delete(id);done.add(id);}
  for(const n of plan.nodes)visit(n.id,0);
  if(plan.layout!=null){object(plan.layout,'layout');for(const [id,p] of Object.entries(plan.layout)){object(p,'layout.'+id);if(!Number.isFinite(p.x)||!Number.isFinite(p.y)||Math.abs(p.x)>100000||Math.abs(p.y)>100000)throw Error('Invalid node position: '+id);}}
}
