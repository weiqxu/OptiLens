import {drawPlan} from './graph.mjs';
const KEY='optilens.assistant.v1',HANDOFF='optilens.assistant.handoff.v1',FOCUS='optilens.assistant.focus.v1';
const el=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;return node;};
const button=(text,fn)=>{const node=el('button',text);node.type='button';node.onclick=fn;return node;};
export async function fingerprint(value){const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(value)));return [...new Uint8Array(bytes)].map(b=>b.toString(16).padStart(2,'0')).join('');}
export function highlightNode(container,id){
 document.querySelectorAll('.assistant-highlight').forEach(n=>n.classList.remove('assistant-highlight'));
 const node=[...container.querySelectorAll('[data-node]')].find(n=>n.dataset.node===id);
 if(!node)throw Error('This node is not present in the selected snapshot.');
 node.classList.add('assistant-highlight');node.dispatchEvent(new MouseEvent('click',{bubbles:true}));node.scrollIntoView({block:'center',inline:'center',behavior:'smooth'});node.focus({preventScroll:true});
}
export async function readEvents(response,onEvent){
 if(!response.ok){const data=await response.json();throw Error(data.error||`Assistant request failed (${response.status})`);}
 const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',completed=false,total=0;
 try{for(;;){const {value,done}=await reader.read();if(done)break;total+=value.byteLength;if(total>3_000_000)throw Error('Assistant response exceeds the display limit.');buffer+=decoder.decode(value,{stream:true}).replace(/\r\n/g,'\n');let boundary;
  while((boundary=buffer.indexOf('\n\n'))>=0){const block=buffer.slice(0,boundary);buffer=buffer.slice(boundary+2);const lines=block.split('\n'),event=lines.find(s=>s.startsWith('event:'))?.slice(6).trim(),data=lines.filter(s=>s.startsWith('data:')).map(s=>s.slice(5).trimStart()).join('\n');
   if(event&&data){const parsed=JSON.parse(data);await onEvent(event,parsed);if(event==='done')completed=true;if(event==='error')throw Error(parsed.error);}
  }
 }
 if(!completed)throw Error('Assistant stream interrupted. Partial text is retained; incomplete suggestions were discarded.');
 }finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
}
async function json(url,body){const r=await fetch(url,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}),data=await r.json();if(!r.ok){const e=Error(data.error||data.errors?.map(x=>`${x.nodeId||'Input'} · ${x.path}: ${x.message}`).join('\n')||`Request failed (${r.status})`);e.issues=data.errors;throw e;}return data;}
export async function mountAssistant(adapter){
 const stylesheet=el('link');stylesheet.rel='stylesheet';stylesheet.href='/assistant.css';document.head.append(stylesheet);
 let messages=[];try{const saved=JSON.parse(sessionStorage.getItem(KEY)||'[]');if(Array.isArray(saved))messages=saved.slice(-20);}catch{}
 let active=null,enabled=false,lastRestore=null;
 const launcher=button('Assistant',()=>{panel.hidden=false;launcher.hidden=true;question.focus();});launcher.id='assistant-open';launcher.setAttribute('aria-controls','assistant-panel');
 const panel=el('aside');panel.id='assistant-panel';panel.hidden=true;panel.setAttribute('aria-label','Assistant');
 const header=el('header');header.append(el('strong','Assistant'),button('Close',()=>{panel.hidden=true;launcher.hidden=false;launcher.focus();}));
 const status=el('p','Loading assistant configuration…');status.id='assistant-status';status.setAttribute('role','status');
 const note=el('p','Current context is sent to the configured model only when you send a question. Suggestions require your review.');note.className='assistant-note';
 const contextLabel=el('p');contextLabel.id='assistant-context';const conversation=el('div');conversation.id='assistant-messages';conversation.setAttribute('role','log');conversation.setAttribute('aria-live','polite');
 const form=el('form'),label=el('label','Ask about this plan or rule');questionSetup();
 function questionSetup(){label.htmlFor='assistant-question';}
 const question=el('textarea');question.id='assistant-question';question.rows=3;question.maxLength=8000;question.placeholder='Explain this transformation…';
 const send=el('button','Send');send.type='submit';send.id='assistant-send';send.disabled=true;
 const stop=button('Stop',()=>stopRequest());stop.id='assistant-stop';stop.disabled=true;
 const clear=button('Clear conversation',()=>{stopRequest();messages=[];persist();render();});clear.id='assistant-clear';
 const restore=button('Restore previous input',async()=>{try{if(!lastRestore)return;const current=await capture();if(current.version!==lastRestore.appliedVersion)throw Error('Input changed after applying the suggestion. Restore is no longer available.');await adapter.apply(lastRestore.input);lastRestore=null;restore.hidden=true;status.textContent='Previous input restored.';}catch(e){status.textContent=e.message;}});restore.id='assistant-restore';restore.hidden=true;
 const actions=el('div');actions.className='assistant-actions';actions.append(send,stop,clear,restore);form.append(label,question,actions);panel.append(header,status,note,contextLabel,conversation,form);document.body.append(launcher,panel);
 function persist(){try{let value=JSON.stringify(messages);while(value.length>1_200_000&&messages.length>2){messages.splice(0,2);value=JSON.stringify(messages);}sessionStorage.setItem(KEY,value);}catch{status.textContent='Tab storage is full; this conversation is available until navigation.';}}
 async function capture(){const raw=adapter.context();const {stamp,...context}=raw;context.version=await fingerprint({page:context.page,database:context.database,input:context.input,stamp});return structuredClone(context);}
 async function stillCurrent(proposal){const current=await capture();if(current.page!==proposal.page||current.database!==proposal.database||current.version!==proposal.contextVersion)throw Error('The input has changed. Generate a new suggestion for the current draft.');return current;}
 async function focus(ref){
  if(ref.kind==='draft'){const c=await capture();if(c.version!==ref.version)throw Error('This reference belongs to an older draft.');}
  if(ref.page!==adapter.page){sessionStorage.setItem(FOCUS,JSON.stringify(ref));location.href=(ref.page==='plan-lab'?'/plan-lab':ref.page==='rules'?'/rules':'/')+'?database='+encodeURIComponent(ref.database);return;}
  await adapter.focus(ref);
 }
 function render(){conversation.replaceChildren();for(const message of messages){
   const article=el('article');article.className='assistant-message '+message.role;article.append(el('strong',message.role==='user'?'You':'Assistant'));
   if(message.contextLabel){const caption=el('small',message.contextLabel);article.append(caption);}
   article.append(el('p',message.content||''));if(message.error){const error=el('p',message.error);error.className='assistant-error';article.append(error);}
   for(const ref of message.nodes||[])article.append(button(`${ref.kind==='draft'?'Draft':`${ref.side} · stage ${ref.stage+1}${ref.event>=0?' · event '+(ref.event+1):''}`} · ${ref.nodeId}`,()=>focus(ref).catch(e=>status.textContent=e.message)));
   for(const source of message.sources||[]){const details=el('details'),summary=el('summary',`${source.version} · ${source.path.split('/').at(-1)}:${source.startLine}–${source.endLine}`);details.append(summary,el('small',`${source.commit}\n${source.path}`));
    details.append(el('pre',source.text.split('\n').map((line,i)=>`${source.startLine+i}  ${line}`).join('\n')));const a=el('a','Open pinned source ↗');if(/^https:\/\/github\.com\/(apache\/calcite|StarRocks\/starrocks)\/blob\/[0-9a-f]{40}\//.test(source.url))a.href=source.url;a.target='_blank';a.rel='noopener noreferrer';details.append(a);article.append(details);}
   for(const proposal of message.proposals||[])article.append(proposalCard(proposal,message));
   conversation.append(article);
  }
 }
 function proposalCard(proposal,message){
  const card=el('section');card.className='assistant-proposal';card.append(el('strong',proposal.title),el('p','Unverified suggestion · Review the input changes, then validate.'));
  const difference=el('details'),summary=el('summary','Review input changes');difference.append(summary);
  for(const [title,value]of [['Current input at send time',message.originalInput],['Proposed input',proposal.input]]){difference.append(el('h4',title),el('pre',JSON.stringify(value,null,2)));}
  card.append(difference);const feedback=el('p'),preview=el('div');preview.className='assistant-plan-preview';let validated=false;
  const apply=button(adapter.page==='rules'?'Open in Lab':'Apply suggestion',async()=>{
   apply.disabled=true;try{const current=await stillCurrent(proposal);if(!validated)throw Error('Validate the suggestion first.');
    if(adapter.page==='rules'){sessionStorage.setItem(HANDOFF,JSON.stringify(proposal));location.href=(proposal.input.inputMode==='plan'?'/plan-lab':'/')+'?database='+encodeURIComponent(proposal.database);return;}
    await adapter.apply(structuredClone(proposal.input));if(adapter.page==='query-lab'){lastRestore={input:current.input,appliedVersion:(await capture()).version};restore.hidden=false;}
    feedback.textContent='Applied to the editor. Run the candidate to collect actual optimizer evidence.';validated=false;
   }catch(e){feedback.textContent=e.message;}
  });apply.disabled=true;
  const validate=button('Validate suggestion',async()=>{
   validate.disabled=true;apply.disabled=true;validated=false;preview.replaceChildren();
   try{await stillCurrent(proposal);feedback.textContent='Validating the candidate…';const result=await json('/api/assistant/proposals/validate',proposal);await stillCurrent(proposal);validated=result.valid;apply.disabled=!validated;feedback.textContent=result.message||'Plan builds successfully. No optimization has been run.';if(result.snapshot)drawPlan(preview,result.snapshot,new Map(),'after',()=>{},.65);
   }catch(e){feedback.textContent=e.message;}finally{validate.disabled=false;}
  });card.append(validate,apply,feedback,preview);return card;
 }
 function stopRequest(){const request=active;if(!request)return;request.controller.abort();json('/api/assistant/cancel',{requestId:request.id}).catch(()=>{});active=null;stop.disabled=true;send.disabled=!enabled;}
 form.onsubmit=async event=>{
  event.preventDefault();if(active||!enabled||!question.value.trim())return;const controller=new AbortController(),id=crypto.randomUUID();active={controller,id};send.disabled=true;stop.disabled=false;
  let message;
  try{
   const context=await capture(),prompt=question.value.trim();if(controller.signal.aborted)return;question.value='';
   const history=messages.filter(m=>!m.error&&m.content).map(m=>({role:m.role,content:m.content})).slice(-16);while(JSON.stringify(history).length>70000)history.shift();
   const label=[context.page,context.database,context.engineVersion,context.ruleId,context.selectedNode?.nodeId,context.runId?'Run '+context.runId:'',context.stage>=0?'Stage '+(context.stage+1):''].filter(Boolean).join(' · ');contextLabel.textContent=label;
   messages.push({role:'user',content:prompt,contextLabel:label});message={role:'assistant',content:'',contextLabel:label,nodes:[],sources:[],proposals:[],originalInput:context.input};messages.push(message);render();persist();status.textContent='Receiving answer…';
   const response=await fetch('/api/assistant/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({requestId:id,question:prompt,history,context}),signal:controller.signal});
   await readEvents(response,(type,data)=>{if(controller.signal.aborted)return;
    if(type==='text')message.content+=data.text;
    if(type==='node')message.nodes.push(data);
    if(type==='source'&&!message.sources.some(s=>s.id===data.id))message.sources.push(data);
    if(type==='proposal')message.proposals.push(data);
    if(type==='notice')message.content+='\n['+data.error+']\n';
    if(type==='error')message.error=data.error;
    if(type==='done')status.textContent='Answer complete · Suggestions remain unverified.';
    render();conversation.scrollTop=conversation.scrollHeight;
   });
  }catch(e){if(message)message.error=controller.signal.aborted?'Stopped. Partial answer retained.':e.message;status.textContent=controller.signal.aborted?'Stopped.':e.message;}
  finally{if(active?.id===id){active=null;stop.disabled=true;send.disabled=!enabled;}render();persist();}
 };
 window.addEventListener('pagehide',stopRequest);
 document.addEventListener('change',e=>{if(['database','lab-database','explorer-database'].includes(e.target.id))stopRequest();});
 render();try{const config=await json('/api/assistant/status');enabled=config.enabled;send.disabled=!enabled;status.textContent=config.message+(config.model?' · '+config.model:'');}catch(e){status.textContent=e.message;}
 try{const raw=sessionStorage.getItem(HANDOFF);if(raw){const proposal=JSON.parse(raw);if(proposal.input.inputMode===(adapter.page==='plan-lab'?'plan':adapter.page==='query-lab'?'sql':'')){
    // Revalidate the signed candidate; navigation never runs an optimization.
    await json('/api/assistant/proposals/validate',proposal);await adapter.apply(proposal.input);sessionStorage.removeItem(HANDOFF);status.textContent='Suggested input loaded. Review it and run when ready.';
 }}}
 catch(e){sessionStorage.removeItem(HANDOFF);status.textContent='Unable to load the suggested input: '+e.message;panel.hidden=false;launcher.hidden=true;}
 try{const raw=sessionStorage.getItem(FOCUS);if(raw){const ref=JSON.parse(raw);if(ref.page===adapter.page){sessionStorage.removeItem(FOCUS);await focus(ref);}}}catch(e){status.textContent=e.message;panel.hidden=false;launcher.hidden=true;}
}
