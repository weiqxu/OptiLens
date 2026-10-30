package dev.optilens;

import static dev.optilens.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Versioned, engine-independent example inputs. Presence is not verification. */
public final class ExampleCases {
  public record Expectation(String kind, int stageIndex, int minimumChanges) {}

  public record Choice(Integer ordinal, String fingerprint) {}

  public record Case(
      String id,
      String targetRule,
      String title,
      String note,
      String engineVersion,
      String sourceCommit,
      Request input,
      Expectation expectation,
      List<Choice> choices) {
    public Example example() {
      return new Example(targetRule, title, note, input);
    }
  }

  private record Document(int formatVersion, List<Case> cases) {}

  private static final Map<String, List<Case>> CASES = load();

  private static Map<String, List<Case>> load() {
    var result = new LinkedHashMap<String, List<Case>>();
    for (String db : List.of("calcite", "starrocks", "trafodion")) {
      var document =
          Json.MAPPER.convertValue(resource("/examples/cases/" + db + ".json"), Document.class);
      Input.require(
          document.formatVersion() == 1 && document.cases() != null, "Invalid example document");
      Set<String> ids = new HashSet<>();
      for (var c : document.cases()) {
        Input.require(
            c.id() != null && !c.id().isBlank() && ids.add(c.id()), "Duplicate or missing case ID");
        Input.require(
            c.input() != null && db.equals(c.input().database()), "Case database mismatch");
        Input.require(
            c.targetRule() != null
                && c.title() != null
                && c.note() != null
                && c.engineVersion() != null
                && c.choices() != null
                && c.choices().size() <= 32,
            "Incomplete example case");
        Input.require(
            c.expectation() != null
                && Set.of("target-change", "no-change").contains(c.expectation().kind())
                && c.expectation().stageIndex() >= 0
                && c.expectation().stageIndex() < c.input().rules().size()
                && c.targetRule().equals(c.input().rules().get(c.expectation().stageIndex()))
                && c.expectation().minimumChanges() >= 0
                && (c.expectation().kind().equals("no-change")
                    || c.expectation().minimumChanges() > 0),
            "Invalid target expectation");
        for (var choice : c.choices())
          Input.require(
              choice.ordinal() != null
                  && choice.ordinal() >= -1
                  && choice.ordinal() < 80
                  && (choice.fingerprint() == null || choice.fingerprint().matches("[0-9a-f]{64}")),
              "Invalid explicit candidate choice");
      }
      result.put(db, List.copyOf(document.cases()));
    }
    return Collections.unmodifiableMap(result);
  }

  public static List<Case> all(String database) {
    Engines.validateDatabase(database);
    return CASES.get(database);
  }

  public static List<Example> examples(String database) {
    // The existing explorer accepts one example per rule; reports retain every case.
    var examples = new LinkedHashMap<String, Example>();
    all(database).forEach(c -> examples.putIfAbsent(c.targetRule(), c.example()));
    return List.copyOf(examples.values());
  }

  public static JsonNode resource(String name) {
    try (var in = ExampleCases.class.getResourceAsStream(name)) {
      if (in == null) throw new IllegalStateException("Missing example resource: " + name);
      return Json.MAPPER.readTree(in);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read " + name, e);
    }
  }

  public static String hash(Case c) {
    return hash(Json.MAPPER.valueToTree(c));
  }

  public static String hash(JsonNode value) {
    return sha256(canonical(value).toString().getBytes(StandardCharsets.UTF_8));
  }

  private static JsonNode canonical(JsonNode value) {
    if (value.isObject()) {
      var result = Json.MAPPER.createObjectNode();
      var keys = new TreeSet<String>();
      value.fieldNames().forEachRemaining(keys::add);
      keys.forEach(k -> result.set(k, canonical(value.get(k))));
      return result;
    }
    if (value.isArray()) {
      var result = Json.MAPPER.createArrayNode();
      value.forEach(v -> result.add(canonical(v)));
      return result;
    }
    return value;
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private ExampleCases() {}
}
