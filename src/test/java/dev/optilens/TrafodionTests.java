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
            .allMatch(r -> !r.runnable() && r.source().matches("core/sql/optimizer/.*:[0-9]+")));
    assertEquals(false, Trafodion.runtime().get("available"));
  }

  @Test
  void routingNeverFallsBackToCalciteOrStarRocks() {
    assertEquals("trafodion", Engines.catalog("trafodion").get("database"));
    assertEquals("trafodion", Engines.planCapabilities("trafodion").get("database"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Engines.validateRule("trafodion", "CoreRules.FILTER_MERGE"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Engines.validateRule("trafodion", "Trafodion.HashJoinRule"));
  }
}
