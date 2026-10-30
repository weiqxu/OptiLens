# Rule example coverage

Calcite version: **1.39.0**. Verified examples: **135 / 222 catalog entries**, covering **135 / 141 runnable entries**. Common multi-rule groups are excluded from these counts. Public aliases are separate catalog entries.

## Verification and execution

Each example executes real Calcite rules. Tests require an event attributed to the target rule and at least one changed local-expression snapshot. Preparation stages, when present, precede the target and are retained in exported records. The explorer compares the target stage only. All rule cases now live in `src/main/resources/examples/cases/`. Direct-plan cases use Plan Lab without SQL conversion. Verification and coverage come from the generated reports described in [JSON examples and verification](json-example-verification.md).

Subquery-removal examples disable SQL-conversion expansion. Reversible rewrites use bounded matches, including one match for join commuting. Dates use a UTC planner context. These are optimizer demonstrations over declared schemas, not query executions or proofs of better performance. A rule producing a candidate does not establish equivalence on every possible input or global optimality.

## Verified examples

| Rule | Example | Preparation stages |
| --- | --- | --- |
| `CoreRules.AGGREGATE_ANY_PULL_UP_CONSTANTS` | Remove a proven constant grouping key | None |
| `CoreRules.AGGREGATE_CASE_TO_FILTER` | Convert conditional aggregation to a filter | None |
| `CoreRules.AGGREGATE_EXPAND_DISTINCT_AGGREGATES` | Expand a distinct aggregate | None |
| `CoreRules.AGGREGATE_EXPAND_DISTINCT_AGGREGATES_TO_JOIN` | Expand distinct aggregates using joins | None |
| `CoreRules.AGGREGATE_EXPAND_WITHIN_DISTINCT` | Expand aggregation with distinct keys | `CoreRules.AGGREGATE_REDUCE_FUNCTIONS` |
| `CoreRules.AGGREGATE_FILTER_TRANSPOSE` | Move aggregation below a filter | `CoreRules.AGGREGATE_PROJECT_MERGE` |
| `CoreRules.AGGREGATE_JOIN_JOIN_REMOVE` | Remove an unused nested outer join | `CoreRules.AGGREGATE_PROJECT_MERGE` |
| `CoreRules.AGGREGATE_JOIN_REMOVE` | Remove an outer join below distinct aggregation | `CoreRules.AGGREGATE_PROJECT_MERGE` |
| `CoreRules.AGGREGATE_JOIN_TRANSPOSE_EXTENDED` | Push partial aggregation below a join | `CoreRules.AGGREGATE_PROJECT_MERGE` |
| `CoreRules.AGGREGATE_MERGE` | Combine nested aggregates | `CoreRules.AGGREGATE_PROJECT_MERGE` |
| `CoreRules.AGGREGATE_PROJECT_MERGE` | Merge an aggregate input projection | None |
| `CoreRules.AGGREGATE_PROJECT_PULL_UP_CONSTANTS` | Remove a constant grouping key | None |
| `CoreRules.AGGREGATE_REDUCE_FUNCTIONS` | Expand an average aggregate | None |
| `CoreRules.AGGREGATE_REMOVE` | Remove aggregation over unique literal rows | None |
| `CoreRules.AGGREGATE_UNION_AGGREGATE` | Remove redundant union-input aggregation | `CoreRules.AGGREGATE_PROJECT_MERGE` → `CoreRules.PROJECT_REMOVE` |
| `CoreRules.AGGREGATE_UNION_AGGREGATE_FIRST` | Remove aggregation from the first union input | `CoreRules.AGGREGATE_PROJECT_MERGE` → `CoreRules.PROJECT_REMOVE` |
| `CoreRules.AGGREGATE_UNION_AGGREGATE_SECOND` | Remove aggregation from the second union input | `CoreRules.AGGREGATE_PROJECT_MERGE` → `CoreRules.PROJECT_REMOVE` |
| `CoreRules.AGGREGATE_UNION_TRANSPOSE` | Push aggregation into union inputs | None |
| `CoreRules.AGGREGATE_VALUES` | Evaluate aggregation over an empty input | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `CoreRules.PROJECT_REMOVE` |
| `CoreRules.CALC_MERGE` | Merge adjacent Calc programs | `CoreRules.FILTER_TO_CALC` → `CoreRules.PROJECT_TO_CALC` |
| `CoreRules.CALC_REDUCE_DECIMALS` | Lower decimal arithmetic in Calc | `CoreRules.PROJECT_TO_CALC` |
| `CoreRules.CALC_REDUCE_EXPRESSIONS` | Fold a constant in Calc | `CoreRules.PROJECT_TO_CALC` |
| `CoreRules.CALC_REMOVE` | Remove an identity Calc | `CoreRules.PROJECT_TO_CALC` |
| `CoreRules.CALC_SPLIT` | Split Calc into relational operators | `CoreRules.FILTER_TO_CALC` |
| `CoreRules.CALC_TO_WINDOW` | Extract a window from Calc | `CoreRules.PROJECT_TO_CALC` |
| `CoreRules.FILTER_AGGREGATE_TRANSPOSE` | Push a grouping-key predicate below aggregation | None |
| `CoreRules.FILTER_CALC_MERGE` | Merge a filter into Calc | `CoreRules.PROJECT_TO_CALC` |
| `CoreRules.FILTER_CORRELATE` | Push a filter through Correlate | `CoreRules.JOIN_TO_CORRELATE` |
| `CoreRules.FILTER_INTO_JOIN` | Push an order filter into a join input | None |
| `CoreRules.FILTER_INTO_JOIN_DUMB` | Push a filter into an inner join | None |
| `CoreRules.FILTER_MERGE` | Combine adjacent filters | `CoreRules.PROJECT_REMOVE` |
| `CoreRules.FILTER_MULTI_JOIN_MERGE` | Attach a filter to MultiJoin | `CoreRules.JOIN_TO_MULTI_JOIN` |
| `CoreRules.FILTER_PROJECT_TRANSPOSE` | Push a filter through a projection | None |
| `CoreRules.FILTER_REDUCE_EXPRESSIONS` | Fold a constant in a predicate | None |
| `CoreRules.FILTER_SAMPLE_TRANSPOSE` | Move a filter below sampling | None |
| `CoreRules.FILTER_SET_OP_TRANSPOSE` | Push a filter into set-operation inputs | None |
| `CoreRules.FILTER_SUB_QUERY_TO_CORRELATE` | Expand a filter subquery | None |
| `CoreRules.FILTER_TO_CALC` | Represent a filter as Calc | None |
| `CoreRules.FILTER_VALUES_MERGE` | Filter literal rows | None |
| `CoreRules.FILTER_WINDOW_TRANSPOSE` | Push a partition-key filter below Window | `CoreRules.PROJECT_TO_LOGICAL_PROJECT_AND_WINDOW` → `CoreRules.FILTER_PROJECT_TRANSPOSE` |
| `CoreRules.HYPER_GRAPH_OPTIMIZE` | Rebuild joins from a hypergraph | `CoreRules.JOIN_TO_HYPER_GRAPH` |
| `CoreRules.INTERSECT_MERGE` | Flatten nested intersections | None |
| `CoreRules.INTERSECT_TO_DISTINCT` | Expand an intersection | None |
| `CoreRules.JOIN_ADD_REDUNDANT_SEMI_JOIN` | Introduce a redundant semijoin | None |
| `CoreRules.JOIN_ASSOCIATE` | Regroup a three-input join | None |
| `CoreRules.JOIN_COMMUTE` | Swap inner-join inputs | None |
| `CoreRules.JOIN_COMMUTE_OUTER` | Swap join inputs with the outer-capable variant | None |
| `CoreRules.JOIN_CONDITION_PUSH` | Push a condition from inside a join | None |
| `CoreRules.JOIN_DERIVE_IS_NOT_NULL_FILTER_RULE` | Infer non-null join inputs | None |
| `CoreRules.JOIN_EXTRACT_FILTER` | Extract an inner join condition | None |
| `CoreRules.JOIN_LEFT_UNION_TRANSPOSE` | Distribute a join over its left union input | None |
| `CoreRules.JOIN_ON_UNIQUE_TO_SEMI_JOIN` | Use a semijoin with a unique right input | None |
| `CoreRules.JOIN_PROJECT_BOTH_TRANSPOSE` | Pull both input projections above a join | None |
| `CoreRules.JOIN_PROJECT_BOTH_TRANSPOSE_INCLUDE_OUTER` | Pull both input projections above a join | None |
| `CoreRules.JOIN_PROJECT_LEFT_TRANSPOSE` | Pull left input projections above a join | None |
| `CoreRules.JOIN_PROJECT_LEFT_TRANSPOSE_INCLUDE_OUTER` | Pull left input projections above a join | None |
| `CoreRules.JOIN_PROJECT_RIGHT_TRANSPOSE` | Pull right input projections above a join | None |
| `CoreRules.JOIN_PROJECT_RIGHT_TRANSPOSE_INCLUDE_OUTER` | Pull right input projections above a join | None |
| `CoreRules.JOIN_PUSH_TRANSITIVE_PREDICATES` | Infer a predicate across an equality join | None |
| `CoreRules.JOIN_REDUCE_EXPRESSIONS` | Simplify a join expression | None |
| `CoreRules.JOIN_RIGHT_UNION_TRANSPOSE` | Distribute a join over its right union input | None |
| `CoreRules.JOIN_SUB_QUERY_TO_CORRELATE` | Expand a subquery in a join condition | None |
| `CoreRules.JOIN_TO_CORRELATE` | Rewrite a join as a correlate | None |
| `CoreRules.JOIN_TO_HYPER_GRAPH` | Represent joins as a hypergraph | None |
| `CoreRules.JOIN_TO_MULTI_JOIN` | Flatten a join tree into MultiJoin | None |
| `CoreRules.LIMIT_MERGE` | Combine nested fetch limits | `CoreRules.PROJECT_REMOVE` |
| `CoreRules.MINUS_MERGE` | Flatten a nested set difference | None |
| `CoreRules.MINUS_TO_DISTINCT` | Expand a set difference | None |
| `CoreRules.MULTI_JOIN_OPTIMIZE` | Choose a join tree from MultiJoin | `CoreRules.JOIN_TO_MULTI_JOIN` → `CoreRules.PROJECT_MULTI_JOIN_MERGE` |
| `CoreRules.MULTI_JOIN_OPTIMIZE_BUSHY` | Construct a bushy join tree | `CoreRules.JOIN_TO_MULTI_JOIN` → `CoreRules.PROJECT_MULTI_JOIN_MERGE` |
| `CoreRules.PROJECT_AGGREGATE_MERGE` | Remove unused aggregate calls | None |
| `CoreRules.PROJECT_CALC_MERGE` | Merge a projection into Calc | `CoreRules.FILTER_TO_CALC` |
| `CoreRules.PROJECT_CORRELATE_TRANSPOSE` | Prune Correlate input columns | `CoreRules.JOIN_TO_CORRELATE` |
| `CoreRules.PROJECT_FILTER_TRANSPOSE` | Move projection below a filter | None |
| `CoreRules.PROJECT_FILTER_TRANSPOSE_WHOLE_EXPRESSIONS` | Push whole expressions below a filter | None |
| `CoreRules.PROJECT_FILTER_TRANSPOSE_WHOLE_PROJECT_EXPRESSIONS` | Push whole projection expressions below a filter | None |
| `CoreRules.PROJECT_FILTER_VALUES_MERGE` | Evaluate a projection and filter on literal rows | None |
| `CoreRules.PROJECT_JOIN_JOIN_REMOVE` | Remove a redundant nested outer join | None |
| `CoreRules.PROJECT_JOIN_REMOVE` | Remove an unused outer join | None |
| `CoreRules.PROJECT_JOIN_TRANSPOSE` | Prune unused join input columns | None |
| `CoreRules.PROJECT_MERGE` | Combine adjacent projections | `CoreRules.PROJECT_FILTER_TRANSPOSE` → `CoreRules.FILTER_PROJECT_TRANSPOSE` |
| `CoreRules.PROJECT_MULTI_JOIN_MERGE` | Record requested fields in MultiJoin | `CoreRules.JOIN_TO_MULTI_JOIN` |
| `CoreRules.PROJECT_OVER_SUM_TO_SUM0_RULE` | Normalize a windowed SUM | None |
| `CoreRules.PROJECT_REDUCE_EXPRESSIONS` | Fold a constant projection | None |
| `CoreRules.PROJECT_REMOVE` | Remove an identity projection | None |
| `CoreRules.PROJECT_SET_OP_TRANSPOSE` | Prune columns before a set operation | None |
| `CoreRules.PROJECT_SUB_QUERY_TO_CORRELATE` | Expand a scalar projection subquery | None |
| `CoreRules.PROJECT_TO_CALC` | Represent a projection as Calc | None |
| `CoreRules.PROJECT_TO_LOGICAL_PROJECT_AND_WINDOW` | Extract a window operator | None |
| `CoreRules.PROJECT_TO_SEMI_JOIN` | Replace a join and distinct input with a semijoin | None |
| `CoreRules.PROJECT_VALUES_MERGE` | Project literal rows | None |
| `CoreRules.PROJECT_WINDOW_TRANSPOSE` | Prune unused Window input columns | `CoreRules.PROJECT_TO_LOGICAL_PROJECT_AND_WINDOW` |
| `CoreRules.SAMPLE_TO_FILTER` | Represent Bernoulli sampling as a filter | None |
| `CoreRules.SEMI_JOIN_FILTER_TRANSPOSE` | Push a semijoin through a filter | `CoreRules.PROJECT_TO_SEMI_JOIN` → `CoreRules.PROJECT_REMOVE` |
| `CoreRules.SEMI_JOIN_JOIN_TRANSPOSE` | Push a semijoin into a join input | `CoreRules.PROJECT_TO_SEMI_JOIN` |
| `CoreRules.SEMI_JOIN_PROJECT_TRANSPOSE` | Push a semijoin through a projection | `CoreRules.PROJECT_TO_SEMI_JOIN` |
| `CoreRules.SEMI_JOIN_REMOVE` | Remove a redundant semijoin candidate | `CoreRules.JOIN_ADD_REDUNDANT_SEMI_JOIN` |
| `CoreRules.SORT_JOIN_COPY` | Copy sort keys below a join | `CoreRules.SORT_PROJECT_TRANSPOSE` |
| `CoreRules.SORT_JOIN_TRANSPOSE` | Push top-N through an outer join | `CoreRules.SORT_PROJECT_TRANSPOSE` |
| `CoreRules.SORT_PROJECT_TRANSPOSE` | Move sorting through a projection | None |
| `CoreRules.SORT_REMOVE_CONSTANT_KEYS` | Remove a constant sort key | None |
| `CoreRules.SORT_REMOVE_REDUNDANT` | Remove sorting on a one-row result | None |
| `CoreRules.SORT_UNION_TRANSPOSE` | Push a top-N bound into union inputs | `CoreRules.PROJECT_REMOVE` |
| `CoreRules.SORT_UNION_TRANSPOSE_MATCH_NULL_FETCH` | Push sorting into union inputs | `CoreRules.PROJECT_REMOVE` |
| `CoreRules.UNION_MERGE` | Flatten nested unions | None |
| `CoreRules.UNION_PULL_UP_CONSTANTS` | Pull a shared constant above a union | None |
| `CoreRules.UNION_TO_DISTINCT` | Separate union and duplicate removal | None |
| `CoreRules.UNION_TO_VALUES` | Combine literal set-operation inputs | `CoreRules.PROJECT_REMOVE` |
| `CoreRules.WINDOW_REDUCE_EXPRESSIONS` | Simplify expressions in a Window | `CoreRules.PROJECT_TO_LOGICAL_PROJECT_AND_WINDOW` |
| `DateRangeRules.FILTER_INSTANCE` | Rewrite date extraction as a range | None |
| `PruneEmptyRules.AGGREGATE_INSTANCE` | Prune empty input in aggregate | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `PruneEmptyRules.CORRELATE_LEFT_INSTANCE` | Prune empty input in correlate left | `CoreRules.JOIN_TO_CORRELATE` → `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `PruneEmptyRules.CORRELATE_RIGHT_INSTANCE` | Prune empty input in correlate right | `CoreRules.JOIN_TO_CORRELATE` → `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` → `PruneEmptyRules.FILTER_INSTANCE` |
| `PruneEmptyRules.FILTER_INSTANCE` | Prune empty input in filter | `PruneEmptyRules.SORT_FETCH_ZERO_INSTANCE` |
| `PruneEmptyRules.INTERSECT_INSTANCE` | Prune empty input in intersect | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `PruneEmptyRules.JOIN_LEFT_INSTANCE` | Prune empty input in join left | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `PruneEmptyRules.JOIN_RIGHT_INSTANCE` | Prune empty input in join right | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `PruneEmptyRules.MINUS_INSTANCE` | Prune empty input in minus | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `PruneEmptyRules.PROJECT_INSTANCE` | Prune empty input in project | `CoreRules.FILTER_REDUCE_EXPRESSIONS` |
| `PruneEmptyRules.SORT_FETCH_ZERO_INSTANCE` | Remove a zero-row fetch | None |
| `PruneEmptyRules.SORT_INSTANCE` | Prune empty input in sort | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `PruneEmptyRules.UNION_INSTANCE` | Prune empty input in union | `CoreRules.FILTER_REDUCE_EXPRESSIONS` → `PruneEmptyRules.PROJECT_INSTANCE` |
| `SingleValuesOptimizationRules.JOIN_LEFT_INSTANCE` | Simplify a join with one literal row | None |
| `SingleValuesOptimizationRules.JOIN_LEFT_PROJECT_INSTANCE` | Simplify a projected single-row input | None |
| `SingleValuesOptimizationRules.JOIN_RIGHT_INSTANCE` | Simplify a join with one literal row | None |
| `SingleValuesOptimizationRules.JOIN_RIGHT_PROJECT_INSTANCE` | Simplify a projected single-row input | None |
| `CoreRules.EXCHANGE_REMOVE_CONSTANT_KEYS` | Remove a constant distribution key (hand-built plan) | None |
| `CoreRules.FILTER_EXPAND_IS_NOT_DISTINCT_FROM` | Expand null-safe equality (hand-built plan) | None |
| `CoreRules.JOIN_PUSH_EXPRESSIONS` | Push an expression out of a join condition (hand-built plan) | None |
| `CoreRules.JOIN_TO_SEMI_JOIN` | Simplify a join with an empty-output aggregate (hand-built plan) | None |
| `CoreRules.MULTI_JOIN_BOTH_PROJECT` | Pull both MultiJoin projections upward (hand-built plan) | None |
| `CoreRules.MULTI_JOIN_LEFT_PROJECT` | Pull left MultiJoin projections upward (hand-built plan) | None |
| `CoreRules.MULTI_JOIN_RIGHT_PROJECT` | Pull right MultiJoin projections upward (hand-built plan) | None |
| `CoreRules.SORT_EXCHANGE_REMOVE_CONSTANT_KEYS` | Remove a constant distribution key (hand-built plan) | None |
| `CoreRules.UNION_REMOVE` | Remove a single-input union (hand-built plan) | None |

## Runnable entries without a verified example

These entries remain selectable for research, but the current SQL and typed-plan paths cannot demonstrate their target rewrite. Some need intentionally mismatched inputs or richer metadata; others produce no observable rewrite in this version. No synthetic before/after result is presented.

| Rule | Reason |
| --- | --- |
| `CoreRules.AGGREGATE_JOIN_TRANSPOSE` | In Calcite 1.39.0 this default variant treats both join inputs as unique and returns without a rewrite. Use the verified AGGREGATE_JOIN_TRANSPOSE_EXTENDED example for aggregate pushdown. |
| `CoreRules.COERCE_INPUTS` | Requires set-operation inputs with mismatched row types. SQL validation already inserts the necessary coercions; Plan Lab requires type-compatible set inputs, so this rule needs a separate fixture for intentionally invalid inputs. |
| `CoreRules.FILTER_TABLE_FUNCTION_TRANSPOSE` | Requires a registered table function with column-mapping metadata; the current schema contains only ordinary tables. |
| `CoreRules.MATCH` | This rule copies a Match operator. A copy alone does not demonstrate an observable optimization; no verified transformation is claimed. |
| `CoreRules.SORT_REMOVE` | Requires a planner that tracks and enforces collation traits. The current HepPlanner does not enforce requested sort traits, so removing a sort would not be a verified equivalent plan. |
| `PruneEmptyRules.EMPTY_TABLE_INSTANCE` | Requires table metadata proving a maximum row count of zero. Schema-only tables do not provide row-count statistics. |

## Entries requiring additional environments

The following 81 entries are disabled in Query lab. Every entry also exposes its reason through `/api/rule-examples` and the explorer.

| Category | Entries | Required setup |
| --- | ---: | --- |
| Core logical rules | 6 | Requires registered star tables and materialized relations; Requires a table adapter supporting filter or projection pushdown |
| Measures | 5 | Measure types are not configured in the current SQL and schema environment |
| Spatial rules | 1 | Spatial functions are not registered in the current SQL environment |
| Materialized views | 7 | Requires registered materialized views and matching metadata |
| Streaming queries | 8 | Requires streaming tables and a streaming query environment |
| Enumerable physical rules | 31 | Requires physical execution conventions and a complete conversion program |
| Bindable physical rules | 12 | Requires interpreter execution conventions and a complete conversion program |
| JDBC adapter | 11 | Requires a JDBC dialect, data source, and execution convention |

## Follow-up work

- Extend the typed plan language and lossless exporter when new operator families are needed.
- Add trait-aware physical planning before demonstrating convention conversion or sort removal.
- Supply explicit table metadata, table functions, and filter/project-capable adapters.
- Add dedicated streaming, materialized-view, measure, spatial, and JDBC environments.
