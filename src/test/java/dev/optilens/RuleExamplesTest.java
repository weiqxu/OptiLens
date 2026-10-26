package dev.optilens;

import static dev.optilens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class RuleExamplesTest {
  @Test
  void libraryHasUniqueRunnableTargetsAndExplicitPreparation() {
    var ids = new HashSet<String>();
    assertTrue(RuleExamples.all().size() >= 126);
    for (var example : RuleExamples.all()) {
      assertTrue(ids.add(example.id()), "Duplicate example: " + example.id());
      var program = example.input().rules();
      assertEquals(example.id(), program.get(program.size() - 1));
      assertEquals(program.size(), new HashSet<>(program).size());
      for (var id : program) assertEquals(1, Rules.get(id).rules().size());
    }
  }

  @Test
  void coverageExplainsEveryCatalogEntryAndOptionsAffectExecution() throws Exception {
    var coverage = RuleExamples.coverage();
    var catalogIds =
        Rules.cards().stream()
            .filter(c -> !c.category().equals("Common rule groups"))
            .map(RuleCard::id)
            .toList();
    assertEquals(catalogIds, coverage.stream().map(RuleExamples.Coverage::id).toList());
    var verifiedIds =
        coverage.stream()
            .filter(c -> c.status().equals("verified"))
            .map(RuleExamples.Coverage::id)
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(
        RuleExamples.all().stream().map(Example::id).collect(java.util.stream.Collectors.toSet()),
        verifiedIds);
    assertTrue(coverage.stream().allMatch(c -> !c.reason().isBlank()));
    var bounded =
        RuleExamples.all().stream()
            .filter(e -> e.id().equals("CoreRules.JOIN_COMMUTE"))
            .findFirst()
            .orElseThrow();
    assertEquals(1, new Optimizer().optimize(bounded.input()).stages().get(0).events().size());
    var subquery =
        RuleExamples.all().stream()
            .filter(e -> e.id().equals("CoreRules.FILTER_SUB_QUERY_TO_CORRELATE"))
            .findFirst()
            .orElseThrow()
            .input();
    var expanded =
        new Request(
            subquery.sql(), subquery.schema(), subquery.rules(), "calcite", new Options(true, 10));
    assertTrue(new Optimizer().optimize(expanded).stages().get(0).events().isEmpty());
    assertFalse(new Optimizer().optimize(subquery).stages().get(0).events().isEmpty());
  }

  @TestFactory
  Stream<DynamicTest> everyExampleProducesEvidenceForItsTargetRule() {
    return RuleExamples.all().stream()
        .map(
            example ->
                DynamicTest.dynamicTest(
                    example.id(),
                    () -> {
                      var definition = Rules.get(example.id());
                      var run = new Optimizer().optimize(example.input());
                      Records.validate(run);
                      assertEquals(
                          example.input().rules(), run.stages().stream().map(Stage::id).toList());
                      var stage = run.stages().get(run.stages().size() - 1);
                      assertEquals(example.id(), stage.id());
                      assertFalse(stage.events().isEmpty(), "No target-rule transformation");
                      assertTrue(
                          stage.events().stream()
                              .anyMatch(e -> !e.before().raw().equals(e.after().raw())),
                          "The target must produce an observable expression change");
                      for (var event : stage.events())
                        assertTrue(
                            definition.card().implementations().contains(event.rule()),
                            event.rule());
                      // A downloaded record must retain the complete program and conversion
                      // settings.
                      var mapper = new ObjectMapper();
                      var restored = mapper.readValue(mapper.writeValueAsBytes(run), Run.class);
                      Records.validate(restored);
                      assertEquals(example.input(), restored.input());
                    }));
  }

  @Test
  void boundsAndLegacyRequestsArePreserved() throws Exception {
    var input = Input.examples().get(0).input();
    for (int limit : List.of(0, -1, 101))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Input.validate(
                  new Request(
                      input.sql(),
                      input.schema(),
                      input.rules(),
                      "calcite",
                      new Options(true, limit))));
    var mapper = new ObjectMapper();
    var legacy = mapper.createObjectNode();
    legacy.put("sql", "SELECT * FROM customers");
    legacy.set("schema", mapper.valueToTree(input.schema()));
    legacy.set("rules", mapper.valueToTree(List.of("CoreRules.PROJECT_REMOVE")));
    var request = mapper.treeToValue(legacy, Request.class);
    assertNull(request.options());
    var run = new Optimizer().optimize(request);
    assertFalse(run.stages().get(0).events().isEmpty());
    assertTrue(run.limitations().get(0).contains("100 matches"));
  }
}
