#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
runtime_home=${1:?Usage: adapters/starrocks/build.sh /path/to/pinned/runtime}
if [[ ! -f "$repo_root/target/optilens.jar" ]]; then
  echo "Build the OptiLens application first (mvn package)." >&2
  exit 1
fi
mkdir -p "$runtime_home/adapter/classes"
find "$repo_root/adapters/starrocks/src/main/java" -name '*.java' -print > "$runtime_home/adapter/sources.txt"
javac --release 17 -cp "$runtime_home/fe/lib/*:$repo_root/target/optilens.jar" -d "$runtime_home/adapter/classes" @"$runtime_home/adapter/sources.txt"
jar --create --file "$runtime_home/adapter/optilens-starrocks.jar" -C "$runtime_home/adapter/classes" .
echo "Built the native StarRocks adapter in $runtime_home/adapter"
