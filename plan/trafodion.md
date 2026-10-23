# OptiLens native optimizer integration

## Baseline and branches

Native baseline: `ecbf9e035d1c7fd5bd36caf73c27840b2dc6c478`.
Native branch: `codex/optimizer-inspection`.
OptiLens branch: `codex/trafodion-integration`.
All new project documentation and UI text use English.

## Implemented foundation

- Statement-local, optional inspection sink in `OptGlobals`.
- Synchronous preparation-phase, before-substitution, generated-candidate,
  Memo-result, task, and accepted-solution callbacks.
- Task metadata copied before self-destruction; candidate callbacks precede
  Memo insertion, which may delete duplicate input objects.
- Callback exceptions disable the sink instead of escaping into optimization.
- A bounded event budget marks truncation without stopping optimization.
- Source inventory exporter checks registration counts and duplicates, records
  commit and file digests, and identifies 30 transformation, 34 implementation,
  and 2 enforcer rules. No runtime verification is claimed.

Run `bash scripts/test-optimizer-inspection.sh` for dependency-independent checks.
Export with `python3 scripts/export-optimizer-catalog.py --output catalog.json`.

## Remaining implementation (required for delivery)

1. Native worker executable and compile-only context/error-recovery entry.
2. In-memory schema provider and structured snapshots with lifetime-safe IDs.
3. Complete manual-plan construction: every existing OptiLens operator,
   expression and parameter, including collection multiplicity, pagination,
   aggregate filters, distribution requirements and empty/zero-column inputs.
4. Rule-consideration and recursive Memo/group lifecycle events.
5. Single-rule candidate enumeration, context preparation, subtree compatibility
   and deterministic replay of user-selected alternatives.
6. OptiLens experiment endpoint, format-v3 history, candidate UI, editable result
   export and fixed-version assistant source integration.
7. Native behavioral parity, input semantics and two-engine regression tests.

The source inventory and observer interfaces are not an operational optimizer
adapter. The OptiLens catalog must report the native runtime unavailable until
worker implementation and native verification are complete.

## Current environment validation

The independent C++ sink and source inventory checks pass. Full `make lite`
stops at the RocksDB development-header prerequisite. MPI and log4cxx headers
are also absent. No native compilation or optimizer behavioral parity is yet
verified. Use the existing `scripts/install-lite-deps.sh` on a build host with
package installation privileges before the full build.

## OptiLens foundation

The database catalog includes Trafodion and its 66 source-pinned rules.
Runtime execution is explicitly unavailable; requests never fall back to Calcite
or StarRocks. Assistant context uses the correct engine version and rule inventory.
The manual input capability schema describes the required target contract;
`implementationStatus: pending` and `runtime.available: false` are authoritative.
No native worker, step endpoint, format-v3 history or candidate UI is delivered yet.

Java tests require Maven dependencies. The default configured internal Maven
repository is unavailable in this environment and its artifacts are not cached.

The new Trafodion Java unit tests pass using temporary Maven Central settings
with the environment proxy. The initial full regression found one expected
catalog-count assertion (two engines versus three), now updated with a
Trafodion HTTP catalog check. Native compilation remains blocked as above.
