package dev.optilens;

import static dev.optilens.Model.*;

import java.util.List;

/** Compatibility accessor for the unified JSON case library. */
public final class PlanExamples {
  public static List<Example> all() {
    return ExampleCases.examples("calcite").stream()
        .filter(e -> "plan".equals(e.input().inputMode()))
        .toList();
  }

  private PlanExamples() {}
}
