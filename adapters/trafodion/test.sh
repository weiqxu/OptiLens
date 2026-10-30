#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd "$(dirname "$0")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 17 -cp "$project_root/target/optilens.jar" -d "$work" \
  "$project_root/adapters/trafodion/src/main/java/dev/optilens/trafodion/TrafodionWorker.java" \
  "$project_root/adapters/trafodion/ProtocolTest.java"
java -ea -cp "$work:$project_root/target/optilens.jar" ProtocolTest
