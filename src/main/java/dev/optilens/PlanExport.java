package dev.optilens;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.apache.calcite.plan.*;
import org.apache.calcite.rel.*;
import org.apache.calcite.rel.core.*;
import org.apache.calcite.rel.hint.Hintable;
import org.apache.calcite.rel.rules.MultiJoin;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.util.*;
import static dev.optilens.Model.*;

/** Exports live relations, never EXPLAIN text. Unsupported information makes the result read-only. */
public final class PlanExport {
  final List<OperatorNode> nodes=new ArrayList<>();int serial;
  public static EditableResult export(RelNode root,FrameworkConfig config){
    try{
      var encoder=new PlanExport();String id=encoder.node(root,0);
      var plan=new PlanDocument(1,id,List.copyOf(encoder.nodes),Map.of());
      RelNode restored=Plans.build(plan,config).root();
      if(!signature(root).equals(signature(restored)))throw new IllegalArgumentException("The editable representation does not preserve every operator attribute and output type");
      return new EditableResult(plan,List.of());
    }catch(Exception|AssertionError e){return new EditableResult(null,List.of(Worker.error(e)));}
  }
  static String signature(RelNode n){n=Snapshots.unwrap(n);return Snapshots.capture(n,"full-plan").raw()+types(n);}
  static String types(RelNode n){n=Snapshots.unwrap(n);return n.getRowType().getFullTypeString()+n.getTraitSet()+n.getInputs().stream().map(PlanExport::types).toList();}
  String node(RelNode original,int depth){
    RelNode n=Snapshots.unwrap(original);if(depth>40||++serial>250)throw new IllegalArgumentException("Expanding shared inputs exceeds the editable plan size or depth limit");
    if(!(n instanceof TableScan || n instanceof Values || n instanceof Filter || n instanceof Project || n instanceof Calc || n instanceof Join || n instanceof Aggregate || n instanceof Sort || n instanceof Exchange || n instanceof SetOp || n instanceof MultiJoin))throw unsupported(n,"operator");
    String id="n"+serial;var inputs=new ArrayList<String>();for(var child:n.getInputs())inputs.add(node(child,depth+1));
    if(n.getConvention()!=Convention.NONE)throw unsupported(n,"physical convention");
    if(n instanceof Hintable h&&!h.getHints().isEmpty())throw unsupported(n,"hints");
    try {
    var a=Json.MAPPER.createObjectNode();String op;
    if(n instanceof TableScan scan){op="TableScan";a.put("table",scan.getTable().getQualifiedName().get(scan.getTable().getQualifiedName().size()-1));}
    else if(n instanceof Values values){op="Values";var columns=a.putArray("columns");for(var f:n.getRowType().getFieldList()){var c=type(f.getType());c.put("name",f.getName());columns.add(c);}var rows=a.putArray("rows");for(var tuple:values.getTuples()){var row=rows.addArray();for(var v:tuple)row.add(value(v));}}
    else if(n instanceof Filter f){op="Filter";a.set("condition",expr(f.getCondition(),n.getInputs()));}
    else if(n instanceof Project p){op="Project";projects(a,p.getProjects(),p.getRowType().getFieldNames(),n.getInputs());if(!p.getVariablesSet().isEmpty())throw unsupported(n,"correlation variables");}
    else if(n instanceof Calc c){op="Calc";var program=c.getProgram();projects(a,program.getProjectList().stream().map(program::expandLocalRef).toList(),c.getRowType().getFieldNames(),n.getInputs());if(program.getCondition()!=null)a.set("condition",expr(program.expandLocalRef(program.getCondition()),n.getInputs()));}
    else if(n instanceof Join j){op="Join";if(!j.getVariablesSet().isEmpty()||j.isSemiJoinDone()||!j.getSystemFieldList().isEmpty())throw unsupported(n,"join metadata");a.put("joinType",j.getJoinType().name());a.set("condition",expr(j.getCondition(),n.getInputs()));}
    else if(n instanceof Aggregate g){op="Aggregate";if(g.getGroupSets().size()!=1||!g.getGroupSets().get(0).equals(g.getGroupSet()))throw unsupported(n,"grouping sets");a.set("groupKeys",Json.MAPPER.valueToTree(g.getGroupSet().asList()));var calls=a.putArray("calls");
      for(var call:g.getAggCallList()){
        if(call.isApproximate()||call.ignoreNulls()||call.distinctKeys!=null||call.hasCollation()||!call.rexList.isEmpty())throw unsupported(n,"aggregate modifiers");
        String name=Plans.AGGREGATES.entrySet().stream().filter(e->e.getValue().equals(call.getAggregation())).map(Map.Entry::getKey).findFirst().orElseThrow(()->unsupported(n,"aggregate function "+call.getAggregation()));
        var c=calls.addObject().put("function",name).put("name",g.getRowType().getFieldNames().get(g.getGroupCount()+calls.size()-1)).put("distinct",call.isDistinct());c.set("args",Json.MAPPER.valueToTree(call.getArgList()));if(call.filterArg>=0)c.put("filter",call.filterArg);
      }
    }
    else if(n instanceof SortExchange e){op="SortExchange";a.set("distribution",distribution(e.getDistribution()));a.set("keys",keys(e.getCollation()));}
    else if(n instanceof Exchange e){op="Exchange";a.set("distribution",distribution(e.getDistribution()));}
    else if(n instanceof Sort s){op="Sort";a.set("keys",keys(s.getCollation()));if(s.offset!=null)a.put("offset",RexLiteral.intValue(s.offset));if(s.fetch!=null)a.put("fetch",RexLiteral.intValue(s.fetch));}
    else if(n instanceof SetOp s){op=n instanceof Union?"Union":n instanceof Intersect?"Intersect":n instanceof Minus?"Minus":null;if(op==null)throw unsupported(n,"set operation");a.put("all",s.all);}
    else if(n instanceof MultiJoin m){op="MultiJoin";if(m.isFullOuterJoin()||m.getJoinTypes().stream().anyMatch(t->t!=JoinRelType.INNER)||m.getOuterJoinConditions().stream().anyMatch(Objects::nonNull))throw unsupported(n,"outer MultiJoin");
      a.set("condition",expr(m.getJoinFilter(),n.getInputs()));if(m.getPostJoinFilter()!=null)a.set("postFilter",expr(m.getPostJoinFilter(),n.getInputs()));
      var fields=a.putArray("projectedFields");for(var f:m.getProjFields())fields.add(f==null?NullNode.instance:Json.MAPPER.valueToTree(f.asList()));
      var counts=a.putArray("refCounts");for(int i=0;i<inputs.size();i++)counts.add(Json.MAPPER.valueToTree(m.getJoinFieldRefCountsMap().get(i)));
    }else throw unsupported(n,"operator");
    nodes.add(new OperatorNode(id,op,List.copyOf(inputs),a));return id;
    }catch(IllegalArgumentException e){throw unsupported(n,e.getMessage());}
  }
  static IllegalArgumentException unsupported(RelNode n,String detail){return new IllegalArgumentException(n.getRelTypeName()+" #"+n.getId()+": unsupported editable "+detail);}
  void projects(ObjectNode a,List<? extends RexNode> expressions,List<String> names,List<RelNode> inputs){var p=a.putArray("projects");for(int i=0;i<expressions.size();i++)p.addObject().put("name",names.get(i)).set("expr",expr(expressions.get(i),inputs));}
  ObjectNode type(RelDataType t){
    if(!Input.TYPES.contains(t.getSqlTypeName().name()))throw new IllegalArgumentException("Unsupported editable type: "+t.getFullTypeString());
    var out=Json.MAPPER.createObjectNode().put("type",t.getSqlTypeName().name()).put("nullable",t.isNullable());
    if(t.getSqlTypeName()==SqlTypeName.DECIMAL)out.put("precision",t.getPrecision()).put("scale",t.getScale());
    if(t.getSqlTypeName()==SqlTypeName.VARCHAR)out.put("precision",t.getPrecision());
    if(t.getSqlTypeName()==SqlTypeName.TIMESTAMP&&t.getPrecision()!=0)throw new IllegalArgumentException("Unsupported editable timestamp precision: "+t.getPrecision());return out;
  }
  JsonNode value(RexLiteral r){if(r.isNull())return NullNode.instance;return switch(r.getType().getSqlTypeName()){
    case BOOLEAN -> BooleanNode.valueOf(r.getValueAs(Boolean.class));case CHAR,VARCHAR -> TextNode.valueOf(r.getValueAs(String.class));
    case DATE -> TextNode.valueOf(r.getValueAs(DateString.class).toString());case TIMESTAMP -> TextNode.valueOf(r.getValueAs(TimestampString.class).toString());
    default -> TextNode.valueOf(r.getValueAs(java.math.BigDecimal.class).toPlainString());};}
  JsonNode expr(RexNode r,List<RelNode> inputs){
    var out=Json.MAPPER.createObjectNode();
    if(r instanceof RexInputRef ref){int index=ref.getIndex();for(int i=0;i<inputs.size();i++){int width=inputs.get(i).getRowType().getFieldCount();if(index<width)return out.put("kind","ref").put("input",i).put("index",index);index-=width;}throw new IllegalArgumentException("Unresolved output field reference");}
    if(r instanceof RexLiteral lit){out=type(r.getType());out.put("kind","literal");out.set("value",value(lit));return out;}
    if(r instanceof RexCall call && !(r instanceof RexOver) && !(r instanceof RexSubQuery)){
      if(call.getOperator()==SqlStdOperatorTable.CAST){out=type(r.getType());out.put("kind","cast");out.set("expr",expr(call.operands.get(0),inputs));return out;}
      String name=Plans.OPERATORS.entrySet().stream().filter(e->e.getValue().equals(call.getOperator())).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IllegalArgumentException("Unsupported editable expression: "+call.getOperator()));
      out.put("kind","call").put("op",name);var args=out.putArray("args");for(var arg:call.operands)args.add(expr(arg,inputs));return out;
    }throw new IllegalArgumentException("Unsupported editable expression: "+r.getClass().getSimpleName());
  }
  JsonNode keys(RelCollation collation){var keys=Json.MAPPER.createArrayNode();for(var k:collation.getFieldCollations()){
    if(!Set.of(RelFieldCollation.Direction.ASCENDING,RelFieldCollation.Direction.DESCENDING).contains(k.direction)||k.nullDirection==RelFieldCollation.NullDirection.UNSPECIFIED)throw new IllegalArgumentException("Unsupported editable collation");
    keys.addObject().put("index",k.getFieldIndex()).put("direction",k.direction==RelFieldCollation.Direction.ASCENDING?"ASC":"DESC").put("nulls",k.nullDirection.name());}return keys;}
  JsonNode distribution(RelDistribution d){var out=Json.MAPPER.createObjectNode();String type=switch(d.getType()){case HASH_DISTRIBUTED -> "HASH";case SINGLETON -> "SINGLETON";case BROADCAST_DISTRIBUTED -> "BROADCAST";case RANDOM_DISTRIBUTED -> "RANDOM";default -> throw new IllegalArgumentException("Unsupported editable distribution: "+d);};out.put("type",type);if(type.equals("HASH"))out.set("keys",Json.MAPPER.valueToTree(d.getKeys()));return out;}
}
