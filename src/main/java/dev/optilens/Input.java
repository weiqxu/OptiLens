package dev.optilens;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static dev.optilens.Model.*;

public final class Input {
  public static final Set<String> TYPES = Set.of("INTEGER", "BIGINT", "DOUBLE", "DECIMAL", "VARCHAR", "BOOLEAN", "DATE", "TIMESTAMP");
  public static void validate(Request r) {
    require(r != null, "Missing request");
    require("calcite".equals(r.database()), "Only Calcite is currently supported");
    require(Set.of("sql","plan").contains(r.inputMode()), "Input mode must be sql or plan");
    if (r.inputMode().equals("plan")) {
      require(r.sql()==null || r.sql().isBlank(), "Plan input must not also contain SQL");
      Plans.validateStructure(r.plan());
      require(r.scopeNodeId()==null || r.plan().nodes().stream().anyMatch(n->n.id().equals(r.scopeNodeId())), "Unknown scope node");
    } else {
      require(r.plan()==null && r.scopeNodeId()==null, "SQL input must not contain a plan or node scope");
      require(r.sql() != null && !r.sql().isBlank() && r.sql().length() <= 10_000, "SQL must contain 1-10000 characters");
    }
    require(r.schema() != null && !r.schema().isEmpty() && r.schema().size() <= 16, "Provide 1-16 tables");
    var tables = new HashSet<String>();
    for (var t : r.schema()) {
      require(t != null, "Table definitions must not be null"); identifier(t.name());
      require(tables.add(t.name().toUpperCase(Locale.ROOT)), "Duplicate table name: " + t.name());
      require(t.columns() != null && !t.columns().isEmpty() && t.columns().size() <= 32, "Each table must have 1-32 columns");
      var columns = new HashSet<String>();
      for (var c : t.columns()) {
        require(c != null, "Column definitions must not be null"); identifier(c.name());
        require(columns.add(c.name().toUpperCase(Locale.ROOT)), "Duplicate column name: " + c.name());
        require(c.type() != null && TYPES.contains(c.type()), "Unsupported column type: " + c.type());
      }
    }
    require(r.rules() != null && r.rules().size() <= Rules.MAX_SELECTED, "Select at most " + Rules.MAX_SELECTED + " rules or rule groups per run");
    if (r.options() != null && r.options().matchLimit() != null)
      require(r.options().matchLimit() >= 1 && r.options().matchLimit() <= 100, "Match limit must be between 1 and 100");
    var seen = new HashSet<String>();
    for (String id : r.rules()) { require(id != null && seen.add(id), "Rule IDs must not be null or duplicated"); Rules.get(id); }
  }
  private static void identifier(String s) {
    require(s != null && s.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"), "Names must be ASCII identifiers of at most 64 letters, digits, or underscores, starting with a letter or underscore");
  }
  public static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }

  public static List<Example> examples() {
    var schema = List.of(new Table("customers", List.of(new Column("id","INTEGER",false), new Column("name","VARCHAR",true), new Column("city","VARCHAR",true))),
        new Table("orders", List.of(new Column("id","INTEGER",false),new Column("customer_id","INTEGER",true),new Column("amount","DECIMAL",true),new Column("created_at","TIMESTAMP",true))));
    var rules = Rules.ALL.stream().map(d -> d.card().id()).toList();
    return List.of(
        example("join", "Customer orders · predicates and column pruning", "Inspect filter placement and projections on join inputs. Actual reads and performance are not measured.", "SELECT c.name FROM customers c JOIN orders o ON c.id = o.customer_id WHERE o.amount > 50 + 50", schema, rules),
        example("constant", "Constant projection", "Constants may be reduced during SQL conversion or rule stages. Refer to the recorded events.", "SELECT name, 10 * 20 AS threshold FROM customers",schema,rules),
        example("outer", "Outer join · NULL-rejecting filter", "A filter on the right table may turn an outer join into an inner join. Inspect the actual join type.", "SELECT c.name, o.amount FROM customers c LEFT JOIN orders o ON c.id = o.customer_id WHERE o.amount > 100",schema,rules),
        example("null", "Outer join · preserving NULL rows", "IS NULL and amount comparisons have different semantics. Do not assume the same pushdown behavior.", "SELECT c.name FROM customers c LEFT JOIN orders o ON c.id = o.customer_id WHERE o.amount IS NULL",schema,rules),
        example("projection", "Subquery · filter through projection", "Inspect how aliased expressions are rewritten after pushdown.", "SELECT name FROM (SELECT name, id + 1 AS next_id FROM customers) t WHERE next_id > 10",schema,rules),
        example("identity", "Query without filters", "Full stage snapshots and attempts are retained even when no transformation is observed.", "SELECT * FROM customers",schema,rules));
  }
  private static Example example(String id,String title,String note,String sql,List<Table> schema,List<String> rules) {
    return new Example(id,title,note,new Request(sql,schema,rules));
  }
  private Input() {}
}
