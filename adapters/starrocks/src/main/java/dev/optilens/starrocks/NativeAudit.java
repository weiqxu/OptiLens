package dev.optilens.starrocks;

import com.starrocks.sql.optimizer.rule.Rule;
import dev.optilens.*;
import dev.optilens.Model.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Audits every pinned implementation, retaining negative results instead of counting them as
 * coverage.
 */
public final class NativeAudit {
  public static void main(String[] args) throws Exception {
    var runtime = NativeVersion.verify();
    var fixtures = List.of(Json.MAPPER.readValue(Path.of(args[0]).toFile(), Example[].class));
    var registry = new NativeRegistry();
    var valid = new ArrayList<Example>();
    var fixtureIssues = new LinkedHashMap<String, Object>();
    for (var fixture : fixtures) {
      var validation = new NativeOptimizer(registry).validate(fixture.input());
      if (validation.valid()) {
        try {
          if (!fixture.input().rules().isEmpty())
            new NativeOptimizer(registry).optimize(fixture.input());
          valid.add(fixture);
        } catch (Throwable e) {
          fixtureIssues.put(
              fixture.id(), List.of(new PlanIssue(null, "preparation", NativeRegistry.message(e))));
        }
      } else fixtureIssues.put(fixture.id(), validation.errors());
    }
    var entries = new ArrayList<Map<String, Object>>();
    var report = new LinkedHashMap<String, Object>();
    report.put("startedAt", Instant.now().toString());
    report.put(
        "provenance",
        Map.of(
            "sourceCommit",
            StarRocks.COMMIT,
            "runtimeLayer",
            "sha256:c7b7fd890580258a9fafa6ef1c971279ee55bbf8ab1f0d83b733651b1c2cf423",
            "runtimeJarSha256",
            sha(
                Files.readAllBytes(
                    Path.of(
                        Class.forName("com.starrocks.common.Version")
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()))),
            "fixtureSha256",
            sha(Files.readAllBytes(Path.of(args[0]))),
            "inventorySha256",
            sha(
                NativeAudit.class
                    .getResourceAsStream("/starrocks-inventory.json")
                    .readAllBytes())));
    report.put("runtime", runtime);
    report.put("fixtureCount", fixtures.size());
    report.put("entries", entries);
    report.put("validFixtureCount", valid.size());
    report.put("fixtureIssues", fixtureIssues);
    for (var entry : registry.entries.values()) {
      if (args.length > 2 && !entry.id().contains(args[2])) continue;
      var item = new LinkedHashMap<String, Object>();
      item.put("id", entry.id());
      item.put("className", entry.className());
      item.put("available", !entry.instances().isEmpty());
      item.put("variants", entry.instances().size());
      item.put(
          "nativeTypes",
          entry.instances().stream()
              .filter(x -> x instanceof Rule)
              .map(x -> ((Rule) x).type().name())
              .distinct()
              .toList());
      var errors = new LinkedHashSet<String>();
      int tried = 0, matched = 0;
      String status = "no-match",
          reason =
              "No fixture matched this rule's preconditions. A dedicated plan or environment is required.";
      if (entry.instances().isEmpty()) {
        status = "requires-configuration";
        reason = entry.error();
      } else
        for (var fixture : valid) {
          tried++;
          var input = fixture.input();
          var program = new ArrayList<>(input.rules());
          if (program.contains(entry.id())) continue;
          program.add(entry.id());
          var request =
              new Request(
                  input.sql(),
                  input.schema(),
                  program,
                  "starrocks",
                  new Options(false, 2),
                  input.inputMode(),
                  input.plan(),
                  null);
          try {
            var run = new NativeOptimizer(registry).optimize(request);
            var stage = run.stages().get(run.stages().size() - 1);
            if (!stage.attempts().isEmpty()) matched++;
            if (stage.events().stream().anyMatch(event -> event.rule().equals(entry.id()))) {
              status = "verified";
              reason =
                  "Executed the native target rule and recorded "
                      + stage.events().size()
                      + " observable transformation(s) using fixture "
                      + fixture.id()
                      + ". This proves a structural rewrite, not query-result equivalence or performance.";
              item.put(
                  "example",
                  new Example(
                      entry.id(),
                      entry.id().substring(10) + " · " + fixture.title(),
                      reason,
                      request));
              item.put(
                  "evidence",
                  Map.of(
                      "fixture",
                      fixture.id(),
                      "events",
                      stage.events().size(),
                      "before",
                      stage.before().raw(),
                      "after",
                      stage.after().raw()));
              break;
            }
          } catch (Throwable e) {
            if (errors.size() < 8) errors.add(fixture.id() + ": " + NativeRegistry.message(e));
          }
        }
      if (!status.equals("verified") && tried > 0) {
        if (!errors.isEmpty()) {
          status = "requires-environment-or-fixture";
          reason =
              "No successful target transformation was observed. Native checks or execution failed for some fixtures; inspect errorSamples for the required setup.";
        } else if (matched > 0) {
          status = "no-observable-change";
          reason =
              "Native invocations completed without an observable transformation on the fixture corpus.";
        }
      }
      if (entry.instances().size() > 1) {
        var variants = new ArrayList<Map<String, Object>>();
        int index = 0;
        try {
          for (Object instance : entry.instances()) {
            registry.entries.put(
                entry.id(),
                new NativeRegistry.Entry(
                    entry.id(), entry.className(), entry.family(), List.of(instance), ""));
            variants.add(auditVariant(registry, entry, instance, index++, valid));
          }
        } finally {
          registry.entries.put(entry.id(), entry);
        }
        item.put("variantChecks", variants);
      }
      item.put("status", status);
      item.put("reason", reason);
      item.put("attemptedFixtures", tried);
      item.put("matchingFixtures", matched);
      item.put("errorSamples", errors);
      entries.add(item);
      report.put("updatedAt", Instant.now().toString());
      Files.write(
          Path.of(args[1]), Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
      System.out.println(entries.size() + " " + entry.id() + " " + status);
      System.out.flush();
    }
    report.put("completedAt", Instant.now().toString());
    Files.write(
        Path.of(args[1]), Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    System.exit(0);
  }

  static String sha(byte[] data) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
  }

  static Map<String, Object> auditVariant(
      NativeRegistry registry,
      NativeRegistry.Entry entry,
      Object instance,
      int index,
      List<Example> fixtures) {
    var result = new LinkedHashMap<String, Object>();
    result.put("index", index);
    result.put(
        "nativeType",
        instance instanceof Rule r ? r.type().name() : entry.className() + "#" + index);
    int tried = 0, matched = 0;
    var errors = new LinkedHashSet<String>();
    String status = "no-match";
    for (var fixture : fixtures) {
      var input = fixture.input();
      if (input.rules().contains(entry.id())) continue;
      tried++;
      var program = new ArrayList<>(input.rules());
      program.add(entry.id());
      var request =
          new Request(
              input.sql(),
              input.schema(),
              program,
              "starrocks",
              new Options(false, 2),
              input.inputMode(),
              input.plan(),
              null);
      try {
        var run = new NativeOptimizer(registry).optimize(request);
        var stage = run.stages().get(run.stages().size() - 1);
        if (!stage.attempts().isEmpty()) matched++;
        if (stage.events().stream().anyMatch(event -> event.rule().equals(entry.id()))) {
          status = "verified";
          result.put("fixture", fixture.id());
          break;
        }
      } catch (Throwable e) {
        if (errors.size() < 3) errors.add(NativeRegistry.message(e));
      }
    }
    if (!status.equals("verified"))
      status =
          !errors.isEmpty()
              ? "requires-environment-or-fixture"
              : matched > 0 ? "no-observable-change" : "no-match";
    result.put("status", status);
    result.put("attemptedFixtures", tried);
    result.put("matchingFixtures", matched);
    result.put("errorSamples", errors);
    return result;
  }
}
