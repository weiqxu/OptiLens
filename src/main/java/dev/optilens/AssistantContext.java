package dev.optilens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static dev.optilens.Model.*;

/** Rehydrates evidence from local records; browser-supplied snapshots are never trusted. */
final class AssistantContext {
  final String database,page,version;
  final ObjectNode data=Json.MAPPER.createObjectNode();
  final Map<String,JsonNode> references=new LinkedHashMap<>();
  final List<String> paths=new ArrayList<>();
  final Map<String,AssistantSources.Excerpt> sources=new LinkedHashMap<>();
  AssistantContext(JsonNode context,Jobs jobs,String question){
    Input.require(context.isObject(),"Missing page context");
    database=context.path("database").asText();Engines.validateDatabase(database);
    page=context.path("page").asText();Input.require(Set.of("plan-lab","query-lab","rules").contains(page),"Unknown assistant page");
    version=context.path("version").asText();Input.require(version.matches("[0-9a-f]{64}"),"Missing context version");
    data.put("page",page);data.put("database",database);data.put("engineVersion",database.equals("calcite")?"1.39.0":database.equals("trafodion")?Trafodion.VERSION:StarRocks.VERSION);
    data.put("contextVersion",version);
    JsonNode draft=context.path("input");
    Input.require(draft.toString().length()<=100_000,"Draft exceeds the input limit");
    data.set("draft",draft); // Drafts may be disconnected or invalid; they are not execution evidence.
    String issue=context.path("validationMessage").asText();Input.require(issue.length()<=8000,"Validation message is too long");
    data.put("reportedValidationMessage",issue);data.set("selectedNode",context.path("selectedNode"));
    var ruleIds=new LinkedHashSet<String>();
    for(var r:draft.path("rules"))ruleIds.add(r.asText());
    if(context.hasNonNull("ruleId"))ruleIds.add(context.path("ruleId").asText());
    Input.require(ruleIds.size()<=32,"Too many context rules");
    String selectedRun=context.path("runId").asText("");
    if(!selectedRun.isBlank()){
      var record=jobs.get(selectedRun);int stageIndex=context.path("stage").asInt(-1);
      if(record!=null&&record.result()!=null&&record.result().input().database().equals(database)&&stageIndex>=0&&stageIndex<record.result().stages().size())ruleIds.add(record.result().stages().get(stageIndex).id());
    }
    var cards=database.equals("calcite")?Rules.cards():database.equals("trafodion")?Trafodion.rules():StarRocks.rules();
    var relevant=cards.stream().filter(c->ruleIds.contains(c.id())).toList();
    relevant.forEach(c->paths.add(c.source()));data.set("rules",Json.MAPPER.valueToTree(relevant));
    data.set("ruleSourceMappings",Json.MAPPER.valueToTree(relevant.stream().map(r->AssistantSources.instance().mapping(database,r)).toList()));
    data.set("capabilities",Json.MAPPER.valueToTree(Engines.planCapabilities(database)));
    var examples=Json.MAPPER.valueToTree(Engines.examples(database));
    var evidence=data.putArray("examples");int size=0;
    for(var e:examples.path("examples"))if(ruleIds.contains(e.path("id").asText())&&size+e.toString().length()<30000){evidence.add(e);size+=e.toString().length();}
    var coverage=data.putArray("coverage");for(var c:examples.path("coverage"))if(ruleIds.contains(c.path("id").asText()))coverage.add(c);
    if(page.equals("plan-lab")&&draft.path("inputMode").asText().equals("plan"))for(var node:draft.path("plan").path("nodes")){
      if(references.size()>=250)break;
      String id=node.path("id").asText();if(id.length()>128)continue;
      references.put("draft:"+id,Json.MAPPER.valueToTree(Map.of("kind","draft","nodeId",id,"version",version,"database",database,"page",page)));
    }
    String runId=context.path("runId").asText("");
    if(!runId.isBlank()){
      var job=jobs.get(runId);Input.require(job!=null&&job.result()!=null,"The selected run is unavailable");
      var run=job.result();Input.require(run.input().database().equals(database),"The selected run belongs to another database");
      int stage=context.path("stage").asInt(-1),event=context.path("event").asInt(-1);
      Input.require(stage>=-1&&stage<run.stages().size(),"Unknown result stage");
      Input.require(event>=-1&&(event==-1||stage>=0&&event<run.stages().get(stage).events().size()),"Unknown result event");
      Snapshot before=run.initial(),after=run.finalPlan();var result=data.putObject("run");
      result.put("id",job.id());result.put("provenance",job.imported()?"imported-unverified":"local-execution");result.set("input",Json.MAPPER.valueToTree(run.input()));
      result.set("limitations",Json.MAPPER.valueToTree(run.limitations()));result.put("stage",stage);result.put("event",event);
      if(stage>=0){var s=run.stages().get(stage);before=s.before();after=s.after();result.put("rule",s.id());result.set("attempts",Json.MAPPER.valueToTree(s.attempts()));result.put("eventCount",s.events().size());result.put("scopeBeforeRootId",s.scopeBeforeRootId());result.put("scopeAfterRootId",s.scopeAfterRootId());
        if(event>=0){var e=s.events().get(event);before=e.before();after=e.after();result.put("callId",e.callId());}}
      result.set("before",Json.MAPPER.valueToTree(before));result.set("after",Json.MAPPER.valueToTree(after));
      for(String side:List.of("before","after"))for(var n:(side.equals("before")?before:after).nodes()){
        String key="run:"+runId+":"+stage+":"+event+":"+side+":"+n.id();
        references.put(key,Json.MAPPER.valueToTree(Map.of("kind","result","runId",runId,"stage",stage,"event",event,"side",side,"nodeId",n.id(),"database",database,"page",page)));
      }
    }
    data.set("nodeReferences",Json.MAPPER.valueToTree(references));
    addSources(AssistantSources.instance().search(database,question+" "+String.join(" ",ruleIds),paths));
    data.set("sourceExcerpts",Json.MAPPER.valueToTree(sources.values()));
    Input.require(data.toString().length()<=450_000,"Selected context is too large. Choose a smaller stage or local event.");
  }
  void addSources(List<AssistantSources.Excerpt> excerpts){excerpts.forEach(e->sources.put(e.id(),e));}
}
