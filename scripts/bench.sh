#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CSV_OUT="${BENCH_CSV:-${ROOT}/docs/bench-results.csv}"

cd "${ROOT}"
JAR_FILE="$(find "${ROOT}/target" -maxdepth 1 -name "syncvault-*.jar" ! -name "original-*" 2>/dev/null | head -n 1 || true)"
if [ -z "${JAR_FILE}" ] || [ "${BENCH_REBUILD:-1}" = "1" ]; then
    mvn -q -B package -DskipTests
    JAR_FILE="$(find "${ROOT}/target" -maxdepth 1 -name "syncvault-*.jar" ! -name "original-*" | head -n 1)"
fi

if [ $# -eq 0 ]; then
    java -jar "${JAR_FILE}" bench --with-netty --csv="${CSV_OUT}" 1000 10000
elif [ "$#" -eq 1 ] && [ "$1" = "--smoke" ]; then
    SMOKE_CSV="$(mktemp /tmp/syncvault-bench-smoke-XXXXXX.csv)"
    trap 'rm -f "${SMOKE_CSV}"' EXIT
    java -jar "${JAR_FILE}" bench --smoke --csv="${SMOKE_CSV}"
    test -s "${SMOKE_CSV}"
    head -n 1 "${SMOKE_CSV}" | grep -q "^scale,op,median_ms,p95_ms,samples,notes$"
else
    java -jar "${JAR_FILE}" bench "$@"
fi
