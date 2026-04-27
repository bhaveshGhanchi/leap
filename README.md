# LEAP — Loss-aware End-to-end Acknowledged Protocol

> **Companion blog series:** [Building TCP From Scratch (1/6) on dev.to](https://dev.to/bhaveshghanchi/building-tcp-from-scratch-16-why-bother-when-tcp-exists-3aom). A 6-part walkthrough of this code, with measurements and the bugs and dead ends included.

A TCP-style reliable transport protocol implemented from scratch on top of UDP,
with a CLI, an end-to-end SHA-256 integrity check, and a benchmarking harness
that measures its behavior under controlled packet loss alongside a TCP
baseline.

> **LEAP** stands for **L**oss-aware **E**nd-to-end **A**cknowledged
> **P**rotocol — a single jump across a lossy network with retransmits,
> congestion control, and verified delivery.

---

## Status

| Component | State |
|---|---|
| Reliable transport (sliding window, fast retransmit, RTO, congestion control) | done, tested at 0%/5%/10% loss on localhost |
| End-to-end SHA-256 integrity | done, verified on every transfer |
| CLI (`leap send` / `leap receive` / `leap benchmark`) | done |
| TCP baseline (`TcpServer` / `TcpClient`) | done |
| Loss-simulation modes (`app`, `proxy`, `kernel`) | all three implemented; only `proxy` produces honest measurements on macOS — see "Kernel mode on macOS" below |
| Benchmark orchestrator + CSV writer | done |
| Plotting script (`plot_benchmark.py`) | done; chart in `docs/benchmark.png` |
| Measured sweep | one run: 10 MiB × {0, 1%, 5%, 10%, 20%} × 3 trials, proxy mode |
| `kernel`-mode (pfctl) measurements | attempted on macOS 14; pf+dummynet does not shape `lo0` traffic on this version, so the run produced no real drops. Failure-mode CSV preserved at `docs/benchmark_kernel_macos14_no_drops.csv`. Linux re-run pending. |
| Unit tests | not yet written |

The LEAP numbers in this README are from the proxy-mode sweep, recorded with
`MAX_RETRIES = 5`. The default has since been raised to 10 to survive bursty
loss; a re-measurement with the new ceiling is pending.

---

## What this project is

LEAP is **TCP rebuilt in user space** — sequence numbers, cumulative ACKs,
sliding window, fast retransmit, slow start, congestion avoidance, adaptive
RTO, all running over Java `DatagramSocket`s. The point isn't to be faster
than TCP; it's to:

1. Show what every box in the TCP state machine actually does, with real code.
2. Measure its behavior honestly under packet loss, with real numbers and a
   documented methodology (including what the test environment does and
   doesn't let us measure — see "Kernel mode on macOS" below).

The repository ships with a CLI (`leap send` / `leap receive`), a
benchmark orchestrator that sweeps loss × file-size × trial, three different
loss-simulation modes, and a plotting script that turns the CSV output into a
chart.

---

## Quick start

```bash
# Build (Java 11+, Maven 3.6+)
mvn package

# Send a file
./bin/leap send <file> --to <host:port>

# Receive (in another terminal)
./bin/leap receive --port 4040 --output received/

# Or pass --help to either command for full flag list
./bin/leap send --help
./bin/leap receive --help
```

Example end-to-end transfer:

```bash
./bin/leap receive --port 4040 --output /tmp/in &
./bin/leap send bench_data/test_1m.bin --to localhost:4040
# → Throughput: 15.4 MB/s, Efficiency: 100.0%, integrity verified (sha256=...)
```

---

## Protocol design

### Packet format

```
| version (1B) | type (1B) | seqNum (4B) | payloadLen (4B) | crc32 (4B) | payload |
```

Three packet types: `DATA`, `ACK`, `FIN`. The `FIN` from the client carries
the file's full SHA-256; the server compares its own digest of the assembled
file before acknowledging. Per-packet CRC32 catches in-flight corruption.

### Reliability mechanisms

- Cumulative ACKs (next-expected-byte semantics).
- Sliding window with configurable size (`--window`, default 20).
- Fast retransmit on three duplicate ACKs.
- Adaptive RTO: estimated_RTT + 4 · dev_RTT, capped at 2 s, exponential
  backoff on timeout.
- Selective receiver buffer for out-of-order delivery, in-order flush to disk.
- `MAX_RETRIES = 10` consecutive timeouts on the same window base before the
  client aborts. (TCP has no equivalent ceiling; this is intentional, so a
  truly broken path can't hang forever.)

### Congestion control

TCP-Tahoe-style: slow start → congestion avoidance with AIMD. On loss,
`ssthresh = max(cwnd/2, 4)` and `cwnd = 1`. `ssthresh` floor at 4 prevents
collapse during early-window losses.

### Integrity

Every transfer computes SHA-256 on both ends and the server logs:

```
[OK]  127.0.0.1:54321 — integrity verified (sha256=58acd477...)
```

If the digests disagree, the server logs `[FAIL] integrity mismatch` and the
file is left on disk for inspection.

---

## Repository layout

```
src/main/java/com/leap/
  packet/        Packet wire format and (de)serialization
  file/          FileChunker (sender) and FileAssembler (receiver)
  client/        Client.java — sender + congestion control
  server/        Server.java — multi-session receiver
  benchmark/     TcpServer / TcpClient / Proxy / Benchmark harness
  utils/         Config constants and ChecksumUtils (SHA-256, CRC32)

bin/leap                Shell launcher for the shaded jar
scripts/
  gen_bench_data.sh     Generate 1 / 10 / 100 MiB test files
  loss_up.sh            macOS pfctl/dummynet packet-loss installer
  loss_down.sh          Tear down kernel-level loss
  kernel_sweep.sh       Drive a full kernel-mode sweep (sudo wrapper)
plot_benchmark.py       Render docs/benchmark.png from the CSV
plot_leap.py            Render per-transfer cwnd / ssthresh charts from leap_log.csv
```

---

## Benchmarking

The benchmark sweeps `(loss_rate × file_size × trial)`, runs each cell with
both protocols, and writes one CSV row per transfer.

```bash
# Generate test files (writes to bench_data/, gitignored)
./scripts/gen_bench_data.sh

# Run the default sweep (10 MiB, 5 loss rates, 3 trials, proxy mode)
./bin/leap benchmark --loss-mode proxy --sizes 10m --trials 3 \
    --loss-rates 0,0.01,0.05,0.1,0.2 --protocols leap,tcp

# Plot
python3 plot_benchmark.py
# → writes docs/benchmark.png
```

### Loss-simulation methodology

Honest simulation of packet loss is harder than it looks, so the harness
supports three independent modes and the README is upfront about what each
mode actually models — and which one was actually used to produce the
numbers below.

| Mode | How loss is applied | Valid for | Requires |
|---|---|---|---|
| `app` | Server drops bytes/datagrams at the application layer | LEAP only | nothing |
| `proxy` | Userspace UDP forwarder drops datagrams at rate `p` | LEAP only (see below) | nothing |
| `kernel` | OS-level packet drop (`pfctl`+`dummynet` on macOS, `tc netem` on Linux) | LEAP **and** TCP | `sudo` |

**Why `proxy` doesn't measure TCP under loss.** An app-layer proxy can't
faithfully drop TCP bytes mid-stream — the kernel has already ACK'd them by
the time userspace sees them, so dropping leaves the connection wedged. The
proxy mode is therefore LEAP-only by design; running TCP through it just
measures TCP at 0% loss with one extra hop.

### Measured results — LEAP, 10 MiB, proxy mode, 3 trials per cell

Run on macOS, loopback, `MAX_RETRIES = 5` (the default at the time of
measurement; current default is 10 — see Status section above):

| Loss rate | Throughput | Retransmits | Efficiency | Integrity |
|---:|---:|---:|---:|---:|
| 0%   | 46.2 MB/s | 0    | 100.0% | 3 / 3 |
| 1%   |  7.3 MB/s | ~106 |  99.0% | 3 / 3 |
| 5%   | 342 KB/s  | ~571 |  94.7% | 3 / 3 |
| 10%  |  ~96 KB/s | ~1290|  88.8% | 2 / 3 † |
| 20%  | *aborted* | n/a  |   n/a  | 0 / 3 |

† One of the three trials at 10% loss tripped the `MAX_RETRIES = 5` ceiling
in use during measurement; the other two completed cleanly. The default
ceiling was subsequently raised to 10 to make this less likely on bursty
loss patterns.

Raw CSV: `docs/benchmark_results.csv`. Chart:

![Benchmark](docs/benchmark.png)

There is **no head-to-head TCP-vs-LEAP throughput table in this README** by
design — see the next section for why, and how to produce one honestly on
Linux.

### Kernel mode on macOS — what we tried and why it didn't ship

The orchestrator and helper scripts for kernel-mode loss are committed and
runnable:

```
scripts/loss_up.sh        # pfctl + dummynet pipe on lo0 with plr=p
scripts/loss_down.sh      # tear it all down (also runs on EXIT trap)
scripts/kernel_sweep.sh   # full sudo-wrapped sweep, writes docs/benchmark_kernel.csv
```

A full sweep was attempted on macOS 14 (10 MiB × {0, 1%, 5%, 10%} × 3 trials
× LEAP+TCP). Every run reported the dummynet pipe configured correctly
(`dnctl pipe show` → `plr 0.050000` etc.) and pf enabled, but the resulting
LEAP numbers showed **0 retransmits and 100% efficiency at every loss rate**
— full-speed transfers, no drops actually occurring. `sudo pfctl -si`
reported `Counters: match 0` while traffic was flowing.

This is a known macOS-Sonoma/Sequoia behavior: the kernel's loopback
fast-path bypasses the pf hook on `lo0`, so dummynet rules attached there
load successfully but match nothing. The failure-mode CSV is preserved at
`docs/benchmark_kernel_macos14_no_drops.csv` as evidence — every LEAP row
in that file has `retransmits=0,efficiency_pct=100.00`, identical to the
0% row, confirming no real drops occurred.

**To produce real TCP-vs-LEAP-under-loss numbers, run on Linux**, where
`tc netem` shapes loopback reliably:

```bash
sudo tc qdisc add dev lo root netem loss 5%
./bin/leap benchmark --loss-mode kernel --sizes 10m --trials 3 \
    --loss-rates 0.05 --protocols leap,tcp --csv docs/benchmark_kernel.csv
sudo tc qdisc del dev lo root
```

That sweep is on the roadmap and will be filled in once a Linux box is
available. The macOS scripts are kept in-tree because they're correct on
older macOS and are the right starting point for a Linux port.

---

## Configuration knobs

`src/main/java/com/leap/utils/Config.java`:

```java
PORT              = 4040    // default UDP port
INITIAL_CWND      = 1       // initial congestion window
SSTHRESH          = 16      // initial slow-start threshold
WINDOW_SIZE       = 5       // default sliding window
TIMEOUT_MS        = 200     // initial socket SO_TIMEOUT
CHUNK_SIZE        = 1024    // payload bytes per packet
DUP_ACK_THRESHOLD = 3       // fast-retransmit trigger
MAX_RETRIES       = 10      // consecutive timeouts before client aborts
HASH_LENGTH       = 32      // SHA-256 digest size
```

CLI flags (`--window`, `--chunk`, `--port`, `--loss`, `--debug`) override the
defaults at runtime.

---

## Limitations and what's intentionally out of scope

- **20% loss is the wall.** With the default retry ceiling, LEAP cannot push
  through 20%+ packet loss; transfers abort by design rather than hang. Raise
  `MAX_RETRIES` if you need to survive worse paths.
- **Single sender → single receiver, single file per session.** No multiplex,
  no resume, no SACK.
- **No encryption, no auth, no NAT traversal.** Localhost / LAN tested only.
- **No measured TCP-vs-LEAP head-to-head under loss in this README.** Honest
  comparison requires kernel-level packet drops. macOS 14+ doesn't shape
  `lo0` traffic via pf+dummynet (see "Kernel mode on macOS"), and a Linux
  `tc netem` re-run is on the roadmap. The `proxy` mode TCP numbers in the
  raw CSV (`docs/benchmark_results.csv`) are passthroughs and should not be
  read as a comparison.

---

## Roadmap

- [x] Reliable transfer with retransmission, sliding window, congestion control
- [x] Adaptive RTO (RFC 6298 style)
- [x] End-to-end SHA-256 integrity
- [x] CLI (`send` / `receive` / `benchmark`)
- [x] Benchmarking harness with three loss-simulation modes (`app`, `proxy`, `kernel`)
- [x] Kernel-mode orchestrator (`scripts/kernel_sweep.sh`) and helper scripts
- [x] One measured sweep (LEAP, 10 MiB, proxy mode, 5 loss rates × 3 trials)
- [ ] Re-run proxy sweep with `MAX_RETRIES = 10` to update the table above
- [ ] Linux kernel-mode sweep with `tc netem` to produce the real TCP-vs-LEAP-under-loss table (macOS pf+dummynet does not shape `lo0` on macOS 14+)
- [ ] Unit tests (a localhost integrity smoke test at minimum)
- [ ] Selective ACK (SACK) for tighter recovery on bursty loss
- [ ] Resume support (persist last cumulative ACK on both sides)
- [ ] TCP Cubic / BBR-style congestion control behind a `--cc` flag
- [ ] Encryption (libsodium / Noise) for non-loopback use

---

## Building and running from the IDE

```bash
mvn package -DskipTests        # → target/leap.jar
java -jar target/leap.jar send <file> --to localhost:4040
java -jar target/leap.jar receive --port 4040 --output received/
java -jar target/leap.jar benchmark --help
```

The `bin/leap` launcher is a thin wrapper that resolves the jar relative to
its own location, so you can put `bin/` on `PATH` and call `leap` from
anywhere.
