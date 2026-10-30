# JSON examples and verification

## Case library

Rule examples live in `src/main/resources/examples/cases/{database}.json`.
Calcite SQL and direct-plan examples, StarRocks examples, and Trafodion examples
share the same version-1 document. Java loaders no longer contain individual
rule-example programs. Trafodion's Query Lab starter is a separate complete
`Model.Example` document in `examples/trafodion-demos.json`.

Each case has a unique `id`, `targetRule`, English `title` and `note`, pinned
`engineVersion`, optional `sourceCommit`, complete `input` (`Model.Request`),
`expectation`, and `choices`. The request contains its schema, SQL or plan,
ordered rule program, scope and options; it does not inherit a hidden schema.
Several cases may target the same rule. The existing explorer displays the first
case for that rule; every case is checked by the verifier.

```json
{
  "formatVersion": 1,
  "cases": [{
    "id": "project-fold-basic",
    "targetRule": "CoreRules.PROJECT_REDUCE_EXPRESSIONS",
    "title": "Fold a constant projection",
    "note": "Observe the multiplication becoming a constant.",
    "engineVersion": "1.39.0",
    "sourceCommit": null,
    "input": {
      "database": "calcite",
      "inputMode": "sql",
      "sql": "SELECT id, 10 * 20 AS threshold FROM customers",
      "schema": [{"name": "customers", "columns": [{"name": "id", "type": "INTEGER"}]}],
      "rules": ["CoreRules.PROJECT_REDUCE_EXPRESSIONS"],
      "options": {"matchLimit": 10}
    },
    "expectation": {"kind": "target-change", "stageIndex": 0, "minimumChanges": 1},
    "choices": []
  }]
}
```

`stageIndex` is zero-based and must identify `targetRule` in `input.rules`.
Preparation rules remain explicit earlier entries. `target-change` requires
actual changed events from the target implementation or changed native
candidates at the target step. A mere attempted match, different display IDs,
preparation change, or another rule's event is insufficient. `no-change` expects
zero observed target changes; passing it does not grant a Verified example badge. Truncated evidence cannot establish a no-change expectation.
The verifier does not execute rows or establish query equivalence or performance.

For Trafodion manual plans, `choices` records explicit candidate ordinals in
execution order, optionally with expected canonical fingerprints. Missing
ordinals are invalid. `-1` explicitly advances an unmatched step unchanged.
A pending target can be checked without adoption, or choices can replay through
it and check the selected event. The bundled replay case chooses two candidates
in separate workers. It never silently picks the first candidate.

`sourceCommit` pins the StarRocks release, or the Trafodion rule-inventory
baseline. Trafodion's actual adapted source commit and complete installed build
identity are recorded separately in verification results. Dirty native builds
are identified by the existing executable/library/bridge fingerprints.

## Run verification

Build first; the application does not run verification on startup:

```sh
mvn package -DskipTests
java -cp target/optilens.jar dev.optilens.CaseVerifier --database calcite --require-runtime
java -cp target/optilens.jar dev.optilens.CaseVerifier --database all
java -cp target/optilens.jar dev.optilens.CaseVerifier \
  --database calcite --case CoreRules.PROJECT_REDUCE_EXPRESSIONS \
  --output target/single-example-report --require-runtime
```

Set `OPTILENS_STARROCKS_HOME` and `OPTILENS_TRAFODION_HOME` as for normal application
use. Native cases use their actual adapters. They never fall back to Calcite.
Missing or incompatible runtimes produce `skipped`; execution errors or unmet
expectations produce `failed`. Any failure exits nonzero. `--require-runtime`
also makes skipped cases exit nonzero, which is useful in engine-specific CI.
Unknown case IDs fail rather than silently running nothing.

The runner verifies at most two cases concurrently through the application's isolated worker queue,
with the same memory, input, output, timeout and descendant termination limits.
It uses temporary history directories that are removed afterward. Existing user
history is untouched. Continuations use the server's candidate validation and
fresh-process replay. Results do not contain API keys or model configuration.

Reports are written to `target/example-reports/{database}.json` by default.
`--case` writes a partial report: it deliberately does not merge old evidence.
A partial report cannot verify omitted cases. Prefer a full run before publishing.

After reviewing a full report, bundle it and rebuild:

```sh
cp target/example-reports/calcite.json src/main/resources/examples/reports/calcite.json
mvn package -DskipTests
```

Repeat for installed native engines. Do not hand-edit reports to mark cases
verified. Updating JSON normally requires no Java changes, but reports must be
regenerated and resources repackaged. Adding a missing engine capability still
requires adapter work.

## Report identity and interface

A report binds results to the complete canonical case SHA-256, validator version,
execution/validation class bytecode (including nested classes), pinned engine
version and actual engine build. Case JSON object key order is ignored; ordered
rules, plan inputs and expression arguments are preserved. Code or input changes
make previous reports stale. Installed native build changes also invalidate old
positive evidence. Without an installed native runtime, a bundled positive
report describes the recorded build rather than a fresh local verification.

Report files are project-controlled build artifacts, not signed attestations.
Imported run records never grant a Verified example badge. The report format
stores status, reason, observed change count, source/build identity and timestamp;
full debugging snapshots remain available by rerunning the input in a Lab.

Coverage states distinguish `verified`, `verified-no-change`, `unverified`,
`stale`, `skipped` and `failed`. A case's presence alone grants no verification.
Rules without cases keep their existing setup explanations. The explorer's
Verified filter and count use report-backed positive transformations only.
Unverified and skipped cases remain runnable when their runtime is available.

`GET /api/examples/verification?database=calcite` returns the bundled report and
current per-case states. The old `/api/starrocks/verification` URL remains as an
alias for StarRocks. `/api/rule-examples` retains its existing response contract.
The historical StarRocks source inventory/audit remains in its catalog for
reference; its old coverage flags are not used to assign current Verified badges.
