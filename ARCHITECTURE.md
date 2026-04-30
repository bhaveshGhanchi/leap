# LEAP architecture

High-level map of the codebase for reviewers who want the shape before diving into classes.

## Overview

```
                    ┌─────────────┐                      ┌─────────────┐
                    │   Client    │      UDP datagrams   │   Server    │
                    │  (sender)   │ ◄──────────────────► │ (receiver)  │
                    └─────────────┘                      └─────────────┘
```

- **One UDP socket** per sender (`Client`) and **one session** per receiver connection on the server (`Server`).
- **One file per transfer session**: the sender chunks a file, sequences packets, and waits for ACKs; the receiver reassembles in order and verifies integrity when the sender signals completion.

## Wire format

Packets are big-endian. Header size is **14 bytes** (`Config.HEADER_SIZE`): version, type, sequence number, payload length, CRC-32 over the payload, then raw payload.

See `com.leap.packet.Packet` for serialization; types include DATA, ACK, and FIN (FIN carries the sender’s full-file SHA-256 for comparison on the receiver).

## Sender (`com.leap.client.Client`)

- Reads the file via **`FileChunker`**, wraps chunks in **`Packet`** instances, sends within a **sliding window**.
- **ACK handling:** cumulative semantics (next expected byte / seq). Advances the window and RTT estimation.
- **Loss recovery:** fast retransmit after duplicate ACK threshold; timeout-based retransmit with adaptive RTO and exponential backoff.
- **Congestion control:** slow start, congestion avoidance (AIMD), reaction to loss by shrinking window / `ssthresh` (Tahoe-style behavior as implemented in repo).

## Receiver (`com.leap.server.Server`)

- Accepts datagrams, parses **`Packet`**, validates CRC32.
- **Out-of-order buffering:** holds chunks until they can be written **in order** to disk via **`FileAssembler`**.
- On **FIN**, compares assembled-file digest to the sender’s SHA-256 and logs success or failure.

## Benchmark harness (`com.leap.benchmark`)

- **`Benchmark`:** orchestrates sweeps over loss rate, file size, trials, and protocol (LEAP vs TCP baseline).
- **`Proxy`:** userspace UDP forwarding with configurable drop probability (useful for LEAP under loss on hosts where kernel shaping is awkward).
- **`TcpServer` / `TcpClient`:** TCP baseline for comparison where the harness supports it (see README for proxy vs kernel modes).

## Configuration

Defaults and tuning knobs live in **`com.leap.utils.Config`**; CLI flags on `send` / `receive` / `benchmark` override many of these at runtime.

## Further reading

- **Deep dive + measurements:** [README.md](README.md)  
- **Portfolio blurbs:** [docs/showcase.md](docs/showcase.md)  
- **Shipping jars:** [RELEASING.md](RELEASING.md)
