#!/usr/bin/env bash
# Generate deterministic random test files for benchmarking.
# Files live in bench_data/ and are gitignored.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT="$ROOT/bench_data"

mkdir -p "$OUT"

declare -a SIZES=(
  "1m:1"
  "10m:10"
  "100m:100"
)

for entry in "${SIZES[@]}"; do
  name="${entry%%:*}"
  mb="${entry##*:}"
  target="$OUT/test_${name}.bin"
  if [[ -f "$target" && $(stat -f%z "$target" 2>/dev/null || stat -c%s "$target") -eq $((mb * 1024 * 1024)) ]]; then
    echo "ok    $target (already ${mb} MiB)"
    continue
  fi
  echo "write $target (${mb} MiB)"
  dd if=/dev/urandom of="$target" bs=1048576 count="$mb" status=none
done

echo "done. files in $OUT"
