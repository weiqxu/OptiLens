package dev.optilens;

import java.util.List;
import static dev.optilens.Model.*;

/** Small SQL programs that exercise one catalog rule at a time. */
public final class RuleExamples {
  private static final List<Table> SCHEMA = Input.examples().get(0).input().schema();
  public static List<Example> all() { return EXAMPLES; }
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
  private RuleExamples() {}
}
