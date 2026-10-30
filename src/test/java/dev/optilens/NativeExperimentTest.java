package dev.optilens;

import static dev.optilens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NativeExperimentTest {
  @Test
  void candidateRecordsRoundTripAndRejectTamperedIdentities() throws Exception {
    var input =
        Json.MAPPER.readValue(
            """
        {"database":"trafodion","inputMode":"plan","sql":null,
        "schema":[{"name":"a","columns":[{"name":"id","type":"INTEGER"}]}],
        "rules":["Trafodion.JoinCommutativityRule"],"options":{"matchLimit":10},
        "plan":{"planVersion":1,"rootId":"s","nodes":[
        {"id":"s","op":"TableScan","inputs":[],"attributes":{"table":"a"}}]}}
        """,
            Request.class);
    var snapshot =
        Json.MAPPER.readValue(
            """
        {"scope":"full-plan","rootId":"n1","nodes":[
        {"id":"n1","type":"scan","attributes":{},"fields":[],"inputs":[]}],"raw":"scan"}
        """,
            Snapshot.class);
    var trace = Json.MAPPER.createObjectNode();
    trace.put("kind", "trafodion-experiment");
    trace.put("sourceCommit", "a".repeat(40));
    trace.put("buildId", "test-build");
    trace.put("generationFingerprint", "b".repeat(64));
    trace.put("currentFingerprint", "c".repeat(64));
    trace.put("stepIndex", 0);
    trace.put("complete", false);
    trace.putArray("replay");
    var candidate = trace.putArray("candidates").addObject();
    candidate.put("ordinal", 0);
    candidate.put("id", "c0-" + "d".repeat(64));
    candidate.put("fingerprint", "d".repeat(64));
    candidate.put("compatible", true);
    for (var name : List.of("before", "after", "preview"))
      candidate.set(name, Json.MAPPER.valueToTree(snapshot));
    var run =
        new Run(
            3,
            "trafodion",
            "test-build",
            "2026-10-27T00:00:00Z",
            input,
            snapshot,
            snapshot,
            List.of(),
            List.of("Fixture; no rule execution evidence."),
            null,
            trace);
    assertDoesNotThrow(() -> Records.validate(run));
    var restored = Json.MAPPER.readValue(Json.MAPPER.writeValueAsBytes(run), Run.class);
    assertDoesNotThrow(() -> Records.validate(restored));
    candidate.put("ordinal", 1);
    assertThrows(IllegalArgumentException.class, () -> Records.validate(run));
    candidate.put("ordinal", 0);
    trace.put("complete", true);
    assertThrows(IllegalArgumentException.class, () -> Records.validate(run));
    trace.put("complete", false);
    trace.put("buildId", "different-build");
    assertThrows(IllegalArgumentException.class, () -> Records.validate(run));
  }
}
