#!/usr/bin/env python3
"""
Render benchmark chart from a LEAP benchmark CSV.

Default: LEAP-only, two panels (throughput + retransmits) suitable for use
as a blog cover image. TCP can be added with --include-tcp, but in proxy
mode only UDP is shaped, so TCP throughput is not a meaningful comparison
and the chart annotates that.

Trials with integrity_ok=0 (transfer failed) are dropped by default; pass
--include-failed to keep them. Loss rates above --max-loss are dropped
(default 0.10) to avoid plotting regimes where every trial failed.

Usage:
  python3 plot_benchmark.py
  python3 plot_benchmark.py --csv docs/benchmark_results.csv --out docs/benchmark.png
  python3 plot_benchmark.py --include-tcp
  python3 plot_benchmark.py --max-loss 0.20 --include-failed
"""

from __future__ import annotations
import argparse
import sys
from pathlib import Path

try:
    import pandas as pd
    import matplotlib.pyplot as plt
    import matplotlib.ticker as mticker
except ImportError as e:
    sys.stderr.write(
        f"missing dependency: {e}\n"
        "install with: pip install pandas matplotlib\n"
    )
    sys.exit(1)


LEAP_COLOR = "#0b5fff"
TCP_COLOR = "#9aa0a6"
GRID_COLOR = "#e5e7eb"
TEXT_COLOR = "#1f2937"


def human_size(n_bytes: int) -> str:
    if n_bytes >= 1024 * 1024:
        return f"{n_bytes // (1024 * 1024)} MiB"
    if n_bytes >= 1024:
        return f"{n_bytes // 1024} KiB"
    return f"{n_bytes} B"


def load_and_filter(
    csv_path: Path,
    file_size: int | None,
    protocols: list[str],
    include_failed: bool,
    max_loss: float,
) -> tuple[pd.DataFrame, int]:
    df = pd.read_csv(csv_path)

    if file_size is None:
        file_size = int(df["file_size_bytes"].max())
    df = df[df["file_size_bytes"] == file_size]

    df = df[df["protocol"].isin(protocols)]
    df = df[df["loss_rate"] <= max_loss]
    if not include_failed:
        df = df[df["integrity_ok"] == 1]

    if df.empty:
        sys.stderr.write(
            f"no rows match in {csv_path} (size={file_size}, "
            f"protocols={protocols}, max_loss={max_loss}, "
            f"include_failed={include_failed})\n"
        )
        sys.exit(1)

    return df, file_size


def aggregate(df: pd.DataFrame) -> pd.DataFrame:
    return (
        df.groupby(["protocol", "loss_rate"], as_index=False)
        .agg(
            bps_mean=("bytes_per_sec", "mean"),
            bps_std=("bytes_per_sec", "std"),
            rtx_mean=("retransmits", "mean"),
            rtx_std=("retransmits", "std"),
            eff_mean=("efficiency_pct", "mean"),
            n=("trial", "count"),
        )
        .sort_values(["protocol", "loss_rate"])
    )


def style_axis(ax: plt.Axes) -> None:
    ax.set_facecolor("white")
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color("#cbd5e1")
    ax.tick_params(colors=TEXT_COLOR, labelsize=11)
    ax.xaxis.label.set_color(TEXT_COLOR)
    ax.yaxis.label.set_color(TEXT_COLOR)
    ax.title.set_color(TEXT_COLOR)
    ax.grid(True, color=GRID_COLOR, linewidth=1, zorder=0)
    ax.set_axisbelow(True)


def plot(
    csv_path: Path,
    out_path: Path,
    file_size: int | None,
    protocols: list[str],
    include_failed: bool,
    max_loss: float,
) -> None:
    df, file_size = load_and_filter(
        csv_path, file_size, protocols, include_failed, max_loss
    )
    agg = aggregate(df)
    loss_mode = df["loss_mode"].iloc[0]
    n_trials = int(agg["n"].max())

    plt.rcParams.update(
        {
            "font.family": "DejaVu Sans",
            "axes.titlesize": 13,
            "axes.titleweight": "semibold",
            "axes.labelsize": 12,
            "legend.fontsize": 11,
            "figure.facecolor": "white",
        }
    )

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5.5))

    style_map = {
        "leap": {"color": LEAP_COLOR, "marker": "o", "label": "LEAP"},
        "tcp": {"color": TCP_COLOR, "marker": "s", "label": "TCP (passthrough)"},
    }

    for protocol, grp in agg.groupby("protocol"):
        s = style_map.get(protocol, {"color": "black", "marker": "o", "label": protocol.upper()})
        mb_mean = grp["bps_mean"] / (1024 * 1024)
        mb_std = grp["bps_std"].fillna(0) / (1024 * 1024)
        ax1.errorbar(
            grp["loss_rate"] * 100,
            mb_mean,
            yerr=mb_std,
            marker=s["marker"],
            label=s["label"],
            color=s["color"],
            capsize=3,
            linewidth=2,
            markersize=7,
            zorder=3,
        )

    ax1.set_xlabel("Packet loss (%)")
    ax1.set_ylabel("Throughput (MB/s)")
    ax1.set_title(f"Throughput vs packet loss - {human_size(file_size)} transfer")
    ax1.set_yscale("log")
    ax1.yaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{v:g}"))
    ax1.legend(loc="lower left", frameon=False)
    style_axis(ax1)

    leap_agg = agg[agg["protocol"] == "leap"]
    if not leap_agg.empty:
        ax2.errorbar(
            leap_agg["loss_rate"] * 100,
            leap_agg["rtx_mean"],
            yerr=leap_agg["rtx_std"].fillna(0),
            marker="o",
            label="LEAP retransmits",
            color=LEAP_COLOR,
            capsize=3,
            linewidth=2,
            markersize=7,
            zorder=3,
        )
        for _, row in leap_agg.iterrows():
            ax2.annotate(
                f"{int(round(row['rtx_mean']))}",
                xy=(row["loss_rate"] * 100, row["rtx_mean"]),
                xytext=(6, 6),
                textcoords="offset points",
                fontsize=10,
                color=TEXT_COLOR,
            )

    ax2.set_xlabel("Packet loss (%)")
    ax2.set_ylabel("Retransmits per transfer (mean)")
    ax2.set_title("Cost of recovery: retransmits grow super-linearly")
    style_axis(ax2)
    ax2.legend(loc="upper left", frameon=False)

    tcp_in_chart = "tcp" in agg["protocol"].unique()
    subtitle_bits = [
        f"loss_mode = {loss_mode}",
        f"{n_trials} trials per point",
    ]
    if tcp_in_chart and loss_mode == "proxy":
        subtitle_bits.append("TCP not shaped in proxy mode")
    subtitle = "   ·   ".join(subtitle_bits)

    fig.suptitle(
        f"LEAP under controlled packet loss",
        fontsize=16,
        fontweight="bold",
        color=TEXT_COLOR,
        y=0.99,
    )
    fig.text(
        0.5,
        0.93,
        subtitle,
        ha="center",
        fontsize=11,
        color="#4b5563",
    )

    fig.tight_layout(rect=(0, 0, 1, 0.91))

    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=150, bbox_inches="tight", facecolor="white")
    print(f"wrote {out_path}")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Plot LEAP benchmark results.")
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
        help="file size in bytes to plot (default: largest in CSV)",
    )
    p.add_argument(
        "--protocols",
        default="leap",
        help="comma-separated protocols to include (default: leap; e.g. leap,tcp)",
    )
    p.add_argument(
        "--include-tcp",
        action="store_true",
        help="shorthand for --protocols leap,tcp",
    )
    p.add_argument(
        "--include-failed",
        action="store_true",
        help="keep trials with integrity_ok=0 (default: drop)",
    )
    p.add_argument(
        "--max-loss",
        type=float,
        default=0.10,
        help="drop loss rates above this (default: 0.10)",
    )
    return p.parse_args()


def main() -> None:
    args = parse_args()
    csv_path = Path(args.csv)
    out_path = Path(args.out)
    if not csv_path.exists():
        sys.stderr.write(f"CSV not found: {csv_path}\n")
        sys.exit(1)

    protocols = [p.strip().lower() for p in args.protocols.split(",") if p.strip()]
    if args.include_tcp and "tcp" not in protocols:
        protocols.append("tcp")

    plot(csv_path, out_path, args.size, protocols, args.include_failed, args.max_loss)


if __name__ == "__main__":
    main()
