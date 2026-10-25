package dev.optilens;

import java.util.*;
import static dev.optilens.Model.*;

/** Source-pinned inventory. Native execution remains unavailable until verified. */
public final class Trafodion {
  private static final com.fasterxml.jackson.databind.JsonNode DATA = load();
  public static final String VERSION = DATA.path("version").asText();
  public static final String COMMIT = DATA.path("commit").asText();
  private static final List<RuleCard> RULES = List.of(Json.MAPPER.convertValue(DATA.get("rules"), RuleCard[].class));
  private static com.fasterxml.jackson.databind.JsonNode load() {
    try (var in = Trafodion.class.getResourceAsStream("/trafodion-catalog.json")) {
      if (in == null) throw new IllegalStateException("Missing Trafodion rule inventory");
      return Json.MAPPER.readTree(in);
    } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
  }
  public static List<RuleCard> rules() { return RULES; }
  public static RuleCard rule(String id) {
    var rule = RULES.stream().filter(r -> r.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown Trafodion rule: " + id));
    Input.require(rule.runnable(), rule.unavailableReason()); return rule;
  }
  public static Map<String,Object> runtime() {
    return Map.of("available", false, "message", "Trafodion native worker implementation and build verification are pending.");
  }
  public static Map<String,Object> catalog() {
    return Map.of("database", "trafodion", "rules", rules(), "examples", List.of(),
        "engineVersion", VERSION, "scope", "Source inventory; native runtime verification pending",
        "runtime", runtime(), "maxSelected", Rules.MAX_SELECTED);
  }
  public static Map<String,Object> examples() {
    return Map.of("database", "trafodion", "engineVersion", VERSION, "examples", List.of(), "coverage", List.of());
  }
  public static Map<String,Object> capabilities() {
    var result = new LinkedHashMap<String,Object>(Plans.capabilities());
    result.put("database", "trafodion"); result.put("inputModes", List.of("sql", "plan"));
    result.put("runtime", runtime()); result.put("implementationStatus", "pending"); return result;
  }
  private Trafodion() {}
}
