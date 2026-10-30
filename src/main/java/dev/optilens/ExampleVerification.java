package dev.optilens;

import java.nio.file.*;
import java.util.*;

/** Only current, generated evidence can grant a Verified example label. */
public final class ExampleVerification {
  public static final int VALIDATOR_VERSION = 1;

  public record Result(
      String caseId,
      String caseHash,
      String status,
      String reason,
      String engineVersion,
      String buildId,
      String sourceCommit,
      int changes,
      String completedAt) {}

  public record Report(
      int formatVersion,
      int validatorVersion,
      String database,
      String adapterHash,
      List<Result> results) {}

  private static class Reports {
    static final Map<String, Report> ALL = load();

    private static Map<String, Report> load() {
      var reports = new HashMap<String, Report>();
      for (String db : List.of("calcite", "starrocks", "trafodion")) {
        try (var stream =
            ExampleVerification.class.getResourceAsStream("/examples/reports/" + db + ".json")) {
          if (stream == null) continue;
          var report = Json.MAPPER.readValue(stream, Report.class);
          if (report.formatVersion() == 1
              && db.equals(report.database())
              && report.results() != null) reports.put(db, report);
        } catch (Exception ignored) {
          // Malformed evidence never grants verification or prevents startup.
        }
      }
      return Map.copyOf(reports);
    }
  }

  public static RuleExamples.Coverage coverage(String database, String rule) {
    var cases =
        ExampleCases.all(database).stream().filter(c -> c.targetRule().equals(rule)).toList();
    if (cases.isEmpty()) return null;
    RuleExamples.Coverage fallback =
        new RuleExamples.Coverage(
            rule,
            "unverified",
            "An example is available, but no current verification report is bundled.");
    var report = Reports.ALL.get(database);
    if (report == null) return fallback;
    for (var c : cases) {
      var status = status(c, report);
      if (status.equals("verified"))
        return new RuleExamples.Coverage(
            rule,
            status,
            "The bundled report records a real target-rule change on the pinned engine/build. Candidate generation does not prove adoption or performance improvement.");
      fallback =
          new RuleExamples.Coverage(
              rule,
              status,
              switch (status) {
                case "stale" ->
                    "The case, validator, adapter or installed engine build changed. Rerun verification.";
                case "skipped" ->
                    "Verification was skipped because the required runtime was unavailable.";
                case "failed" ->
                    "The case failed verification. See the generated report for evidence and diagnostics.";
                case "verified-no-change" ->
                    "The expected absence of a target-rule change was verified; this is not a verified transformation example.";
                default ->
                    "An example is available, but no current verification report is bundled.";
              });
    }
    return fallback;
  }

  public static String status(ExampleCases.Case c, Report report) {
    if (report == null || report.results() == null) return "unverified";
    if (report.formatVersion() != 1
        || report.validatorVersion() != VALIDATOR_VERSION
        || !c.input().database().equals(report.database())
        || !adapterHash().equals(report.adapterHash())) return "stale";
    var entries =
        report.results().stream().filter(r -> r != null && c.id().equals(r.caseId())).toList();
    if (entries.size() != 1) return "unverified";
    var r = entries.get(0);
    if (!ExampleCases.hash(c).equals(r.caseHash()) || !c.engineVersion().equals(r.engineVersion()))
      return "stale";
    String installed = installedBuild(c.input().database());
    if (installed != null && !installed.equals(r.buildId()) && "passed".equals(r.status()))
      return "stale";
    if ("passed".equals(r.status())) {
      if (c.input().database().equals("starrocks")
          && !Objects.equals(c.sourceCommit(), r.sourceCommit())) return "stale";
      if (c.input().database().equals("trafodion")
          && (r.sourceCommit() == null
              || !r.sourceCommit().matches("[0-9a-f]{40}")
              || r.buildId() == null
              || !r.buildId().startsWith(r.sourceCommit().substring(0, 12) + "-"))) return "stale";
      try {
        java.time.Instant.parse(r.completedAt());
      } catch (Exception e) {
        return "unverified";
      }

      if (r.buildId() == null || r.buildId().isBlank() || r.completedAt() == null)
        return "unverified";
      if (c.expectation().kind().equals("no-change"))
        return r.changes() == 0 ? "verified-no-change" : "failed";
      return r.changes() >= c.expectation().minimumChanges() ? "verified" : "failed";
    }
    return r.status() != null && Set.of("failed", "skipped").contains(r.status())
        ? r.status()
        : "unverified";
  }

  public static String installedBuild(String database) {
    if (database.equals("calcite")) return "calcite-1.39.0";
    if (database.equals("trafodion")) {
      var runtime = Trafodion.runtime();
      return Boolean.TRUE.equals(runtime.get("available")) ? (String) runtime.get("buildId") : null;
    }
    if (!Boolean.TRUE.equals(StarRocks.runtime().get("available"))) return null;
    try {
      Path home = Path.of(System.getenv("OPTILENS_STARROCKS_HOME"));
      return StarRocks.VERSION
          + "-"
          + StarRocks.COMMIT
          + "-"
          + ExampleCases.sha256(Files.readAllBytes(home.resolve("adapter/optilens-starrocks.jar")));
    } catch (Exception e) {
      return null;
    }
  }

  private static class AdapterIdentity {
    static final String HASH = compute();

    private static void collect(Class<?> type, Set<String> names) {
      names.add(type.getName());
      for (var nested : type.getDeclaredClasses()) collect(nested, names);
    }

    private static String compute() {
      var bytes = new java.io.ByteArrayOutputStream();
      // Changes to execution, construction or evidence interpretation invalidate the report.
      var names = new TreeSet<String>();
      for (var name :
          List.of(
              "ExampleCases",
              "ExampleVerification",
              "CaseVerifier",
              "Worker",
              "Optimizer",
              "Plans",
              "Snapshots",
              "Rules",
              "Records",
              "Input",
              "Model",
              "Engines",
              "Jobs",
              "StarRocks",
              "Trafodion",
              "Json")) {
        try {
          collect(
              Class.forName(
                  "dev.optilens." + name, false, ExampleVerification.class.getClassLoader()),
              names);
        } catch (ClassNotFoundException e) {
          throw new IllegalStateException(e);
        }
      }
      for (var name : names) {
        try (var in =
            ExampleVerification.class.getResourceAsStream(
                "/" + name.replace('.', '/') + ".class")) {
          if (in == null) throw new IllegalStateException("Missing verifier class: " + name);
          bytes.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          bytes.write(in.readAllBytes());
        } catch (java.io.IOException e) {
          throw new IllegalStateException(e);
        }
      }
      return ExampleCases.sha256(bytes.toByteArray());
    }
  }

  public static String adapterHash() {
    return AdapterIdentity.HASH;
  }

  public static com.fasterxml.jackson.databind.JsonNode report(String database) {
    Engines.validateDatabase(database);
    var node = Json.MAPPER.createObjectNode();
    node.put("database", database);
    node.set("report", Json.MAPPER.valueToTree(Reports.ALL.get(database)));
    var statuses = node.putArray("cases");
    for (var c : ExampleCases.all(database)) {
      var entry = statuses.addObject();
      entry.put("id", c.id());
      entry.put("targetRule", c.targetRule());
      entry.put("status", status(c, Reports.ALL.get(database)));
    }
    return node;
  }

  private ExampleVerification() {}
}
