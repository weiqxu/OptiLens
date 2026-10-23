#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
runtime_home=${OPTILENS_STARROCKS_HOME:?Set OPTILENS_STARROCKS_HOME to the pinned runtime}
output=${1:-"$repo_root/adapters/starrocks/verification.json"}
java -Xmx768m -XX:ActiveProcessorCount=2 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp "$runtime_home/adapter/optilens-starrocks.jar:$runtime_home/fe/lib/*:$repo_root/target/optilens.jar" \
  dev.optilens.starrocks.NativeAudit "$repo_root/adapters/starrocks/fixtures.json" "$output" "${2:-}"
