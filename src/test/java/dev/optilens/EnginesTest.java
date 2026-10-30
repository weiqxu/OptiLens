package dev.optilens;

import static dev.optilens.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EnginesTest {
  @Test
  void catalogsAreIsolatedAndAllInventoryEntriesHaveEvidenceStatus() {
    var calcite = Engines.catalog("calcite");
    var starrocks = Engines.catalog("starrocks");
    assertEquals(Rules.cards(), calcite.get("rules"));
    assertEquals(StarRocks.rules(), starrocks.get("rules"));
    assertTrue(StarRocks.rules().size() >= 262);
    assertEquals(StarRocks.rules().size(), StarRocks.coverage().size());
    var ids = new HashSet<String>();
    for (var card : StarRocks.rules()) {
      assertTrue(ids.add(card.id()));
      assertTrue(card.source().contains(StarRocks.COMMIT));
      assertFalse(card.source().contains("/blob/main/"));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> Engines.validateRule("calcite", "StarRocks.MergeTwoFiltersRule"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Engines.validateRule("starrocks", "CoreRules.FILTER_MERGE"));
    assertThrows(IllegalArgumentException.class, () -> Engines.catalog("other"));
  }

  @Test
  void nativeCapabilitiesDoNotOfferCalciteOnlyOperators() {
    var caps = Engines.planCapabilities("starrocks");
    var operators = (Map<?, ?>) caps.get("operators");
    assertTrue(operators.containsKey("Join"));
    assertFalse(operators.containsKey("Calc"));
    assertFalse(operators.containsKey("MultiJoin"));
    assertEquals("starrocks", caps.get("database"));
  }

  @Test
  void everyPublishedNativeExampleHasObservedTargetEvidence() {
    var statuses = new HashMap<String, String>();
    StarRocks.coverage().forEach(c -> statuses.put(c.id(), c.status()));
    for (var example : StarRocks.examples()) {
      assertNotNull(statuses.get(example.id()));
      assertTrue(
          Set.of("verified", "unverified", "stale", "skipped", "failed")
              .contains(statuses.get(example.id())));
      assertEquals("starrocks", example.input().database());
      assertTrue(example.input().rules().contains(example.id()));
      Input.validate(example.input());
    }
  }
}
