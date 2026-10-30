# Trafodion native adapter

This adapter invokes the Trafodion-Lite C++ optimizer in a fresh process for each
SQL or manual-plan request. It compiles through `OPTIMIZE`, exports plans while native objects
are alive, and never executes the user query. Calcite is not used to optimize
Trafodion inputs.

## Install

Use a Trafodion-Lite checkout containing `trafodion-lite-optimizer` (the
`master` branch):

```sh
git clone --branch master https://github.com/weiqxu/Trafodion-Lite.git Trafodion-Lite
cd Trafodion-Lite
OMPI_CXX=/usr/bin/g++ make -j4 lite
cd /path/to/OptiLens
mvn package -DskipTests
adapters/trafodion/install.sh /path/to/Trafodion-Lite /path/to/trafodion-runtime
OPTILENS_TRAFODION_HOME=/path/to/trafodion-runtime java -jar target/optilens.jar
```

The source checkout and its shared libraries must remain in place. This is an
in-place development installation, not a portable standalone distribution.
Java 17, Python 3 and the Trafodion-Lite native build dependencies are required.
See [the integration guide](../../plan/trafodion-native-adapter.md).

## Supported behavior

Select **Trafodion Lite** in Query lab and load **Native join optimization**.
Supply the schema in the editor; table descriptors are built from the request's
in-memory catalog. Tables do not need to exist in RocksDB. Native estimates are
used for statistics. Both logical input and selected physical output are shown.

Rules are observation filters during one complete cost-based native search.
Their displayed order does not determine execution order. Events contain local
candidate expressions; Memo group leaves preserve unresolved native group
references. Candidate production does not prove final adoption or improvement.
The common before/after plans are complete search boundaries, not intermediate
ordered stages. The native join regression verifies changed candidates from `HashJoinRule` and
`JoinCommutativityRule`. The other 64 entries have no verified example yet.

### Manual plans and candidate replay

Open **Plan lab**, select **Trafodion Lite**, and click **New** to load a native
join experiment. The starter includes two JoinCommutativity steps so you can
swap a join and then swap it back. Edit the canvas or JSON, click **Validate**,
then **Run**. Validation returns native output fields and the mapping from
editor nodes to native subgraphs without creating a history record.

For each step, inspect the generated candidate list and click **Preview
candidate** to compare complete plans. **Choose candidate & continue** explicitly
adopts the selected compatible candidate. No candidate is adopted automatically.
A rule with no candidates offers an explicit unchanged continuation. Editing
the execution input invalidates continuation; moving nodes on the canvas does
not. Imported records remain viewable but cannot authorize a continuation.

Every continuation launches a fresh native process, reconstructs the original
input, and replays prior choices. It verifies the engine build, candidate order,
canonical fingerprints, compatibility, Memo status and truncation. A mismatch
fails instead of silently choosing another candidate. Native results remain
read-only: lossless **Use result as input** is not implemented; continuing a
native experiment uses replay rather than reconstructing text attributes.

All 14 editor operators have native construction paths, including six join
forms, aggregates, set ALL/DISTINCT, pagination, Exchange and SortExchange.
Calc, MultiJoin and complex operators may lower into several native nodes;
these preparation changes are distinct from selected-rule events. Construction
and binding tests do not prove runtime row semantics or all-rule coverage.
Only selected rules are evaluated in manual experiments; full SQL optimization
continues to use observation filters. Missing installations remain disabled.

## Protocol and versioning

`trafodion-lite-optimizer INPUT.json OUTPUT.json` reads an envelope containing
`request`, optional `action` (`validate` or `step`), optional `replay`, and `observedRules` (`id`, native `name`). Its response contains
`protocolVersion: 1`, `sourceCommit`, full-plan `initial` and `finalPlan`, local
`events`, and `truncated`, or an `error`. Manual responses contain `validation`, or `prepared`, `current`, selected stages and an `experiment` candidate/replay trace. OptiLens saves manual experiments as record format 3; SQL stays format 2, and older records remain readable. `--identity OUTPUT.json` provides the
compiled identity without starting the compiler.

The installer verifies the protocol and pinned rule source checksums. Runtime
checks verify the executable SHA-256, source commit, shared-library hashes,
rule checksums and bridge presence. Records retain a commit/binary build ID.
The bridge checks response identity, event identities and record structure.
The native worker has memory, CPU, input and output limits. The Java bridge and
server enforce wall-clock timeouts and terminate native descendants.

## Tests

```sh
adapters/trafodion/test.sh
python3 adapters/trafodion/test-native.py /path/to/trafodion-runtime
python3 /path/to/Trafodion-Lite/scripts/test-native-plan-lab.py
OPTILENS_TEST_URL=http://127.0.0.1:8080 npm run test:trafodion
```

The native test compares the final plan with and without observation, requires
real changed join-rule candidates, and checks eight schema types, aggregation,
VALUES, missing tables, invalid input and rejection of writes. Trafodion SQL
syntax requires `VALUES (1 + 2)` for this table-free expression; `SELECT 1 + 2`
is not silently translated to another SQL dialect.
