#!/usr/bin/env python3
"""
Render per-transfer cwnd / ssthresh / loss-event charts from a LEAP client log.

Usage:
  python3 plot_leap.py
  python3 plot_leap.py --csv leap_log.csv --out-dir .
  python3 plot_leap.py --csv path/to/leap_log.csv --out-dir charts/
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    import matplotlib.pyplot as plt
    import pandas as pd
except ImportError as e:
    sys.stderr.write(
        f"missing dependency: {e}\n"
        "install with: pip install pandas matplotlib\n"
    )
    sys.exit(1)


def plot(csv_path: Path, out_dir: Path) -> None:
    df = pd.read_csv(csv_path)
    df["time"] = df["time"] / 1000.0
    out_dir.mkdir(parents=True, exist_ok=True)

    cwnd_path = out_dir / "cwnd_vs_time.png"
    ssthresh_path = out_dir / "ssthresh_vs_time.png"
    events_path = out_dir / "events.png"

    plt.figure()
    plt.plot(df["time"], df["cwnd"])
    plt.xlabel("Time (seconds)")
    plt.ylabel("Congestion Window (cwnd)")
    plt.title("CWND vs Time")
    plt.grid()
    plt.savefig(cwnd_path)

    plt.figure()
    plt.plot(df["time"], df["ssthresh"])
    plt.xlabel("Time (seconds)")
    plt.ylabel("ssthresh")
    plt.title("ssthresh vs Time")
    plt.grid()
    plt.savefig(ssthresh_path)

    events = df[df["event"].isin(["TIMEOUT", "FAST_RETX"])]
    plt.figure()
    plt.scatter(events["time"], events["cwnd"], label="Events")
    plt.xlabel("Time (seconds)")
    plt.ylabel("cwnd")
    plt.title("Loss Events (Timeout & Fast Retransmit)")
    plt.legend()
    plt.grid()
    plt.savefig(events_path)

    print("Graphs saved:")
    print(f" - {cwnd_path}")
    print(f" - {ssthresh_path}")
    print(f" - {events_path}")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Plot LEAP per-transfer congestion traces.")
    p.add_argument(
        "--csv",
        default="leap_log.csv",
        help="input CSV (default: leap_log.csv)",
    )
    p.add_argument(
        "--out-dir",
        default=".",
        help="directory for PNG outputs (default: current directory)",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    csv_path = Path(args.csv)
    if not csv_path.exists():
        sys.stderr.write(f"CSV not found: {csv_path}\n")
        sys.exit(1)
    plot(csv_path, Path(args.out_dir))


if __name__ == "__main__":
    main()
