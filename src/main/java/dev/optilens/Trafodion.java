package dev.optilens;

import static dev.optilens.Model.*;

import java.util.*;

/** Source-pinned rules and a separately installed native compiler runtime. */
public final class Trafodion {
  private static final com.fasterxml.jackson.databind.JsonNode DATA = load();
  public static final String VERSION = DATA.path("version").asText();
  public static final String COMMIT = DATA.path("commit").asText();
  private static final List<RuleCard> RULES =
      List.of(Json.MAPPER.convertValue(DATA.get("rules"), RuleCard[].class));

  private static com.fasterxml.jackson.databind.JsonNode load() {
    try (var in = Trafodion.class.getResourceAsStream("/trafodion-catalog.json")) {
      if (in == null) throw new IllegalStateException("Missing Trafodion rule inventory");
      return Json.MAPPER.readTree(in);
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static List<RuleCard> rules() {
    return RULES.stream()
        .map(
            r ->
                new RuleCard(
                    r.id(),
                    r.title(),
                    "Observe candidates produced by this rule during full native optimization.",
                    r.conditions(),
                    r.implementations(),
                    "https://github.com/weiqxu/Trafodion-Lite/blob/"
                        + COMMIT
                        + "/"
                        + r.source().replace(":", "#L"),
                    r.category(),
                    true,
                    null))
        .toList();
  }

  public static RuleCard rule(String id) {
    var rule =
        rules().stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown Trafodion rule: " + id));
    return rule;
  }

  public static Map<String, Object> runtime() {
    String configured = System.getenv("OPTILENS_TRAFODION_HOME");
    if (configured == null || configured.isBlank())
      return Map.of(
          "available",
          false,
          "message",
          "Set OPTILENS_TRAFODION_HOME after installing adapters/trafodion.");
    return runtime(java.nio.file.Path.of(configured));
  }

  public static Map<String, Object> runtime(java.nio.file.Path home) {
    try {
      var manifest = Json.MAPPER.readTree(home.resolve("provenance.json").toFile());
      Input.require(manifest.path("protocolVersion").asInt() == 1, "Unsupported native protocol");
      Input.require(manifest.path("engine").asText().equals("trafodion"), "Wrong native engine");
      String commit = manifest.path("sourceCommit").asText();
      Input.require(commit.matches("[0-9a-f]{40}"), "Invalid native source commit");
      var binary = java.nio.file.Path.of(manifest.path("binary").asText());
      Input.require(java.nio.file.Files.isExecutable(binary), "Native optimizer binary is missing");
      Input.require(
          sha256(binary).equals(manifest.path("binarySha256").asText()),
          "Native binary changed; reinstall the adapter");
      Input.require(
          java.nio.file.Files.isRegularFile(home.resolve("adapter/optilens-trafodion.jar")),
          "Trafodion bridge jar is missing");
      Input.require(
          sha256(home.resolve("adapter/optilens-trafodion.jar"))
              .equals(manifest.path("bridgeSha256").asText()),
          "Trafodion bridge changed; reinstall the adapter");
      var libraries = manifest.path("libraries");
      Input.require(
          libraries.isObject() && libraries.size() > 0,
          "Native shared-library provenance is missing");
      var fingerprint =
          new StringBuilder(manifest.path("binarySha256").asText())
              .append('\n')
              .append(manifest.path("bridgeSha256").asText())
              .append('\n');
      var libraryHashes = new TreeMap<String, String>();
      libraries
          .fields()
          .forEachRemaining(e -> libraryHashes.put(e.getKey(), e.getValue().asText()));
      libraryHashes.forEach(
          (path, checksum) -> fingerprint.append(path).append('=').append(checksum).append('\n'));
      String buildHash =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(
                          fingerprint
                              .toString()
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      Input.require(
          buildHash.equals(manifest.path("buildSha256").asText()),
          "Native build fingerprint is invalid");
      var entries = libraries.fields();
      while (entries.hasNext()) {
        var entry = entries.next();
        Input.require(
            verifiedHash(java.nio.file.Path.of(entry.getKey())).equals(entry.getValue().asText()),
            "Native shared library changed; reinstall the adapter");
      }
      var root = java.nio.file.Path.of(manifest.path("sourceRoot").asText());
      Input.require(
          java.nio.file.Files.isDirectory(root.resolve("core/sqf/export/lib64d")),
          "Trafodion shared libraries are missing");
      var sources = DATA.path("sources").fields();
      while (sources.hasNext()) {
        var source = sources.next();
        Input.require(
            sha256(root.resolve(source.getKey())).equals(source.getValue().asText()),
            "Native rule sources differ from the bundled inventory");
      }
      return Map.of(
          "available",
          true,
          "message",
          "Native SQL optimizer installed; rule selection filters observations.",
          "buildId",
          commit.substring(0, 12) + "-" + buildHash.substring(0, 12));
    } catch (Exception e) {
      return Map.of(
          "available",
          false,
          "message",
          "Trafodion installation is incomplete or incompatible: " + e.getMessage());
    }
  }

  private record Stamp(long size, java.nio.file.attribute.FileTime modified, String checksum) {}

  private static final java.util.concurrent.ConcurrentMap<java.nio.file.Path, Stamp> HASHES =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static String verifiedHash(java.nio.file.Path path) throws Exception {
    var attributes =
        java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
    var known = HASHES.get(path);
    if (known != null
        && known.size() == attributes.size()
        && known.modified().equals(attributes.lastModifiedTime())) return known.checksum();
    String checksum = sha256(path);
    HASHES.put(path, new Stamp(attributes.size(), attributes.lastModifiedTime(), checksum));
    return checksum;
  }

  public static String sha256(java.nio.file.Path path) throws Exception {
    var digest = java.security.MessageDigest.getInstance("SHA-256");
    try (var in = java.nio.file.Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int read;
      while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
    }
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  public static Map<String, Object> catalog() {
    return Map.of(
        "database",
        "trafodion",
        "rules",
        rules(),
        "examples",
        sqlExamples(),
        "engineVersion",
        VERSION,
        "scope",
        "Full native optimization. Selected rules filter candidate events; their order does not control native search.",
        "runtime",
        runtime(),
        "maxSelected",
        Rules.MAX_SELECTED,
        "executionMode",
        "native-search");
  }

  public static Map<String, Object> examples() {
    return Map.of(
        "database",
        "trafodion",
        "engineVersion",
        VERSION,
        "examples",
        ruleExamples(),
        "coverage",
        coverage());
  }

  public static Map<String, Object> capabilities() {
    var result = new LinkedHashMap<String, Object>(Plans.capabilities());
    result.put("database", "trafodion");
    result.put("inputModes", List.of("sql", "plan"));
    result.put(
        "message",
        "Native plan experiments construct and bind operators directly. Select a candidate to continue; each step replays in a fresh worker.");
    result.put("runtime", runtime());
    result.put("implementationStatus", "native-experiments");
    result.put("experimentMode", "candidate-replay");
    result.put(
        "nativeConstruction",
        Map.of(
            "implementedOperators",
            List.of(
                "TableScan",
                "Values",
                "Filter",
                "Project",
                "Calc",
                "Join",
                "Aggregate",
                "Union",
                "Intersect",
                "Minus",
                "Sort",
                "Exchange",
                "SortExchange",
                "MultiJoin"),
            "pending",
            List.of(),
            "maximumCanonicalExpressionBytes",
            100000));
    result.put(
        "nativeDefaultContext",
        Map.of(
            "pass",
            2,
            "executionLocation",
            "ESP",
            "availableCPUs",
            2,
            "distributionPartitions",
            2,
            "singletonPartitions",
            1));
    return result;
  }

  public static List<Example> sqlExamples() {
    return List.of(
        Json.MAPPER.convertValue(
            ExampleCases.resource("/examples/trafodion-demos.json"), Example[].class));
  }

  public static List<Example> ruleExamples() {
    return ExampleCases.examples("trafodion");
  }

  public static List<RuleExamples.Coverage> coverage() {
    return rules().stream()
        .map(
            r -> {
              var evidence = ExampleVerification.coverage("trafodion", r.id());
              return evidence != null
                  ? evidence
                  : new RuleExamples.Coverage(
                      r.id(),
                      "not-covered",
                      "Catalogued native rule. No verified changed-candidate example is bundled yet.");
            })
        .toList();
  }

  private Trafodion() {}
}
