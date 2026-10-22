#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${ROOT}"
mvn -q package -DskipTests

JAR_FILE="$(find "${ROOT}/target" -maxdepth 1 -name "syncvault-*.jar" ! -name "original-*" | head -n 1)"

cd tests/integration
if [ $# -eq 0 ]; then
    python3 tester.py --progdir="${JAR_FILE}" samples/*.in student_tests/*.in
else
    python3 tester.py --progdir="${JAR_FILE}" "$@"
fi
