package dev.optilens;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleExamplesTest {
  @Test void everyExampleRunsOnlyItsNamedRuleAndProducesRealTransformations() throws Exception {
    var ids=new HashSet<String>();
    for(var example:RuleExamples.all()){
      assertTrue(ids.add(example.id()),"Duplicate example: "+example.id());
      assertEquals(List.of(example.id()),example.input().rules());
      var definition=Rules.get(example.id());
      assertEquals(1,definition.rules().size(),"Examples must use individual rules");
      var run=new Optimizer().optimize(example.input());
      Records.validate(run);
      assertEquals(1,run.stages().size());
      var stage=run.stages().get(0);
      assertEquals(example.id(),stage.id());
      assertFalse(stage.events().isEmpty(),"No transformation for "+example.id());
      for(var event:stage.events())assertTrue(definition.card().implementations().contains(event.rule()),event.rule());
    }
  }
}
