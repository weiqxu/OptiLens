# Trafodion native adapter and manual experiments

## Implementation

`adapters/trafodion` contains the Java file-protocol bridge and installation
script. `Trafodion.java` validates installation identity and rule-source
compatibility. `Engines` routes Trafodion requests exclusively to this bridge.
The native entry point is `core/sql/bin/TrafodionLiteOptimizerMain.cpp` in the
Trafodion-Lite `master` branch. The native adapter and its OptiLens integration
are merged into `master` in both repositories.

The worker bootstraps the embedded compiler, parses and checks a read-only AST,
then stops compilation at `OPTIMIZE`. It does not generate or execute a user
query. Request-local table definitions are installed through
`LiteOptimizerCatalog`; ordinary SQLCI/server metadata paths retain their
existing RocksDB behavior when no request catalog is installed.

The existing optimizer observer copies initial plans and local candidates
synchronously. Native search and match ordering are unchanged. Collection limits
truncate evidence without stopping native optimization. Memo references remain
explicit in local candidate snapshots rather than being replaced with an
arbitrary group expression.

## Installation and use

Follow [the adapter installation instructions](../adapters/trafodion/README.md).
The installation references an existing debug source build; moving or deleting
its checkout invalidates it. Reinstall after rebuilding the native executable.
Only mark the installation available after the expected binary, bridge, native
rule source checksums and provenance are present.

Restart OptiLens with `OPTILENS_TRAFODION_HOME` set, open Query lab, select
Trafodion Lite, load the native join query, and click Run. Selected rules filter
candidate events across a full native search. An empty selection still performs
full optimization. Review actual local events separately from the globally
selected final plan. Run records remain format version 2 and can be imported
without installing the native runtime; rerunning requires installation.

## Manual construction, stepping and replay

The native worker directly constructs bound RelExpr and ItemExpr objects from
the editor contract. It does not convert the relational plan into SQL, run the
normalizer, or invoke a hidden optimizer search. Scalar parsing is confined to
fixed scalar templates and explicitly typed literals. Preparation maps each
editing node to its native subgraph and preserves the original input document.

All 14 operators have construction coverage. RIGHT join swaps native inputs
and restores output references; aggregate filters use conditional arguments;
SUM0 adds a typed zero; set ALL uses occurrence numbering and NULL-safe row
matching; pagination uses native sequence numbering and range filters.
Exchange/SortExchange carry actual partitioning/sorting properties. These
lowerings are preparation, not evidence of a native rule firing. Validation
is compile-only and does not execute rows or establish a physical cost optimum.

Each manual step freezes the current Memo inputs, evaluates only the selected
rule, and records changed candidates before insertion can delete them. Matching
uses pass-2 default ESP context, two CPUs/pipelines and explicit input properties.
Candidate compatibility checks required outputs and declared physical properties;
unknown physical properties cannot prove a requirement. User selection is
mandatory, including when there is only one compatible candidate.

`POST /api/experiments/step` accepts a saved run ID, candidate ID and current
input. The server derives replay data from its own locally saved record; client
fingerprints are not trusted. Execution-input changes and engine-version changes
are rejected. Layout-only changes are allowed. Canonical fingerprints exclude
pointers and unstable ValueIds. Replay checks the complete generated candidate
list, ordering, Memo outcomes, compatibility and truncation before applying the
selected ordinal/fingerprint. Subtree experiments only enumerate within the
prepared node boundary and verify reconnection outputs/properties.

Manual history uses format 3 and includes preparation, node mappings, candidates,
and replay. Formats 1 and 2 remain readable. SQL remains format 2. Native-to-editor
lossless export remains unavailable; replay supports continued experimentation
without claiming editable reconstruction. No automatic candidate choice or
native query execution is provided.

The 66 source-pinned rules remain catalogued. Making a rule selectable for
observation is not a verified trigger. Real coverage must require native changed
candidate events and report external Lite dependencies separately. Native rule
sources must match the bundled inventory; other native changes are identified by
the actual source commit and executable hash. Uncommitted development builds
are distinguished by their executable hash and are not reproducible from the
commit alone.

## Validation

Run Maven tests and the formatting checks; native protocol tests cover identity,
invalid events, observation semantics and record validation. Run the native
inspection tests in Trafodion-Lite. A real linked native executable must compile
the join example without accessing a persistent catalog before this slice is
considered runtime-verified. Build or runtime failures must not be reported as
successful native rule coverage.

The real native regression currently observes two changed HashJoin candidates
and one JoinCommutativity candidate for the bundled join query. Both target
rules have Rule Explorer examples. The remaining 64 rules retain explicit
unverified coverage; no blanket all-rules verification is claimed.
