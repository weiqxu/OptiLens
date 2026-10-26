package dev.optilens;

import static dev.optilens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class OptimizerTest {
  @Test
  void realRulesProducePairedLocalEvidenceAndFullStageBoundaries() throws Exception {
    Run run = new Optimizer().optimize(Input.examples().get(0).input());
    Records.validate(run);
    assertEquals("full-plan", run.initial().scope());
    assertEquals(5, run.stages().size());
    assertTrue(
        run.stages().stream().mapToInt(s -> s.events().size()).sum() > 0,
        "Expected real rule productions");
    for (var stage : run.stages()) {
      var keys = new HashSet<String>();
      for (var event : stage.events()) {
        assertEquals("local-expression", event.before().scope());
        assertEquals("local-expression", event.after().scope());
        assertTrue(
            stage.attempts().stream()
                .anyMatch(a -> a.callId() == event.callId() && a.rule().equals(event.rule())));
        assertTrue(
            keys.add(event.callId() + ":" + event.rule() + ":" + event.after().rootId()),
            "Do not double-count before/after callbacks");
      }
    }
    assertNotEquals(run.initial().raw(), run.finalPlan().raw());
    assertTrue(run.initial().raw().contains("LogicalFilter"));
    assertTrue(run.finalPlan().raw().contains("LogicalProject"));
    String frozen = Json.MAPPER.writeValueAsString(run.initial());
    new Optimizer().optimize(Input.examples().get(1).input());
    assertEquals(
        frozen,
        Json.MAPPER.writeValueAsString(run.initial()),
        "Historical snapshots must be immutable values");
  }

  @Test
  void allBuiltInCasesAreValidAndRoundTrip() throws Exception {
    for (var example : Input.examples()) {
      var run = new Optimizer().optimize(example.input());
      var restored = Json.MAPPER.readValue(Json.MAPPER.writeValueAsBytes(run), Run.class);
      Records.validate(restored);
      assertEquals(run, restored, example.id());
    }
  }

  @Test
  void disablingAllRulesPreservesTheConvertedPlan() throws Exception {
    var request = Input.examples().get(0).input();
    var run = new Optimizer().optimize(new Request(request.sql(), request.schema(), List.of()));
    assertEquals(run.initial(), run.finalPlan());
    assertTrue(run.stages().isEmpty());
  }

  @Test
  void constantsAreActuallyEvaluated() throws Exception {
    var run = new Optimizer().optimize(Input.examples().get(1).input());
    assertTrue(
        run.stages().get(0).events().size() > 0, "Expression reduction must have an executor");
    assertTrue(run.finalPlan().raw().contains("200"), run.finalPlan().raw());
    assertFalse(run.finalPlan().raw().contains("*(10, 20)"));
  }

  @Test
  void nullAcceptingFilterPreservesOuterJoinWhileRejectingFilterCanSimplifyIt() throws Exception {
    var rejecting = new Optimizer().optimize(Input.examples().get(2).input());
    var accepting = new Optimizer().optimize(Input.examples().get(3).input());
    assertTrue(
        rejecting.finalPlan().raw().contains("joinType=[inner]"), rejecting.finalPlan().raw());
    assertTrue(
        accepting.finalPlan().raw().contains("joinType=[left]"), accepting.finalPlan().raw());
  }

  @Test
  void invalidSqlAndMutationsAreRejected() {
    var request = Input.examples().get(0).input();
    assertThrows(
        Exception.class,
        () ->
            new Optimizer()
                .optimize(
                    new Request(
                        "SELECT missing FROM customers", request.schema(), request.rules())));
    assertThrows(
        Exception.class,
        () ->
            new Optimizer()
                .optimize(new Request("DELETE FROM customers", request.schema(), request.rules())));
    assertThrows(
        Exception.class,
        () ->
            new Optimizer()
                .optimize(
                    new Request(
                        "SELECT * FROM customers; SELECT * FROM orders",
                        request.schema(),
                        request.rules())));
    assertThrows(
        IllegalArgumentException.class,
        () -> Input.validate(new Request("SELECT 1", request.schema(), List.of("unknown"))));
  }

  @Test
  void importedCyclesAndMissingNodesAreRejected() throws Exception {
    var run = new Optimizer().optimize(Input.examples().get(5).input());
    var cycle =
        new Snapshot(
            "full-plan",
            "x",
            List.of(new PlanNode("x", "Filter", Map.of(), List.of(), List.of("x"))),
            "cycle");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Records.validate(
                new Run(
                    1,
                    run.engine(),
                    run.engineVersion(),
                    run.createdAt(),
                    run.input(),
                    cycle,
                    run.finalPlan(),
                    run.stages(),
                    run.limitations())));
  }
}
