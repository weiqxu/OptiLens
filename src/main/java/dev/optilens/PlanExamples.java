package dev.optilens;

import static dev.optilens.Model.*;

import java.io.IOException;
import java.util.*;

public final class PlanExamples {
  private record Entry(String id, String title, String note, PlanDocument plan) {}

  private static final List<Example> ALL = load();

  private static List<Example> load() {
    try (var stream = PlanExamples.class.getResourceAsStream("/plan-examples.json")) {
      if (stream == null) throw new IllegalStateException("Missing plan examples");
      return Arrays.stream(Json.MAPPER.readValue(stream, Entry[].class))
          .map(
              e ->
                  new Example(
                      e.id(),
                      e.title(),
                      e.note(),
                      new Request(
                          null,
                          Input.examples().get(0).input().schema(),
                          List.of(e.id()),
                          "calcite",
                          new Options(null, 10),
                          "plan",
                          e.plan(),
                          null)))
          .toList();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static List<Example> all() {
    return ALL;
  }

  private PlanExamples() {}
}
