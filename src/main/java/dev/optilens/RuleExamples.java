package dev.optilens;

import static dev.optilens.Model.*;

import java.util.List;

/**
 * Verified SQL programs, with explicit preparation stages when a rule needs an intermediate plan.
 */
public final class RuleExamples {

  public static List<Example> all() {
    return LIBRARY;
  }

  public record Coverage(String id, String status, String reason) {}

  public static List<Coverage> coverage() {

    return Rules.cards().stream()
        .filter(c -> !c.category().equals("Common rule groups"))
        .map(
            c -> {
              var evidence = ExampleVerification.coverage("calcite", c.id());
              if (evidence != null) return evidence;
              if (!c.runnable())
                return new Coverage(c.id(), "requires-environment", c.unavailableReason());
              String reason =
                  switch (c.id()) {
                    case "CoreRules.AGGREGATE_JOIN_TRANSPOSE" ->
                        "In Calcite 1.39.0 this default variant treats both join inputs as unique and returns without a rewrite. Use the verified AGGREGATE_JOIN_TRANSPOSE_EXTENDED example for aggregate pushdown.";
                    case "CoreRules.MATCH" ->
                        "This rule copies a Match operator. A copy alone does not demonstrate an observable optimization; no verified transformation is claimed.";
                    case "CoreRules.COERCE_INPUTS" ->
                        "Requires set-operation inputs with mismatched row types. SQL validation already inserts the necessary coercions; a direct relational-plan fixture is needed.";
                    case "CoreRules.EXCHANGE_REMOVE_CONSTANT_KEYS",
                            "CoreRules.SORT_EXCHANGE_REMOVE_CONSTANT_KEYS" ->
                        "Requires an Exchange or SortExchange with constant distribution keys. SQL conversion in this environment does not construct distributed exchange operators.";
                    case "CoreRules.FILTER_EXPAND_IS_NOT_DISTINCT_FROM" ->
                        "SQL conversion expands IS NOT DISTINCT FROM before the rule stage. A direct relational-plan fixture is needed to preserve the original expression.";
                    case "CoreRules.FILTER_TABLE_FUNCTION_TRANSPOSE" ->
                        "Requires a registered table function with column-mapping metadata; the current schema contains only ordinary tables.";
                    case "CoreRules.JOIN_PUSH_EXPRESSIONS" ->
                        "SQL conversion already pushes the tested join-key expressions. A direct relational-plan fixture is needed to preserve the pre-rewrite join condition.";
                    case "CoreRules.JOIN_TO_SEMI_JOIN" ->
                        "Requires a join whose right input is an aggregate with no output fields. A direct relational-plan fixture is needed to preserve this shape.";
                    case "CoreRules.MULTI_JOIN_BOTH_PROJECT",
                            "CoreRules.MULTI_JOIN_LEFT_PROJECT",
                            "CoreRules.MULTI_JOIN_RIGHT_PROJECT" ->
                        "Requires a partially flattened join tree with Project over MultiJoin under a remaining binary join. A targeted relational-plan fixture is needed; full-tree flattening removes that match.";
                    case "CoreRules.SORT_REMOVE" ->
                        "Requires a planner that tracks and enforces collation traits. The current HepPlanner does not enforce requested sort traits, so removing a sort would not be a verified equivalent plan.";
                    case "CoreRules.UNION_REMOVE" ->
                        "Requires a Union with exactly one input. SQL UNION produces at least two inputs; a direct relational-plan fixture is needed.";
                    case "PruneEmptyRules.EMPTY_TABLE_INSTANCE" ->
                        "Requires table metadata proving a maximum row count of zero. Schema-only tables do not provide row-count statistics.";
                    default ->
                        "No reproducible target-rule transformation has been verified in this environment.";
                  };
              String status =
                  c.id().equals("CoreRules.AGGREGATE_JOIN_TRANSPOSE")
                          || c.id().equals("CoreRules.MATCH")
                      ? "no-observable-rewrite"
                      : "requires-plan-setup";
              return new Coverage(c.id(), status, reason);
            })
        .toList();
  }

  private static final List<Example> LIBRARY = ExampleCases.examples("calcite");

  private RuleExamples() {}
}
