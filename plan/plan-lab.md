# Plan Lab

Status: implemented for Calcite 1.39.0, Java 17, native JavaScript and SVG.

All implementation, interface text, examples, documentation, and future contributions use English, as required by the project language policy in the README.

## Workflow

Open `/plan-lab` from the main navigation. The left palette adds logical operators by dragging or clicking. The middle canvas shows editable nodes; the right inspector edits the selected operator and structured expressions.

Connect a child's top output port to the appropriate bottom input port on its parent. Join ports explicitly identify left and right inputs. Connections can also be made by clicking ports, pressing Enter on focused ports, or choosing inputs in the inspector. Select **Set as root** after adding a new root. Input controls support disconnecting, reordering, and adding/removing variadic ports.

Drafts may contain disconnected nodes. Before submission, the client and server independently require one rooted, connected tree with no cycles or shared inputs. **Copy subtree** creates independent nodes; **Delete node** disconnects its former consumers. Undo/redo includes attributes, geometry, root, schema, scope, and rule order. Auto layout and zoom help inspect larger trees.

Canvas and JSON share one request model. **Apply JSON**, or switching back to Canvas with valid JSON, updates that model. Malformed JSON or editor shapes leave the working canvas intact. Type and field errors remain editable and are reported by **Validate & preview** with a node ID and property path. Validation runs in the same isolated, bounded worker pool as optimization and creates no history record.

Search the existing rule catalog, add rules in order, and reorder the program with the arrow buttons. The default scope is the whole tree. Selecting a node in **Optimization scope** limits every stage to that subtree. Match limits remain configurable from 1 to 100 per stage; the Plan Lab starter uses 10.

**Run rules** preserves the original editor input. Results show complete initial/final plans, complete stage boundaries, the selected subtree at each stage, and individual local rule events. **Use result as input** copies the entire supported result to the canvas, assigns fresh editor IDs, clears the rule program, and restores whole-tree scope. Choose new rules to continue the experiment.

## Input model

`POST /api/runs` accepts either SQL or a hand-built plan. Omitted `inputMode` means `sql`; SQL mode rejects `plan` and `scopeNodeId`. Plan mode requires `plan`, accepts optional `scopeNodeId`, and rejects nonblank SQL. Schema, database, ordered rules, and match limit use the existing model. `expandSubqueries` has no effect in plan mode.

A minimal hand-built request:

```json
{
  "inputMode": "plan",
  "database": "calcite",
  "schema": [{"name": "t", "columns": [{"name": "id", "type": "INTEGER", "nullable": false}]}],
  "rules": ["CoreRules.UNION_REMOVE"],
  "options": {"matchLimit": 10},
  "scopeNodeId": null,
  "plan": {
    "planVersion": 1,
    "rootId": "union",
    "nodes": [
      {"id": "scan", "op": "TableScan", "inputs": [], "attributes": {"table": "t"}},
      {"id": "union", "op": "Union", "inputs": ["scan"], "attributes": {"all": true}}
    ],
    "layout": {"union": {"x": 40, "y": 40}, "scan": {"x": 40, "y": 195}}
  }
}
```

`layout` is optional editor information; it has no effect on plan execution. IDs are stable within an editor draft and contain 1–64 ASCII letters, digits, underscores, or hyphens. Inputs are ordered child IDs. The submitted document contains every node exactly once, including nodes outside a selected scope.

**Export draft** saves the complete plan request, including schema, layout, rules, and scope. **Import plan / run** accepts that request, a bare plan document using the current schema/settings, or a versioned run record. This preserves self-contained experiments while permitting standalone plan interchange.

## Operators

`GET /api/plan-capabilities` supplies the operator arities, supported attribute names, expression forms and operators, scalar types, parameter ranges, and input limits. Negative arity values denote a variadic operator's minimum input count.

| Operator | Attributes |
| --- | --- |
| TableScan | `table`: a declared table name |
| Values | `columns`: named scalar types; `rows`: arrays of literal values |
| Filter | `condition`: BOOLEAN expression |
| Project | `projects`: ordered `{name, expr}` outputs |
| Join | `joinType`: INNER, LEFT, RIGHT, FULL, SEMI, ANTI; `condition` |
| Aggregate | `groupKeys`: unique ascending input indexes; `calls`: `{function, args, name, distinct, filter?}` |
| Sort | `keys`: `{index, direction: ASC/DESC, nulls: FIRST/LAST}`; optional nonnegative `offset`, `fetch` |
| Union, Intersect, Minus | `all`: true/false; one or more inputs with matching column types |
| Calc | `projects`, optional BOOLEAN `condition` |
| Exchange | `distribution`: `{type: HASH, keys: [...]}` or SINGLETON, BROADCAST, RANDOM |
| SortExchange | `distribution`, `keys` |
| MultiJoin | Inner joins only: `condition`, optional `postFilter`, `projectedFields`, `refCounts` |

Aggregate functions are COUNT, SUM, SUM0, AVG, MIN, and MAX. COUNT accepts zero arguments for COUNT(*) or one field index; the other functions accept one field. DISTINCT requires an argument. `filter` references a BOOLEAN NOT NULL input field, as required by Calcite's AggregateCall. For a nullable predicate or computed argument, add a Project explicitly; use `IS TRUE(predicate)` for the filter field. No hidden projection or cast is inserted. An Aggregate with both `groupKeys: []` and `calls: []` remains legal and has zero output fields.

MultiJoin's optional projection sets and reference counts preserve metadata used by projection rules. Reference counts are derived from the join condition when omitted. Changing inputs or conditions in the inspector clears stale counts. Its input fields retain positional identity even if names repeat.

## Expressions and types

Expressions are objects, not SQL text:

- Field: `{"kind":"ref","input":0,"index":1}`. Both indexes are zero-based; Join input 0 is left, input 1 is right. The inspector lists fields from each corresponding input.
- Constant: `{"kind":"literal","type":"INTEGER","nullable":false,"value":10}`. Numeric values may be JSON numbers or strings; strings preserve BIGINT/DECIMAL precision. NULL requires a nullable type. As in Calcite, a non-null constant itself has a non-null type.
- Operator: `{"kind":"call","op":"+","args":[...]}`. Supported calls include arithmetic, comparisons, AND/OR/NOT, IS NULL, IS NOT NULL, IS TRUE, IS FALSE, IS NOT DISTINCT FROM, COALESCE, and CASE.
- Cast: `{"kind":"cast","type":"BIGINT","nullable":false,"expr":...}`. Casts remain explicit Rex calls. A cast cannot assert a nullable input is non-null.
- CASE uses an odd argument list: condition, value, condition, value, ..., ELSE value.

Types are INTEGER, BIGINT, DOUBLE, DECIMAL, VARCHAR, BOOLEAN, DATE, and TIMESTAMP. DECIMAL permits precision 1–19 (the pinned Calcite type system limit) and scale 0–precision, defaulting to (18,2). VARCHAR permits length 1–65536, defaulting to 255. DATE uses `YYYY-MM-DD`; TIMESTAMP supports whole seconds in `YYYY-MM-DD HH:MM:SS` form. Expression constants and Values columns support explicit precision/scale; declared table schemas retain their existing fixed DECIMAL/VARCHAR defaults.

Comparable/arithmetic operands, CASE result branches, and COALESCE arguments must have matching types apart from nullability. Insert CAST explicitly to align types. Invalid types and indexes fail validation rather than relying on implicit conversion.

## Construction and execution

`Plans` constructs operators through Calcite logical factories and expressions through Rex APIs. RelBuilder is used only to resolve a table scan, never to build projections, filters, joins, or sets. Adjacent Projects, constant-true Filters, one-input Unions, and empty-output Aggregates therefore survive a run with no rules. Calc uses a RexProgram to encode its expressions; no optimization rule program runs during construction.

`Optimizer` separates initial plan construction from its shared HepPlanner stage executor. For a scoped run, only the selected relation is submitted to each stage. The result replaces that relation along its original ancestor path, leaving other branches untouched. Exact output field names, order, types, and nullability must match before replacement; otherwise the run stops with an explicit scope compatibility error.

Every stage records `scopeBeforeRootId` and `scopeAfterRootId`, referring to the corresponding complete snapshots. Local rule events remain local expressions and are not presented as whole-plan rewrites.

`POST /api/plans/validate` accepts the same plan request and returns `{valid, snapshot, fields, errors}`. A valid response is HTTP 200. Invalid plans return HTTP 422 with `{nodeId, path, message}` issues. The `fields` map associates editor IDs with typed output field descriptions. Structural validation precedes worker launch; type construction happens inside the worker.

## Records and editable results

New runs use `formatVersion: 2`. Version 1 SQL records still load, display, import/export, and rerun with their original defaults. They have no editable representation and cannot be reconstructed from snapshot text.

The worker exports `editableResult` while it still owns actual RelNodes. The exporter checks operators, expressions, hints, traits, and required metadata, then recompiles the exported document and compares raw operator structure plus every output type and trait. Shared result inputs are duplicated into independent editable subtrees.

If information cannot be represented without loss, `editableResult.plan` is null and `reasons` explains the unsupported node or limit. Window, Correlate, HyperGraph, physical conventions, unsupported scalar expressions/types, grouping sets, and non-inner MultiJoin forms remain complete read-only results. No operator or attribute is silently omitted to enable editing. SQL text itself is never reverse-imported into the canvas.

## Limits and validation

Input limits include 250 operators, depth 40, expression depth 32, 2,000 expression objects, 1,000 literal rows per Values, 32 Values columns, 1,024 projections per node, 128 aggregate calls, and 32 selected rules. Attribute JSON is separately bounded to depth 64 and 20,000 items; request bodies have a 100 KB limit. Expanding a shared result beyond 250 nodes disables editable export.

Validation and optimization share two isolated workers, eight queue positions, 384 MB heap per worker, and a 25-second worker timeout. Existing snapshot, attempt, event, output-size, and history limits still apply.

Backend tests cover direct construction and editable round trips across every supported operator and scalar type, exact subtree isolation, incompatible scoped outputs, malformed structures and expressions, explicit casts, expression limits, DAG expansion limits, unmatched rules, read-only results, and old record compatibility. HTTP tests verify validation produces no history or temporary-file residue. Browser checks cover structured expression editing, dragging and connecting, input order, undo/redo, JSON draft preservation, validation errors, real scoped transformations, continuing a result, import/export, and responsive layouts.

Nine new plan examples verify real target-rule changes: UNION_REMOVE, FILTER_EXPAND_IS_NOT_DISTINCT_FROM, JOIN_PUSH_EXPRESSIONS, JOIN_TO_SEMI_JOIN, the three MULTI_JOIN_*_PROJECT variants, EXCHANGE_REMOVE_CONSTANT_KEYS, and SORT_EXCHANGE_REMOVE_CONSTANT_KEYS. Together with the existing 126 SQL examples, coverage is 135 of 141 runnable catalog entries. Query lab and Rule explorer retain their regression suites.
