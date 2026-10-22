package dev.optilens;

import java.util.List;
import java.util.Map;

public final class Model {
  private Model() {}
  public record Column(String name, String type, Boolean nullable) {}
  public record Table(String name, List<Column> columns) {}
  public record Options(Boolean expandSubqueries, Integer matchLimit) {}
  public record Request(String sql, List<Table> schema, List<String> rules, String database, Options options) {
    public Request { if (database == null) database = "calcite"; }
    public Request(String sql, List<Table> schema, List<String> rules, String database) { this(sql, schema, rules, database, null); }
    public Request(String sql, List<Table> schema, List<String> rules) { this(sql, schema, rules, "calcite"); }
  }
  public record PlanNode(String id, String type, Map<String, String> attributes,
                         List<String> fields, List<String> inputs) {}
  public record Snapshot(String scope, String rootId, List<PlanNode> nodes, String raw) {}
  public record Attempt(int callId, String rule, List<String> matchedIds) {}
  public record Event(int sequence, int callId, String rule, Snapshot before, Snapshot after) {}
  public record Stage(String id, String title, String description, String conditions, String source,
                      Snapshot before, Snapshot after, List<Attempt> attempts, List<Event> events) {}
  public record Run(int formatVersion, String engine, String engineVersion, String createdAt,
                    Request input, Snapshot initial, Snapshot finalPlan, List<Stage> stages,
                    List<String> limitations) {}
  public record RuleCard(String id, String title, String description, String conditions,
                         List<String> implementations, String source, String category, boolean runnable, String unavailableReason) {}
  public record Example(String id, String title, String note, Request input) {}
}
