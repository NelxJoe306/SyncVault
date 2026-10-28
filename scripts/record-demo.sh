#!/usr/bin/env bash
# Record docs/images/sync-demo.gif from scripts/demo.tape (real SyncVault CLI).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/target/syncvault-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  echo "missing $JAR — run: mvn -B package -DskipTests" >&2
  exit 1
fi
BIN="$(mktemp -d)"
trap 'rm -rf "$BIN" /tmp/sv-demo-rec' EXIT
cat > "$BIN/syncvault" <<WRAP
#!/bin/sh
exec java -jar "$JAR" "\$@"
WRAP
chmod +x "$BIN/syncvault"
# demo.tape hardcodes /tmp/sv-bin — keep that path stable for VHS Hide setup
mkdir -p /tmp/sv-bin
cp "$BIN/syncvault" /tmp/sv-bin/syncvault
chmod +x /tmp/sv-bin/syncvault
mkdir -p /tmp/sv-demo-rec "$ROOT/docs/images"
# free port if a leftover serve is up
pkill -f 'syncvault-0.1.0-SNAPSHOT.jar serve' 2>/dev/null || true
sleep 0.3
export PATH="$HOME/go/bin:/tmp/sv-bin:$PATH"
cd "$ROOT"
vhs scripts/demo.tape
ls -lh docs/images/sync-demo.gif
# rough duration via ffprobe if available
ffprobe -v error -select_streams v:0 -show_entries stream=duration,nb_frames -of default=nw=1 docs/images/sync-demo.gif 2>/dev/null || true
