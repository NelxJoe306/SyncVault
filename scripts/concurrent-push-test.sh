#!/usr/bin/env bash
set -euo pipefail

# Concurrent Netty push stress and consistency test for SyncVault.
# Starts one `serve` hub with a shared authentication token, spawns ~10 concurrent
# client processes that each initialize, fetch, create a unique commit, and push
# using CAS-aware push + pull/merge retry on conflict, then verifies full
# repository consistency across the hub and all clients.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

CLIENTS="${CLIENTS:-10}"
MAX_RETRIES="${MAX_RETRIES:-25}"
SHARED_TOKEN="${SHARED_TOKEN:-syncvault-concurrent-token-2026}"

cd "${ROOT}"
JAR_FILE="$(find "${ROOT}/target" -maxdepth 1 -name "syncvault-*.jar" ! -name "original-*" 2>/dev/null | head -n 1 || true)"
if [ -z "${JAR_FILE}" ] || [ "${REBUILD:-0}" = "1" ]; then
    mvn -q -B package -DskipTests
    JAR_FILE="$(find "${ROOT}/target" -maxdepth 1 -name "syncvault-*.jar" ! -name "original-*" | head -n 1)"
fi

WORKDIR="$(mktemp -d /tmp/syncvault-concurrent-push-XXXXXX)"
HUB_PID=""

cleanup() {
    if [ -n "${HUB_PID}" ] && kill -0 "${HUB_PID}" 2>/dev/null; then
        kill "${HUB_PID}" 2>/dev/null || true
        wait "${HUB_PID}" 2>/dev/null || true
    fi
    rm -rf "${WORKDIR}"
}
trap cleanup EXIT

PORT="$(python3 -c '
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
')"

echo "=== SyncVault Concurrent Netty Push Test ==="
echo "Workspace : ${WORKDIR}"
echo "Hub addr  : 127.0.0.1:${PORT} (shared token enabled)"
echo "Clients   : ${CLIENTS} concurrent processes (max retries: ${MAX_RETRIES})"

# 1. Initialize hub repository and start Netty `serve` with shared token
mkdir -p "${WORKDIR}/hub"
(
    cd "${WORKDIR}/hub"
    java -jar "${JAR_FILE}" init
)

(
    cd "${WORKDIR}/hub"
    exec java -jar "${JAR_FILE}" serve "${PORT}" --token="${SHARED_TOKEN}"
) >"${WORKDIR}/hub.log" 2>&1 &
HUB_PID=$!

# Wait for hub port to accept TCP connections
python3 - "${PORT}" "${HUB_PID}" <<'PY'
import os, socket, sys, time
port = int(sys.argv[1])
pid = int(sys.argv[2])
deadline = time.time() + 15.0
while time.time() < deadline:
    try:
        os.kill(pid, 0)
    except OSError:
        sys.exit("Hub process exited unexpectedly before binding port")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        if s.connect_ex(("127.0.0.1", port)) == 0:
            sys.exit(0)
    time.sleep(0.05)
sys.exit(f"Timed out waiting for hub port {port}")
PY

# Verify token enforcement before launching stress wave
mkdir -p "${WORKDIR}/auth_probe"
(
    cd "${WORKDIR}/auth_probe"
    java -jar "${JAR_FILE}" init
    java -jar "${JAR_FILE}" add-remote origin "127.0.0.1:${PORT}"
    if java -jar "${JAR_FILE}" fetch origin master --token="wrong-token" >"${WORKDIR}/auth_probe.log" 2>&1; then
        echo "ERROR: Hub accepted a request with an invalid token" >&2
        exit 1
    fi
    grep -q "Authentication failed" "${WORKDIR}/auth_probe.log"
)

# 2. Initialize all clients and stage their unique commits at the common initial base
for i in $(seq 1 "${CLIENTS}"); do
    CLIENT_DIR="${WORKDIR}/client_${i}"
    mkdir -p "${CLIENT_DIR}"
    (
        cd "${CLIENT_DIR}"
        java -jar "${JAR_FILE}" init
        java -jar "${JAR_FILE}" add-remote origin "127.0.0.1:${PORT}"
        java -jar "${JAR_FILE}" fetch origin master --token="${SHARED_TOKEN}"
        printf "client=%d\nmessage=client-%d unique commit\n" "${i}" "${i}" > "client_${i}.txt"
        java -jar "${JAR_FILE}" add "client_${i}.txt"
        java -jar "${JAR_FILE}" commit "client-${i} unique commit"
    )
done

# 3. Spawn all client push workers concurrently
run_client_push() {
    local idx="$1"
    local cdir="${WORKDIR}/client_${idx}"
    local clog="${WORKDIR}/client_${idx}.log"
    local cstat="${WORKDIR}/status_${idx}.env"

    local attempt=0
    local cas_rejections=0
    local non_ff_rejections=0
    local other_rejections=0

    cd "${cdir}"
    while [ "${attempt}" -lt "${MAX_RETRIES}" ]; do
        attempt=$((attempt + 1))
        set +e
        push_out="$(java -jar "${JAR_FILE}" push origin master --token="${SHARED_TOKEN}" 2>&1)"
        push_rc=$?
        set -e

        if [ "${push_rc}" -eq 0 ]; then
            printf "[attempt %d] PUSH OK\n" "${attempt}" >> "${clog}"
            cat > "${cstat}" <<EOF
CLIENT_ID=${idx}
STATUS=OK
ATTEMPTS=${attempt}
CAS_REJECTIONS=${cas_rejections}
NON_FF_REJECTIONS=${non_ff_rejections}
OTHER_REJECTIONS=${other_rejections}
EOF
            return 0
        fi

        printf "[attempt %d] PUSH REJECTED: %s\n" "${attempt}" "${push_out}" >> "${clog}"
        if [[ "${push_out}" == *"Push rejected: expected tip"* ]]; then
            cas_rejections=$((cas_rejections + 1))
        elif [[ "${push_out}" == *"Please pull down remote changes before pushing"* ]]; then
            non_ff_rejections=$((non_ff_rejections + 1))
        else
            other_rejections=$((other_rejections + 1))
        fi

        # Refetch and merge remote tip before retrying push
        set +e
        java -jar "${JAR_FILE}" pull origin master --token="${SHARED_TOKEN}" >> "${clog}" 2>&1
        set -e

        # Small jitter (20ms to 120ms) to reduce retry collisions
        sleep "$(awk -v seed="${idx}${attempt}" 'BEGIN { srand(seed); printf "%.3f", 0.02 + rand() * 0.10 }')"
    done

    cat > "${cstat}" <<EOF
CLIENT_ID=${idx}
STATUS=FAIL
ATTEMPTS=${attempt}
CAS_REJECTIONS=${cas_rejections}
NON_FF_REJECTIONS=${non_ff_rejections}
OTHER_REJECTIONS=${other_rejections}
EOF
    return 1
}

PIDS=()
for i in $(seq 1 "${CLIENTS}"); do
    run_client_push "${i}" &
    PIDS+=("$!")
done

FAILURES=0
for pid in "${PIDS[@]}"; do
    if ! wait "${pid}"; then
        FAILURES=$((FAILURES + 1))
    fi
done

echo ""
echo "=== Per-Client Push Results ==="
TOTAL_ATTEMPTS=0
TOTAL_CAS=0
TOTAL_NON_FF=0
TOTAL_OTHER=0
FIRST_TRY_WINS=0

for i in $(seq 1 "${CLIENTS}"); do
    # shellcheck disable=SC1090
    source "${WORKDIR}/status_${i}.env"
    echo "Client $(printf '%2d' "${CLIENT_ID}"): status=${STATUS}, attempts=${ATTEMPTS}, cas_rejections=${CAS_REJECTIONS}, non_ff_rejections=${NON_FF_REJECTIONS}, other_rejections=${OTHER_REJECTIONS}"
    TOTAL_ATTEMPTS=$((TOTAL_ATTEMPTS + ATTEMPTS))
    TOTAL_CAS=$((TOTAL_CAS + CAS_REJECTIONS))
    TOTAL_NON_FF=$((TOTAL_NON_FF + NON_FF_REJECTIONS))
    TOTAL_OTHER=$((TOTAL_OTHER + OTHER_REJECTIONS))
    if [ "${STATUS}" = "OK" ] && [ "${ATTEMPTS}" -eq 1 ]; then
        FIRST_TRY_WINS=$((FIRST_TRY_WINS + 1))
    fi
done

echo ""
echo "=== Aggregate Contention Summary ==="
echo "Clients succeeded       : $((CLIENTS - FAILURES)) / ${CLIENTS}"
echo "First-attempt push wins : ${FIRST_TRY_WINS}"
echo "Total push attempts     : ${TOTAL_ATTEMPTS}"
echo "CAS tip rejections      : ${TOTAL_CAS}"
echo "Non-FF tip rejections   : ${TOTAL_NON_FF}"
echo "Other rejections        : ${TOTAL_OTHER}"

if [ "${FAILURES}" -ne 0 ]; then
    echo "ERROR: ${FAILURES} client(s) failed to push within ${MAX_RETRIES} attempts" >&2
    exit 1
fi

# 4. Post-join consistency checks on hub and all clients
echo ""
echo "=== Consistency Verification ==="

# 4a. Hub fsck
HUB_FSCK="$(cd "${WORKDIR}/hub" && java -jar "${JAR_FILE}" fsck)"
if [ "${HUB_FSCK}" != "fsck: ok" ]; then
    echo "ERROR: Hub fsck failed: ${HUB_FSCK}" >&2
    exit 1
fi
echo "[OK] Hub fsck passed (fsck: ok)"

# 4b. Verify all client commit messages exist in hub history
for i in $(seq 1 "${CLIENTS}"); do
    MSG="client-${i} unique commit"
    COMMIT_ID="$(cd "${WORKDIR}/hub" && java -jar "${JAR_FILE}" find "${MSG}")"
    if [ -z "${COMMIT_ID}" ]; then
        echo "ERROR: Hub missing commit with message '${MSG}'" >&2
        exit 1
    fi
done
HUB_COMMIT_COUNT="$(cd "${WORKDIR}/hub" && java -jar "${JAR_FILE}" global-log | grep -c '^commit ')"
MIN_EXPECTED=$((CLIENTS + 1))
if [ "${HUB_COMMIT_COUNT}" -lt "${MIN_EXPECTED}" ]; then
    echo "ERROR: Hub commit count (${HUB_COMMIT_COUNT}) is less than minimum expected (${MIN_EXPECTED})" >&2
    exit 1
fi
echo "[OK] Hub contains all ${CLIENTS} unique client commits (total commits in DAG: ${HUB_COMMIT_COUNT})"

# 4c. Pull final master into every client and verify identical state and working tree files
for i in $(seq 1 "${CLIENTS}"); do
    (
        cd "${WORKDIR}/client_${i}"
        java -jar "${JAR_FILE}" pull origin master --token="${SHARED_TOKEN}" >/dev/null
        CLIENT_FSCK="$(java -jar "${JAR_FILE}" fsck)"
        if [ "${CLIENT_FSCK}" != "fsck: ok" ]; then
            echo "ERROR: Client ${i} fsck failed: ${CLIENT_FSCK}" >&2
            exit 1
        fi
        for j in $(seq 1 "${CLIENTS}"); do
            if [ ! -f "client_${j}.txt" ]; then
                echo "ERROR: Client ${i} missing file client_${j}.txt after final pull" >&2
                exit 1
            fi
            EXPECTED_LINE="message=client-${j} unique commit"
            if ! grep -q "${EXPECTED_LINE}" "client_${j}.txt"; then
                echo "ERROR: Client ${i} has unexpected content in client_${j}.txt" >&2
                exit 1
            fi
            FOUND_ID="$(java -jar "${JAR_FILE}" find "client-${j} unique commit")"
            if [ -z "${FOUND_ID}" ]; then
                echo "ERROR: Client ${i} missing commit for client ${j}" >&2
                exit 1
            fi
        done
    )
done
echo "[OK] All ${CLIENTS} clients converged to hub master tip, passed fsck, and verified all ${CLIENTS} files and commit messages"
echo "=== Concurrent Netty Push Test PASSED ==="
