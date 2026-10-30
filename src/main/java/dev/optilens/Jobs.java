package dev.optilens;

import static dev.optilens.Model.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class Jobs implements AutoCloseable {
  public record Job(
      String id, String status, boolean imported, String submittedAt, String error, Run result) {}

  private final Path directory;
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
  private final int timeoutSeconds;

  public Jobs(Path directory) throws Exception {
    this(directory, 25);
  }

  Jobs(Path directory, int timeoutSeconds) throws Exception {
    this.directory = directory.toAbsolutePath();
    this.timeoutSeconds = timeoutSeconds;
    Files.createDirectories(directory);
    try (var files = Files.list(directory)) {
      for (var p :
          files
              .filter(f -> f.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
              .sorted()
              .limit(200)
              .toList()) {
        try {
          if (Files.size(p) > 8_500_000) continue;
          var job = Json.MAPPER.readValue(p.toFile(), Job.class);
          if (!p.getFileName().toString().equals(job.id() + ".json")) continue;
          if ("succeeded".equals(job.status())) Records.validate(job.result());
          if (!Set.of("succeeded", "failed").contains(job.status()))
            job =
                new Job(
                    job.id(),
                    "failed",
                    job.imported(),
                    job.submittedAt(),
                    "The service restarted and interrupted the previous run",
                    null);
          save(job);
        } catch (Exception e) {
          System.err.println("Skipped unreadable run: " + p.getFileName());
        }
      }
    }
  }

  public synchronized Job submit(Request request) throws Exception {
    return submit(request, Json.MAPPER.valueToTree(request));
  }

  public synchronized Job step(String runId, String candidateId, Request input) throws Exception {
    Job previous = jobs.get(runId);
    Input.require(
        previous != null && !previous.imported() && "succeeded".equals(previous.status()),
        "Continue from a locally executed, successful experiment");
    Run run = previous.result();
    Input.require(
        run.formatVersion() == 3
            && run.nativeTrace() != null
            && !run.nativeTrace().path("complete").asBoolean(),
        "This run has no pending native experiment step");
    Input.require(
        executionInput(run.input()).equals(executionInput(input)),
        "The current draft changed. Start a new experiment before choosing a candidate.");
    var runtime = Trafodion.runtime();
    Input.require(
        Boolean.TRUE.equals(runtime.get("available"))
            && run.engineVersion().equals(runtime.get("buildId")),
        "Native engine build changed. Start a new experiment.");
    var replay =
        (com.fasterxml.jackson.databind.node.ArrayNode) run.nativeTrace().path("replay").deepCopy();
    var choice = Json.MAPPER.createObjectNode();
    if ("unchanged".equals(candidateId)) {
      Input.require(
          run.nativeTrace().path("candidates").isEmpty(),
          "Select a generated candidate before continuing");
      choice
          .put("ordinal", -1)
          .put("fingerprint", run.nativeTrace().path("currentFingerprint").asText());
    } else {
      com.fasterxml.jackson.databind.JsonNode selected = null;
      for (var candidate : run.nativeTrace().path("candidates"))
        if (candidate.path("id").asText().equals(candidateId)) selected = candidate;
      Input.require(
          selected != null && selected.path("compatible").asBoolean(),
          "Unknown or incompatible native candidate");
      choice
          .put("ordinal", selected.path("ordinal").asInt())
          .put("fingerprint", selected.path("fingerprint").asText());
    }
    choice.put("generationFingerprint", run.nativeTrace().path("generationFingerprint").asText());
    replay.add(choice);
    var envelope = Json.MAPPER.createObjectNode();
    envelope.set("request", Json.MAPPER.valueToTree(run.input()));
    envelope.set("replay", replay);
    return submit(run.input(), envelope);
  }

  private Job submit(Request request, com.fasterxml.jackson.databind.JsonNode workerInput)
      throws Exception {
    Input.validate(request);
    capacity();
    String id = UUID.randomUUID().toString();
    Job job = new Job(id, "queued", false, Instant.now().toString(), null, null);
    Path input = directory.resolve(id + ".input.json");
    Json.MAPPER.writeValue(input.toFile(), workerInput);
    save(job);
    try {
      executor.execute(() -> execute(job, input, request));
    } catch (RejectedExecutionException e) {
      jobs.remove(id);
      Files.deleteIfExists(input);
      Files.deleteIfExists(directory.resolve(id + ".json"));
      throw e;
    }
    return job;
  }

  public synchronized Job imported(Run run) throws Exception {
    Records.validate(run);
    capacity();
    Job job =
        new Job(
            UUID.randomUUID().toString(), "succeeded", true, Instant.now().toString(), null, run);
    save(job);
    return job;
  }

  public PlanValidation validatePlan(Request request) throws Exception {
    try {
      Input.require(
          request != null && "plan".equals(request.inputMode()),
          "Plan validation requires plan mode");
      Input.validate(request);
    } catch (Plans.Invalid e) {
      return new PlanValidation(false, null, Map.of(), List.of(e.issue));
    } catch (IllegalArgumentException e) {
      return new PlanValidation(
          false, null, Map.of(), List.of(new PlanIssue(null, "plan", Worker.error(e))));
    }
    // Shares the bounded worker queue with optimization, but creates no history record.
    var future =
        executor.submit(
            () -> {
              Path input = Files.createTempFile(directory, "validation-", ".input.json"),
                  output = input.resolveSibling(input.getFileName() + ".output.json");
              Process process = null;
              try {
                Json.MAPPER.writeValue(input.toFile(), request);
                process =
                    new ProcessBuilder(Engines.workerCommand(request, input, output, true))
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
                  throw new TimeoutException("Plan validation exceeded the worker time limit");
                if (process.exitValue() != 0
                    || !Files.exists(output)
                    || Files.size(output) > 8_000_000)
                  throw new IllegalArgumentException(
                      "Plan validation worker failed or exceeded the output limit");
                var outcome = Json.MAPPER.readValue(output.toFile(), Worker.Outcome.class);
                if (outcome.error() != null) throw new IllegalArgumentException(outcome.error());
                return outcome.validation();
              } finally {
                if (process != null && process.isAlive()) {
                  terminate(process);
                  process.waitFor(5, TimeUnit.SECONDS);
                }
                Files.deleteIfExists(input);
                Files.deleteIfExists(output);
              }
            });
    try {
      return future.get();
    } catch (ExecutionException e) {
      throw new IllegalArgumentException(Worker.error(e.getCause()), e.getCause());
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw e;
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode executionInput(Request input) {
    var tree = Json.MAPPER.valueToTree(input);
    if (tree.path("plan").isObject())
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("plan")).remove("layout");
    return tree;
  }

  private static void terminate(Process process) {
    var descendants = process.descendants().toList();
    for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroyForcibly();
    process.destroyForcibly();
  }

  private void capacity() {
    Input.require(
        jobs.size() < 200,
        "The limit of 200 local records has been reached. Stop the service, then back up and clean data/runs.");
  }

  public Job get(String id) {
    return jobs.get(id);
  }

  public List<Map<String, Object>> list() {
    return jobs.values().stream()
        .sorted(Comparator.comparing(Job::submittedAt).reversed())
        .limit(30)
        .map(
            j ->
                Map.<String, Object>of(
                    "id",
                    j.id(),
                    "status",
                    j.status(),
                    "imported",
                    j.imported(),
                    "submittedAt",
                    j.submittedAt()))
        .toList();
  }

  private void execute(Job original, Path input, Request request) {
    Process process = null;
    Path output = directory.resolve(original.id() + ".worker.json");
    try {
      save(new Job(original.id(), "running", false, original.submittedAt(), null, null));
      process =
          new ProcessBuilder(Engines.workerCommand(request, input, output, false))
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
        throw new TimeoutException(
            "Optimization exceeded " + timeoutSeconds + " seconds; the worker has been terminated");
      if (process.exitValue() != 0 || !Files.exists(output))
        throw new IllegalStateException(
            "The optimization worker exited unexpectedly; memory or query complexity limits may have been exceeded");
      if (Files.size(output) > 8_000_000)
        throw new IllegalStateException("Run record exceeds the size limit");
      var outcome = Json.MAPPER.readValue(output.toFile(), Worker.Outcome.class);
      if (outcome.error() != null) throw new IllegalArgumentException(outcome.error());
      Records.validate(outcome.result());
      save(
          new Job(
              original.id(), "succeeded", false, original.submittedAt(), null, outcome.result()));
    } catch (Exception e) {
      try {
        save(
            new Job(original.id(), "failed", false, original.submittedAt(), Worker.error(e), null));
      } catch (Exception storageError) {
        jobs.put(
            original.id(),
            new Job(
                original.id(),
                "failed",
                false,
                original.submittedAt(),
                "Failed to save the run record",
                null));
      }
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    } finally {
      if (process != null && process.isAlive()) {
        terminate(process);
        try {
          process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
      }
      try {
        Files.deleteIfExists(input);
        Files.deleteIfExists(output);
      } catch (Exception ignored) {
      }
    }
  }

  private void save(Job job) throws Exception {
    Path target = directory.resolve(job.id() + ".json"),
        temp = directory.resolve(job.id() + ".tmp");
    Json.MAPPER.writeValue(temp.toFile(), job);
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    jobs.put(job.id(), job);
  }

  @Override
  public void close() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(8, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
