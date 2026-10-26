package dev.optilens.starrocks;

import dev.optilens.*;
import dev.optilens.Model.*;
import java.nio.file.*;
import java.util.*;

/** Behavioral checks against real FE objects, separate from catalog coverage. */
public final class NativeChecks {
  static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    NativeVersion.verify();
    var registry = new NativeRegistry();
    var optimizer = new NativeOptimizer(registry);
    var fixtures = List.of(Json.MAPPER.readValue(Path.of(args[0]).toFile(), Example[].class));
    var original = fixtures.get(0).input();
    var untouched = optimizer.optimize(original);
    require(
        untouched.initial().raw().equals(untouched.finalPlan().raw()),
        "A rule-free plan was rewritten");
    require(
        untouched.initial().nodes().stream()
                .filter(n -> n.type().equals("LogicalFilterOperator"))
                .count()
            == 2,
        "Adjacent filters were lost at construction");
    var filter =
        fixtures.stream()
            .filter(e -> e.title().equals("Remove a true filter"))
            .findFirst()
            .orElseThrow();
    require(
        optimizer.optimize(filter.input()).initial().nodes().stream()
            .anyMatch(n -> n.type().equals("LogicalFilterOperator")),
        "A true filter was simplified during construction");
    var single =
        fixtures.stream()
            .filter(e -> e.title().equals("Single-input union"))
            .findFirst()
            .orElseThrow();
    require(
        optimizer
            .optimize(single.input())
            .initial()
            .nodes()
            .get(0)
            .type()
            .equals("LogicalUnionOperator"),
        "Single-input union was removed during construction");
    var doc = Json.MAPPER.valueToTree(original.plan());
    var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) doc.get("nodes");
    var right = Json.MAPPER.valueToTree(original.plan()).get("nodes");
    for (var node : right) {
      var copy = node.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) copy)
          .put("id", "right-" + copy.get("id").asText());
      var inputs = (com.fasterxml.jackson.databind.node.ArrayNode) copy.get("inputs");
      for (int i = 0; i < inputs.size(); i++)
        inputs.set(i, Json.MAPPER.getNodeFactory().textNode("right-" + inputs.get(i).asText()));
      nodes.add(copy);
    }
    nodes.add(
        Json.MAPPER.readTree(
            "{\"id\":\"root\",\"op\":\"Union\",\"inputs\":[\""
                + original.plan().rootId()
                + "\",\"right-"
                + original.plan().rootId()
                + "\"],\"attributes\":{\"all\":true}}"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("rootId", "root");
    var plan = Json.MAPPER.treeToValue(doc, PlanDocument.class);
    var scoped =
        new Request(
            null,
            original.schema(),
            List.of("StarRocks.MergeTwoFiltersRule"),
            "starrocks",
            new Options(false, 10),
            "plan",
            plan,
            original.plan().rootId());
    var run = optimizer.optimize(scoped);
    Records.validate(run);
    require(
        run.finalPlan().nodes().stream()
                .filter(n -> n.type().equals("LogicalFilterOperator"))
                .count()
            == 3,
        "Subtree optimization changed the other branch");
    require(
        run.stages().get(0).events().size() == 1,
        "Expected exactly one filter merge in the selected branch");
    var bad = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.valueToTree(original);
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            bad.get("plan")
                .get("nodes")
                .get(1)
                .get("attributes")
                .get("condition")
                .get("args")
                .get(0))
        .put("index", 999);
    var invalid = optimizer.validate(Json.MAPPER.treeToValue(bad, Request.class));
    require(
        !invalid.valid() && invalid.errors().get(0).nodeId() != null,
        "Missing located field error");
    var reorderPlan =
        Json.MAPPER.readValue(
            """
        {"planVersion":1,"rootId":"project","nodes":[
          {"id":"scan","op":"TableScan","inputs":[],"attributes":{"table":"customers"}},
          {"id":"project","op":"Project","inputs":["scan"],"attributes":{"projects":[
            {"name":"city","expr":{"kind":"ref","input":0,"index":2}},
            {"name":"id","expr":{"kind":"ref","input":0,"index":0}}]}}],"layout":{}}
        """,
            PlanDocument.class);
    var reordered =
        optimizer.validate(
            new Request(
                null, original.schema(), List.of(), "starrocks", null, "plan", reorderPlan, null));
    require(
        reordered.valid() && reordered.fields().get("project").get(0).contains("city"),
        "Validation changed manual projection field order");
    int count = 0;
    for (var example : StarRocks.examples()) {
      var evidence = optimizer.optimize(example.input());
      Records.validate(evidence);
      require(
          evidence.stages().stream()
              .filter(s -> s.id().equals(example.id()))
              .flatMap(s -> s.events().stream())
              .anyMatch(e -> e.rule().equals(example.id())),
          "Published example did not transform its target: " + example.id());
      count++;
    }
    System.out.println(
        "Native checks passed: construction fidelity, subtree isolation, located errors, record validation, and "
            + count
            + " published examples.");
    System.exit(0);
  }
}
