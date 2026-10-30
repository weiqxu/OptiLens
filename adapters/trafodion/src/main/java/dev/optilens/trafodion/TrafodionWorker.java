package dev.optilens.trafodion;

import static dev.optilens.Model.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.optilens.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** File-protocol bridge. The native child compiles SQL, never executes a user query. */
public final class TrafodionWorker {
  private TrafodionWorker() {}

  public static void main(String[] args) throws Exception {
    Worker.Outcome outcome;
    try {
      JsonNode transport = Json.MAPPER.readTree(Path.of(args[0]).toFile());
      Request request =
          Json.MAPPER.treeToValue(
              transport.has("request") ? transport.get("request") : transport, Request.class);
      Input.validate(request);
      boolean validation = args.length > 2 && args[2].equals("validate");
      Path home = Path.of(Objects.requireNonNull(System.getenv("OPTILENS_TRAFODION_HOME")));
      var runtime = Trafodion.runtime(home);
      if (!Boolean.TRUE.equals(runtime.get("available")))
        throw new IllegalArgumentException((String) runtime.get("message"));
      JsonNode manifest = Json.MAPPER.readTree(home.resolve("provenance.json").toFile());
      JsonNode response = execute(request, manifest, validation, transport.path("replay"));
      if (validation)
        outcome =
            new Worker.Outcome(
                null,
                null,
                Json.MAPPER.treeToValue(response.path("validation"), PlanValidation.class));
      else
        outcome =
            new Worker.Outcome(
                "plan".equals(request.inputMode())
                    ? decodeExperiment(
                        request,
                        response,
                        manifest.path("sourceCommit").asText(),
                        (String) runtime.get("buildId"))
                    : decode(
                        request,
                        response,
                        manifest.path("sourceCommit").asText(),
                        (String) runtime.get("buildId")),
                null);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      message = message.substring(0, Math.min(2000, message.length()));
      if (args.length > 2 && args[2].equals("validate")) {
        String node = null, path = "plan";
        int colon = message.indexOf(':');
        if (colon > 0 && colon < 100) {
          node = message.substring(0, colon);
          path = "attributes";
        }
        outcome =
            new Worker.Outcome(
                null,
                null,
                new PlanValidation(
                    false, null, Map.of(), List.of(new PlanIssue(node, path, message))));
      } else outcome = new Worker.Outcome(null, message);
    }
    byte[] bytes = Json.MAPPER.writeValueAsBytes(outcome);
    if (bytes.length > 8_000_000)
      bytes =
          Json.MAPPER.writeValueAsBytes(
              new Worker.Outcome(null, "Native run exceeds the 8 MB record limit"));
    Files.write(Path.of(args[1]), bytes);
  }

  private static JsonNode execute(
      Request request, JsonNode manifest, boolean validation, JsonNode replay) throws Exception {
    Path work = Files.createTempDirectory("optilens-trafodion-");
    Process process = null;
    try {
      Path input = work.resolve("input.json"), output = work.resolve("output.json");
      var observed =
          request.rules().stream()
              .map(id -> Map.of("id", id, "name", Trafodion.rule(id).title()))
              .toList();
      var envelope = Json.MAPPER.createObjectNode();
      var nativeInput =
          (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(request);
      if ("plan".equals(request.inputMode())) annotate(nativeInput.path("plan"));
      envelope.set("request", nativeInput);
      envelope.set("observedRules", Json.MAPPER.valueToTree(observed));
      envelope.put("action", validation ? "validate" : "step");
      if (replay.isArray()) envelope.set("replay", replay);
      Json.MAPPER.writeValue(input.toFile(), envelope);
      Path root = Path.of(manifest.path("sourceRoot").asText());
      var builder =
          new ProcessBuilder(manifest.path("binary").asText(), input.toString(), output.toString())
              .directory(work.toFile())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD);
      var environment = builder.environment();
      environment.put("TRAF_HOME", root.resolve("core/sqf").toString());
      environment.put("TRAF_LITE", "1");
      environment.put("TRAF_VAR", work.resolve("var").toString());
      environment.put("TRAF_LOG", work.resolve("log").toString());
      environment.put("TRAF_LITE_STORE_DIR", work.resolve("store").toString());
      environment.put(
          "LD_LIBRARY_PATH",
          root.resolve("core/sql/lib/linux/64bit/debug")
              + ":"
              + root.resolve("core/sqf/export/lib64d")
              + ":"
              + environment.getOrDefault("LD_LIBRARY_PATH", ""));
      process = builder.start();
      if (!process.waitFor(20, TimeUnit.SECONDS))
        throw new IllegalArgumentException("Native compilation exceeded 20 seconds");
      if (process.exitValue() != 0 || !Files.isRegularFile(output))
        throw new IllegalArgumentException("Native optimizer exited without a response");
      if (Files.size(output) > 8_000_000)
        throw new IllegalArgumentException("Native response exceeds 8 MB");
      JsonNode result = Json.MAPPER.readTree(output.toFile());
      if (result.hasNonNull("error"))
        throw new IllegalArgumentException(result.path("error").asText());
      return result;
    } finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
      }
      try (var files = Files.walk(work)) {
        for (var file : files.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(file);
      }
    }
  }

  private static void annotate(JsonNode value) {
    if (value.isObject() && "literal".equals(value.path("kind").asText()))
      ((com.fasterxml.jackson.databind.node.ObjectNode) value)
          .put("valueIsNull", value.path("value").isNull());
    if (value.isContainerNode()) value.elements().forEachRemaining(TrafodionWorker::annotate);
  }

  public static Run decodeExperiment(Request request, JsonNode data, String commit, String version)
      throws Exception {
    if (data.path("protocolVersion").asInt() != 1
        || !commit.equals(data.path("sourceCommit").asText()))
      throw new IllegalArgumentException("Native experiment engine identity changed");
    Snapshot prepared = Json.MAPPER.treeToValue(data.path("prepared"), Snapshot.class);
    Snapshot current = Json.MAPPER.treeToValue(data.path("current"), Snapshot.class);
    List<Stage> stages = new ArrayList<>();
    int index = 0;
    for (var step : data.path("stages")) {
      String id = step.path("id").asText();
      RuleCard card = Trafodion.rule(id);
      Snapshot before = Json.MAPPER.treeToValue(step.path("before"), Snapshot.class);
      Snapshot after = Json.MAPPER.treeToValue(step.path("after"), Snapshot.class);
      List<Event> events = List.of();
      List<Attempt> attempts = List.of();
      if (step.path("event").isObject()) {
        Snapshot localBefore =
            Json.MAPPER.treeToValue(step.path("event").path("before"), Snapshot.class);
        Snapshot localAfter =
            Json.MAPPER.treeToValue(step.path("event").path("after"), Snapshot.class);
        events = List.of(new Event(1, ++index, id, localBefore, localAfter));
        attempts = List.of(new Attempt(index, id, List.of(localBefore.rootId())));
      }
      stages.add(
          new Stage(
              id,
              card.title(),
              "Explicitly selected native candidate, reproduced in a fresh worker. No other rule search runs.",
              card.conditions(),
              card.source(),
              before,
              after,
              attempts,
              events,
              step.path("scopeBeforeRootId").asText(before.rootId()),
              step.path("scopeAfterRootId").asText(after.rootId())));
    }
    var trace = (com.fasterxml.jackson.databind.node.ObjectNode) data.path("experiment").deepCopy();
    trace.put("kind", "trafodion-experiment");
    trace.put("sourceCommit", commit);
    trace.put("buildId", version);
    trace.set("nativeNodeMapping", data.path("mapping"));
    trace.set(
        "context",
        Json.MAPPER.valueToTree(
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
                1)));
    trace.set(
        "preparation",
        Json.MAPPER.valueToTree(
            List.of(
                "Operators and expressions are constructed through native C++ APIs. Only typed scalar literals and scalar templates use the native expression parser; no complete SQL query is generated.",
                "Binding, identity column-equivalence maps, INNER join predicate storage and logical-property initialization precede rule experiments. The native normalizer and optimizer search do not run.",
                "Calc, RIGHT joins, aggregation outputs, set occurrence matching, null ordering, pagination and distributed enforcers may expand into native subgraphs. Original editing IDs are mapped to the prepared native graph.")));
    trace.set("fields", data.path("fields"));
    Run run =
        new Run(
            3,
            "trafodion",
            version,
            Instant.now().toString(),
            request,
            prepared,
            current,
            stages,
            List.of(
                "Hand-built native operators are bound and have logical properties initialized without running the native normalizer or optimizer search.",
                "Each step invokes only its selected native rule with recorded native physical requirements and pass 2. Candidates are generated before Memo insertion and are not cost-optimality claims.",
                "A fresh worker reconstructs the original native input and verifies every selected candidate ordinal and structural fingerprint before proceeding.",
                "Native results remain read-only; continuing an experiment uses replay and does not require lossy conversion back to editor JSON."),
            new EditableResult(
                null,
                List.of(
                    "Use native candidate replay to continue. Lossless editor export is not implemented.")),
            trace);
    Records.validate(run);
    return run;
  }

  /** Validate provenance and translate observations without claiming an ordered rewrite program. */
  public static Run decode(Request request, JsonNode data, String commit, String version)
      throws Exception {
    Input.validate(request);
    if (!"trafodion".equals(request.database()) || !"sql".equals(request.inputMode()))
      throw new IllegalArgumentException("Native response requires Trafodion SQL input");
    if (data.path("protocolVersion").asInt() != 1
        || !commit.equals(data.path("sourceCommit").asText()))
      throw new IllegalArgumentException(
          "Native worker version does not match the installed build");
    Snapshot initial = Json.MAPPER.treeToValue(data.get("initial"), Snapshot.class);
    Snapshot after = Json.MAPPER.treeToValue(data.get("finalPlan"), Snapshot.class);
    if (!data.path("events").isArray() || data.path("events").size() > 80)
      throw new IllegalArgumentException("Invalid native event count");
    Map<String, List<Event>> grouped = new LinkedHashMap<>();
    Set<Integer> calls = new HashSet<>();
    int sequence = 0;
    for (var event : data.path("events")) {
      String id = event.path("rule").asText();
      int call = event.path("callId").asInt();
      if (!request.rules().contains(id) || call <= 0 || !calls.add(call))
        throw new IllegalArgumentException("Invalid native rule event identity");
      grouped
          .computeIfAbsent(id, ignored -> new ArrayList<>())
          .add(
              new Event(
                  ++sequence,
                  call,
                  id,
                  Json.MAPPER.treeToValue(event.get("before"), Snapshot.class),
                  Json.MAPPER.treeToValue(event.get("after"), Snapshot.class)));
    }
    List<Stage> stages = new ArrayList<>();
    for (String id : request.rules()) {
      RuleCard rule = Trafodion.rule(id);
      List<Event> events = grouped.getOrDefault(id, List.of());
      stages.add(
          new Stage(
              id,
              rule.title(),
              "Observation filter over one complete native optimizer search. These full-plan snapshots are the common search boundaries, not ordered rule stages. A candidate event does not mean the final plan selected it.",
              rule.conditions(),
              rule.source(),
              initial,
              after,
              events.stream()
                  .map(e -> new Attempt(e.callId(), id, List.of(e.before().rootId())))
                  .toList(),
              events,
              initial.rootId(),
              after.rootId()));
    }
    var limitations =
        new ArrayList<>(
            List.of(
                "Trafodion performs complete native cost-based SQL optimization. Selected rules filter observations; all native rules remain enabled and selection order does not control search.",
                "Candidate generation is not final plan adoption or proof of a performance improvement. Unrecorded rejection reasons are unknown.",
                "Schema is supplied in memory. Statistics use the current Lite estimates; user queries are never executed.",
                "Native results are read-only. Manual experiments use Plan Lab and fresh-process candidate replay.",
                "Native source commit: " + commit + "; binary build: " + version));
    if (data.path("truncated").asBoolean())
      limitations.add("Native event collection was truncated; optimization continued normally.");
    Run run =
        new Run(
            2,
            "trafodion",
            version,
            Instant.now().toString(),
            request,
            initial,
            after,
            stages,
            limitations,
            new EditableResult(
                null,
                List.of(
                    "Native operators cannot yet be exported losslessly to an editable plan.")));
    Records.validate(run);
    return run;
  }
}
