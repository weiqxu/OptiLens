package dev.optilens;

import java.lang.reflect.Modifier;
import java.util.*;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.rules.CoreRules;
import org.junit.jupiter.api.Test;
import static dev.optilens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
  @Test void everyPublishedCoreRuleIsListedAndIdsAreUnique() throws Exception {
    var cards=Rules.cards();var ids=new HashSet<String>();
    for(var card:cards){assertTrue(ids.add(card.id()),card.id());assertFalse(card.source().isBlank());}
    for(var field:CoreRules.class.getFields())
      if(Modifier.isStatic(field.getModifiers())&&RelOptRule.class.isAssignableFrom(field.getType()))
        assertTrue(ids.contains("CoreRules."+field.getName()),field.getName());
    assertTrue(cards.size()>200);
    assertTrue(cards.stream().anyMatch(c->c.id().startsWith("JdbcRules.")&&!c.runnable()));
    assertTrue(cards.stream().anyMatch(c->c.id().startsWith("StreamRules.")));
    System.out.println("Catalog: "+cards.size()+" entries, "+cards.stream().filter(RuleCard::runnable).count()+" runnable (including 5 presets)");
  }
  @Test void individualRuleProducesEvidenceAndExpandedProgramsRoundTrip() throws Exception {
    var example=Input.examples().get(1).input();
    var request=new Request(example.sql(),example.schema(),List.of("CoreRules.PROJECT_REDUCE_EXPRESSIONS"),"calcite");
    var run=new Optimizer().optimize(request);
    assertEquals(1,run.stages().size());assertFalse(run.stages().get(0).events().isEmpty());
    assertTrue(run.finalPlan().raw().contains("200"));
    var program=new ArrayList<>(Input.examples().get(0).input().rules());
    program.add("CoreRules.FILTER_MERGE");program.add("CoreRules.PROJECT_REMOVE");
    run=new Optimizer().optimize(new Request(example.sql(),example.schema(),program,"calcite"));
    assertEquals(7,run.stages().size());
    Records.validate(Json.MAPPER.readValue(Json.MAPPER.writeValueAsBytes(run),Run.class));
  }
  @Test void unsupportedEnginesUnavailableRulesAndOversizedProgramsAreRejected() throws Exception {
    var e=Input.examples().get(0).input();
    assertThrows(IllegalArgumentException.class,()->Input.validate(new Request(e.sql(),e.schema(),e.rules(),"postgres")));
    var unavailable=Rules.cards().stream().filter(c->!c.runnable()).findFirst().orElseThrow();
    assertThrows(IllegalArgumentException.class,()->Input.validate(new Request(e.sql(),e.schema(),List.of(unavailable.id()))));
    var tooMany=Rules.cards().stream().filter(RuleCard::runnable).limit(Rules.MAX_SELECTED+1).map(RuleCard::id).toList();
    assertThrows(IllegalArgumentException.class,()->Input.validate(new Request(e.sql(),e.schema(),tooMany)));
    var json=Json.MAPPER.valueToTree(e);((com.fasterxml.jackson.databind.node.ObjectNode)json).remove("database");
    var legacy=Json.MAPPER.treeToValue(json,Request.class);Input.validate(legacy);assertEquals("calcite",legacy.database());
  }
}
