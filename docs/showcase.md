# Showcase copy: LEAP

Use these snippets on GitHub, LinkedIn, and your CV so the story stays consistent.

## GitHub repository description (one line)

**Reliable transport over UDP in Java: sliding window, congestion control, benchmarks, end-to-end SHA-256 integrity.**

Suggested **topics** on the repo: `udp`, `java`, `networking`, `tcp`, `reliable-transport`, `congestion-control`, `benchmark`, `systems-programming`.

## LinkedIn: project entry

**Title:** LEAP: reliable file transfer over UDP (Java)

**Description (short):**

LEAP implements TCP-like reliability on top of UDP: cumulative ACKs, sliding window, fast retransmit, adaptive RTO, and Tahoe-style congestion control using Java `DatagramSocket`. Includes end-to-end SHA-256 verification and a benchmark harness with controlled packet-loss simulation. Documented methodology and measured results; companion blog series on building and debugging the stack.

**Links:** [github.com/bhaveshGhanchi/leap](https://github.com/bhaveshGhanchi/leap) · [Building TCP From Scratch (dev.to)](https://dev.to/bhaveshghanchi/building-tcp-from-scratch-16-why-bother-when-tcp-exists-3aom)

## Resume / CV: bullet points

Pick one or two:

- Implemented reliable transport over UDP in Java (sliding window, cumulative ACKs, fast retransmit, adaptive RTO, congestion control) with per-packet CRC32 and end-to-end SHA-256 integrity verification.
- Built CLI and benchmark harness to measure throughput and retransmits under controlled packet loss; documented limitations of kernel vs userspace loss simulation on different platforms.

## Elevator pitch (~15 seconds)

“I implemented a small reliable protocol over UDP (sliding windows, retransmits, congestion control), with a CLI and benchmarks under simulated loss, plus end-to-end file integrity. It’s in Java for clarity; the README and a blog series walk through the design and what broke along the way.”
