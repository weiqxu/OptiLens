package dev.optilens.starrocks;

import dev.optilens.*;
import dev.optilens.Model.*;
import com.starrocks.sql.optimizer.*;
import com.starrocks.sql.optimizer.operator.logical.*;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.*;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriter;
import com.starrocks.sql.optimizer.rewrite.scalar.ScalarOperatorRewriteRule;
import com.starrocks.sql.optimizer.rule.Rule;
import com.starrocks.sql.optimizer.rule.tree.TreeRewriteRule;
import com.starrocks.sql.optimizer.task.*;
import java.time.Instant;
import java.util.*;

final class NativeOptimizer {
  final NativeRegistry registry;
  NativeOptimizer(NativeRegistry registry){this.registry=registry;}
  PlanValidation validate(Request request) {
    try {
      var session=new NativeSession(request.schema());var compiler=new NativePlans(session);var root=compiler.build(request);
      var fields=new LinkedHashMap<String,List<String>>();compiler.built.forEach((id,node)->fields.put(id,compiler.columns(node).stream().map(c->c.getId()+": "+c.getName()+" "+c.getType().toSql()+(c.isNullable()?" NULL":" NOT NULL")).toList()));
      return new PlanValidation(true,NativeSnapshots.capture(root,session.columns,"full-plan"),fields,List.of());
    }catch(NativePlans.Invalid e){return new PlanValidation(false,null,Map.of(),List.of(e.issue));}
    catch(Exception e){return new PlanValidation(false,null,Map.of(),List.of(new PlanIssue(null,"plan",NativeRegistry.message(e))));}
  }
  Run optimize(Request request) {
    var session=new NativeSession(request.schema());var compiler=new NativePlans(session);var root=compiler.build(request);
    var path=new ArrayList<Integer>();
    if(request.scopeNodeId()!=null) {
      var target=compiler.built.get(request.scopeNodeId());if(target==null||!find(root,target,path))throw new IllegalArgumentException("Unknown optimization scope: "+request.scopeNodeId());
    }
    var initial=NativeSnapshots.capture(root,session.columns,"full-plan");var stages=new ArrayList<Stage>();
    int limit=request.options()!=null&&request.options().matchLimit()!=null?request.options().matchLimit():100;
    for(String id:request.rules()) {
      var entry=registry.get(id);var card=StarRocks.rule(id);var before=NativeSnapshots.capture(root,session.columns,"full-plan");
      var selected=at(root,path);var expected=NativeSnapshots.fields(selected,session.columns);String scopeBefore=NativeSnapshots.rootId(root,path);
      var program=new Program(session,id,limit);session.task(selected);
      for(Object rule:entry.instances())selected=program.execute(selected,rule);
      NativePlans.derive(selected);
      if(!path.isEmpty()&&!expected.equals(NativeSnapshots.fields(selected,session.columns)))throw new IllegalArgumentException("The selected rule changes subtree column identities, names, types or nullability; the result cannot be reattached");
      if(path.isEmpty())root=selected;else {var parent=at(root,path.subList(0,path.size()-1));parent.setChild(path.get(path.size()-1),selected);NativePlans.derive(root);}
      var after=NativeSnapshots.capture(root,session.columns,"full-plan");
      stages.add(new Stage(id,card.title(),card.description(),card.conditions(),card.source(),before,after,List.copyOf(program.attempts),List.copyOf(program.events),scopeBefore,NativeSnapshots.rootId(root,path)));
    }
    var finalPlan=NativeSnapshots.capture(root,session.columns,"full-plan");
    var editable=new EditableResult(null,List.of("Native StarRocks results are read-only in this adapter. The original manual plan remains editable; native operator attributes are never reconstructed from display text."));
    if(request.inputMode().equals("plan")&&initial.raw().equals(finalPlan.raw()))editable=new EditableResult(request.plan(),List.of());
    return new Run(2,"starrocks",StarRocks.VERSION,Instant.now().toString(),request,initial,finalPlan,List.copyOf(stages),List.of(
        "Native StarRocks "+StarRocks.VERSION+" at "+StarRocks.COMMIT+". No query is executed.",
        "Rules run as a bounded, ordered experiment. This is not the complete cost-based optimizer; the first returned alternative is used.",
        "Individual rules assume native planner phases. A recorded change does not certify query-result equivalence or performance.",
        "Tables contain schema metadata only. Statistics, populated materialized views, external catalogs and storage services are not simulated.",
        "SQL parsing and logical-plan translation use StarRocks itself and may normalize expressions before the initial snapshot."),editable);
  }
  static boolean find(OptExpression root,OptExpression target,List<Integer> path){if(root==target)return true;for(int i=0;i<root.arity();i++){path.add(i);if(find(root.inputAt(i),target,path))return true;path.remove(path.size()-1);}return false;}
  static OptExpression at(OptExpression root,List<Integer> path){for(int i:path)root=root.inputAt(i);return root;}
  static final class Matcher extends RewriteTreeTask {
    Matcher(TaskContext task){super(task,OptExpression.create(new LogicalTreeAnchorOperator()),List.of(),true);}
    boolean accepts(Pattern pattern,OptExpression root){return match(pattern,root);}
  }
  static final class Program {
    final NativeSession session;final String id;final int limit;final List<Attempt> attempts=new ArrayList<>();final List<Event> events=new ArrayList<>();int calls;
    Program(NativeSession session,String id,int limit){this.session=session;this.id=id;this.limit=limit;}
    OptExpression execute(OptExpression root,Object rule) {
      if(rule instanceof Class<?> type) {
        try {for(var constructor:type.getConstructors())if(constructor.getParameterCount()==1) {
          Object argument=switch(constructor.getParameterTypes()[0].getName()) {
            case "com.starrocks.qe.ConnectContext"->session.connection;
            case "com.starrocks.qe.SessionVariable"->session.connection.getSessionVariable();
            case "com.starrocks.sql.optimizer.task.TaskContext"->session.task(root);
            case "com.starrocks.sql.optimizer.OptimizerContext"->session.optimizer;
            default->null;
          };
          if(argument!=null)return execute(root,constructor.newInstance(argument));
        }}catch(ReflectiveOperationException e){throw new IllegalArgumentException(NativeRegistry.message(e));}
        throw new IllegalArgumentException("No supported native context constructor");
      }
      if(rule instanceof Rule relational) {
        var matcher=new Matcher(session.task(root));
        for(int pass=0;pass<limit&&events.size()<limit;pass++){int previous=events.size();root=visit(root,relational,matcher,0);NativePlans.derive(root);if(previous==events.size())break;}
        return root;
      }
      if(rule instanceof ScalarOperatorRewriteRule scalar)return scalarVisit(root,scalar,0);
      if(rule instanceof TreeRewriteRule tree) {
        var before=snapshot(root);int call=attempt();var after=tree.rewrite(root,session.task(root));if(after==null)throw new IllegalStateException("Native tree rule returned null");NativePlans.derive(after);record(call,before,after);return after;
      }
      throw new IllegalArgumentException("Unsupported native rule family");
    }
    OptExpression visit(OptExpression root,Rule rule,Matcher matcher,int depth) {
      if(depth>60||NativeSnapshots.size(root)>1000)throw new IllegalArgumentException("Native result exceeds plan limits");
      if(events.size()<limit&&matcher.accepts(rule.getPattern(),root)&&!rule.exhausted(session.optimizer)) {
        String actual="StarRocks."+rule.getClass().getSimpleName();int call=attempt(actual);
        if(rule.check(root,session.optimizer)) {
          for(var predecessor:rule.predecessorRules())root=visit(root,predecessor,matcher,depth);
          var before=snapshot(root);var property=root.getLogicalProperty();var alternatives=rule.transform(root,session.optimizer);
          if(alternatives!=null&&!alternatives.isEmpty())root=alternatives.get(0);
          if(!root.getOp().isLogical()&&root.getLogicalProperty()==null)root.setLogicalProperty(new com.starrocks.sql.optimizer.base.LogicalProperty(property));
          NativePlans.derive(root);record(call,before,root,actual);
          for(var successor:rule.successorRules())root=visit(root,successor,matcher,depth);
        }
      }
      for(int i=0;i<root.arity();i++)root.setChild(i,visit(root.inputAt(i),rule,matcher,depth+1));NativePlans.derive(root);return root;
    }
    OptExpression scalarVisit(OptExpression root,ScalarOperatorRewriteRule rule,int depth) {
      if(depth>60)throw new IllegalArgumentException("Native result exceeds depth limit");
      for(int i=0;i<root.arity();i++)root.setChild(i,scalarVisit(root.inputAt(i),rule,depth+1));
      if(events.size()>=limit)return root;
      var before=snapshot(root);int call=attempt();var op=root.getOp();
      if(op.getPredicate()!=null)op.setPredicate(rewrite(op.getPredicate(),rule));
      if(op instanceof LogicalProjectOperator project){var map=new LinkedHashMap<ColumnRefOperator,ScalarOperator>();project.getColumnRefMap().forEach((key,value)->map.put(key,rewrite(value,rule)));root=OptExpression.create(new LogicalProjectOperator(map),root.getInputs());}
      else if(op instanceof LogicalJoinOperator join) {var builder=new LogicalJoinOperator.Builder().withOperator(join);if(join.getOnPredicate()!=null)builder.setOnPredicate(rewrite(join.getOnPredicate(),rule));root=OptExpression.create(builder.build(),root.getInputs());}
      NativePlans.derive(root);record(call,before,root);return root;
    }
    ScalarOperator rewrite(ScalarOperator value,ScalarOperatorRewriteRule rule){return new ScalarOperatorRewriter().rewrite(value.clone(),List.of(rule));}
    Snapshot snapshot(OptExpression root){return NativeSnapshots.capture(root,session.columns,"local-expression");}
    int attempt(){return attempt(id);}
    int attempt(String ruleId){if(attempts.size()>=10000)throw new IllegalArgumentException("Native rule attempt limit exceeded");int call=++calls;attempts.add(new Attempt(call,ruleId,List.of("r0")));return call;}
    void record(int call,Snapshot before,OptExpression root){record(call,before,root,id);}
    void record(int call,Snapshot before,OptExpression root,String ruleId){var after=snapshot(root);if(!before.raw().equals(after.raw())||!before.nodes().get(0).fields().equals(after.nodes().get(0).fields()))events.add(new Event(events.size()+1,call,ruleId,before,after));}
  }
}
