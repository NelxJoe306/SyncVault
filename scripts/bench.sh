#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT}"
mvn -q -B package -DskipTests

JAR_FILE="$(find "${ROOT}/target" -maxdepth 1 -name "syncvault-*.jar" ! -name "original-*" | head -n 1)"

if [ $# -eq 0 ]; then
    java -jar "${JAR_FILE}" bench 1000 5000 10000
else
    java -jar "${JAR_FILE}" bench "$@"
fi
