# StarRocks native adapter

## Release and provenance

The adapter targets **StarRocks 4.0.14**, source commit **7c18dd9dded1587dad24d912dcf8e777daf919f7**, on Java 17. The FE binary reports `4.0.14` / `7c18dd9`; the worker checks these values before accepting input. This is a fixed stable release, not a moving latest-version dependency. English is required throughout this project.

The runtime comes from the public `starrocks/fe-ubuntu:4.0.14` image. The installer downloads only its FE layer and verifies SHA-256:

```text
c7b7fd890580258a9fafa6ef1c971279ee55bbf8ab1f0d83b733651b1c2cf423
```

No Docker daemon is required. Runtime binaries are installed separately and are not committed. Retain the upstream licenses and notices in the extracted FE distribution; those dependencies retain their own licenses.

## Install and start

Build the main application, install the optional runtime, then compile the adapter against that runtime:

```bash
mvn package
python3 adapters/starrocks/tools/install_runtime.py /tmp/optilens-starrocks-runtime
bash adapters/starrocks/build.sh /tmp/optilens-starrocks-runtime
OPTILENS_STARROCKS_HOME=/tmp/optilens-starrocks-runtime java -jar target/optilens.jar
```

Use a persistent directory instead of `/tmp` if the runtime should survive temporary-file cleanup. A full runtime download requires approximately 680 MB compressed, plus extraction space. Interrupted downloads resume from completed chunks. The installer rejects a checksum mismatch before extraction.

Open `/rules?database=starrocks`, `/?database=starrocks`, or `/plan-lab?database=starrocks`. The catalog and verification report remain available without the optional runtime; execution reports how to install it. Calcite remains bundled and is the default engine for older URLs and requests.

## Execution model

SQL is parsed and analyzed by the actual StarRocks FE and converted by its `RelationTransformer`. Manual plans are built with native `Logical*Operator` and scalar expression classes. Calcite does not translate or optimize StarRocks requests.

The FE lives in a separate worker JVM and dependency classpath. It uses in-memory table schemas and one metadata partition per table. It does not start a database server, storage nodes, a journal, or query execution. Schema-only metadata does not establish table emptiness, so native empty-scan pruning is disabled. Statistics, populated materialized views, storage placement, external catalogs, and tuning guides are not fabricated.

Selected rules run in order with native pattern matching, precondition checks, and transformation calls. The first returned alternative becomes the next input. Native predecessor and successor rules retain their own event names. Whole-tree rules and scalar rewrite rules use their corresponding native interfaces. Scalar traversal currently covers predicates, explicit projects, and join conditions; it does not claim to visit every expression embedded in every native operator.

This is a bounded rule experiment, not the complete StarRocks cost-based optimizer. Rules may depend on a specific upstream planning phase. A recorded transformation proves an observable native rewrite, not result equivalence, final cost-based candidate selection, or better performance. Preparatory rule programs are explicit in examples. Physical outputs are displayed but never executed.

Snapshots are captured before mutations. Local event node IDs are local to that invocation. A selected subtree is optimized separately and reattached only when native column identities, names, types, and nullability still match. Other branches are not passed through the rule program. Existing worker time, memory, expression, graph, and record limits apply.

## Plan Lab support

Native manual input supports TableScan, Values, Filter, Project, Join, Aggregate, Sort, Union, Intersect, and Minus. Join supports INNER, LEFT, RIGHT, FULL, SEMI, and ANTI. Aggregates support COUNT, SUM, AVG, MIN, MAX, and DISTINCT. Intersect and Minus require DISTINCT. Aggregate FILTER, SUM0, Calc, Exchange, SortExchange, and MultiJoin are not exposed as native manual operators in this adapter.

Expressions support field references, explicit typed constants, CAST, arithmetic, comparisons, boolean operators, NULL checks, null-safe equality, COALESCE, and CASE. Function signatures must match native types; add CAST explicitly when necessary. Valid manual structures are not deliberately simplified during construction.

Changed native results currently remain **read-only**. The original input stays editable, and full run records can be exported, imported, and rerun. An unchanged manual plan can be reused directly. Native results are never reconstructed by parsing snapshot display strings. Lossless export of arbitrary native results is a remaining adapter capability.

## Complete audit and coverage

The pinned source inventory includes all `TF_`, `IMP_`, and `GP_` entries in `RuleType`, plus public concrete implementations of the relational, scalar, and whole-tree rule interfaces under the FE optimizer source. Abstract bases, enum sentinels, private nested helpers, and unrelated AST visitors are outside this inventory. A concrete base class can be included even if its default implementation is inert.

Current results:

| Measure | Result |
| --- | ---: |
| RuleType enum entries inventoried | 246 |
| Concrete implementations checked | 262 |
| Loaded implementation instances/variants | 289 |
| Implementations with an observed target transformation | 88 |
| Completed invocations with no observable change | 104 |
| No matching fixture | 58 |
| Native environment or fixture errors | 9 |
| Additional constructor configuration required | 3 |
| Unmapped enum entries retained as non-runnable catalog entries | 20 |
| Total catalog entries | 282 |

The audit contains 277 candidate fixtures. 268 passed input and preparatory-program checks; nine unsuitable preparation programs were excluded and are recorded in `fixtureIssues`. Every available implementation was attempted against the valid corpus until a target transformation was found or the corpus was exhausted. Multi-instance entries also have independent `variantChecks`; success for one variant does not certify the others. Combination rules are containers: child-rule changes are not counted as changes produced by the container itself.

Only the final named target's own observable event qualifies an example as verified. Source enumeration, references to upstream tests, matching, preparation-stage changes, and error-free no-ops are not counted. The 88 published examples were independently rerun and their complete records validated. Upstream test references are source metadata, not a claim that the entire upstream test suite was executed.

The machine-readable evidence is [verification.json](../adapters/starrocks/verification.json), also available from `GET /api/starrocks/verification` and the Rule explorer report link. It includes runtime provenance, statuses, attempts, errors, before/after evidence, reusable inputs, and variant checks. [starrocks-inventory.json](../src/main/resources/starrocks-inventory.json) records source paths and file hashes.

## Reproduce the audit

Check out the exact upstream commit, then regenerate the source inventory:

```bash
python3 adapters/starrocks/tools/inventory.py /path/to/starrocks src/main/resources/starrocks-inventory.json
python3 adapters/starrocks/tools/fixtures.py adapters/starrocks/fixtures.json
OPTILENS_STARROCKS_HOME=/tmp/optilens-starrocks-runtime bash adapters/starrocks/audit.sh adapters/starrocks/verification.json
python3 adapters/starrocks/tools/catalog.py src/main/resources/starrocks-inventory.json src/main/resources --audit adapters/starrocks/verification.json
mvn package
```

`audit.sh` runs the native audit in a bounded JVM and writes incremental results. An optional second argument filters implementation names for investigation; a filtered report must not replace the complete published report. The application runtime has stricter per-job limits than the offline corpus audit.

Behavioral checks cover construction fidelity, subtree isolation, located type errors, imported-record structure, and replay of every published example:

```bash
java -Xmx768m -XX:ActiveProcessorCount=2 \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp "$OPTILENS_STARROCKS_HOME/adapter/optilens-starrocks.jar:$OPTILENS_STARROCKS_HOME/fe/lib/*:target/optilens.jar" \
  dev.optilens.starrocks.NativeChecks adapters/starrocks/fixtures.json
```

Run `npm run test:starrocks` against a running configured service using `OPTILENS_TEST_URL`. Existing Calcite, Plan Lab, record compatibility, graph, and browser regressions remain required.

## API additions

- `GET /api/catalog?database=starrocks`
- `GET /api/rule-examples?database=starrocks`
- `GET /api/plan-capabilities?database=starrocks`
- `GET /api/starrocks/verification`
- `POST /api/runs` and `POST /api/plans/validate`: use `database: "starrocks"` with the existing request model.

Omitting `database` preserves Calcite behavior. A rule from another engine is rejected. SQL subquery-expansion options are Calcite-specific and are hidden for StarRocks. Version 1 SQL records and version 2 records remain readable.
