# Trafodion Lite integration and usage

## Current support

OptiLens and Trafodion-Lite both contain integration foundations. They do not
yet provide an end-to-end Trafodion optimization experiment.

| Capability | Status |
| --- | --- |
| Fixed-source inventory of 66 native rules | Implemented |
| OptiLens engine/catalog routing and Assistant version/rule context | Implemented |
| Runtime availability in all three database selectors | Implemented; unavailable engines are grey, labelled Not installed, and disabled |
| Optional native compiler, candidate, task and solution callbacks | Implemented in Trafodion-Lite |
| Standalone Lite SQLCI and local SQL server | Existing native runtime; requires its own successful build |
| OptiLens native optimizer worker and structured plan export | Pending |
| Manual plan construction, single-rule candidates and deterministic replay | Pending |
| Editable native results, experiment endpoint and format-v3 history | Pending |

The bundled inventory is pinned to source commit
`ecbf9e035d1c7fd5bd36caf73c27840b2dc6c478`. It contains 30 transformation,
34 implementation and two enforcer rules, with source paths, line numbers and
hashes. Rule registration is not evidence that a rule has been triggered.
The earlier integration branches were `codex/optimizer-inspection` in the native
repository and `codex/trafodion-integration` here; the foundation changes are
integrated into their respective local master branches.

## Inspect support in OptiLens

Build and start OptiLens from its repository:

```bash
cd "$HOME/src/OptiLens"
mvn package
java -jar target/optilens.jar
```

Inspect the read-only catalog and declared input contract:

```bash
curl -fsS 'http://127.0.0.1:8080/api/catalog?database=trafodion'
curl -fsS 'http://127.0.0.1:8080/api/plan-capabilities?database=trafodion'
```

`runtime.available` is false and the capability response has
`implementationStatus: pending`. The normal database selector therefore disables
Trafodion. A direct Rule Explorer URL can inspect the source catalog, but cannot
run an example. Query Lab and Plan Lab also block execution for this runtime.
The advertised operator schema describes the intended input contract, not a
completed native plan builder. Requests never fall back to Calcite or StarRocks.

## Integration method

The intended execution boundary is a separate native C++ optimizer process,
called by the Java backend with bounded JSON request/result files. This keeps
compiler state, memory limits, failures and dependencies outside the web server.
SQL will stop after native optimization; manual inputs must construct native
operators and typed expressions directly, without converting a whole plan to SQL.

The current native patch installs no observer during normal compilation.
Compiler-side consumers subclass `OptimizerInspection`, create it with a callback
budget, and attach it to a live statement's
`getOptGlobals()->inspection`. Available callbacks cover existing compiler phase
boundaries, inputs before `nextSubstitute()`, generated candidates before Memo
insertion, immediate Memo results, tasks and accepted context solutions.

Observers must copy native data synchronously, respect statement lifetimes and
compiler error recovery, and avoid calling APIs that change matching or logical
properties. Callback failure disables collection; truncation does not stop the
optimizer. These hooks are not yet a JSON snapshot exporter, complete Memo trace,
or proof that a candidate was selected in the final root plan.

The native repository's
[inspection guide](https://github.com/weiqxu/Trafodion-Lite/blob/master/plan/optilens-integration.md)
describes the API and remaining work.

## Build and use standalone Trafodion Lite

This is a separate SQL runtime. It executes SQL and persists data; it is useful
for checking the native compiler/runtime while the OptiLens worker is unfinished.

Install dependencies and build:

```bash
cd "$HOME/src/Trafodion-Lite"
bash scripts/install-lite-deps.sh --dry-run
bash scripts/install-lite-deps.sh -y
OMPI_CXX=/usr/bin/g++ make lite
```

The installer needs package-installation privileges. It installs development
libraries including RocksDB, MPI, log4cxx, Thrift and Protobuf.

Configure each terminal that will run a native tool:

```bash
cd "$HOME/src/Trafodion-Lite"
export TRAF_HOME="$PWD/core/sqf"
export TRAF_LITE=1
export TRAF_LITE_STORE_DIR="$HOME/.local/share/trafodion-lite"
mkdir -p "$TRAF_LITE_STORE_DIR"
export SQL_LIBS="$PWD/core/sql/lib/linux/64bit/debug"
export SQF_LIBS="$PWD/core/sqf/export/lib64d"
export LD_LIBRARY_PATH="$SQL_LIBS:$SQF_LIBS${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
"$SQL_LIBS/sqlci"
```

At the SQLCI prompt, try:

```sql
SELECT 1 FROM (VALUES(1)) AS t(x);
CREATE TABLE optilens_demo (id INT, label VARCHAR(20));
INSERT INTO optilens_demo VALUES (1, 'one'), (2, 'two');
SELECT * FROM optilens_demo WHERE id > 1;
exit;
```

Alternatively start the local server:

```bash
"$SQL_LIBS/trafodion-lite-server" --listen 127.0.0.1 --port 23400
```

In a second terminal with the same environment:

```bash
"$SQL_LIBS/trafodion-lite-client" --host 127.0.0.1 --port 23400
```

This uses the repository's reduced Trafodion Type 4 protocol. Starting this
server does not activate OptiLens: no automatic server connection or Trafodion
runtime-home setting is implemented. See the native
[build and usage guide](https://github.com/weiqxu/Trafodion-Lite/blob/master/plan/trafodion-lite-usage.md).

## Verify the implemented foundation

In Trafodion-Lite:

```bash
bash scripts/test-optimizer-inspection.sh
python3 scripts/export-optimizer-catalog.py --output /tmp/trafodion-catalog.json
```

These checks validate observer isolation, callback budgets and the source
inventory without requiring a complete native build. Exporting a new catalog
does not automatically replace OptiLens's bundled, fixed-version inventory.
A catalog update must review the source commit, hashes and adapter compatibility.

In OptiLens:

```bash
mvn -Dtest=ServerTest,TrafodionTests test
npm test
# With a running test instance and Playwright installed:
npm run test:database-options
```

The targeted backend tests and three-page availability checks have passed.
A complete native compiler build and SQLCI/server smoke run with the inspection
patch remain unverified; no native optimizer behavioral parity is claimed.

## Remaining execution work

Complete the compile-only worker and its error handling, an in-memory catalog,
structured snapshots with stable identities, the complete manual input builder,
single-rule candidate enumeration, subtree compatibility, deterministic replay,
editable-result export, candidate UI, historical records and pinned native
Assistant source excerpts. Test all promised input semantics and native trace
parity before marking the runtime available.
