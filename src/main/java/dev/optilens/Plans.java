package dev.optilens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.google.common.collect.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.calcite.plan.*;
import org.apache.calcite.rel.*;
import org.apache.calcite.rel.core.*;
import org.apache.calcite.rel.logical.*;
import org.apache.calcite.rel.rules.MultiJoin;
import org.apache.calcite.rel.type.*;
import org.apache.calcite.rex.*;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.*;
import org.apache.calcite.tools.*;
import org.apache.calcite.util.*;
import static dev.optilens.Model.*;

/** Versioned, closed input language for constructing logical plans without SQL rewrites. */
public final class Plans {
  static final int MAX_DECIMAL_PRECISION=RelDataTypeSystem.DEFAULT.getMaxPrecision(SqlTypeName.DECIMAL);
  public static final Map<String,Integer> ARITY = Map.ofEntries(
      Map.entry("TableScan",0),Map.entry("Values",0),Map.entry("Filter",1),Map.entry("Project",1),
      Map.entry("Join",2),Map.entry("Aggregate",1),Map.entry("Sort",1),Map.entry("Union",-1),
      Map.entry("Intersect",-1),Map.entry("Minus",-1),Map.entry("Calc",1),Map.entry("Exchange",1),
      Map.entry("SortExchange",1),Map.entry("MultiJoin",-2));
  public static final Map<String,SqlOperator> OPERATORS = Map.ofEntries(
      Map.entry("+",SqlStdOperatorTable.PLUS),Map.entry("-",SqlStdOperatorTable.MINUS),
      Map.entry("*",SqlStdOperatorTable.MULTIPLY),Map.entry("/",SqlStdOperatorTable.DIVIDE),
      Map.entry("=",SqlStdOperatorTable.EQUALS),Map.entry("<>",SqlStdOperatorTable.NOT_EQUALS),
      Map.entry(">",SqlStdOperatorTable.GREATER_THAN),Map.entry(">=",SqlStdOperatorTable.GREATER_THAN_OR_EQUAL),
      Map.entry("<",SqlStdOperatorTable.LESS_THAN),Map.entry("<=",SqlStdOperatorTable.LESS_THAN_OR_EQUAL),
      Map.entry("AND",SqlStdOperatorTable.AND),Map.entry("OR",SqlStdOperatorTable.OR),Map.entry("NOT",SqlStdOperatorTable.NOT),
      Map.entry("IS NULL",SqlStdOperatorTable.IS_NULL),Map.entry("IS NOT NULL",SqlStdOperatorTable.IS_NOT_NULL),
      Map.entry("IS TRUE",SqlStdOperatorTable.IS_TRUE),Map.entry("IS FALSE",SqlStdOperatorTable.IS_FALSE),
      Map.entry("IS NOT DISTINCT FROM",SqlStdOperatorTable.IS_NOT_DISTINCT_FROM),
      Map.entry("COALESCE",SqlStdOperatorTable.COALESCE),Map.entry("CASE",SqlStdOperatorTable.CASE));
  public static final Map<String,SqlAggFunction> AGGREGATES = Map.of("COUNT",SqlStdOperatorTable.COUNT,"SUM",SqlStdOperatorTable.SUM,
      "SUM0",SqlStdOperatorTable.SUM0,"AVG",SqlStdOperatorTable.AVG,"MIN",SqlStdOperatorTable.MIN,"MAX",SqlStdOperatorTable.MAX);
  public static final class Invalid extends IllegalArgumentException {
    public final PlanIssue issue;
    Invalid(String node,String path,String message){super((node==null?"Plan":node)+" · "+path+": "+message);issue=new PlanIssue(node,path,message);}
  }
  static void need(boolean ok,String node,String path,String message){if(!ok)throw new Invalid(node,path,message);}
  public static Map<String,Object> capabilities(){
    return Map.of("planVersion",1,"operators",ARITY,"expressions",OPERATORS.keySet().stream().sorted().toList(),
        "aggregates",AGGREGATES.keySet().stream().sorted().toList(),"types",Input.TYPES.stream().sorted().toList(),
        "limits",Map.of("nodes",250,"depth",40,"expressionDepth",32,"expressionItems",2000,"valueRows",1000),
        "parameters",Map.of("joinTypes",List.of("INNER","LEFT","RIGHT","FULL","SEMI","ANTI"),
          "distributions",List.of("HASH","SINGLETON","BROADCAST","RANDOM"),"sortDirections",List.of("ASC","DESC"),"nullOrdering",List.of("FIRST","LAST"),
          "expressionForms",Map.of("ref",List.of("input","index"),"literal",List.of("type","nullable","precision","scale","value"),"cast",List.of("type","nullable","precision","scale","expr"),"call",List.of("op","args")),
          "decimal",Map.of("precisionMin",1,"precisionMax",MAX_DECIMAL_PRECISION,"defaultPrecision",18,"defaultScale",2),"varchar",Map.of("lengthMin",1,"lengthMax",65536,"defaultLength",255),"timestampPrecision",0,"multiJoinTypes",List.of("INNER")),
        "attributes",Map.ofEntries(Map.entry("TableScan",List.of("table")),Map.entry("Values",List.of("columns","rows")),
          Map.entry("Filter",List.of("condition")),Map.entry("Project",List.of("projects")),Map.entry("Calc",List.of("projects","condition")),
          Map.entry("Join",List.of("joinType","condition")),Map.entry("Aggregate",List.of("groupKeys","calls")),
          Map.entry("Sort",List.of("keys","offset","fetch")),Map.entry("Exchange",List.of("distribution")),
          Map.entry("SortExchange",List.of("distribution","keys")),Map.entry("MultiJoin",List.of("condition","postFilter","projectedFields","refCounts")),
          Map.entry("Union",List.of("all")),Map.entry("Intersect",List.of("all")),Map.entry("Minus",List.of("all"))));
  }
  public static void validateStructure(PlanDocument p){
    need(p!=null,null,"plan","A plan is required");need(p.planVersion()==1,null,"planVersion","Unsupported plan version");
    need(p.nodes()!=null && !p.nodes().isEmpty() && p.nodes().size()<=250,null,"nodes","Provide 1–250 nodes");
    var nodes=new LinkedHashMap<String,OperatorNode>();int[] count={0};
    for(var n:p.nodes()){
      need(n!=null,null,"nodes","Null node");need(n.id()!=null&&n.id().matches("[A-Za-z0-9_-]{1,64}"),n.id(),"id","Use a unique identifier of 1–64 letters, digits, underscores or hyphens");
      need(nodes.put(n.id(),n)==null,n.id(),"id","Duplicate node identifier");
      need(n.op()!=null&&ARITY.containsKey(n.op()),n.id(),"op","Unsupported operator");
      need(n.inputs()!=null&&n.inputs().size()<=250,n.id(),"inputs","Invalid input list");int arity=ARITY.get(n.op());
      need(arity>=0?n.inputs().size()==arity:n.inputs().size()>=-arity,n.id(),"inputs","Wrong input count for "+n.op());
      need(n.attributes()!=null&&n.attributes().isObject(),n.id(),"attributes","An attribute object is required");
      jsonLimits(n.attributes(),n.id(),0,count);
    }
    need(nodes.containsKey(p.rootId()),null,"rootId","Unknown root");var parents=new HashSet<String>();
    for(var n:p.nodes())for(String child:n.inputs()){
      need(child!=null&&nodes.containsKey(child),n.id(),"inputs","Unknown input node");
      need(parents.add(child),n.id(),"inputs","Shared inputs are not supported; duplicate the subtree instead");
    }
    need(!parents.contains(p.rootId()),p.rootId(),"rootId","The root cannot be an input");
    var visited=new HashSet<String>();walk(p.rootId(),nodes,visited,new HashSet<>(),0);
    need(visited.size()==nodes.size(),null,"nodes","All nodes must be connected to the root");
    if(p.layout()!=null){need(p.layout().size()<=250,null,"layout","Too many positions");p.layout().forEach((id,pos)->need(nodes.containsKey(id)&&pos!=null&&Double.isFinite(pos.x())&&Double.isFinite(pos.y())&&Math.abs(pos.x())<=100000&&Math.abs(pos.y())<=100000,id,"layout","Invalid node position"));}
  }
  static void jsonLimits(JsonNode n,String id,int depth,int[] count){
    need(depth<=64&&++count[0]<=20000,id,"attributes","Attribute structure is too large or deep");
    if(n.isTextual())need(n.textValue().length()<=10000,id,"attributes","Attribute text is too long");
    if(n.isContainerNode())n.forEach(v->jsonLimits(v,id,depth+1,count));
  }
  static void walk(String id,Map<String,OperatorNode> nodes,Set<String> visited,Set<String> path,int depth){
    need(depth<=40,id,"inputs","Plan exceeds 40 levels");need(path.add(id),id,"inputs","Cycle detected");
    for(String child:nodes.get(id).inputs())walk(child,nodes,visited,path,depth+1);path.remove(id);visited.add(id);
  }
  public record Built(RelNode root,Map<String,RelNode> nodes) {}
  public static Built build(PlanDocument document,FrameworkConfig config){return new Compiler(config).build(document);}
  static final class Compiler {
    final RelBuilder builder;final RelOptCluster cluster;final RelDataTypeFactory types;final RexBuilder rex;
    final Map<String,RelNode> built=new LinkedHashMap<>();Map<String,OperatorNode> specs;String node;int expressions;
    Compiler(FrameworkConfig config){builder=RelBuilder.create(config);cluster=builder.getCluster();types=cluster.getTypeFactory();rex=cluster.getRexBuilder();}
    Built build(PlanDocument p){validateStructure(p);specs=new HashMap<>();p.nodes().forEach(n->specs.put(n.id(),n));var root=node(p.rootId());return new Built(root,Map.copyOf(built));}
    RelNode node(String id){
      var n=specs.get(id);var inputs=n.inputs().stream().map(this::node).toList();node=id;JsonNode a=n.attributes();
      try {
        var allowed=(Map<?,?>)capabilities().get("attributes");var fields=(List<?>)allowed.get(n.op());
        a.fieldNames().forEachRemaining(k->need(fields.contains(k),id,"attributes."+k,"Unknown attribute"));
        RelNode result=switch(n.op()){
          case "TableScan" -> builder.scan(text(a,"table")).build();
          case "Values" -> values(a);
          case "Filter" -> LogicalFilter.create(inputs.get(0),condition(required(a,"condition"),inputs,"condition"));
          case "Project" -> project(a,inputs,false);
          case "Calc" -> project(a,inputs,true);
          case "Join" -> {
            var kind=JoinRelType.valueOf(text(a,"joinType"));need(Set.of(JoinRelType.INNER,JoinRelType.LEFT,JoinRelType.RIGHT,JoinRelType.FULL,JoinRelType.SEMI,JoinRelType.ANTI).contains(kind),node,"joinType","Unsupported join type");
            yield LogicalJoin.create(inputs.get(0),inputs.get(1),List.of(),condition(required(a,"condition"),inputs,"condition"),Set.of(),kind);
          }
          case "Aggregate" -> aggregate(a,inputs.get(0));
          case "Sort" -> LogicalSort.create(inputs.get(0),collation(a.path("keys"),inputs.get(0)),bound(a,"offset"),bound(a,"fetch"));
          case "Union","Intersect","Minus" -> {
            for(var input:inputs)need(RelOptUtil.areRowTypesEqual(inputs.get(0).getRowType(),input.getRowType(),false),node,"inputs","Set inputs must have identical column types; insert explicit casts");
            boolean all=bool(a,"all",false);
            yield n.op().equals("Union")?LogicalUnion.create(inputs,all):n.op().equals("Intersect")?LogicalIntersect.create(inputs,all):LogicalMinus.create(inputs,all);
          }
          case "Exchange" -> LogicalExchange.create(inputs.get(0),distribution(required(a,"distribution"),inputs.get(0)));
          case "SortExchange" -> LogicalSortExchange.create(inputs.get(0),distribution(required(a,"distribution"),inputs.get(0)),collation(a.path("keys"),inputs.get(0)));
          case "MultiJoin" -> multi(a,inputs);
          default -> throw new IllegalArgumentException("Unsupported operator");
        };
        need(result.isValid(Litmus.IGNORE,null),node,"attributes","Operator is not type-valid");built.put(id,result);return result;
      }catch(Invalid e){throw e;}catch(Exception|AssertionError e){throw new Invalid(id,"attributes",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
    }
    void keys(JsonNode object,String path,String... allowed){
      need(object!=null&&object.isObject(),node,path,"Expected an object");var names=Set.of(allowed);
      object.fieldNames().forEachRemaining(k->need(names.contains(k),node,path+"."+k,"Unknown attribute"));
    }
    JsonNode required(JsonNode a,String key){need(a.hasNonNull(key),node,key,"Required field");return a.get(key);}
    String text(JsonNode a,String key){var v=required(a,key);need(v.isTextual(),node,key,"Expected text");return v.asText();}
    boolean bool(JsonNode a,String key,boolean fallback){if(!a.has(key))return fallback;need(a.get(key).isBoolean(),node,key,"Expected boolean");return a.get(key).asBoolean();}
    List<Integer> indexes(JsonNode a,RelNode input,String path){need(a.isArray(),node,path,"Expected field indexes");var result=new ArrayList<Integer>();for(var i:a){need(i.isIntegralNumber()&&i.canConvertToInt()&&i.intValue()>=0&&i.intValue()<input.getRowType().getFieldCount(),node,path,"Field index out of range");result.add(i.intValue());}return result;}
    RelDataType type(JsonNode spec){
      String name=text(spec,"type");need(Input.TYPES.contains(name),node,"type","Unsupported scalar type");var sql=SqlTypeName.valueOf(name);
      for(String key:List.of("precision","scale"))if(spec.has(key))need(spec.get(key).isIntegralNumber()&&spec.get(key).canConvertToInt(),node,key,"Expected an integer");
      need(!spec.has("precision")||sql==SqlTypeName.DECIMAL||sql==SqlTypeName.VARCHAR,node,"precision","Precision is only supported for DECIMAL and VARCHAR");
      need(!spec.has("scale")||sql==SqlTypeName.DECIMAL,node,"scale","Scale is only supported for DECIMAL");
      int precision=spec.path("precision").asInt(sql==SqlTypeName.DECIMAL?18:sql==SqlTypeName.VARCHAR?255:0),scale=spec.path("scale").asInt(2);
      if(sql==SqlTypeName.DECIMAL)need(precision>=1&&precision<=MAX_DECIMAL_PRECISION&&scale>=0&&scale<=precision,node,"type","Invalid decimal precision or scale");
      if(sql==SqlTypeName.VARCHAR)need(precision>=1&&precision<=65536,node,"type","Invalid string length");
      RelDataType t=sql==SqlTypeName.DECIMAL?types.createSqlType(sql,precision,scale):sql==SqlTypeName.VARCHAR?types.createSqlType(sql,precision):types.createSqlType(sql);
      return types.createTypeWithNullability(t,bool(spec,"nullable",true));
    }
    RelNode values(JsonNode a){
      var columns=required(a,"columns");need(columns.isArray()&&columns.size()<=32,node,"columns","Use at most 32 columns");var row=types.builder();var names=new HashSet<String>();
      for(var c:columns){keys(c,"columns","name","type","nullable","precision","scale");String name=text(c,"name");need(names.add(name),node,"columns","Column names must be unique");row.add(name,type(c));}
      var rowType=row.build();var tuples=ImmutableList.<ImmutableList<RexLiteral>>builder();var rows=required(a,"rows");need(rows.isArray()&&rows.size()<=1000,node,"rows","Use at most 1000 literal rows");
      for(var r:rows){need(r.isArray()&&r.size()==columns.size(),node,"rows","Row width differs from columns");var tuple=ImmutableList.<RexLiteral>builder();for(int i=0;i<r.size();i++)tuple.add(literal(r.get(i),rowType.getFieldList().get(i).getType()));tuples.add(tuple.build());}
      return LogicalValues.create(cluster,rowType,tuples.build());
    }
    RexLiteral literal(JsonNode value,RelDataType t){
      need(value!=null,node,"value","A literal value is required (use null explicitly)");
      if(value.isNull()){need(t.isNullable(),node,"value","NULL requires a nullable type");return rex.makeNullLiteral(t);}
      Object v=switch(t.getSqlTypeName()){
        case BOOLEAN -> {need(value.isBoolean(),node,"value","Expected boolean");yield value.booleanValue();}
        case VARCHAR -> {need(value.isTextual()&&value.textValue().length()<=t.getPrecision(),node,"value","Expected text within the declared length");yield value.textValue();}
        case DATE -> {need(value.isTextual(),node,"value","Expected a date string");yield new DateString(value.textValue());}
        case TIMESTAMP -> {need(value.isTextual()&&value.textValue().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),node,"value","Expected a timestamp with whole seconds (YYYY-MM-DD HH:MM:SS)");yield new TimestampString(value.textValue());}
        default -> {need(value.isNumber()||value.isTextual(),node,"value","Expected a numeric literal");BigDecimal decimal=new BigDecimal(value.asText());
          switch(t.getSqlTypeName()){case INTEGER -> decimal.intValueExact();case BIGINT -> decimal.longValueExact();case DOUBLE -> need(Double.isFinite(decimal.doubleValue()),node,"value","Double literal is out of range");case DECIMAL -> {need(decimal.scale()<=t.getScale()&&decimal.precision()-decimal.scale()<=t.getPrecision()-t.getScale(),node,"value","Decimal does not fit declared type");}default -> {}}
          yield decimal;
        }
      };
      // Preserve the declared VARCHAR type; RexBuilder.makeLiteral otherwise returns CHAR.
      if(t.getSqlTypeName()==SqlTypeName.VARCHAR)return RexLiteral.fromJdbcString(types.createTypeWithNullability(t,false),SqlTypeName.CHAR,(String)v);
      RexNode result=rex.makeLiteral(v,types.createTypeWithNullability(t,false),false);
      need(result instanceof RexLiteral,node,"value","Literal needs an explicit cast");return (RexLiteral)result;
    }
    RexNode condition(JsonNode a,List<RelNode> inputs,String path){var value=expr(a,inputs,0,path);need(value.getType().getSqlTypeName()==SqlTypeName.BOOLEAN,node,path,"Condition must be BOOLEAN");return value;}
    RexNode expr(JsonNode a,List<RelNode> inputs,int depth,String path){
      need(a!=null&&a.isObject(),node,path,"Expected expression object");need(depth<=32&&++expressions<=2000,node,path,"Expression depth or count limit exceeded");
      String kind=text(a,"kind");
      Set<String> allowed=switch(kind){case "ref" -> Set.of("kind","input","index");case "literal" -> Set.of("kind","type","nullable","precision","scale","value");case "cast" -> Set.of("kind","type","nullable","precision","scale","expr");case "call" -> Set.of("kind","op","args");default -> Set.of("kind");};
      a.fieldNames().forEachRemaining(k->need(allowed.contains(k),node,path+"."+k,"Unknown expression attribute"));
      if(kind.equals("ref")){
        var i=a.path("input");var j=a.path("index");need(i.isIntegralNumber()&&i.canConvertToInt()&&i.intValue()>=0&&i.intValue()<inputs.size(),node,path+".input","Unknown input");
        var fields=inputs.get(i.intValue()).getRowType().getFieldList();need(j.isIntegralNumber()&&j.canConvertToInt()&&j.intValue()>=0&&j.intValue()<fields.size(),node,path+".index","Unknown field");
        int offset=0;for(int k=0;k<i.intValue();k++)offset+=inputs.get(k).getRowType().getFieldCount();return rex.makeInputRef(fields.get(j.intValue()).getType(),offset+j.intValue());
      }
      if(kind.equals("literal"))return literal(a.get("value"),type(a));
      if(kind.equals("cast")){var child=expr(required(a,"expr"),inputs,depth+1,path+".expr");var t=type(a);need(!child.getType().isNullable()||t.isNullable(),node,path,"CAST cannot assert that a nullable value is non-null");need(SqlTypeUtil.canCastFrom(t,child.getType(),true),node,path,"Unsupported cast");return rex.makeAbstractCast(t,child);}
      need(kind.equals("call"),node,path,"Unknown expression kind");String name=text(a,"op");var op=OPERATORS.get(name);need(op!=null,node,path+".op","Unsupported expression operator");
      var args=required(a,"args");need(args.isArray(),node,path+".args","Expected expression array");var values=new ArrayList<RexNode>();for(int i=0;i<args.size();i++)values.add(expr(args.get(i),inputs,depth+1,path+".args["+i+"]"));
      int size=values.size();
      if(Set.of("NOT","IS NULL","IS NOT NULL","IS TRUE","IS FALSE").contains(name))need(size==1,node,path,"Expected one argument");
      else if(Set.of("AND","OR","COALESCE").contains(name))need(size>=2,node,path,"Expected at least two arguments");
      else if(name.equals("CASE"))need(size>=3&&size%2==1,node,path,"CASE takes condition/value pairs followed by ELSE");
      else need(size==2,node,path,"Expected two arguments");
      if(Set.of("AND","OR","NOT","IS TRUE","IS FALSE").contains(name))for(var v:values)need(SqlTypeUtil.inBooleanFamily(v.getType()),node,path,"Boolean arguments required");
      if(Set.of("+","-","*","/").contains(name))for(var v:values)need(SqlTypeUtil.isNumeric(v.getType()),node,path,"Numeric arguments required");
      if(Set.of("=","<>",">",">=","<","<=","IS NOT DISTINCT FROM").contains(name))need(SqlTypeUtil.isComparable(values.get(0).getType(),values.get(1).getType()),node,path,"Incomparable argument types");
      if(name.equals("CASE"))for(int i=0;i<size-1;i+=2)need(SqlTypeUtil.inBooleanFamily(values.get(i).getType()),node,path,"CASE conditions must be BOOLEAN");
      var operands=new ArrayList<RexNode>();
      if(name.equals("CASE")){for(int i=1;i<size;i+=2)operands.add(values.get(i));operands.add(values.get(size-1));}
      else if(!Set.of("NOT","IS NULL","IS NOT NULL","IS TRUE","IS FALSE","AND","OR").contains(name))operands.addAll(values);
      for(var v:operands)need(SqlTypeUtil.equalSansNullability(types,operands.get(0).getType(),v.getType()),node,path,"Argument types must match; insert an explicit CAST");
      return rex.makeCall(op,values);
    }
    RelNode project(JsonNode a,List<RelNode> inputs,boolean calc){
      var list=required(a,"projects");need(list.isArray()&&list.size()<=1024,node,"projects","Invalid projection list");var names=new ArrayList<String>();var exprs=new ArrayList<RexNode>();
      for(int i=0;i<list.size();i++){var item=list.get(i);keys(item,"projects["+i+"]","name","expr");names.add(text(item,"name"));exprs.add(expr(required(item,"expr"),inputs,0,"projects["+i+"].expr"));}
      need(new HashSet<>(names).size()==names.size(),node,"projects","Output names must be unique");
      if(!calc)return LogicalProject.create(inputs.get(0),List.of(),exprs,names);
      var program=new RexProgramBuilder(inputs.get(0).getRowType(),rex);for(int i=0;i<exprs.size();i++)program.addProject(exprs.get(i),names.get(i));
      if(a.hasNonNull("condition"))program.addCondition(condition(a.get("condition"),inputs,"condition"));return LogicalCalc.create(inputs.get(0),program.getProgram());
    }
    RelNode aggregate(JsonNode a,RelNode input){
      var keys=indexes(required(a,"groupKeys"),input,"groupKeys");need(new HashSet<>(keys).size()==keys.size(),node,"groupKeys","Duplicate grouping keys");need(keys.equals(keys.stream().sorted().toList()),node,"groupKeys","Grouping keys must be in ascending input order");
      var list=required(a,"calls");need(list.isArray()&&list.size()<=128,node,"calls","Invalid aggregate calls");var calls=new ArrayList<AggregateCall>();
      for(var c:list){keys(c,"calls","function","args","filter","distinct","name");String name=text(c,"function");var fn=AGGREGATES.get(name);need(fn!=null,node,"calls","Unsupported aggregate");var args=indexes(required(c,"args"),input,"calls.args");need(name.equals("COUNT")?args.size()<=1:args.size()==1,node,"calls.args","Invalid aggregate argument count");
        if(Set.of("SUM","SUM0","AVG").contains(name))need(SqlTypeUtil.isNumeric(input.getRowType().getFieldList().get(args.get(0)).getType()),node,"calls.args","Numeric argument required");
        int filter=-1;if(c.hasNonNull("filter")){filter=c.get("filter").asInt(-1);need(c.get("filter").isIntegralNumber()&&c.get("filter").canConvertToInt()&&filter>=0&&filter<input.getRowType().getFieldCount()&&SqlTypeUtil.inBooleanFamily(input.getRowType().getFieldList().get(filter).getType()),node,"calls.filter","Filter must reference a BOOLEAN input field");need(!input.getRowType().getFieldList().get(filter).getType().isNullable(),node,"calls.filter","Aggregate filter must be BOOLEAN NOT NULL; project IS TRUE(condition) explicitly");}
        need(!bool(c,"distinct",false)||!args.isEmpty(),node,"calls.distinct","DISTINCT requires an argument");
        calls.add(AggregateCall.create(fn,bool(c,"distinct",false),args,filter,keys.size(),input,null,text(c,"name")));
      }
      return LogicalAggregate.create(input,ImmutableBitSet.of(keys),null,calls);
    }
    RexNode bound(JsonNode a,String key){if(!a.hasNonNull(key))return null;var n=a.get(key);need(n.isIntegralNumber()&&n.canConvertToInt()&&n.intValue()>=0,node,key,"Expected a non-negative integer");return rex.makeExactLiteral(new BigDecimal(n.asText()));}
    RelCollation collation(JsonNode keys,RelNode input){need(keys.isArray(),node,"keys","Expected sort keys");var fields=new ArrayList<RelFieldCollation>();for(var k:keys){keys(k,"keys","index","direction","nulls");int index=k.path("index").asInt(-1);need(k.path("index").isIntegralNumber()&&k.path("index").canConvertToInt()&&index>=0&&index<input.getRowType().getFieldCount(),node,"keys","Invalid sort field");String direction=k.path("direction").asText("ASC"),nulls=k.path("nulls").asText("LAST");need(Set.of("ASC","DESC").contains(direction)&&Set.of("FIRST","LAST").contains(nulls),node,"keys","Invalid sort direction or null ordering");fields.add(new RelFieldCollation(index,direction.equals("ASC")?RelFieldCollation.Direction.ASCENDING:RelFieldCollation.Direction.DESCENDING,RelFieldCollation.NullDirection.valueOf(nulls)));}return RelCollations.of(fields);}
    RelDistribution distribution(JsonNode d,RelNode input){keys(d,"distribution","type","keys");need(!d.has("keys")||"HASH".equals(d.path("type").asText()),node,"distribution.keys","Only HASH distribution takes keys");return switch(text(d,"type")){
      case "HASH" -> RelDistributions.hash(indexes(required(d,"keys"),input,"distribution.keys"));
      case "SINGLETON" -> RelDistributions.SINGLETON;case "BROADCAST" -> RelDistributions.BROADCAST_DISTRIBUTED;case "RANDOM" -> RelDistributions.RANDOM_DISTRIBUTED;
      default -> throw new Invalid(node,"distribution.type","Unsupported distribution");};}
    RelNode multi(JsonNode a,List<RelNode> inputs){
      RexNode condition=condition(required(a,"condition"),inputs,"condition");var row=types.builder();for(var input:inputs)row.addAll(input.getRowType().getFieldList());
      var post=a.hasNonNull("postFilter")?condition(a.get("postFilter"),inputs,"postFilter"):null;
      var projected=new ArrayList<ImmutableBitSet>();var counts=ImmutableMap.<Integer,ImmutableIntList>builder();int offset=0;
      var refs=new int[inputs.stream().mapToInt(n->n.getRowType().getFieldCount()).sum()];
      condition.accept(new RexVisitorImpl<Void>(true){public Void visitInputRef(RexInputRef r){refs[r.getIndex()]++;return null;}});
      if(a.hasNonNull("projectedFields"))need(a.get("projectedFields").isArray()&&a.get("projectedFields").size()==inputs.size(),node,"projectedFields","Expected one field set per input");
      if(a.hasNonNull("refCounts"))need(a.get("refCounts").isArray()&&a.get("refCounts").size()==inputs.size(),node,"refCounts","Expected counts for each input");
      for(int i=0;i<inputs.size();i++){
        JsonNode p=a.hasNonNull("projectedFields")?a.get("projectedFields").get(i):NullNode.instance;
        projected.add(p.isNull()?null:ImmutableBitSet.of(indexes(p,inputs.get(i),"projectedFields")));
        int width=inputs.get(i).getRowType().getFieldCount();int[] c=Arrays.copyOfRange(refs,offset,offset+width);offset+=width;
        if(a.hasNonNull("refCounts")){var values=a.get("refCounts").get(i);need(values.isArray()&&values.size()==width,node,"refCounts","Incorrect field count");for(int j=0;j<width;j++){need(values.get(j).isIntegralNumber()&&values.get(j).canConvertToInt()&&values.get(j).intValue()>=0,node,"refCounts","Invalid reference count");c[j]=values.get(j).intValue();}}
        counts.put(i,ImmutableIntList.of(c));
      }
      return new MultiJoin(cluster,inputs,condition,row.build(),false,Collections.nCopies(inputs.size(),null),Collections.nCopies(inputs.size(),JoinRelType.INNER),projected,counts.build(),post);
    }
  }
  private Plans(){}
}
