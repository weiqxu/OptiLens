package dev.optilens;

import static dev.optilens.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/**
 * Version-pinned catalog and evidence; native StarRocks classes live only in the separate worker.
 */
public final class StarRocks {
  public static final String VERSION = "4.0.14";
  public static final String COMMIT = "7c18dd9dded1587dad24d912dcf8e777daf919f7";
  private static final JsonNode DATA = load("/starrocks-catalog.json");
  private static final List<RuleCard> RULES =
      List.of(Json.MAPPER.convertValue(DATA.get("rules"), RuleCard[].class));
  private static final List<Example> EXAMPLES =
      List.of(Json.MAPPER.convertValue(load("/starrocks-examples.json"), Example[].class));

  static JsonNode load(String name) {
    try (var in = StarRocks.class.getResourceAsStream(name)) {
      if (in == null) throw new IllegalStateException("Missing StarRocks resource: " + name);
      return Json.MAPPER.readTree(in);
    } catch (java.io.IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  public static List<RuleCard> rules() {
    return RULES;
  }

  public static List<Example> examples() {
    return EXAMPLES;
  }

  public static List<RuleExamples.Coverage> coverage() {
    return List.of(Json.MAPPER.convertValue(DATA.get("coverage"), RuleExamples.Coverage[].class));
  }

  public static String scope() {
    return DATA.path("scope").asText();
  }

  public static RuleCard rule(String id) {
    var card =
        RULES.stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown StarRocks rule: " + id));
    Input.require(card.runnable(), card.unavailableReason());
    return card;
  }

  public static Map<String, Object> capabilities() {
    return Json.MAPPER.convertValue(
        DATA.get("capabilities"),
        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
  }

  public static JsonNode verification() {
    return DATA.get("verification");
  }

  public static Map<String, Object> runtime() {
    String home = System.getenv("OPTILENS_STARROCKS_HOME");
    if (home != null && !home.isBlank()) {
      Path path = Path.of(home);
      if (Files.isDirectory(path.resolve("fe/lib"))
          && Files.isRegularFile(path.resolve("adapter/optilens-starrocks.jar")))
        try {
          var marker = Json.MAPPER.readTree(path.resolve("provenance.json").toFile());
          if (VERSION.equals(marker.path("version").asText())
              && COMMIT.equals(marker.path("commit").asText())
              && "sha256:c7b7fd890580258a9fafa6ef1c971279ee55bbf8ab1f0d83b733651b1c2cf423"
                  .equals(marker.path("layer").asText()))
            return Map.of(
                "available", true, "message", "StarRocks " + VERSION + " worker configured");
          return Map.of(
              "available",
              false,
              "message",
              "StarRocks runtime provenance does not match the pinned release. Reinstall with adapters/starrocks/tools/install_runtime.py.");
        } catch (java.io.IOException e) {
          return Map.of(
              "available",
              false,
              "message",
              "StarRocks runtime provenance is missing or unreadable. Reinstall the pinned runtime.");
        }
    }
    return Map.of(
        "available",
        false,
        "message",
        "StarRocks runtime is not installed. Follow plan/starrocks.md and set OPTILENS_STARROCKS_HOME before starting OptiLens.");
  }

  private StarRocks() {}
}
