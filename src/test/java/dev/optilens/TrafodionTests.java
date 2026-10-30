package dev.optilens;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TrafodionTests {
  @Test
  void inventoryHasStableUniqueIdsAndNoInventedVerification() {
    assertEquals(66, Trafodion.rules().size());
    assertEquals(66, Trafodion.rules().stream().map(Model.RuleCard::id).distinct().count());
    assertTrue(
        Trafodion.rules().stream()
            .allMatch(
                r ->
                    r.runnable()
                        && r.source()
                            .matches(
                                "https://github.com/weiqxu/Trafodion-Lite/blob/"
                                    + Trafodion.COMMIT
                                    + "/core/sql/optimizer/.*#L[0-9]+")));
    assertTrue(
        Trafodion.examples().get("examples") instanceof java.util.List<?> list && list.size() == 2);
    Trafodion.sqlExamples().forEach(e -> Input.validate(e.input()));
    Trafodion.ruleExamples().forEach(e -> Input.validate(e.input()));
    assertEquals(66, Trafodion.coverage().size());
    assertTrue(
        Trafodion.coverage().stream().filter(c -> c.status().equals("verified")).count() <= 2);
  }

  @Test
  void routingNeverFallsBackToCalciteOrStarRocks() {
    assertEquals("trafodion", Engines.catalog("trafodion").get("database"));
    assertEquals("trafodion", Engines.planCapabilities("trafodion").get("database"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Engines.validateRule("trafodion", "CoreRules.FILTER_MERGE"));
    assertDoesNotThrow(() -> Engines.validateRule("trafodion", "Trafodion.HashJoinRule"));
    assertEquals(java.util.List.of("sql", "plan"), Trafodion.capabilities().get("inputModes"));
  }

  @Test
  void missingOrWrongProvenanceNeverEnablesNativeRuntime(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
    assertEquals(false, Trafodion.runtime(home).get("available"));
    java.nio.file.Files.writeString(
        home.resolve("provenance.json"), "{\"engine\":\"calcite\",\"protocolVersion\":1}");
    assertEquals(false, Trafodion.runtime(home).get("available"));
  }
}
