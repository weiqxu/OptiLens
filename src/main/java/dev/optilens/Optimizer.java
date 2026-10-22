package dev.optilens;

import java.time.Instant;
import java.util.*;
import org.apache.calcite.config.Lex;
import org.apache.calcite.plan.RelOptListener;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexExecutorImpl;
import org.apache.calcite.DataContexts;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.Frameworks;
import static dev.optilens.Model.*;

public final class Optimizer {
  public Run optimize(Request request) throws Exception {
    Input.validate(request);
    var schema=Frameworks.createRootSchema(true);
    for(var table:request.schema())schema.add(table.name(),new AbstractTable(){
      @Override public RelDataType getRowType(RelDataTypeFactory f){
        var b=f.builder();
        for(var c:table.columns()){
          var type=SqlTypeName.valueOf(c.type());
          RelDataType t=type==SqlTypeName.DECIMAL?f.createSqlType(type,18,2):type==SqlTypeName.VARCHAR?f.createSqlType(type,255):f.createSqlType(type);
          b.add(c.name(),f.createTypeWithNullability(t,!Boolean.FALSE.equals(c.nullable())));
        }return b.build();
      }
    });
    var config=Frameworks.newConfigBuilder().defaultSchema(schema)
        .parserConfig(SqlParser.config().withLex(Lex.MYSQL).withCaseSensitive(false))
        .sqlToRelConverterConfig(SqlToRelConverter.config().withTrimUnusedFields(false).withExpand(true)).build();
    try(var parser=Frameworks.getPlanner(config)){
      var parsed=parser.parse(request.sql());
      Input.require(parsed.getKind().belongsTo(SqlKind.QUERY),"This release supports queries such as SELECT and WITH only; DDL and data modifications are not executed");
      var validated=parser.validate(parsed);
      RelNode current=parser.rel(validated).project();
      // ReduceExpressionsRule resolves its executor through the relation's cluster planner,
      // which is distinct from each experimental HepPlanner below.
      current.getCluster().getPlanner().setExecutor(new RexExecutorImpl(DataContexts.EMPTY));
      Snapshot initial=Snapshots.capture(current,"full-plan");
      List<Stage> stages=new ArrayList<>();
      int[] total={0};
      for(String ruleId:request.rules()){
        var d=Rules.get(ruleId); var card=d.card();
        var before=Snapshots.capture(current,"full-plan");
        var program=new HepProgramBuilder().addMatchLimit(100);
        d.rules().forEach(program::addRuleInstance);
        var planner=new HepPlanner(program.build());
        planner.setExecutor(new RexExecutorImpl(DataContexts.EMPTY));
        List<Attempt> attempts=new ArrayList<>(); List<Event> events=new ArrayList<>();
        planner.addListener(new RelOptListener(){
          public void ruleAttempted(RuleAttemptedEvent e){
            if(!e.isBefore())return;
            Input.require(++total[0]<=500,"The run stopped after exceeding the limit of 500 rule attempts");
            var call=e.getRuleCall();
            attempts.add(new Attempt(call.id,call.getRule().toString(),Arrays.stream(call.rels).map(r->String.valueOf(Snapshots.unwrap(r).getId())).toList()));
          }
          public void ruleProductionSucceeded(RuleProductionEvent e){
            // Before-registration callback contains both the old match and the new expression.
            // Its after-registration counterpart is deliberately not counted again.
            if(!e.isBefore())return;
            Input.require(events.size()<80,"The run stopped after exceeding the limit of 80 transformations per stage");
            var call=e.getRuleCall();
            events.add(new Event(events.size()+1,call.id,call.getRule().toString(),
                Snapshots.capture(call.rels[0],"local-expression"),Snapshots.capture(e.getRel(),"local-expression")));
          }
          public void relEquivalenceFound(RelEquivalenceEvent e){}
          public void relDiscarded(RelDiscardedEvent e){}
          public void relChosen(RelChosenEvent e){}
        });
        planner.setRoot(current);current=planner.findBestExp();
        stages.add(new Stage(card.id(),card.title(),card.description(),card.conditions(),card.source(),before,
            Snapshots.capture(current,"full-plan"),List.copyOf(attempts),List.copyOf(events)));
      }
      return new Run(1,"Apache Calcite","1.39.0",Instant.now().toString(),request,initial,
          Snapshots.capture(current,"full-plan"),List.copyOf(stages),List.of(
          "A controlled HepPlanner experiment in stages, not the default Calcite optimization pipeline. Each group has a limit of 100 matches.",
          "The initial snapshot is captured after SQL conversion. Individual rewrites during parsing, validation, and conversion are not traced.",
          "Rule events show local expressions; stage boundaries show complete plans. Final candidate selection and rejection reasons are not collected.",
          "Only table schemas are declared, with no real data or statistics. Queries are not executed, and performance or cost benefits are not measured."));
    }
  }
}
