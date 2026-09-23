#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Uses Gradle to manage dependencies (MongoDB driver needed for compilation).
set -euo pipefail
cd "$(dirname "$0")"

echo "==> compiling and running with Gradle"
if [ $# -eq 0 ]; then
  ./gradlew --no-daemon --console=plain selfCheck
else
  ./gradlew --no-daemon --console=plain selfCheck --args="$*"
fi
