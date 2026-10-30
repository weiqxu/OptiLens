# Trafodion Lite integration and usage

## Current support

The development branch `codex/trafodion-native-adapter` adds a native SQL
adapter in both repositories. See [native adapter installation and use](trafodion-native-adapter.md)
and [the adapter README](../adapters/trafodion/README.md). The instructions below
for `codex/trafodion-optimizer-research` describe the earlier standalone runtime
and inspection foundation; that branch does not contain the new SQL worker.
Native manual construction, candidate selection and replay are now implemented on the adapter branch; see [the implementation guide](trafodion-native-adapter.md) for the current behavior and remaining boundaries. The sections below preserve the original design contract.

| Capability | Status |
| --- | --- |
| Fixed-source inventory of 66 native rules | Implemented |
| OptiLens engine/catalog routing and Assistant version/rule context | Implemented |
| Runtime availability in all three database selectors | Implemented; unavailable engines are grey, labelled Not installed, and disabled |
| Optional native compiler, candidate, task and solution callbacks | Implemented in Trafodion-Lite |
| Standalone Lite SQLCI and local SQL server | Existing native runtime; requires its own successful build |
| OptiLens native SQL optimizer worker and structured plan export | Implemented on `codex/trafodion-native-adapter`; requires successful build and installation smoke test |
| Manual plan construction, single-rule candidates and deterministic replay | Pending |
| Editable native results, experiment endpoint and format-v3 history | Pending |

The bundled inventory is pinned to source commit
`ecbf9e035d1c7fd5bd36caf73c27840b2dc6c478`. It contains 30 transformation,
34 implementation and two enforcer rules, with source paths, line numbers and
hashes. Rule registration is not evidence that a rule has been triggered.

## Repositories and installation branches

Use these repositories and branches for the installation steps below:

| Repository | Branch | Purpose |
| --- | --- | --- |
| `weiqxu/OptiLens` | `master` | Web UI, rule catalog, runtime availability and integration documentation |
| `weiqxu/Trafodion-Lite` | `codex/trafodion-optimizer-research` | Native inspection hooks, inventory exporter, usage guides and optimizer research documents |

The required Trafodion-Lite branch includes both the inspection code and the
research documents. Its reference revision for this guide is
`f1bb021be`; record `git rev-parse HEAD` after checkout when reproducing an
experiment. This checkout revision is distinct from the older source baseline
used by OptiLens's bundled rule inventory.

The earlier `codex/optimizer-inspection` branch contains the initial native
foundation only. Although that foundation was merged into local native
`master`, do not assume another checkout's `origin/master` contains it. Use the
explicit research branch above for this installation. Use `codex/trafodion-native-adapter` for the newer OptiLens SQL worker;
the research branch alone is insufficient.

For a new checkout:

```bash
mkdir -p "$HOME/src"
git clone --branch master https://github.com/weiqxu/OptiLens.git "$HOME/src/OptiLens"
git clone --branch codex/trafodion-optimizer-research --single-branch \
  https://github.com/weiqxu/Trafodion-Lite.git "$HOME/src/Trafodion-Lite"
```

For an existing Trafodion-Lite checkout, preserve or commit local edits before
switching branches, then fetch and update the required branch:

```bash
cd "$HOME/src/Trafodion-Lite"
git fetch origin
git switch codex/trafodion-optimizer-research
git pull --ff-only origin codex/trafodion-optimizer-research
git branch --show-current
git rev-parse HEAD
```

The expected branch name is `codex/trafodion-optimizer-research`. An existing
single-branch clone that does not fetch this branch can first create its remote
tracking reference explicitly:

```bash
git fetch origin \
  refs/heads/codex/trafodion-optimizer-research:refs/remotes/origin/codex/trafodion-optimizer-research
git switch codex/trafodion-optimizer-research
```

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
[inspection guide](https://github.com/weiqxu/Trafodion-Lite/blob/codex/trafodion-optimizer-research/plan/optilens-integration.md)
describes the API and remaining work.

## Build and use standalone Trafodion Lite

This is a separate SQL runtime. It executes SQL and persists data; it is useful
for checking the native compiler/runtime while the OptiLens worker is unfinished.

After checking out `codex/trafodion-optimizer-research` as described above,
install dependencies and build:

```bash
cd "$HOME/src/Trafodion-Lite"
git branch --show-current
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
[build and usage guide](https://github.com/weiqxu/Trafodion-Lite/blob/codex/trafodion-optimizer-research/plan/trafodion-lite-usage.md).

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
