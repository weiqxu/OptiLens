package dev.optilens;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.optilens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class PlansTest {
  static OperatorNode node(String id,String op,String attrs,String... inputs) throws Exception {
    return new OperatorNode(id,op,List.of(inputs),Json.MAPPER.readTree(attrs));
  }
  static OperatorNode scan(String id) throws Exception{return node(id,"TableScan","{\"table\":\"customers\"}");}
  static OperatorNode project(String id,String input,int index) throws Exception{return node(id,"Project","{\"projects\":[{\"name\":\"x\",\"expr\":{\"kind\":\"ref\",\"input\":0,\"index\":"+index+"}}]}",input);}
  static OperatorNode computed(String id,String input) throws Exception {
    return node(id,"Project", "{\"projects\":[{\"name\":\"x\",\"expr\":{\"kind\":\"call\",\"op\":\"+\",\"args\":[{\"kind\":\"ref\",\"input\":0,\"index\":0},{\"kind\":\"literal\",\"type\":\"INTEGER\",\"value\":1}]}}]}",input);
  }
  static Request request(String root,List<OperatorNode> nodes,List<String> rules,String scope){return new Request(null,Input.examples().get(0).input().schema(),rules,"calcite",new Options(null,10),"plan",new PlanDocument(1,root,nodes,Map.of()),scope);}
  @Test void directConstructionPreservesRedundantOperatorsAndRoundTrips() throws Exception {
    var nodes=List.of(scan("s"),project("p1","s",0),project("p2","p1",0),node("f","Filter","{\"condition\":{\"kind\":\"literal\",\"type\":\"BOOLEAN\",\"value\":true}}","p2"),node("u","Union","{\"all\":true}","f"));
    var run=new Optimizer().optimize(request("u",nodes,List.of(),null));
    assertEquals(5,run.initial().nodes().size());assertEquals(run.initial(),run.finalPlan());assertEquals(2,run.formatVersion());
    assertNotNull(run.editableResult().plan(),run.editableResult().reasons().toString());
    var next=new Request(null,run.input().schema(),List.of(),"calcite",null,"plan",run.editableResult().plan(),null);
    var rerun=new Optimizer().optimize(next);assertEquals(run.finalPlan().raw(),rerun.initial().raw());Records.validate(run);
    var encoded=Json.MAPPER.writeValueAsBytes(run);Records.validate(Json.MAPPER.readValue(encoded,Run.class));
  }
  @Test void scopeChangesOnlyOneBranchAndSupportsContinuingTheResult() throws Exception {
    var nodes=List.of(scan("left"),computed("l1","left"),computed("l2","l1"),scan("right"),computed("r1","right"),computed("r2","r1"),node("u","Union","{\"all\":true}","l2","r2"));
    var run=new Optimizer().optimize(request("u",nodes,List.of("CoreRules.PROJECT_MERGE"),"l2"));Records.validate(run);
    assertEquals(4,run.initial().nodes().stream().filter(n->n.type().equals("LogicalProject")).count());
    assertEquals(3,run.finalPlan().nodes().stream().filter(n->n.type().equals("LogicalProject")).count());
    var right=run.initial().nodes().stream().filter(n->n.type().equals("LogicalTableScan")).toList().get(1);
    assertTrue(run.finalPlan().nodes().contains(right));assertNotEquals(run.initial().rootId(),run.stages().get(0).scopeBeforeRootId());
    assertNotNull(run.editableResult().plan(),run.editableResult().reasons().toString());
    var next=new Request(null,run.input().schema(),List.of("CoreRules.PROJECT_MERGE"),"calcite",null,"plan",run.editableResult().plan(),null);
    var second=new Optimizer().optimize(next);Records.validate(second);
    assertFalse(second.stages().get(0).events().isEmpty());
    var byId=new HashMap<String,PlanNode>();second.finalPlan().nodes().forEach(n->byId.put(n.id(),n));
    for(String child:byId.get(second.finalPlan().rootId()).inputs()){
      var projection=byId.get(child);assertEquals("LogicalProject",projection.type());
      assertEquals("LogicalTableScan",byId.get(projection.inputs().get(0)).type());
    }
  }
  @Test void rejectsStructuralAndTypedErrorsWithLocations() throws Exception {
    var s=scan("s");
    assertThrows(IllegalArgumentException.class,()->Input.validate(request("s",List.of(s,s),List.of(),null)));
    assertThrows(IllegalArgumentException.class,()->Input.validate(request("s",List.of(s),List.of(),"missing")));
    assertThrows(IllegalArgumentException.class,()->Input.validate(request("u",List.of(s,node("u","Union","{}","s","s")),List.of(),null)));
    assertThrows(IllegalArgumentException.class,()->Input.validate(request("p",List.of(project("p","p",0)),List.of(),null)));
    var bad=new Optimizer().validate(request("p",List.of(s,project("p","s",90)),List.of(),null));assertFalse(bad.valid());assertEquals("p",bad.errors().get(0).nodeId());assertTrue(bad.errors().get(0).path().contains("index"));
    var badCondition=new Optimizer().validate(request("f",List.of(s,node("f","Filter","{\"condition\":{\"kind\":\"literal\",\"type\":\"INTEGER\",\"value\":1}}","s")),List.of(),null));assertFalse(badCondition.valid());assertEquals("f",badCondition.errors().get(0).nodeId());
    assertFalse(new Optimizer().validate(request("f",List.of(s,node("f","Filter","{\"condition\":{\"kind\":\"call\",\"op\":\"+\",\"args\":[{\"kind\":\"literal\",\"type\":\"BOOLEAN\",\"value\":true},{\"kind\":\"literal\",\"type\":\"INTEGER\",\"value\":1}]}}","s")),List.of(),null)).valid());
  }
  @Test void unsupportedResultsAreExplicitAndLegacyRecordsRemainReadable() throws Exception {
    var example=RuleExamples.all().stream().filter(e->e.id().equals("CoreRules.JOIN_TO_CORRELATE")).findFirst().orElseThrow();var run=new Optimizer().optimize(example.input());
    assertNull(run.editableResult().plan());assertTrue(run.editableResult().reasons().get(0).contains("Correlate"));
    var legacy=new Run(1,run.engine(),run.engineVersion(),run.createdAt(),run.input(),run.initial(),run.finalPlan(),run.stages(),run.limitations());Records.validate(legacy);
    var json=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(legacy);((com.fasterxml.jackson.databind.node.ObjectNode)json.get("input")).remove(List.of("inputMode","plan","scopeNodeId"));json.remove("editableResult");
    Records.validate(Json.MAPPER.treeToValue(json,Run.class));
  }
  @Test void allManualExamplesHaveLiveTargetEvents() throws Exception {
    assertEquals(9,PlanExamples.all().size());
    for(var example:PlanExamples.all()){
      var run=new Optimizer().optimize(example.input());Records.validate(run);
      assertFalse(run.stages().get(0).events().isEmpty(),example.id());
      assertNotEquals(run.stages().get(0).events().get(0).before().raw(),run.stages().get(0).events().get(0).after().raw());
    }
  }
  static void assertEditable(Request input) throws Exception {
    var run=new Optimizer().optimize(input);
    assertNotNull(run.editableResult().plan(),run.editableResult().reasons().toString());
    var next=new Request(null,input.schema(),List.of(),"calcite",null,"plan",run.editableResult().plan(),null);
    assertEquals(run.finalPlan().raw(),new Optimizer().optimize(next).initial().raw());
  }
  @Test void supportedOperatorsAndScalarTypesRoundTripWithoutRules() throws Exception {
    String values="""
        {"columns":[{"name":"i","type":"INTEGER","nullable":false},
          {"name":"b","type":"BOOLEAN","nullable":false},
          {"name":"d","type":"DECIMAL","precision":18,"scale":2},
          {"name":"s","type":"VARCHAR","precision":20},
          {"name":"date","type":"DATE"},{"name":"ts","type":"TIMESTAMP"},
          {"name":"big","type":"BIGINT"},{"name":"double","type":"DOUBLE"}],
         "rows":[[1,true,"123.45","text","2026-01-01","2026-01-01 10:00:00","9223372036854775807","1.5"],
                 [2,false,null,null,null,null,null,null]]}
        """;
    var v=node("v","Values",values);assertEditable(request("v",List.of(v),List.of(),null));
    for(String op:List.of("Union","Intersect","Minus"))for(boolean all:List.of(true,false))
      assertEditable(request("set",List.of(v,node("v2","Values",values),node("set",op,"{\"all\":"+all+"}","v","v2")),List.of(),null));
    for(String type:List.of("INNER","LEFT","RIGHT","FULL","SEMI","ANTI"))
      assertEditable(request("j",List.of(scan("l"),scan("r"),node("j","Join","{\"joinType\":\""+type+"\",\"condition\":{\"kind\":\"call\",\"op\":\"=\",\"args\":[{\"kind\":\"ref\",\"input\":0,\"index\":0},{\"kind\":\"ref\",\"input\":1,\"index\":0}]}}","l","r")),List.of(),null));
    for(String fn:List.of("COUNT","SUM","SUM0","AVG","MIN","MAX"))
      assertEditable(request("a",List.of(v,node("a","Aggregate","{\"groupKeys\":[],\"calls\":[{\"function\":\""+fn+"\",\"args\":[0],\"name\":\"result\",\"distinct\":true,\"filter\":1}]}","v")),List.of(),null));
    assertEditable(request("a",List.of(v,node("a","Aggregate","{\"groupKeys\":[],\"calls\":[]}","v")),List.of(),null));
    assertEditable(request("sort",List.of(v,node("sort","Sort","{\"keys\":[{\"index\":0,\"direction\":\"DESC\",\"nulls\":\"FIRST\"}],\"offset\":2,\"fetch\":4}","v")),List.of(),null));
    for(String type:List.of("HASH","RANDOM","BROADCAST","SINGLETON"))for(String op:List.of("Exchange","SortExchange"))
      assertEditable(request("e",List.of(v,node("e",op,"{\"distribution\":{\"type\":\""+type+"\""+(type.equals("HASH")?",\"keys\":[0]":"")+"}"+(op.equals("SortExchange")?",\"keys\":[{\"index\":0,\"direction\":\"ASC\",\"nulls\":\"LAST\"}]":"")+"}","v")),List.of(),null));
    var projection=project("c","v",0);assertEditable(request("c",List.of(v,new OperatorNode("c","Calc",projection.inputs(),projection.attributes())),List.of(),null));
    for(var example:PlanExamples.all())assertEditable(new Request(null,example.input().schema(),List.of(),"calcite",null,"plan",example.input().plan(),null));
  }
  @Test void expressionLimitsAndUnmatchedProgramsAreExplicit() throws Exception {
    var expression=Json.MAPPER.createObjectNode().put("kind","literal").put("type","BOOLEAN").put("value",true);
    for(int i=0;i<34;i++){var outer=Json.MAPPER.createObjectNode().put("kind","call").put("op","NOT");outer.putArray("args").add(expression);expression=outer;}
    var attributes=Json.MAPPER.createObjectNode().set("condition",expression);
    assertThrows(IllegalArgumentException.class,()->new Optimizer().optimize(request("f",List.of(scan("s"),new OperatorNode("f","Filter",List.of("s"),attributes)),List.of(),null)));
    var run=new Optimizer().optimize(request("s",List.of(scan("s")),List.of("CoreRules.UNION_REMOVE"),null));
    assertTrue(run.stages().get(0).events().isEmpty());assertEquals(run.initial().raw(),run.finalPlan().raw());
  }

  @Test void rejectsScopedOutputChangesAndOversizedSharedResults() throws Exception {
    var values=node("v","Values","{\"columns\":[{\"name\":\"id\",\"type\":\"INTEGER\",\"nullable\":false}],\"rows\":[[1]]}");
    var error=assertThrows(IllegalArgumentException.class,()->new Optimizer().optimize(request("f",List.of(values,project("p","v",0),node("f","Filter","{\"condition\":{\"kind\":\"literal\",\"type\":\"BOOLEAN\",\"value\":true}}","p")),List.of("CoreRules.PROJECT_REMOVE"),"p")));
    assertTrue(error.getMessage().contains("Scoped rewrite changed output"),error.getMessage());
    var input=request("v",List.of(values),List.of(),null);var config=Optimizer.config(input);
    var child=Plans.build(input.plan(),config).root();
    var dag=org.apache.calcite.rel.logical.LogicalUnion.create(Collections.nCopies(251,child),true);
    var exported=PlanExport.export(dag,config);assertNull(exported.plan());assertTrue(exported.reasons().get(0).contains("Expanding shared inputs"));
  }
  @Test void rejectsImplicitCastsAndExpressionCountOverflow() throws Exception {
    var wideDecimal=node("v","Values","{\"columns\":[{\"name\":\"x\",\"type\":\"DECIMAL\",\"precision\":38,\"scale\":2}],\"rows\":[[1]]}");
    var decimalError=new Optimizer().validate(request("v",List.of(wideDecimal),List.of(),null));assertFalse(decimalError.valid());assertTrue(decimalError.errors().get(0).message().contains("precision"));
    var mixed=node("p","Project","{\"projects\":[{\"name\":\"x\",\"expr\":{\"kind\":\"call\",\"op\":\"+\",\"args\":[{\"kind\":\"literal\",\"type\":\"INTEGER\",\"value\":1},{\"kind\":\"literal\",\"type\":\"BIGINT\",\"value\":2}]}}]}","s");
    var failure=new Optimizer().validate(request("p",List.of(scan("s"),mixed),List.of(),null));assertFalse(failure.valid());assertTrue(failure.errors().get(0).message().contains("explicit CAST"));
    var attrs=Json.MAPPER.createObjectNode();var projects=attrs.putArray("projects");
    for(int i=0;i<701;i++)projects.addObject().put("name","x"+i).set("expr",computed("p","s").attributes().get("projects").get(0).get("expr"));
    var large=request("p",List.of(scan("s"),new OperatorNode("p","Project",List.of("s"),attrs)),List.of(),null);
    Input.validate(large);var invalid=new Optimizer().validate(large);assertFalse(invalid.valid());assertTrue(invalid.errors().get(0).message().contains("Expression depth or count"));
  }

}
