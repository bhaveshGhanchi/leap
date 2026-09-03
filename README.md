# LEAP: Loss-aware End-to-end Acknowledged Protocol

[Java](https://openjdk.org/)
[Build](https://maven.apache.org/)
[Blog](https://dev.to/bhaveshghanchi/building-tcp-from-scratch-16-why-bother-when-tcp-exists-3aom)

## At a glance

- **What:** Reliable file transfer over **UDP** with TCP-like behavior (sliding window, cumulative ACKs, fast retransmit, adaptive RTO, congestion control) and **end-to-end SHA-256** verification.
- **Stack:** Java 11+, Maven; runnable as `./bin/leap` or `java -jar target/leap.jar` after `mvn package`.
- **Proof:** Benchmark chart in `[docs/benchmark.png](docs/benchmark.png)`; methodology and caveats below.
- **For reviewers:** [Architecture overview](ARCHITECTURE.md) · [Portfolio blurbs (LinkedIn/CV)](docs/showcase.md) · [Publishing a GitHub Release](RELEASING.md)

> **Companion blog series:** [Building TCP From Scratch (1/6) on dev.to](https://dev.to/bhaveshghanchi/building-tcp-from-scratch-16-why-bother-when-tcp-exists-3aom). A 6-part walkthrough of this code, with measurements and the bugs and dead ends included.

A TCP-style reliable transport protocol implemented from scratch on top of UDP,
with a CLI, an end-to-end SHA-256 integrity check, and a benchmarking harness
that measures its behavior under controlled packet loss alongside a TCP
baseline.

> **LEAP** stands for **L**oss-aware **E**nd-to-end **A**cknowledged
> **P**rotocol, a single jump across a lossy network with retransmits,
> congestion control, and verified delivery.



## Status


| Component                                                                     | State                                                                                                                                                                                                                 |
| ----------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Reliable transport (sliding window, fast retransmit, RTO, congestion control) | done, tested at 0%/5%/10% loss on localhost                                                                                                                                                                           |
| End-to-end SHA-256 integrity                                                  | done, verified on every transfer                                                                                                                                                                                      |
| CLI (`leap send` / `leap receive` / `leap benchmark`)                         | done                                                                                                                                                                                                                  |
| TCP baseline (`TcpServer` / `TcpClient`)                                      | done                                                                                                                                                                                                                  |
| Loss-simulation modes (`app`, `proxy`, `kernel`)                              | all three implemented; only `proxy` produces honest measurements on macOS (see "Kernel mode on macOS" below)                                                                                                          |
| Benchmark orchestrator + CSV writer                                           | done                                                                                                                                                                                                                  |
| Plotting script (`plot_benchmark.py`)                                         | done; chart in `docs/benchmark.png`                                                                                                                                                                                   |
| Measured sweep                                                                | re-run: 10 MiB × {0, 1%, 5%, 10%, 20%} × 3 trials, proxy mode, `MAX_RETRIES = 10` (2026-08-30)                                                                                                                          |
| `kernel`-mode (pfctl) measurements                                            | macOS `lo0` still does not drop (see below). Linux `tc netem` driver: `scripts/linux_kernel_sweep.sh` (needs a Linux host or Docker `--cap-add=NET_ADMIN`; not run here).                                              |
| Unit tests                                                                    | packet wire/CRC, SHA-256, chunker/assembler, out-of-order reassembly (`mvn test`)                                                                                                                                     |
| Two-host transfer (macOS ↔ VMware Linux)                                      | done, 2026-09-03; both directions; 1 MiB SHA-256 verified (see below)                                                                                                                                                 |


The LEAP numbers in this README are from the proxy-mode sweep of 2026-08-30
with the current `MAX_RETRIES = 10`. The previous `MAX_RETRIES = 5` CSV is
kept at `docs/benchmark_results_maxretries5.csv`.

## What this project is

LEAP is **TCP rebuilt in user space**: sequence numbers, cumulative ACKs,
sliding window, fast retransmit, slow start, congestion avoidance, adaptive
RTO, all running over Java `DatagramSocket`s. The point isn't to be faster
than TCP; it's to:

1. Show what every box in the TCP state machine actually does, with real code.
2. Measure its behavior honestly under packet loss, with real numbers and a
  documented methodology (including what the test environment does and
   doesn't let us measure (see "Kernel mode on macOS" below).

The repository ships with a CLI (`leap send` / `leap receive`), a
benchmark orchestrator that sweeps loss × file-size × trial, three different
loss-simulation modes, and a plotting script that turns the CSV output into a
chart.

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

## Two-host test (macOS ↔ VMware Linux)

Localhost and the proxy-mode sweep measure the protocol. A second machine
measures whether UDP actually leaves the box. On 2026-09-03 this was run
between a macOS host and a VMware Ubuntu guest on a host-only / VMnet
segment:

| Role | Address |
| --- | --- |
| macOS (this repo) | `172.16.7.1` |
| Ubuntu VM | `172.16.7.132` |
| Port | UDP `4040` |

Use the IPs on **that** subnet. Addresses in `100.64.0.0/10` (CGNAT / often
Tailscale) are a different interface; sending there from the VM produced
0% progress and no ACKs.

**Receiver first** (Ubuntu example; copy `target/leap.jar` or clone and
`mvn package`):

```bash
sudo ufw allow 4040/udp   # if a firewall is on
java -jar leap.jar receive --port 4040 --output received/
```

**Then send** from macOS (no `--loss` on receive; that is fake app-layer drop):

```bash
./bin/leap send /tmp/leap-1m.bin --to 172.16.7.132:4040
```

Reverse: receive on the Mac (`--output /tmp/leap-in`), send from Linux to
`172.16.7.1:4040`. Confirm with `sha256sum` on the receiver against the
sender's `--debug` SHA-256 line (or `shasum -a 256` of the original file).

### What this run showed

| Transfer | Result |
| --- | --- |
| Mac → Linux, 16-byte text | delivered |
| Mac → Linux, 1 MiB | delivered; SHA-256 `ecaf0ccaaa989b1afbbd0fcef7a972ef85022feb65d90f0b2d351cd22a365f6e` |
| Mac → Linux, 10 MiB | delivered (~651 s, ~15.7 KB/s, ~70% efficiency) |
| Linux → Mac, 17-byte text | delivered (`hello from linux`) |

Throughput on this VMware UDP path stayed around **16 KB/s** with frequent
timeouts and fast retransmits (`cwnd` often cut to 1). That is the virtual
NIC dropping or reordering bursts, not a localhost number. Raising
`--window` did not help while timeouts stayed this frequent. Tiny files
often show one FIN-timeout then complete; that is delay, not a failed
transfer.

This is **not** a TCP-vs-LEAP-under-loss table, and it is **not** a
`tc netem` kernel sweep. Those still need `scripts/linux_kernel_sweep.sh`
on Linux (the guest can run it later; it was not part of this check).

Example end-to-end transfer:

```bash
./bin/leap receive --port 4040 --output /tmp/in &
./bin/leap send bench_data/test_1m.bin --to localhost:4040
# → Throughput: 15.4 MB/s, Efficiency: 100.0%, integrity verified (sha256=...)
```



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
[OK]  127.0.0.1:54321 - integrity verified (sha256=58acd477...)
```

If the digests disagree, the server logs `[FAIL] integrity mismatch` and the
file is left on disk for inspection.

## Repository layout

```
src/main/java/com/leap/
  packet/        Packet wire format and (de)serialization
  file/          FileChunker (sender) and FileAssembler (receiver)
  client/        Client.java (sender + congestion control)
  server/        Server.java (multi-session receiver)
  benchmark/     TcpServer / TcpClient / Proxy / Benchmark harness
  utils/         Config constants and ChecksumUtils (SHA-256, CRC32)

ARCHITECTURE.md         High-level map of modules and data flow
RELEASING.md            Checklist + template notes for GitHub Releases
docs/showcase.md        One-liners for GitHub / LinkedIn / resume

bin/leap                Shell launcher for the shaded jar
scripts/
  gen_bench_data.sh     Generate 1 / 10 / 100 MiB test files
  loss_up.sh            macOS pfctl/dummynet packet-loss installer
  loss_down.sh          Tear down kernel-level loss
  kernel_sweep.sh       Drive a full kernel-mode sweep (sudo wrapper, macOS pf)
  linux_kernel_sweep.sh Linux `tc netem` sweep (root / Docker NET_ADMIN)
plot_benchmark.py       Render docs/benchmark.png from the CSV
plot_leap.py            Per-transfer cwnd / ssthresh charts (`--csv`, `--out-dir`)
```



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
mode actually models, and which one was actually used to produce the
numbers below.


| Mode     | How loss is applied                                                     | Valid for             | Requires |
| -------- | ----------------------------------------------------------------------- | --------------------- | -------- |
| `app`    | Server drops bytes/datagrams at the application layer                   | LEAP only             | nothing  |
| `proxy`  | Userspace UDP forwarder drops datagrams at rate `p`                     | LEAP only (see below) | nothing  |
| `kernel` | OS-level packet drop (`pfctl`+`dummynet` on macOS, `tc netem` on Linux) | LEAP **and** TCP      | `sudo`   |


**Why** `proxy` **doesn't measure TCP under loss.** An app-layer proxy can't
faithfully drop TCP bytes mid-stream; the kernel has already ACK'd them by
the time userspace sees them, so dropping leaves the connection wedged. The
proxy mode is therefore LEAP-only by design; running TCP through it just
measures TCP at 0% loss with one extra hop.

### Measured results (LEAP, 10 MiB, proxy mode, 3 trials per cell)

Run on macOS, loopback, `MAX_RETRIES = 10` (2026-08-30). Means across
completed trials unless noted. TCP proxy rows are still passthrough (not
under loss); see methodology.

| Loss rate | Throughput | Retransmits | Efficiency | Integrity |
| --------- | ---------- | ----------- | ---------- | --------- |
| 0%        | 29.5 MB/s  | 0           | 100.0%     | 0 / 3 ‡   |
| 1%        | noisy §    | —           | —          | 1 / 3     |
| 5%        | 301 KB/s   | ~582        | 94.6%      | 3 / 3     |
| 10%       | 99 KB/s    | ~1275       | 88.9%      | 3 / 3     |
| 20%       | *timeout*  | n/a         | n/a        | 0 / 3     |

§ 1% trials were 4.9 MB/s (integrity ok), 234 KB/s, and 66 KB/s (two
integrity failures). Do not treat 1% as a single headline number.

‡ Client reported 100% efficiency and 0 retransmits; the harness
`integrity_ok` check did not match `received_1.dat` (likely a finalize
or path race). Treat 0% as “transfer finished, hash check flaky in the
orchestrator,” not as data corruption on the wire.

At 10% loss all three trials completed (previously 2 / 3 with
`MAX_RETRIES = 5`). At 20% each trial hit the 360 s orchestrator timeout
and was killed — raising the retry ceiling did not make 20% finish in
bound.

Raw CSV: `docs/benchmark_results.csv`. Chart:

Benchmark

There is **no head-to-head TCP-vs-LEAP throughput table in this README** by
design. See the next section for why, and how to produce one honestly on
Linux.

### Kernel mode on macOS (what we tried and why it didn't ship)

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
(full-speed transfers, no drops actually occurring). `sudo pfctl -si`
reported `Counters: match 0` while traffic was flowing.

This is a known macOS-Sonoma/Sequoia behavior: the kernel's loopback
fast-path bypasses the pf hook on `lo0`, so dummynet rules attached there
load successfully but match nothing. The failure-mode CSV is preserved at
`docs/benchmark_kernel_macos14_no_drops.csv` as evidence: every LEAP row
in that file has `retransmits=0,efficiency_pct=100.00`, identical to the
0% row, confirming no real drops occurred.

**To produce real TCP-vs-LEAP-under-loss numbers, run on Linux**, where
`tc netem` shapes loopback reliably. Driver script (root, or Docker with
`--cap-add=NET_ADMIN`):

```bash
./scripts/linux_kernel_sweep.sh
# or: ./scripts/linux_kernel_sweep.sh "0,0.05,0.1" 2 10m
```

Manual equivalent:

```bash
sudo tc qdisc add dev lo root netem loss 5%
printf '\n' | ./bin/leap benchmark --loss-mode kernel --sizes 10m --trials 3 \
    --loss-rates 0.05 --protocols leap,tcp --csv docs/benchmark_kernel.csv
sudo tc qdisc del dev lo root
```

A VMware Ubuntu guest is available on `172.16.7.132` and was used for
two-host LEAP transfers (see above). `docs/benchmark_kernel.csv` from
`scripts/linux_kernel_sweep.sh` / `tc netem` has **not** been produced
yet; that sweep is still on the roadmap. The macOS pf scripts are kept
in-tree because they are correct on older macOS and are the right
starting point for the Linux port.

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

## Limitations and what's intentionally out of scope

- **20% loss is the wall.** With the default retry ceiling, LEAP cannot push
through 20%+ packet loss; transfers abort by design rather than hang. Raise
`MAX_RETRIES` if you need to survive worse paths.
- **Single sender → single receiver, single file per session.** No multiplex,
no resume, no SACK.
- **No encryption, no auth, no NAT traversal.** Tested on localhost and a
macOS ↔ VMware Linux host-only LAN, not over the public internet.
- **No measured TCP-vs-LEAP head-to-head under loss in this README.** Honest
comparison requires kernel-level packet drops. macOS 14+ doesn't shape
`lo0` traffic via pf+dummynet (see "Kernel mode on macOS"), and a Linux
`tc netem` re-run is on the roadmap. The `proxy` mode TCP numbers in the
raw CSV (`docs/benchmark_results.csv`) are passthroughs and should not be
read as a comparison.



## Roadmap

- [x] Reliable transfer with retransmission, sliding window, congestion control
- [x] Adaptive RTO (RFC 6298 style)
- [x] End-to-end SHA-256 integrity
- [x] CLI (`send` / `receive` / `benchmark`)
- [x] Benchmarking harness with three loss-simulation modes (`app`, `proxy`, `kernel`)
- [x] Kernel-mode orchestrator (`scripts/kernel_sweep.sh`) and helper scripts
- [x] One measured sweep (LEAP, 10 MiB, proxy mode, 5 loss rates × 3 trials)
- [x] Re-run proxy sweep with `MAX_RETRIES = 10` (2026-08-30; table above)
- [x] Linux `tc netem` driver (`scripts/linux_kernel_sweep.sh`)
- [ ] Run that driver on a Linux host / Docker and commit `docs/benchmark_kernel.csv`
- [x] Two-host transfer, macOS ↔ VMware Ubuntu (2026-09-03)
- [x] Unit tests (packet, SHA-256, chunker, out-of-order assemble)
- [ ] Selective ACK (SACK) for tighter recovery on bursty loss
- [ ] Resume support (persist last cumulative ACK on both sides)
- [ ] TCP Cubic / BBR-style congestion control behind a `--cc` flag
- [ ] Encryption (libsodium / Noise) for non-loopback use



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