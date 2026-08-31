#!/usr/bin/env bash
# linux_kernel_sweep.sh - LEAP-vs-TCP under tc netem on Linux loopback.
#
# Usage (as root, on Linux):
#   ./scripts/linux_kernel_sweep.sh
#   ./scripts/linux_kernel_sweep.sh "0,0.05,0.1" 2 10m
#
# Writes docs/benchmark_kernel.csv. The Java harness pauses for Enter in
# kernel mode; this script feeds a newline after installing each qdisc.
#
# ============================================================
# Must run on Linux. macOS lo0 bypasses pf+dummynet (see README).
# In Docker: --cap-add=NET_ADMIN
# ============================================================

set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "linux_kernel_sweep: Linux only (this host is $(uname -s))" >&2
  exit 1
fi
if [[ $EUID -ne 0 ]]; then
  echo "linux_kernel_sweep: need root for tc netem (use sudo or Docker --cap-add=NET_ADMIN)" >&2
  exit 1
fi
if ! command -v tc >/dev/null; then
  echo "linux_kernel_sweep: tc not found (install iproute2)" >&2
  exit 1
fi

RATES="${1:-0,0.01,0.05,0.1}"
TRIALS="${2:-2}"
SIZES="${3:-10m}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

CSV="docs/benchmark_kernel.csv"
mkdir -p docs
rm -f "$CSV"

mvn -q package -DskipTests
"$SCRIPT_DIR/gen_bench_data.sh"

cleanup() {
  tc qdisc del dev lo root 2>/dev/null || true
}
trap cleanup EXIT

IFS=',' read -ra RATE_LIST <<< "$RATES"
for rate in "${RATE_LIST[@]}"; do
  rate="${rate// /}"
  echo
  echo "==================== loss = $rate ===================="
  tc qdisc del dev lo root 2>/dev/null || true
  if [[ "$rate" != "0" && "$rate" != "0.0" ]]; then
    pct="$(awk -v r="$rate" 'BEGIN { printf "%.4f", r * 100 }')"
    tc qdisc add dev lo root netem loss "${pct}%"
    tc qdisc show dev lo
  else
    echo "[linux_kernel_sweep] no netem (baseline)"
  fi

  # Benchmark.java waits for Enter before each kernel-mode rate.
  printf '\n' | ./bin/leap benchmark \
    --loss-mode kernel \
    --loss-rates "$rate" \
    --sizes "$SIZES" \
    --trials "$TRIALS" \
    --protocols leap,tcp \
    --csv "$CSV"
done

echo
echo "[linux_kernel_sweep] wrote $CSV"
echo "[linux_kernel_sweep] plot: python3 plot_benchmark.py --csv $CSV --out docs/benchmark_kernel.png --include-tcp --max-loss 0.20 --include-failed"
