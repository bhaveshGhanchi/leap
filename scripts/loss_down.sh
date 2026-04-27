#!/usr/bin/env bash
# Disable the kernel-level packet loss installed by scripts/loss_up.sh.
# Safe to run even if nothing was installed.

set -euo pipefail

if [[ "${OSTYPE:-}" != darwin* ]]; then
  echo "loss_down: macOS-only." >&2
  exit 1
fi

if [[ $EUID -ne 0 ]]; then
  echo "loss_down: must run as root (use sudo)" >&2
  exit 1
fi

dnctl -q flush || true
pfctl -q -a "dummynet-anchor/leap_bench" -F all 2>/dev/null || true
pfctl -q -F all 2>/dev/null || true
pfctl -q -d 2>/dev/null || true

echo "loss_down: cleared."
