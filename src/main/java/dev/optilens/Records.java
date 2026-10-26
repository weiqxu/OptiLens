package dev.optilens;

import static dev.optilens.Model.*;

import java.util.*;

/** Validate imported data structurally; importing does not verify its claimed provenance. */
public final class Records {
  public static void validate(Run run) {
    Input.require(
        run != null && (run.formatVersion() == 1 || run.formatVersion() == 2),
        "Unsupported record format. Import an OptiLens format-version 1 or 2 run record.");
    text(run.engine(), 120);
    text(run.engineVersion(), 80);
    text(run.createdAt(), 80);
    Input.require(
        run.formatVersion() != 1 || run.input() != null && "sql".equals(run.input().inputMode()),
        "Version 1 records must use SQL input");
    Input.validate(run.input());
    snapshot(run.initial());
    snapshot(run.finalPlan());
    Input.require(
        run.stages() != null && run.stages().size() <= Rules.MAX_SELECTED, "Invalid stage count");
    Input.require(
        run.stages().size() == run.input().rules().size(), "Stages do not match the rule program");
    int count = 0;
    for (int i = 0; i < run.stages().size(); i++) {
      var s = run.stages().get(i);
      Input.require(s != null, "Stages must not be null");
      Input.require(
          run.input().rules().get(i).equals(s.id()), "Stage order does not match the rule program");
      text(s.title(), 120);
      text(s.description(), 4000);
      text(s.conditions(), 4000);
      text(s.source(), 1000);
      snapshot(s.before());
      snapshot(s.after());
      if (run.formatVersion() == 2) {
        Input.require(
            s.scopeBeforeRootId() != null
                && s.before().nodes().stream().anyMatch(n -> n.id().equals(s.scopeBeforeRootId())),
            "Invalid scope root before stage");
        Input.require(
            s.scopeAfterRootId() != null
                && s.after().nodes().stream().anyMatch(n -> n.id().equals(s.scopeAfterRootId())),
            "Invalid scope root after stage");
      }
      Input.require(
          s.attempts() != null
              && s.attempts().size() <= 500
              && s.events() != null
              && s.events().size() <= 80,
          "Invalid event count");
      count += s.attempts().size();
      Set<String> calls = new HashSet<>();
      for (var a : s.attempts()) {
        Input.require(
            a != null && a.matchedIds() != null && a.matchedIds().size() <= 250,
            "Invalid attempt record");
        text(a.rule(), 500);
        a.matchedIds().forEach(id -> text(id, 100));
        calls.add(a.callId() + ":" + a.rule());
      }
      for (var e : s.events()) {
        Input.require(e != null, "Events must not be null");
        text(e.rule(), 500);
        Input.require(
            calls.contains(e.callId() + ":" + e.rule()),
            "Transformation event has no matching attempt");
        snapshot(e.before());
        snapshot(e.after());
      }
    }
    Input.require(count <= 500, "Too many recorded attempts");
    Input.require(
        run.limitations() != null && run.limitations().size() <= 20,
        "Missing or excessive capability descriptions");
    run.limitations().forEach(s -> text(s, 4000));
    if (run.editableResult() != null) {
      var editable = run.editableResult();
      Input.require(
          editable.reasons() != null && editable.reasons().size() <= 250,
          "Invalid editable result reasons");
      editable.reasons().forEach(reason -> text(reason, 4000));
      if (editable.plan() != null) {
        Plans.validateStructure(editable.plan());
        Input.require(
            editable.reasons().isEmpty(), "Editable plan must not also have unsupported reasons");
      }
    }
  }

  private static void text(String s, int max) {
    Input.require(s != null && s.length() <= max, "Record field is missing or too long");
  }

  private static void snapshot(Snapshot p) {
    Input.require(
        p != null && ("full-plan".equals(p.scope()) || "local-expression".equals(p.scope())),
        "Invalid snapshot scope");
    text(p.rootId(), 100);
    text(p.raw(), 500_000);
    Input.require(
        p.nodes() != null && !p.nodes().isEmpty() && p.nodes().size() <= 250,
        "Invalid snapshot node count");
    Map<String, PlanNode> nodes = new HashMap<>();
    for (var n : p.nodes()) {
      Input.require(n != null, "Nodes must not be null");
      text(n.id(), 100);
      text(n.type(), 200);
      Input.require(nodes.put(n.id(), n) == null, "Duplicate node identifier");
      Input.require(
          n.attributes() != null
              && n.attributes().size() <= 100
              && n.fields() != null
              && n.fields().size() <= 1024
              && n.inputs() != null
              && n.inputs().size() <= 250,
          "Invalid node attributes");
      n.attributes()
          .forEach(
              (k, v) -> {
                text(k, 200);
                text(v, 50_000);
              });
      n.fields().forEach(f -> text(f, 1000));
      n.inputs().forEach(id -> text(id, 100));
    }
    var visited = new HashMap<String, Integer>();
    walk(p.rootId(), nodes, new HashSet<>(), visited, 0);
    Input.require(
        visited.size() == nodes.size(), "Snapshot contains nodes unreachable from the root");
  }

  private static int walk(
      String id,
      Map<String, PlanNode> nodes,
      Set<String> path,
      Map<String, Integer> visited,
      int depth) {
    Input.require(
        depth <= 40 && nodes.containsKey(id),
        "Plan exceeds the depth limit or references a missing node");
    if (visited.containsKey(id)) {
      Input.require(depth + visited.get(id) <= 40, "Plan exceeds the depth limit");
      return visited.get(id);
    }
    Input.require(path.add(id), "Plan contains a cycle");
    int height = 0;
    for (String child : nodes.get(id).inputs())
      height = Math.max(height, 1 + walk(child, nodes, path, visited, depth + 1));
    path.remove(id);
    visited.put(id, height);
    return height;
  }

  private Records() {}
}
