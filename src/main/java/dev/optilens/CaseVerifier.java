package dev.optilens;

import static dev.optilens.Model.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Offline runner using the same isolated, resource-limited workers as the application. */
public final class CaseVerifier {
  public static void main(String[] args) throws Exception {
    String database = "all", caseId = null;
    Path output = Path.of("target/example-reports");
    boolean requireRuntime = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--database" -> database = args[++i];
        case "--case" -> caseId = args[++i];
        case "--output" -> output = Path.of(args[++i]);
        case "--require-runtime" -> requireRuntime = true;
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }
    List<String> databases =
        database.equals("all") ? List.of("calcite", "starrocks", "trafodion") : List.of(database);
    Files.createDirectories(output);
    int failed = 0, skipped = 0, selected = 0;
    for (String db : databases) {
      var results = new ArrayList<ExampleVerification.Result>();
      var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var futures = new ArrayList<java.util.concurrent.Future<ExampleVerification.Result>>();
        for (var c : ExampleCases.all(db)) {
          if (caseId != null && !caseId.equals(c.id())) continue;
          selected++;
          futures.add(executor.submit(() -> verify(c)));
        }
        for (var future : futures) {
          var result = future.get();
          results.add(result);
          System.out.println(
              db + " " + result.caseId() + " " + result.status() + " · " + result.reason());
          if (result.status().equals("failed")) failed++;
          if (result.status().equals("skipped")) skipped++;
        }
      } finally {
        executor.shutdownNow();
      }
      if (!results.isEmpty()) {
        var report =
            new ExampleVerification.Report(
                1,
                ExampleVerification.VALIDATOR_VERSION,
                db,
                ExampleVerification.adapterHash(),
                List.copyOf(results));
        Path path = output.resolve(db + ".json"),
            temporary = Files.createTempFile(output, db, ".tmp");
        Json.MAPPER.writeValue(temporary.toFile(), report);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Input.require(selected > 0, "No matching cases");
    if (failed > 0 || requireRuntime && skipped > 0) System.exit(1);
  }

  public static ExampleVerification.Result verify(ExampleCases.Case c) {
    String build = null, source = c.sourceCommit();
    Path directory = null;
    try {
      Input.validate(c.input());
      String version =
          switch (c.input().database()) {
            case "calcite" -> "1.39.0";
            case "starrocks" -> StarRocks.VERSION;
            default -> Trafodion.VERSION;
          };
      Input.require(
          version.equals(c.engineVersion()), "Case engine version differs from the pinned engine");
      if (c.input().database().equals("starrocks"))
        Input.require(
            StarRocks.COMMIT.equals(source), "Case source commit differs from the pinned release");
      if (c.input().database().equals("trafodion"))
        Input.require(
            Trafodion.COMMIT.equals(source),
            "Case source baseline differs from the rule inventory");
      build = ExampleVerification.installedBuild(c.input().database());
      if (build == null)
        return result(
            c,
            "skipped",
            "Required runtime is not installed or has incompatible provenance",
            null,
            source,
            0);
      if (c.input().database().equals("trafodion"))
        source =
            Json.MAPPER
                .readTree(
                    Path.of(System.getenv("OPTILENS_TRAFODION_HOME"), "provenance.json").toFile())
                .path("sourceCommit")
                .asText();
      directory = Files.createTempDirectory("optilens-verify-");
      try (var jobs = new Jobs(directory)) {
        var job = await(jobs, jobs.submit(c.input()));
        for (var choice : c.choices()) {
          var trace = job.result().nativeTrace();
          Input.require(
              trace != null && !trace.path("complete").asBoolean(),
              "Choice has no pending native step");
          String id = "unchanged";
          if (choice.ordinal() >= 0) {
            var candidate = trace.path("candidates").get(choice.ordinal());
            Input.require(candidate != null, "Requested candidate ordinal was not generated");
            Input.require(
                choice.fingerprint() == null
                    || choice.fingerprint().equals(candidate.path("fingerprint").asText()),
                "Expected candidate fingerprint changed");
            id = candidate.path("id").asText();
          }
          job = await(jobs, jobs.step(job.id(), id, c.input()));
        }
        int changes = assess(c, job.result());
        Input.require(
            build.equals(ExampleVerification.installedBuild(c.input().database())),
            "Engine build changed during verification");
        if (job.result().nativeTrace() != null)
          source = job.result().nativeTrace().path("sourceCommit").asText(source);
        return result(
            c,
            "passed",
            c.expectation().kind().equals("no-change")
                ? "Expected no-change observed"
                : "Observed " + changes + " real target-rule changes",
            build,
            source,
            changes);
      }
    } catch (Exception e) {
      return result(c, "failed", Worker.error(e), build, source, 0);
    } finally {
      if (directory != null) {
        try (var files = Files.walk(directory)) {
          for (var file : files.sorted(Comparator.reverseOrder()).toList())
            Files.deleteIfExists(file);
        } catch (java.io.IOException ignored) {
        }
      }
    }
  }

  private static Jobs.Job await(Jobs jobs, Jobs.Job job) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
    while (true) {
      if (System.nanoTime() > deadline)
        throw new java.util.concurrent.TimeoutException("Verification worker timed out");
      job = jobs.get(job.id());
      if (job.status().equals("succeeded")) return job;
      if (job.status().equals("failed")) throw new IllegalArgumentException(job.error());
      Thread.sleep(25);
    }
  }

  public static int assess(ExampleCases.Case c, Run run) {
    Records.validate(run);
    Input.require(c.input().equals(run.input()), "Worker changed the case input");
    if (!c.input().database().equals("trafodion"))
      Input.require(
          c.engineVersion().equals(run.engineVersion()), "Unexpected worker engine version");
    if (c.expectation().kind().equals("no-change"))
      Input.require(
          run.limitations().stream()
                  .noneMatch(s -> s.toLowerCase(java.util.Locale.ROOT).contains("was truncated"))
              && (run.nativeTrace() == null || !run.nativeTrace().path("truncated").asBoolean()),
          "Truncated evidence cannot establish a no-change expectation");
    int step = c.expectation().stageIndex(), changes;
    if (run.formatVersion() == 3
        && step == run.nativeTrace().path("stepIndex").asInt()
        && !run.nativeTrace().path("complete").asBoolean()) {
      Input.require(
          !run.nativeTrace().path("truncated").asBoolean(), "Candidate enumeration was truncated");
      changes = 0;
      for (var candidate : run.nativeTrace().path("candidates"))
        if (!candidate
            .path("fingerprint")
            .asText()
            .equals(run.nativeTrace().path("currentFingerprint").asText())) changes++;
    } else {
      Input.require(
          step < run.stages().size(),
          "Target step has not been reached; add explicit replay choices");
      var stage = run.stages().get(step);
      Input.require(
          c.targetRule().equals(stage.id()), "Evidence came from a different target stage");
      Set<String> implementations = implementations(c.input().database(), c.targetRule());
      changes =
          (int)
              stage.events().stream()
                  .filter(e -> implementations.contains(e.rule()))
                  .filter(
                      e ->
                          !structure(e.before(), c.input().database())
                              .equals(structure(e.after(), c.input().database())))
                  .count();
    }
    Input.require(
        c.expectation().kind().equals("no-change")
            ? changes == 0
            : changes >= c.expectation().minimumChanges(),
        "Target expectation not met: observed " + changes + " changes");
    return changes;
  }

  private static Set<String> implementations(String database, String rule) {
    RuleCard card =
        switch (database) {
          case "calcite" -> Rules.get(rule).card();
          case "starrocks" -> StarRocks.rule(rule);
          default -> Trafodion.rule(rule);
        };
    var names = new HashSet<>(card.implementations());
    names.add(rule);
    return names;
  }

  /** Traverse graph identity separately from display IDs; never compare complete raw strings. */
  public static String structure(Snapshot snapshot, String database) {
    var nodes = new HashMap<String, PlanNode>();
    snapshot.nodes().forEach(n -> nodes.put(n.id(), n));
    var ids = new LinkedHashMap<String, Integer>();
    return ExampleCases.hash(shape(snapshot.rootId(), nodes, ids, database));
  }

  private static com.fasterxml.jackson.databind.JsonNode shape(
      String id, Map<String, PlanNode> nodes, Map<String, Integer> ids, String database) {
    if (ids.containsKey(id)) return Json.MAPPER.valueToTree(Map.of("ref", ids.get(id)));
    ids.put(id, ids.size());
    var n = nodes.get(id);
    var result = Json.MAPPER.createObjectNode();
    result.put("type", n.type());
    var attributes = Json.MAPPER.createObjectNode();
    n.attributes()
        .forEach(
            (k, v) ->
                attributes.put(
                    k, database.equals("trafodion") ? v.replaceAll("\\[\\d+\\]", "[value]") : v));
    result.set("attributes", attributes);
    var fields = result.putArray("fields");
    n.fields()
        .forEach(
            f ->
                fields.add(
                    database.equals("trafodion") ? f.replaceAll("\\[\\d+\\]", "[value]") : f));
    var inputs = result.putArray("inputs");
    n.inputs().forEach(child -> inputs.add(shape(child, nodes, ids, database)));
    return result;
  }

  private static ExampleVerification.Result result(
      ExampleCases.Case c, String status, String reason, String build, String source, int changes) {
    return new ExampleVerification.Result(
        c.id(),
        ExampleCases.hash(c),
        status,
        reason,
        c.engineVersion(),
        build,
        source,
        changes,
        Instant.now().toString());
  }

  private CaseVerifier() {}
}
