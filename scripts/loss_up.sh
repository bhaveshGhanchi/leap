#!/usr/bin/env bash
# Enable kernel-level packet loss on the loopback interface (macOS only).
#
# Usage:  sudo ./scripts/loss_up.sh <loss-rate>        # e.g. 0.10 for 10%
#         sudo ./scripts/loss_up.sh 0.10 --ports 4040,5050
#
# Affects BOTH TCP and UDP traffic on lo0 to the specified ports
# (default: 4040 LEAP and 5050 TCP benchmark).
#
# Run scripts/loss_down.sh when finished to restore normal loopback.
#
# Requires: macOS with dummynet (ships by default) + sudo.
#
# Methodology: dummynet installs a pipe with plr (packet-loss-rate) and
# pf.conf redirects matching loopback traffic into the pipe. This applies
# real kernel-level drops so TCP's congestion control reacts exactly as
# it would on a lossy physical link.

set -euo pipefail

if [[ "${OSTYPE:-}" != darwin* ]]; then
  echo "loss_up: this script is macOS-only. Use 'tc netem' on Linux." >&2
  exit 1
fi

if [[ $# -lt 1 ]]; then
  echo "usage: sudo $0 <loss-rate 0.0-1.0> [--ports 4040,5050]" >&2
  exit 2
fi

LOSS="$1"; shift
PORTS="4040,5050"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ports) PORTS="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "loss_up: must run as root (use sudo)" >&2
  exit 1
fi

PF_ANCHOR="leap_bench"
PF_CONF="/tmp/leap_bench_pf.conf"

dnctl -q flush || true
dnctl pipe 1 config plr "$LOSS"

{
  echo "dummynet-anchor \"$PF_ANCHOR\""
  echo "anchor \"$PF_ANCHOR\""
} > "$PF_CONF"

pfctl -q -f "$PF_CONF" 2>/dev/null || true

PORT_LIST=$(echo "$PORTS" | tr ',' ' ')
PORT_EXPR=$(printf "port { %s }" "$(echo "$PORT_LIST" | sed 's/ /, /g')")

ANCHOR_RULES=$(cat <<EOF
dummynet in  on lo0 proto { tcp, udp } from any to any $PORT_EXPR pipe 1
dummynet out on lo0 proto { tcp, udp } from any to any $PORT_EXPR pipe 1
dummynet in  on lo0 proto { tcp, udp } from any $PORT_EXPR to any pipe 1
dummynet out on lo0 proto { tcp, udp } from any $PORT_EXPR to any pipe 1
EOF
)

echo "$ANCHOR_RULES" | pfctl -q -a "dummynet-anchor/$PF_ANCHOR" -f - 2>/dev/null || true

pfctl -q -E 2>/dev/null || true

echo "loss_up: pf+dummynet active. loss=$LOSS ports=$PORTS interface=lo0"
echo "         run 'sudo ./scripts/loss_down.sh' when done."
