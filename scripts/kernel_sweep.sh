#!/usr/bin/env bash
# kernel_sweep.sh — run the LEAP-vs-TCP benchmark in kernel-loss mode.
#
# Usage:  sudo ./scripts/kernel_sweep.sh
#         sudo ./scripts/kernel_sweep.sh "0,0.01,0.05,0.1" 3 10m
#
# For each loss rate, this script:
#   1. Installs (or clears) pfctl+dummynet rules on lo0 via loss_up.sh.
#   2. Runs the benchmark for that single rate as the original user
#      (so output files aren't owned by root).
#   3. Tears the rules back down before the next iteration.
#
# A trap on EXIT guarantees loss_down runs even on Ctrl-C or crash.
#
# Output: docs/benchmark_kernel.csv (one row per transfer).
#
# ============================================================
# KNOWN LIMITATION — macOS 14+ (Sonoma / Sequoia):
# pf+dummynet rules attached to lo0 are loaded successfully but
# the kernel's loopback fast-path bypasses the pf hook, so no
# packets are actually shaped. Symptom: LEAP runs at full speed
# with 0 retransmits and 100% efficiency at every loss rate, and
# `sudo pfctl -si` shows `match 0` while traffic is flowing.
# Evidence of this failure mode is preserved at:
#   docs/benchmark_kernel_macos14_no_drops.csv
# For real TCP-vs-LEAP-under-loss numbers, run on Linux with
# `tc qdisc add dev lo root netem loss 5%` instead.
# ============================================================

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "kernel_sweep: must be run as root (use sudo)" >&2
  exit 1
fi
if [[ -z "${SUDO_USER:-}" ]]; then
  echo "kernel_sweep: \$SUDO_USER not set; run via 'sudo', not as root login" >&2
  exit 1
fi

RATES="${1:-0,0.01,0.05,0.1}"
TRIALS="${2:-3}"
SIZES="${3:-10m}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

CSV="docs/benchmark_kernel.csv"
mkdir -p docs
rm -f "$CSV"

# Make sure the jar is built and test data exists, both as the real user.
sudo -u "$SUDO_USER" mvn -q package -DskipTests
sudo -u "$SUDO_USER" "$SCRIPT_DIR/gen_bench_data.sh" >/dev/null

cleanup() {
  echo
  echo "[kernel_sweep] tearing down pfctl rules…"
  "$SCRIPT_DIR/loss_down.sh" >/dev/null 2>&1 || true
}
trap cleanup EXIT

IFS=',' read -ra RATE_LIST <<< "$RATES"
for rate in "${RATE_LIST[@]}"; do
  rate="${rate// /}"   # trim whitespace
  echo
  echo "==================== loss = $rate ===================="

  # Always start clean.
  "$SCRIPT_DIR/loss_down.sh" >/dev/null 2>&1 || true

  if [[ "$rate" != "0" && "$rate" != "0.0" ]]; then
    "$SCRIPT_DIR/loss_up.sh" "$rate"
  else
    echo "[kernel_sweep] no loss installed (baseline)"
  fi

  sudo -u "$SUDO_USER" ./bin/leap benchmark \
    --loss-mode kernel \
    --loss-rates "$rate" \
    --sizes "$SIZES" \
    --trials "$TRIALS" \
    --protocols leap,tcp \
    --csv "$CSV"

  "$SCRIPT_DIR/loss_down.sh" >/dev/null 2>&1 || true
done

echo
echo "[kernel_sweep] wrote $CSV"
echo "[kernel_sweep] plot with: python3 plot_benchmark.py --csv $CSV --out docs/benchmark_kernel.png"
