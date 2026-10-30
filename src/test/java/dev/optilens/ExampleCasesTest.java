package dev.optilens;

import static dev.optilens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ExampleCasesTest {
  @Test
  void migratedCasesHaveValidInputsAndPreserveLibraryCounts() {
    assertEquals(135, ExampleCases.all("calcite").size());
    assertEquals(88, ExampleCases.all("starrocks").size());
    assertEquals(3, ExampleCases.all("trafodion").size());
    for (String db : List.of("calcite", "starrocks", "trafodion")) {
      var ids = new HashSet<String>();
      for (var c : ExampleCases.all(db)) {
        assertTrue(ids.add(c.id()));
        Input.validate(c.input());
        assertEquals(c.targetRule(), c.input().rules().get(c.expectation().stageIndex()));
      }
    }
  }

  @Test
  void presenceIsNotProofAndReportsExpireWhenInputsOrValidatorChange() {
    var c = ExampleCases.all("calcite").get(0);
    assertEquals("unverified", ExampleVerification.status(c, null));
    var r =
        new ExampleVerification.Result(
            c.id(),
            ExampleCases.hash(c),
            "passed",
            "Fixture",
            c.engineVersion(),
            "calcite-1.39.0",
            null,
            1,
            "2026-10-30T00:00:00Z");
    var report =
        new ExampleVerification.Report(
            1,
            ExampleVerification.VALIDATOR_VERSION,
            "calcite",
            ExampleVerification.adapterHash(),
            List.of(r));
    assertEquals("verified", ExampleVerification.status(c, report));
    var changed =
        new ExampleCases.Case(
            c.id(),
            c.targetRule(),
            c.title(),
            "Changed note",
            c.engineVersion(),
            c.sourceCommit(),
            c.input(),
            c.expectation(),
            c.choices());
    assertEquals("stale", ExampleVerification.status(changed, report));
    assertEquals(
        "stale",
        ExampleVerification.status(
            c,
            new ExampleVerification.Report(
                1, 0, "calcite", report.adapterHash(), report.results())));
    assertEquals(
        "stale",
        ExampleVerification.status(
            c, new ExampleVerification.Report(1, 1, "calcite", "old-adapter", report.results())));
    assertEquals(
        "unverified",
        ExampleVerification.status(
            c,
            new ExampleVerification.Report(1, 1, "calcite", report.adapterHash(), List.of(r, r))));
  }

  @Test
  void idsDoNotCountAsChangesButRealOperatorChangesDo() {
    var a =
        new Snapshot(
            "full-plan",
            "a",
            List.of(
                new PlanNode("a", "Scan", Map.of("table", "x"), List.of("id: INTEGER"), List.of())),
            "raw-a");
    var b =
        new Snapshot(
            "full-plan",
            "b",
            List.of(
                new PlanNode("b", "Scan", Map.of("table", "x"), List.of("id: INTEGER"), List.of())),
            "raw-b");
    var c =
        new Snapshot(
            "full-plan",
            "c",
            List.of(new PlanNode("c", "Values", Map.of(), List.of("id: INTEGER"), List.of())),
            "raw-c");
    assertEquals(CaseVerifier.structure(a, "calcite"), CaseVerifier.structure(b, "calcite"));
    assertNotEquals(CaseVerifier.structure(a, "calcite"), CaseVerifier.structure(c, "calcite"));
  }

  @Test
  void unrelatedOrMissingTargetEvidenceCannotPass() throws Exception {
    var c = ExampleCases.all("calcite").get(0);
    var run = new Optimizer().optimize(c.input());
    assertTrue(CaseVerifier.assess(c, run) > 0);
    var original = run.stages().get(0);
    var empty =
        new Stage(
            original.id(),
            original.title(),
            original.description(),
            original.conditions(),
            original.source(),
            original.before(),
            original.after(),
            original.attempts(),
            List.of(),
            original.scopeBeforeRootId(),
            original.scopeAfterRootId());
    var noEvents =
        new Run(
            run.formatVersion(),
            run.engine(),
            run.engineVersion(),
            run.createdAt(),
            run.input(),
            run.initial(),
            run.finalPlan(),
            List.of(empty),
            run.limitations(),
            run.editableResult());
    assertThrows(IllegalArgumentException.class, () -> CaseVerifier.assess(c, noEvents));
    var changed = original.events().get(0);
    var unrelated =
        new Event(
            changed.sequence(),
            changed.callId(),
            "DifferentRule",
            changed.before(),
            changed.after());
    var unrelatedStage =
        new Stage(
            original.id(),
            original.title(),
            original.description(),
            original.conditions(),
            original.source(),
            original.before(),
            original.after(),
            List.of(new Attempt(changed.callId(), "DifferentRule", List.of())),
            List.of(unrelated),
            original.scopeBeforeRootId(),
            original.scopeAfterRootId());
    var unrelatedRun =
        new Run(
            run.formatVersion(),
            run.engine(),
            run.engineVersion(),
            run.createdAt(),
            run.input(),
            run.initial(),
            run.finalPlan(),
            List.of(unrelatedStage),
            run.limitations(),
            run.editableResult());
    assertThrows(IllegalArgumentException.class, () -> CaseVerifier.assess(c, unrelatedRun));
  }

  @Test
  void expectedNoChangeDoesNotBecomeAVerifiedTransformation() throws Exception {
    var base = ExampleCases.all("calcite").get(0);
    var input =
        new Request(
            "SELECT name FROM customers",
            base.input().schema(),
            List.of(base.targetRule()),
            "calcite");
    var c =
        new ExampleCases.Case(
            "no-change",
            base.targetRule(),
            "No constants",
            "Negative case",
            base.engineVersion(),
            null,
            input,
            new ExampleCases.Expectation("no-change", 0, 0),
            List.of());
    assertEquals(0, CaseVerifier.assess(c, new Optimizer().optimize(input)));
    var result =
        new ExampleVerification.Result(
            c.id(),
            ExampleCases.hash(c),
            "passed",
            "No change",
            c.engineVersion(),
            "calcite-1.39.0",
            null,
            0,
            "2026-10-30T00:00:00Z");
    var report =
        new ExampleVerification.Report(
            1, 1, "calcite", ExampleVerification.adapterHash(), List.of(result));
    assertEquals("verified-no-change", ExampleVerification.status(c, report));
  }

  @Test
  void skippedFailedAndMalformedReportsNeverGrantVerification() throws Exception {
    var c = ExampleCases.all("calcite").get(0);
    for (String status : List.of("skipped", "failed")) {
      var result =
          new ExampleVerification.Result(
              c.id(),
              ExampleCases.hash(c),
              status,
              "Fixture",
              c.engineVersion(),
              null,
              null,
              0,
              "2026-10-30T00:00:00Z");
      var report =
          new ExampleVerification.Report(
              1, 1, "calcite", ExampleVerification.adapterHash(), List.of(result));
      assertEquals(status, ExampleVerification.status(c, report));
    }
    assertEquals(
        "unverified",
        ExampleVerification.status(
            c,
            new ExampleVerification.Report(
                1, 1, "calcite", ExampleVerification.adapterHash(), null)));
    assertNull(
        Json.MAPPER.readValue("{}", ExampleCases.Choice.class).ordinal(),
        "Missing ordinal must never select candidate zero by default");
  }
}
