# Publishing a GitHub Release

Use this checklist when tagging a version so reviewers can run LEAP without cloning Maven first.

## Prerequisites

- `mvn package` succeeds locally.
- Artifact path: **`target/leap.jar`** (shaded jar from `maven-shade-plugin`, `finalName` = `leap`).
- Requires **Java 11+** on the machine running the jar.

## Steps

1. **Choose a version**  
   Align the git tag with `pom.xml` `<version>` (currently `1.0.0`), e.g. tag `v1.0.0`.

2. **Build the release jar**

   ```bash
   mvn package -DskipTests
   ```

3. **Smoke-test the jar**

   ```bash
   java -jar target/leap.jar --help
   java -jar target/leap.jar receive --port 4040 --output /tmp/leap-test-out &
   java -jar target/leap.jar send README.md --to localhost:4040
   ```

4. **Create a GitHub Release**  
   - Tag: `v1.0.0` (or matching version).  
   - **Attach binary:** upload `target/leap.jar` (rename upload to `leap.jar` in the UI if you prefer a clean name).  
   - Paste the release notes below (README link is set for `bhaveshGhanchi/leap`).

## Release notes template

Copy into the GitHub Release description:

```markdown
## LEAP: quick run

Requires **Java 11+**.

```bash
java -jar leap.jar receive --port 4040 --output ./received &
java -jar leap.jar send path/to/file.bin --to localhost:4040
```

- Full CLI: `java -jar leap.jar --help`
- Source and methodology: [repository README](https://github.com/bhaveshGhanchi/leap#readme)

## What’s in this build

- Reliable UDP transport: sliding window, cumulative ACKs, fast retransmit, adaptive RTO, TCP-Tahoe-style congestion control
- End-to-end SHA-256 integrity on each transfer
- Commands: `send`, `receive`, `benchmark`

**Blog:** [Building TCP From Scratch on dev.to](https://dev.to/bhaveshghanchi/building-tcp-from-scratch-16-why-bother-when-tcp-exists-3aom)
```
