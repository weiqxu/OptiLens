package dev.optilens;

import static dev.optilens.Model.*;

import java.lang.reflect.Modifier;
import java.util.*;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.rel.rules.CoreRules;

public final class Rules {
  public static final int MAX_SELECTED = 32;

  public record Definition(RuleCard card, List<RelOptRule> rules) {}

  private static final String SOURCE =
      "https://github.com/apache/calcite/blob/calcite-1.39.0/core/src/main/java/org/apache/calcite/rel/rules/";
  public static final List<Definition> ALL =
      List.of(
          definition(
              "reduce",
              "Expression reduction",
              "Evaluate constant expressions and simplify filters and projections using Calcite type and NULL semantics.",
              "Matches Filter or Project. Only expressions the engine considers safe to evaluate are reduced. SQL conversion may already have performed some reductions.",
              "ReduceExpressionsRule.java",
              CoreRules.FILTER_REDUCE_EXPRESSIONS,
              CoreRules.PROJECT_REDUCE_EXPRESSIONS),
          definition(
              "filter-join",
              "Join predicate pushdown",
              "Push filter or join conditions into eligible inputs, potentially simplifying the join type.",
              "Behavior depends on join type, column references, and NULL-rejecting conditions. Inner-join assumptions do not automatically apply to outer joins.",
              "FilterJoinRule.java",
              CoreRules.FILTER_INTO_JOIN,
              CoreRules.JOIN_CONDITION_PUSH),
          definition(
              "filter-project",
              "Filter through projection",
              "Rewrite filter conditions in terms of the projection input and push them below the projection.",
              "Matches Filter → Project. Window expressions or other barriers may prevent a transformation.",
              "FilterProjectTransposeRule.java",
              CoreRules.FILTER_PROJECT_TRANSPOSE),
          definition(
              "project-join",
              "Join input column pruning",
              "Add projections to join inputs, retaining expressions needed by parent outputs and join conditions.",
              "Matches Project → Join. Added projections indicate logical output pruning, not proof of fewer storage reads.",
              "ProjectJoinTransposeRule.java",
              CoreRules.PROJECT_JOIN_TRANSPOSE),
          definition(
              "project-cleanup",
              "Projection cleanup",
              "Merge adjacent projections and remove identity projections.",
              "Transforms only when the engine confirms that expression merging or an identity mapping is valid. Specific reasons for producing no result are not collected.",
              "ProjectMergeRule.java",
              CoreRules.PROJECT_MERGE,
              CoreRules.PROJECT_REMOVE));

  private static Definition definition(
      String id,
      String title,
      String description,
      String conditions,
      String source,
      RelOptRule... rules) {
    var list = List.of(rules);
    return new Definition(
        new RuleCard(
            id,
            title,
            description,
            conditions,
            list.stream().map(Object::toString).toList(),
            SOURCE + source,
            "Common rule groups",
            true,
            ""),
        list);
  }

  private static final String ROOT =
      "https://github.com/apache/calcite/blob/calcite-1.39.0/core/src/main/java/";
  public static final String SCOPE =
      "The catalog covers Calcite 1.39.0 CoreRules, PruneEmptyRules, DateRangeRules, SingleValuesOptimizationRules, MeasureRules, SpatialRules, MaterializedViewRules, StreamRules, EnumerableRules, Bindables, and JdbcRules. It includes public rule constants, lists, and JDBC rule types, excluding additional adapters, custom rules, and the full set of runtime-configured variants.";
  private static final List<Definition> CATALOG = catalog();

  private static List<Definition> catalog() {
    var result = new ArrayList<>(ALL);
    collect(result, "rel.rules.CoreRules", "Core logical rules", "");
    collect(result, "rel.rules.PruneEmptyRules", "Empty-result pruning", "");
    collect(result, "rel.rules.SingleValuesOptimizationRules", "Single-row values", "");
    collect(result, "rel.rules.DateRangeRules", "Date ranges", "");
    collect(
        result,
        "rel.rules.MeasureRules",
        "Measures",
        "Measure types are not configured in the current SQL and schema environment");
    collect(
        result,
        "rel.rules.SpatialRules",
        "Spatial rules",
        "Spatial functions are not registered in the current SQL environment");
    collect(
        result,
        "rel.rules.materialize.MaterializedViewRules",
        "Materialized views",
        "Requires registered materialized views and matching metadata");
    collect(
        result,
        "rel.stream.StreamRules",
        "Streaming queries",
        "Requires streaming tables and a streaming query environment");
    collect(
        result,
        "adapter.enumerable.EnumerableRules",
        "Enumerable physical rules",
        "Requires physical execution conventions and a complete conversion program");
    collect(
        result,
        "interpreter.Bindables",
        "Bindable physical rules",
        "Requires interpreter execution conventions and a complete conversion program");
    try {
      var jdbc = Class.forName("org.apache.calcite.adapter.jdbc.JdbcRules");
      var classes = new ArrayList<Class<?>>(Arrays.asList(jdbc.getDeclaredClasses()));
      classes.add(Class.forName("org.apache.calcite.adapter.jdbc.JdbcToEnumerableConverterRule"));
      classes.stream()
          .filter(
              c -> RelOptRule.class.isAssignableFrom(c) && !Modifier.isAbstract(c.getModifiers()))
          .sorted(Comparator.comparing(Class::getSimpleName))
          .forEach(
              c -> {
                String name = "JdbcRules." + c.getSimpleName();
                result.add(
                    new Definition(
                        new RuleCard(
                            name,
                            name,
                            "JDBC adapter rule type",
                            "Rule instances are created for a JDBC dialect and execution convention.",
                            List.of(c.getName()),
                            source(c),
                            "JDBC adapter",
                            false,
                            "Requires a JDBC dialect, data source, and execution convention"),
                        List.of()));
              });
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
    return List.copyOf(result);
  }

  private static void collect(
      List<Definition> result, String path, String category, String unavailable) {
    try {
      Class<?> owner = Class.forName("org.apache.calcite." + path);
      var fields =
          Arrays.stream(owner.getFields())
              .filter(f -> Modifier.isStatic(f.getModifiers()))
              .sorted(Comparator.comparing(java.lang.reflect.Field::getName))
              .toList();
      Set<RelOptRule> named = Collections.newSetFromMap(new IdentityHashMap<>());
      for (var field : fields)
        if (RelOptRule.class.isAssignableFrom(field.getType())) {
          var rule = (RelOptRule) field.get(null);
          named.add(rule);
          add(result, owner.getSimpleName() + "." + field.getName(), rule, category, unavailable);
        }
      for (var field : fields)
        if (Iterable.class.isAssignableFrom(field.getType())) {
          int index = 0;
          for (Object value : (Iterable<?>) field.get(null)) {
            if (value instanceof RelOptRule rule && named.add(rule))
              add(
                  result,
                  owner.getSimpleName() + "." + field.getName() + "." + index,
                  rule,
                  category,
                  unavailable);
            index++;
          }
        }
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void add(
      List<Definition> result, String id, RelOptRule rule, String category, String unavailable) {
    // These CoreRules entries require table adapters or registered star schemas.
    if (id.contains("STAR_TABLE"))
      unavailable = "Requires registered star tables and materialized relations";
    if (id.equals("CoreRules.FILTER_SCAN")
        || id.equals("CoreRules.FILTER_INTERPRETER_SCAN")
        || id.equals("CoreRules.PROJECT_TABLE_SCAN")
        || id.equals("CoreRules.PROJECT_INTERPRETER_TABLE_SCAN"))
      unavailable = "Requires a table adapter supporting filter or projection pushdown";
    result.add(
        new Definition(
            new RuleCard(
                id,
                id,
                rule.toString(),
                "Triggering depends on Calcite operator matching and semantic conditions. A runnable rule may not match the current SQL.",
                List.of(rule.toString()),
                source(rule.getClass()),
                category,
                unavailable.isEmpty(),
                unavailable),
            List.of(rule)));
  }

  private static String source(Class<?> type) {
    while (type.getEnclosingClass() != null) type = type.getEnclosingClass();
    return ROOT + type.getName().replace('.', '/') + ".java";
  }

  public static Definition get(String id) {
    var definition =
        CATALOG.stream()
            .filter(r -> r.card.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported rule: " + id));
    Input.require(
        definition.card.runnable(),
        "Rule is currently unavailable: " + id + "; " + definition.card.unavailableReason());
    return definition;
  }

  public static List<RuleCard> cards() {
    return CATALOG.stream().map(Definition::card).toList();
  }

  private Rules() {}
}
