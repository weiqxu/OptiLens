package dev.optilens;

import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static dev.optilens.Model.*;

/** Verified SQL programs, with explicit preparation stages when a rule needs an intermediate plan. */
public final class RuleExamples {
  private static final List<Table> SCHEMA = Input.examples().get(0).input().schema();
  public static List<Example> all() { return LIBRARY; }
  public record Coverage(String id, String status, String reason) {}
  public static List<Coverage> coverage() {
    var verified = all().stream().map(Example::id).collect(java.util.stream.Collectors.toSet());
    return Rules.cards().stream().filter(c -> !c.category().equals("Common rule groups")).map(c -> {
      if (verified.contains(c.id())) return new Coverage(c.id(), "verified", "Verified by executing the complete program and observing a transformation from the named target rule.");
      if (!c.runnable()) return new Coverage(c.id(), "requires-environment", c.unavailableReason());
      String reason = switch (c.id()) {
        case "CoreRules.AGGREGATE_JOIN_TRANSPOSE" -> "In Calcite 1.39.0 this default variant treats both join inputs as unique and returns without a rewrite. Use the verified AGGREGATE_JOIN_TRANSPOSE_EXTENDED example for aggregate pushdown.";
        case "CoreRules.MATCH" -> "This rule copies a Match operator. A copy alone does not demonstrate an observable optimization; no verified transformation is claimed.";
        case "CoreRules.COERCE_INPUTS" -> "Requires set-operation inputs with mismatched row types. SQL validation already inserts the necessary coercions; a direct relational-plan fixture is needed.";
        case "CoreRules.EXCHANGE_REMOVE_CONSTANT_KEYS", "CoreRules.SORT_EXCHANGE_REMOVE_CONSTANT_KEYS" -> "Requires an Exchange or SortExchange with constant distribution keys. SQL conversion in this environment does not construct distributed exchange operators.";
        case "CoreRules.FILTER_EXPAND_IS_NOT_DISTINCT_FROM" -> "SQL conversion expands IS NOT DISTINCT FROM before the rule stage. A direct relational-plan fixture is needed to preserve the original expression.";
        case "CoreRules.FILTER_TABLE_FUNCTION_TRANSPOSE" -> "Requires a registered table function with column-mapping metadata; the current schema contains only ordinary tables.";
        case "CoreRules.JOIN_PUSH_EXPRESSIONS" -> "SQL conversion already pushes the tested join-key expressions. A direct relational-plan fixture is needed to preserve the pre-rewrite join condition.";
        case "CoreRules.JOIN_TO_SEMI_JOIN" -> "Requires a join whose right input is an aggregate with no output fields. A direct relational-plan fixture is needed to preserve this shape.";
        case "CoreRules.MULTI_JOIN_BOTH_PROJECT", "CoreRules.MULTI_JOIN_LEFT_PROJECT", "CoreRules.MULTI_JOIN_RIGHT_PROJECT" -> "Requires a partially flattened join tree with Project over MultiJoin under a remaining binary join. A targeted relational-plan fixture is needed; full-tree flattening removes that match.";
        case "CoreRules.SORT_REMOVE" -> "Requires a planner that tracks and enforces collation traits. The current HepPlanner does not enforce requested sort traits, so removing a sort would not be a verified equivalent plan.";
        case "CoreRules.UNION_REMOVE" -> "Requires a Union with exactly one input. SQL UNION produces at least two inputs; a direct relational-plan fixture is needed.";
        case "PruneEmptyRules.EMPTY_TABLE_INSTANCE" -> "Requires table metadata proving a maximum row count of zero. Schema-only tables do not provide row-count statistics.";
        default -> "No reproducible target-rule transformation has been verified in this environment.";
      };
      String status = c.id().equals("CoreRules.AGGREGATE_JOIN_TRANSPOSE") || c.id().equals("CoreRules.MATCH") ? "no-observable-rewrite" : "requires-plan-setup";
      return new Coverage(c.id(), status, reason);
    }).toList();
  }
  private record Entry(String id, String title, String note, String sql, List<String> preparation, Options options) {}
  private static List<Example> load() {
    var result = new ArrayList<>(EXAMPLES);
    try (var stream = RuleExamples.class.getResourceAsStream("/rule-examples.json")) {
      if (stream == null) throw new IllegalStateException("Missing rule example library");
      for (var entry : new ObjectMapper().readValue(stream, Entry[].class)) {
        var program = new ArrayList<>(entry.preparation());
        program.add(entry.id());
        result.add(new Example(entry.id(), entry.title(), entry.note(),
            new Request(entry.sql(), SCHEMA, List.copyOf(program), "calcite", entry.options())));
      }
    } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    result.addAll(PlanExamples.all());
    return List.copyOf(result);
  }
  private static Example example(String rule, String title, String note, String sql) {
    return new Example(rule, title, note, new Request(sql, SCHEMA, List.of(rule), "calcite"));
  }
  private static final String JOIN = "SELECT c.name FROM customers c JOIN orders o ON c.id = o.customer_id WHERE o.amount > 100";
  private static final List<Example> EXAMPLES = List.of(
    example("CoreRules.PROJECT_REDUCE_EXPRESSIONS", "Fold a constant projection", "The multiplication in the output becomes a constant value. Compare the Project expression before and after the rule.", "SELECT name, 10 * 20 AS threshold FROM customers"),
    example("CoreRules.FILTER_REDUCE_EXPRESSIONS", "Fold a constant in a predicate", "The amount threshold is evaluated inside the filter expression.", "SELECT name FROM customers WHERE id > 10 * 20"),
    example("CoreRules.FILTER_INTO_JOIN", "Push an order filter into a join input", "The amount predicate references only orders, allowing it to move below the inner join.", JOIN),
    example("CoreRules.FILTER_PROJECT_TRANSPOSE", "Push a filter through a projection", "The next_id alias is replaced with its input expression when the filter moves below Project.", "SELECT name FROM (SELECT name, id + 1 AS next_id FROM customers) t WHERE next_id > 10"),
    example("CoreRules.PROJECT_JOIN_TRANSPOSE", "Prune unused join input columns", "Input projections retain the requested output and columns needed for the join. This is logical pruning, not measured storage savings.", "SELECT c.name FROM customers c JOIN orders o ON c.id = o.customer_id"),
    example("CoreRules.PROJECT_REMOVE", "Remove an identity projection", "A projection that preserves every input field can be removed.", "SELECT * FROM customers"),
    example("CoreRules.JOIN_TO_CORRELATE", "Rewrite a join as a correlate", "The join is rewritten using a correlated input and corresponding predicates.", JOIN),
    example("CoreRules.JOIN_EXTRACT_FILTER", "Extract an inner join condition", "The inner join condition moves into a separate filter above a Cartesian join.", JOIN),
    example("CoreRules.JOIN_CONDITION_PUSH", "Push a condition from inside a join", "A condition that references only the orders input can move out of the inner join condition.", "SELECT c.name FROM customers c JOIN orders o ON c.id = o.customer_id AND o.amount > 100"),
    example("CoreRules.JOIN_REDUCE_EXPRESSIONS", "Simplify a join expression", "Constant arithmetic in the join condition is reduced.", "SELECT c.name FROM customers c JOIN orders o ON c.id = o.customer_id AND o.amount > 50 + 50"),
    example("CoreRules.FILTER_TO_CALC", "Represent a filter as Calc", "Calc combines projection and filtering in an expression program. This change does not execute the query.", "SELECT name FROM customers WHERE id > 10"),
    example("CoreRules.PROJECT_TO_CALC", "Represent a projection as Calc", "The projection is represented as a Calc expression program.", "SELECT name, id + 1 AS next_id FROM customers"),
    example("CoreRules.PROJECT_FILTER_TRANSPOSE", "Move projection below a filter", "A projection is placed below the filter while retaining fields required by the predicate.", "SELECT name FROM customers WHERE id > 10"),
    example("CoreRules.AGGREGATE_REDUCE_FUNCTIONS", "Expand an average aggregate", "AVG is expressed using simpler aggregates and a final expression, preserving Calcite type and NULL semantics.", "SELECT customer_id, AVG(amount) AS average_amount FROM orders GROUP BY customer_id"),
    example("CoreRules.AGGREGATE_EXPAND_DISTINCT_AGGREGATES", "Expand a distinct aggregate", "Distinct aggregation is rewritten into relational operators that isolate duplicate elimination.", "SELECT customer_id, COUNT(DISTINCT amount) FROM orders GROUP BY customer_id"),
    example("CoreRules.AGGREGATE_PROJECT_MERGE", "Merge an aggregate input projection", "The aggregate uses the underlying input fields directly when the projection can be absorbed.", "SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id"),
    example("CoreRules.AGGREGATE_CASE_TO_FILTER", "Convert conditional aggregation to a filter", "A CASE expression used by an aggregate becomes an aggregate filter when its semantics allow it.", "SELECT SUM(CASE WHEN amount > 100 THEN amount ELSE NULL END) FROM orders"),
    example("CoreRules.AGGREGATE_UNION_TRANSPOSE", "Push aggregation into union inputs", "Partial aggregates are computed below UNION ALL and combined above it.", "SELECT customer_id, SUM(amount) FROM (SELECT customer_id, amount FROM orders UNION ALL SELECT customer_id, amount FROM orders) t GROUP BY customer_id"),
    example("CoreRules.SORT_PROJECT_TRANSPOSE", "Move sorting through a projection", "The sort and projection are reordered while preserving ordering semantics.", "SELECT name FROM customers ORDER BY name"),
    example("CoreRules.UNION_TO_DISTINCT", "Separate union and duplicate removal", "A distinct UNION becomes UNION ALL followed by duplicate elimination.", "SELECT name FROM customers UNION SELECT city FROM customers"),
    example("CoreRules.INTERSECT_TO_DISTINCT", "Expand an intersection", "INTERSECT is expressed using aggregation and filtering while preserving distinct semantics.", "SELECT name FROM customers INTERSECT SELECT city FROM customers"),
    example("CoreRules.MINUS_TO_DISTINCT", "Expand a set difference", "EXCEPT is rewritten into relational operators that preserve set difference semantics.", "SELECT name FROM customers EXCEPT SELECT city FROM customers"),
    example("CoreRules.FILTER_VALUES_MERGE", "Filter literal rows", "A filter over literal rows is evaluated and merged into Values.", "SELECT * FROM (VALUES (1), (2), (3)) AS t(id) WHERE id > 1"),
    example("CoreRules.PROJECT_VALUES_MERGE", "Project literal rows", "Expressions over literal rows are evaluated into new Values rows.", "SELECT id + 1 FROM (VALUES (1), (2)) AS t(id)"),
    example("PruneEmptyRules.SORT_FETCH_ZERO_INSTANCE", "Remove a zero-row fetch", "A fetch limit of zero allows the subtree to be replaced by an empty result.", "SELECT * FROM customers FETCH NEXT 0 ROWS ONLY"),
    example("SingleValuesOptimizationRules.JOIN_LEFT_INSTANCE", "Simplify a join with one literal row", "A one-row Values input can be replaced with equivalent expressions and filters.", "SELECT c.name FROM (VALUES (1)) AS v(id) JOIN customers c ON v.id = c.id")
  );
  private static final List<Example> LIBRARY = load();
  private RuleExamples() {}
}
