# OptiLens

See every step of SQL optimization.

OptiLens is a locally hosted web research tool. Supply SQL and table schemas, collect real optimization events from Apache Calcite, and explore complete plan snapshots and individual rule transformations in your browser.

## Project language

**English is the required language throughout this project.** Use English for documentation and plans, user interface text, error messages, logs, code identifiers and comments, test descriptions and example explanations, commit messages, and pull request descriptions. All new contributions and updates must follow this convention.

The documentation has been translated to English. Existing non-English application text predates this policy and still needs to be migrated; this documentation update does not change the application's current interface language.

## Current version: 0.1.0

Implemented features:

- Database selection (currently Calcite only), a searchable rule catalog with category filters, and selection of individual rules or common rule groups.
- Editable SQL and declarative table schemas.
- Real rule attempts and expression production events from Calcite 1.39.0 / HepPlanner.
- Visual comparisons of initial and final plans, stage boundaries, and individual rule events.
- Difference highlighting, graph zoom, node details, and raw plan inspection.
- Six built-in examples covering constants, predicates, projections, outer joins, and NULL conditions.
- Asynchronous jobs, local record storage, history, and JSON export and import.
- Isolated worker processes with time and memory limits, input validation, and error reporting.

OptiLens is not a complete database and does not execute user queries. The first release has no real table data or statistics and makes no claims about performance gains. Automated source analysis, additional database integrations, and equivalent-candidate search graphs remain future work.

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

## Reading the results

1. **Initial to final:** the initial logical plan after SQL conversion and the final output of the selected rule program.
2. **Rule stage:** the complete plan before and after a rule group; a group may contain several Calcite rules.
3. **Local event:** the expression matched by a single rule invocation and the expression it produced, rather than the entire query plan.
4. **Node details:** attributes, output fields, input nodes, and the basis for difference matching.
5. **Raw records:** Calcite output, invocation identifiers, matched nodes, and recorded attempts.

Rule groups run in page order. This is a controlled experiment, not Calcite's default optimization pipeline. The before/after callbacks for a rule production are counted only once. Snapshots store independent values instead of references to mutable plan objects.

Difference matching first uses node identifiers within the same run, then attempts unique attribute or operator/table-origin matching. Structural inference is labeled in node details, and ambiguous matches are not forced. A node appearing only before or after a transformation means no correspondence was established; it does not necessarily prove deletion or insertion.

Imported files undergo structural validation only. The interface labels their provenance as unverified by re-execution. An absence of recorded events does not prove that a database lacks an optimization. Producing a candidate does not prove that it was selected or improved performance.

## Databases and the rule catalog

Selecting Apache Calcite loads the built-in rule catalog for the pinned dependency version, 1.39.0. The original five common rule groups remain available. Other entries can be searched by name or implementation and filtered by category, with links to their source code. Searching changes only what is displayed; it preserves existing selections. The common-groups control restores the default selection, while clearing the selection creates a program with no rules.

The catalog reads public static rules and rule lists from `CoreRules`, `PruneEmptyRules`, `SingleValuesOptimizationRules`, `DateRangeRules`, `MeasureRules`, `SpatialRules`, `MaterializedViewRules`, `StreamRules`, `EnumerableRules`, and `Bindables`. It also lists concrete `JdbcRules` types and JDBC-to-Enumerable conversion. Public constant aliases are shown separately; list entries already exposed as constants are not added again. This scope excludes additional adapters, custom rules, and the full set of variants created by runtime configuration. Calcite has no fixed global rule set spanning all plugins.

Logical rules can be selected individually. Physical conversion, streaming, spatial, measure, materialized-view, and adapter rules show their required environment; entries that cannot currently run are disabled. Selecting a rule does not guarantee that it will match the current SQL or that arbitrary combinations are compatible. A run accepts up to 32 rules or groups, executed in catalog order. Common groups can overlap with individual rules; any resulting repeated execution is part of the selected program.

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
| `POST /api/runs` | Submit `{database, sql, schema, rules}` and receive a job ID |
| `GET /api/runs` | Summaries of the 30 most recent runs |
| `GET /api/runs/{id}` | Job status and result |
| `GET /api/runs/{id}/download` | Export the result as JSON |
| `POST /api/import` | Import a result and create a new local record ID |

POST requests use `Content-Type: application/json`. The only supported `database` value is currently `calcite`; older requests that omit it default to Calcite. `rules` is an ordered list of runnable catalog entry IDs, such as `CoreRules.PROJECT_REDUCE_EXPRESSIONS`. Legacy group IDs remain supported: `reduce`, `filter-join`, `filter-project`, `project-join`, and `project-cleanup`. An empty list produces only the initial plan.

Job states are `queued`, `running`, `succeeded`, and `failed`. Successful results use `formatVersion: 1` and contain `input`, `initial`, `finalPlan`, `stages`, and `limitations`.

## Resource limits

- At most two concurrent optimization workers and eight queued jobs, with a 384 MB heap per worker.
- A 25-second timeout per worker and a match limit of 100 per rule group.
- Up to 10,000 SQL characters, 16 tables, and 32 columns per table.
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
node --test tests/graph.test.mjs
```

Optional browser tests require a running service:

```bash
npm ci
npx playwright install chromium
npm run test:browser
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
  Optimizer.java     SQL conversion, HepPlanner, and event collection
  Snapshots.java     Independent snapshots, Hep node unwrapping, and raw plans
  Model.java         Versioned run data structures
  Rules.java         Rule programs and source references
  Input.java         Schema validation and built-in examples
  Records.java       Imported record validation
src/main/resources/web/  Web interface without external CDN dependencies
src/test/java/           Backend tests
tests/                   Graph difference and browser tests
plan/                    Design and implementation records
```

The frontend uses native JavaScript modules and SVG, and the backend uses JDK HTTP Server. The first release therefore requires no additional frontend build system or service framework. Optional npm dependencies are used only for browser tests.

## Plans and references

- [Design and implementation plan](plan/implementation-plan.md)
- [First-release implementation record](plan/v0.1-implementation.md)
- [Calcite 1.39.0 source](https://github.com/apache/calcite/tree/calcite-1.39.0)
- [RelOptListener](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/RelOptListener.html)
- [RuleMatchVisualizer](https://calcite.apache.org/javadocAggregate/org/apache/calcite/plan/visualizer/RuleMatchVisualizer.html)
