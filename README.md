# OptiLens

See every step of SQL optimization.

OptiLens is a locally hosted web research tool. Supply SQL or build a logical operator tree with table schemas, collect real optimization events from Apache Calcite or StarRocks, and explore complete plan snapshots and individual rule transformations in your browser.

## Project language

**English is the required language throughout this project.** Use English for documentation and plans, user interface text, error messages, logs, code identifiers and comments, test descriptions and example explanations, commit messages, and pull request descriptions. All new contributions and updates must follow this convention. Assistant replies are an explicit exception: they follow the language of the user’s question; generated code and rule identifiers remain English.

The documentation, interface, built-in examples, rule catalog descriptions, and application-generated messages use English. Historical and imported records retain their original content and language, and user-supplied SQL and data are not translated.

## Current version: 0.1.0

Implemented features:

- Database selection (Calcite 1.39.0 and optional StarRocks 4.0.14), a searchable rule catalog with category filters, and selection of individual rules or common rule groups.
- A dedicated rule explorer with a database selector, searchable rule list, and 135 verified Calcite rule examples (126 SQL and nine hand-built plans) and 88 verified native StarRocks examples with live before/after plans and explicit preparation stages where needed.
- Editable SQL and declarative table schemas.
- A shared Assistant panel with optional streaming model integration, pinned rule-source citations, graph references, and validated candidate previews.
- Plan Lab: drag-and-connect logical operators, structured expression editing, JSON interchange, undo/redo, and isolated validation.
- Whole-tree or selected-subtree rule programs, with lossless supported results available as the next editable input.
- Real rule attempts and expression production events from Calcite 1.39.0 / HepPlanner.
- Visual comparisons of initial and final plans, stage boundaries, and individual rule events.
- Difference highlighting, graph zoom, node details, and raw plan inspection.
- Six built-in examples covering constants, predicates, projections, outer joins, and NULL conditions.
- Asynchronous jobs, local record storage, history, and JSON export and import.
- Isolated worker processes with time and memory limits, input validation, and error reporting.

OptiLens is not a complete database and does not execute user queries. The first release has no real table data or statistics and makes no claims about performance gains. Additional database integrations and equivalent-candidate search graphs remain future work. StarRocks includes a pinned source inventory and an explicit per-rule verification report.

## Getting started

Requires **JDK 17+ and Maven 3.8+**. The first build needs access to Maven dependency repositories.

```bash
cd /home/weiqxu/src/OptiLens
mvn package
java -jar target/optilens.jar
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080), select a built-in example, and run optimization. Frontend assets are bundled in the JAR; running the application does not require Node.js or a separate frontend development server.

To customize the port and record directory:

```bash
OPTILENS_PORT=8081 OPTILENS_DATA_DIR=./data/runs java -jar target/optilens.jar
```

The service listens only on `127.0.0.1` and is intended for local research. Records are stored in `data/runs/` by default, which Git ignores. Stop the service before backing up this directory. On restart, the service restores records and marks interrupted jobs as failed.

Browser access through another local forwarding port is supported, such as `http://localhost:49894`. Address validation accepts valid ports on `localhost`, `127.0.0.1`, and `[::1]`; the browser port does not have to match the backend port. Non-local addresses are still rejected.

## Assistant

Use **Assistant** in Query Lab, Plan Lab or Rule Explorer to ask about the current input, selected rule or actual optimization event. Responses stream into the panel; node references locate the corresponding graph snapshot, and source references show pinned files and line numbers. Review and validate suggested inputs before applying them, then run the optimizer to obtain real evidence.

Configure `OPTILENS_ASSISTANT_ENDPOINT` (the complete Chat Completions URL), `OPTILENS_ASSISTANT_MODEL`, and optionally `OPTILENS_ASSISTANT_API_KEY` in the server environment. Set `OPTILENS_ASSISTANT_TOOLS=false` for an explicit text-only gateway. Without configuration, the optimizer works normally and the panel explains setup. Keys never enter the browser; conversation history stays in the current tab. The configured model receives the context shown when you send a question.

See [Assistant configuration, source provenance and acceptance checks](plan/assistant.md) for the complete protocol and limits. Model analysis and suggestions do not themselves establish optimizer behavior or performance gains.

## Rule explorer

Open [http://127.0.0.1:8080/rules](http://127.0.0.1:8080/rules), or use **Rule explorer** from the query lab. Choose a database, then select a rule from the left-hand list. Search by rule name or implementation, filter by category, or show only rules with verified examples.

The Calcite explorer lists 222 individual catalog entries; common multi-rule groups remain in the query lab. For 135 rules, selecting an entry submits a real example: 126 use SQL and nine use a directly constructed logical plan. Some programs first apply preparation rules to construct a Calc, Window, MultiJoin, semijoin, or empty input. The selected target rule is always the final stage, and the explorer compares only that stage, not the preparation results. The preparation program and conversion settings are visible below the SQL. The right panel shows the SQL or plan document, table schema, explanation, and complete before/after plans. Use the View menu for local rule events, select graph nodes for details, inspect raw plans, or download the run. Each example is checked against Calcite 1.39.0 to ensure its named target produces an event and an observable expression change; no diagram is a simulated transformation.

Completed and pending examples are reused within the current page session. **Run again** creates a new run. Example runs are stored in normal history and count toward the existing job and record limits. Switching rules never assigns a late result to the newly selected rule.

Entries without a verified example explicitly say so. Rules requiring additional configuration explain what is missing. **Try in Query lab** opens the selected rule and its example SQL when available; otherwise it provides an editable starting query without claiming it matches the rule. **Try in Plan Lab** opens hand-built examples for editing. Date-range rules use an explicit UTC connection context and have a verified example. Coverage is 135 of 141 runnable entries (222 total catalog entries). The remaining six runnable entries explain their missing plan shape, metadata, or planner requirements, or why no observable rewrite is claimed. The other 81 entries require specialized environments such as physical conventions, adapters, materialized views, or streaming schemas. See the [coverage report](plan/rule-example-coverage.md).

## Plan Lab

Open [http://127.0.0.1:8080/plan-lab](http://127.0.0.1:8080/plan-lab). Drag operators from the palette, connect a child’s top output to a parent’s bottom input, and edit the selected node in the inspector. Join ports distinguish left and right inputs. Set the root, choose ordered rules and an optional subtree scope, then use **Validate & preview** and **Run rules**.

The canvas supports subtree copying, input reordering, auto layout, zoom, undo/redo, and JSON editing. Invalid JSON leaves the existing canvas intact. Export/import preserves the schema, editor geometry, rule order, and scope. The original input stays unchanged when a run completes; results provide complete plans, selected-subtree comparisons, and local rule events.

**Use result as input** copies a supported complete result into a new experiment and clears rules and scope. Unsupported operators or metadata remain visible as read-only results with an explanation. Plan construction uses logical factories and Rex expressions directly, so redundant Projects, constant-true Filters, single-input Unions, and zero-output Aggregates are available for rule experiments.

Calcite operators include TableScan, Values, Filter, Project, six Join types, Aggregate, Sort, Union/Intersect/Minus, Calc, Exchange, SortExchange, and inner MultiJoin. The inspector provides field references, typed literals, operator calls, CAST, and CASE. Type conversions must be explicit. See [Plan Lab design and input format](plan/plan-lab.md) for operator attributes, expression examples, API responses, and limits.

## StarRocks

The optional adapter uses the real StarRocks **4.0.14** FE, pinned to commit `7c18dd9dded1587dad24d912dcf8e777daf919f7`. It supports native SQL and manual plans in a separate worker process, without starting storage nodes or executing queries. Database selection updates rules, examples, and Plan Lab capabilities.

All 262 inventoried concrete implementations have an audit status. **88 have verified transformation examples**; the remaining entries explicitly report no observed change, no match, or required setup. The catalog also retains 20 unmapped enum entries. This is not a claim that every StarRocks rule has a passing transformation example. Changed native results currently remain read-only.

See [StarRocks setup, scope, limitations, and reproducible verification](plan/starrocks.md). The Rule explorer links to the complete report, including per-variant checks and native errors.

## Reading the results

1. **Initial to final:** the initial logical plan after SQL conversion or direct plan construction and the final output of the selected rule program.
2. **Rule stage:** the complete plan before and after a rule group; a group may contain several native rules.
3. **Local event:** the expression matched by a single rule invocation and the expression it produced, rather than the entire query plan.
4. **Node details:** attributes, output fields, input nodes, and the basis for difference matching.
5. **Raw records:** Native optimizer output, invocation identifiers, matched nodes, and recorded attempts.

Rules and groups run in selection order. Loaded examples and imported records preserve their recorded program order. This is a controlled experiment, not the engine's complete default optimization pipeline. The before/after callbacks for a rule production are counted only once. Snapshots store independent values instead of references to mutable plan objects.

Difference matching first uses node identifiers within the same run, then attempts unique attribute or operator/table-origin matching. Structural inference is labeled in node details, and ambiguous matches are not forced. A node appearing only before or after a transformation means no correspondence was established; it does not necessarily prove deletion or insertion.

Imported files undergo structural validation only. The interface labels their provenance as unverified by re-execution. An absence of recorded events does not prove that a database lacks an optimization. Producing a candidate does not prove that it was selected or improved performance.

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

- [Design and implementation plan](plan/implementation-plan.md)
- [First-release implementation record](plan/v0.1-implementation.md)
- [Calcite 1.39.0 source](https://github.com/apache/calcite/tree/calcite-1.39.0)
- [RelOptListener](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/RelOptListener.html)
- [RuleMatchVisualizer](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/visualizer/RuleMatchVisualizer.html)

## License

Copyright 2026 OptiLens contributors.

OptiLens is licensed under the [Apache License, Version 2.0](LICENSE).
Third-party dependencies remain subject to their respective licenses and notices.
