#!/usr/bin/env python3
"""
Render benchmark comparison chart from docs/benchmark_results.csv.

Two panels:
  1. Throughput vs loss rate, one line per protocol, with error bars.
  2. Efficiency vs loss rate, LEAP only.

Usage:
  python3 plot_benchmark.py                        # default paths
  python3 plot_benchmark.py --csv path/to.csv --out path/to.png
  python3 plot_benchmark.py --size 10485760        # filter to one file size
"""

from __future__ import annotations
import argparse
import sys
from pathlib import Path

try:
    import pandas as pd
    import matplotlib.pyplot as plt
except ImportError as e:
    sys.stderr.write(
        f"missing dependency: {e}\n"
        "install with: pip install pandas matplotlib\n"
    )
    sys.exit(1)


def human_size(n_bytes: int) -> str:
    if n_bytes >= 1024 * 1024:
        return f"{n_bytes // (1024 * 1024)} MiB"
    if n_bytes >= 1024:
        return f"{n_bytes // 1024} KiB"
    return f"{n_bytes} B"


def plot(csv_path: Path, out_path: Path, file_size: int | None) -> None:
    df = pd.read_csv(csv_path)

    if file_size is not None:
        df = df[df["file_size_bytes"] == file_size]
    else:
        file_size = int(df["file_size_bytes"].max())
        df = df[df["file_size_bytes"] == file_size]

    if df.empty:
        sys.stderr.write(f"no rows in {csv_path} for size={file_size}\n")
        sys.exit(1)

    agg = (
        df.groupby(["protocol", "loss_rate"])
        .agg(
            bps_mean=("bytes_per_sec", "mean"),
            bps_std=("bytes_per_sec", "std"),
            eff_mean=("efficiency_pct", "mean"),
            eff_std=("efficiency_pct", "std"),
            n=("trial", "count"),
        )
        .reset_index()
    )

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 5))

    colors = {"leap": "tab:blue", "rftp": "tab:blue", "tcp": "tab:orange"}
    for protocol, grp in agg.groupby("protocol"):
        grp = grp.sort_values("loss_rate")
        mb_mean = grp["bps_mean"] / (1024 * 1024)
        mb_std = grp["bps_std"].fillna(0) / (1024 * 1024)
        ax1.errorbar(
            grp["loss_rate"] * 100,
            mb_mean,
            yerr=mb_std,
            marker="o",
            label=protocol.upper(),
            color=colors.get(protocol, None),
            capsize=3,
        )

    ax1.set_xlabel("Packet loss (%)")
    ax1.set_ylabel("Throughput (MB/s)")
    ax1.set_title(f"Throughput vs packet loss — {human_size(file_size)} file")
    ax1.set_yscale("log")
    ax1.grid(True, which="both", alpha=0.3)
    ax1.legend()

    leap_only = agg[agg["protocol"].isin(["leap", "rftp"])].sort_values("loss_rate")
    if not leap_only.empty:
        ax2.errorbar(
            leap_only["loss_rate"] * 100,
            leap_only["eff_mean"],
            yerr=leap_only["eff_std"].fillna(0),
            marker="s",
            label="LEAP",
            color=colors["leap"],
            capsize=3,
        )
    ax2.set_xlabel("Packet loss (%)")
    ax2.set_ylabel("Efficiency (%)")
    ax2.set_title("LEAP efficiency (unique / total packets)")
    ax2.set_ylim(0, 105)
    ax2.grid(True, alpha=0.3)
    ax2.legend()

    fig.suptitle(
        f"LEAP vs TCP — loss_mode={df['loss_mode'].iloc[0]}, "
        f"trials={int(agg['n'].max())}",
        fontsize=11,
    )
    fig.tight_layout(rect=(0, 0, 1, 0.95))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=150)
    print(f"wrote {out_path}")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Plot LEAP vs TCP benchmark.")
    p.add_argument(
        "--csv",
        default="docs/benchmark_results.csv",
        help="input CSV (default: docs/benchmark_results.csv)",
    )
    p.add_argument(
        "--out",
        default="docs/benchmark.png",
        help="output image path (default: docs/benchmark.png)",
    )
    p.add_argument(
        "--size",
        type=int,
        default=None,
        help="file size in bytes to plot (default: largest present in CSV)",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    csv_path = Path(args.csv)
    out_path = Path(args.out)
    if not csv_path.exists():
        sys.stderr.write(f"CSV not found: {csv_path}\n")
        sys.exit(1)
    plot(csv_path, out_path, args.size)


if __name__ == "__main__":
    main()
