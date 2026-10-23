# Development and API reference

For installation and everyday workflows, see the [README](../README.md).
Contribution conventions live in [contributing.md](contributing.md).

## Databases and the rule catalog

Selecting Apache Calcite loads the built-in rule catalog for the pinned dependency version, 1.39.0. The original five common rule groups remain available. Other entries can be searched by name or implementation and filtered by category, with links to their source code. Searching changes only what is displayed; it preserves existing selections. The common-groups control restores the default selection, while clearing the selection creates a program with no rules.

The catalog reads public static rules and rule lists from `CoreRules`, `PruneEmptyRules`, `SingleValuesOptimizationRules`, `DateRangeRules`, `MeasureRules`, `SpatialRules`, `MaterializedViewRules`, `StreamRules`, `EnumerableRules`, and `Bindables`. It also lists concrete `JdbcRules` types and JDBC-to-Enumerable conversion. Public constant aliases are shown separately; list entries already exposed as constants are not added again. This scope excludes additional adapters, custom rules, and the full set of variants created by runtime configuration. Calcite has no fixed global rule set spanning all plugins.

Logical rules can be selected individually. Physical conversion, streaming, spatial, measure, materialized-view, and adapter rules show their required environment; entries that cannot currently run are disabled. Selecting a rule does not guarantee that it will match the current SQL or that arbitrary combinations are compatible. A run accepts up to 32 rules or groups, executed in request order. Common groups can overlap with individual rules; any resulting repeated execution is part of the selected program.

## Table schema format

```json
[
  {
    "name": "customers",
    "columns": [
      { "name": "id", "type": "INTEGER", "nullable": false },
      { "name": "name", "type": "VARCHAR", "nullable": true }
    ]
  }
]
```

Supported types are `INTEGER`, `BIGINT`, `DOUBLE`, `DECIMAL`, `VARCHAR`, `BOOLEAN`, `DATE`, and `TIMESTAMP`. `DECIMAL` uses fixed precision and scale `(18,2)`; `VARCHAR` uses length `(255)`. Columns are nullable when `nullable` is omitted. Table and column names use ASCII identifiers. Query name matching is case-insensitive, and backtick quoting is supported.

## API

| Method and path | Purpose |
| --- | --- |
| `GET /api/health` | Service health |
| `GET /api/catalog` | Database types, rule catalog, catalog scope, and built-in examples |
| `GET /api/rule-examples` | Verified example programs and per-rule coverage reasons |
| `POST /api/runs` | Submit a SQL or plan request and receive a job ID |
| `GET /api/plan-capabilities` | Supported operators, expressions, parameters, types, and limits |
| `POST /api/plans/validate` | Validate a plan in an isolated worker without creating history |
| `GET /api/runs` | Summaries of the 30 most recent runs |
| `GET /api/runs/{id}` | Job status and result |
| `GET /api/runs/{id}/download` | Export the result as JSON |
| `POST /api/import` | Import a result and create a new local record ID |

POST requests use `Content-Type: application/json`. Supported `database` values are `calcite` and `starrocks`; older requests that omit it default to Calcite. StarRocks execution requires its optional pinned runtime. `rules` is an ordered list of runnable catalog entry IDs, such as `CoreRules.PROJECT_REDUCE_EXPRESSIONS`. Legacy group IDs remain supported: `reduce`, `filter-join`, `filter-project`, `project-join`, and `project-cleanup`. An empty list produces only the initial plan.

Use `inputMode: "plan"`, `plan: {planVersion: 1, rootId, nodes, layout?}`, and optional `scopeNodeId` for hand-built plans. Omitted `inputMode` means SQL. Both modes share `schema`, `database`, `rules`, and match limits; a request cannot contain both SQL and a plan.

Requests may include `options: {"expandSubqueries": false, "matchLimit": 1}`. Subquery expansion defaults to `true`; disabling it preserves subqueries for subquery-removal rules. The match limit defaults to `100`. Small limits make reversible rules such as join commuting useful as bounded demonstrations. They do not establish an optimizer fixed point. These settings and the ordered preparation program survive export/import and the Query lab handoff. Older requests without `options` retain the defaults. Plan mode ignores `expandSubqueries`. Planner connection time zone is UTC.

Job states are `queued`, `running`, `succeeded`, and `failed`. Successful results use `formatVersion: 2` and contain `input`, `initial`, `finalPlan`, `stages`, `limitations`, and `editableResult`. Version 1 SQL records remain readable and rerunnable. Stage scope IDs identify the optimized subtree in each complete snapshot.

## Resource limits

- At most two concurrent optimization workers and eight queued jobs, with a 384 MB heap per worker.
- A 25-second timeout per worker and a configurable match limit of 1–100 per stage (default 100).
- Up to 10,000 SQL characters, 16 tables, and 32 columns per table.
- Manual inputs are limited to 250 operators, tree depth 40, expression depth 32, and 2,000 expression objects; request bodies are limited to 100 KB.
- Up to 250 nodes and 40 levels per snapshot, 500 attempts per run, and 80 transformations per stage.
- An 8 MB limit on imports and worker output, with at most 200 stored local records.

Runs fail when collection limits are exceeded; truncated results are not presented as complete records. HepPlanner's match limit is part of the controlled optimization configuration and does not guarantee a global optimum or exhaustive rewriting.

## Validation

Java tests cover real rule transformations, constant evaluation, outer-join and NULL boundaries, programs without rules, invalid input, record round trips, the HTTP lifecycle, restart recovery, and worker timeouts:

```bash
mvn test
```

Graph difference and DAG layout tests require Node.js 18+ but no dependency installation:

```bash
npm test
```

Optional browser tests require a running service:

```bash
npm ci
npx playwright install chromium
npm run test:browser
npm run test:rules
npm run test:plan-lab
```

The local port-forwarding regression test starts its own temporary backend and proxy. Run `mvn package` first:

```bash
npm run test:forwarding
```

Set `OPTILENS_TEST_URL` to change the test URL; the default is `http://127.0.0.1:8080`. Browser tests create real runs and imported records on the target service, so use a separate `OPTILENS_DATA_DIR` for testing. Set `OPTILENS_SCREENSHOT_DIR` to choose a screenshot directory.

## Code layout

```text
src/main/java/dev/optilens/
  Server.java        Local HTTP API and static assets
  Jobs.java          Job queue, persistence, and worker lifecycle
  Worker.java        Isolated Calcite optimization entry point
  Engines.java       Engine-specific catalogs, capabilities, and worker routing
  StarRocks.java      Pinned native catalog, runtime checks, and verification evidence
  Optimizer.java     Calcite SQL/plan optimization, scopes, and event collection
  Plans.java         Typed direct logical-plan construction and capabilities
  PlanExport.java    Lossless editable export of live relational results
  PlanExamples.java Verified hand-built examples for additional rules
  Snapshots.java     Independent snapshots, Hep node unwrapping, and raw plans
  Model.java         Versioned run data structures
  Rules.java         Rule programs and source references
  RuleExamples.java  Verified SQL examples for individual rules
  Input.java         Schema validation and built-in examples
  Records.java       Imported record validation
src/main/resources/web/  Web interface without external CDN dependencies
src/test/java/           Backend tests
tests/                   Graph difference and browser tests
plan/                    Design and implementation records
adapters/starrocks/      Native FE worker, reproducible inventory, fixtures, and audit
```

The frontend uses native JavaScript modules and SVG, and the backend uses JDK HTTP Server. The first release therefore requires no additional frontend build system or service framework. Optional npm dependencies are used only for browser tests.

## Plans and references

- [Design and implementation plan](implementation-plan.md)
- [First-release implementation record](v0.1-implementation.md)
- [Calcite 1.39.0 source](https://github.com/apache/calcite/tree/calcite-1.39.0)
- [RelOptListener](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/RelOptListener.html)
- [RuleMatchVisualizer](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/visualizer/RuleMatchVisualizer.html)

