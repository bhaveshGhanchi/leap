package com.leap.benchmark;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Benchmark orchestrator: sweeps loss-rate × file-size × protocol × trial,
 * runs each transfer by spawning LEAP or TCP server/client subprocesses,
 * and appends one CSV row per transfer to docs/benchmark_results.csv.
 *
 * Loss modes:
 *   app    - loss applied at the application layer (LEAP server's built-in
 *            --loss and TcpServer's --loss). Included as a methodology
 *            contrast; TCP numbers here are NOT a fair protocol comparison.
 *   proxy  - Proxy.java sits in between. Drops UDP datagrams and TCP chunks
 *            at the configured probability. No root required.
 *   kernel - Expects the operator to have run scripts/loss_up.sh beforehand.
 *            This harness doesn't touch pfctl; it just transfers and measures.
 *
 * Output columns:
 *   protocol,loss_mode,loss_rate,file_size_bytes,trial,
 *   time_ms,bytes_per_sec,retransmits,efficiency_pct,integrity_ok
 */
public class Benchmark {

    // Ports used for each configuration.
    private static final int LEAP_SERVER_PORT   = 4040;
    private static final int LEAP_PROXY_PORT    = 4041;
    private static final int TCP_SERVER_PORT    = 5050;
    private static final int TCP_PROXY_PORT     = 5051;

    // Hard upper bound on any single transfer. If a run exceeds this, we
    // kill the subprocesses and record it as failed. Scales roughly with
    // file size in the caller.
    private static final long PER_RUN_TIMEOUT_MS_BASE = 60_000L;

    static String outputDir = "docs";
    static String benchDataDir = "bench_data";
    static String jar;

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help")) {
            printUsage();
            return;
        }

        String lossMode = opts.getOrDefault("loss-mode", "proxy");
        List<Double> lossRates = parseLossRates(opts.getOrDefault("loss-rates", "0,0.01,0.05,0.1,0.2,0.3"));
        List<String> sizes = Arrays.asList(opts.getOrDefault("sizes", "1m,10m,100m").split(","));
        int trials = Integer.parseInt(opts.getOrDefault("trials", "3"));
        List<String> protocols = Arrays.asList(opts.getOrDefault("protocols", "leap,tcp").split(","));
        outputDir = opts.getOrDefault("out", "docs");
        benchDataDir = opts.getOrDefault("data", "bench_data");
        String csvPath = opts.getOrDefault("csv", outputDir + "/benchmark_results.csv");

        jar = locateJar();

        new File(outputDir).mkdirs();
        boolean writeHeader = !new File(csvPath).exists();
        try (FileWriter csv = new FileWriter(csvPath, true)) {
            if (writeHeader) {
                csv.write("protocol,loss_mode,loss_rate,file_size_bytes,trial,"
                        + "time_ms,bytes_per_sec,retransmits,efficiency_pct,integrity_ok\n");
            }

            if (lossMode.equals("kernel")) {
                System.out.println("[bench] kernel mode - expecting pfctl already active.");
                System.out.println("        For each loss rate, this harness will PAUSE and ask you");
                System.out.println("        to run scripts/loss_up.sh with that rate, then press Enter.");
            }

            for (double loss : lossRates) {
                if (lossMode.equals("kernel") && loss > 0) {
                    System.out.println();
                    System.out.println("===================================================");
                    System.out.println("Next loss rate: " + loss);
                    System.out.println("In another terminal, run:");
                    System.out.println("  sudo ./scripts/loss_down.sh");
                    System.out.println("  sudo ./scripts/loss_up.sh " + loss);
                    System.out.println("Then press ENTER here to continue.");
                    System.out.println("===================================================");
                    System.in.read();
                } else if (lossMode.equals("kernel") && loss == 0) {
                    System.out.println();
                    System.out.println("===================================================");
                    System.out.println("Loss rate 0 - ensure pfctl is DISABLED.");
                    System.out.println("Run: sudo ./scripts/loss_down.sh");
                    System.out.println("Then press ENTER here to continue.");
                    System.out.println("===================================================");
                    System.in.read();
                }

                for (String size : sizes) {
                    File dataFile = new File(benchDataDir + "/test_" + size + ".bin");
                    if (!dataFile.exists()) {
                        System.err.println("[skip] missing data file: " + dataFile);
                        continue;
                    }
                    long sizeBytes = dataFile.length();

                    for (String protocol : protocols) {
                        for (int t = 1; t <= trials; t++) {
                            System.out.println();
                            System.out.printf("[run] protocol=%s loss=%s size=%s trial=%d%n",
                                    protocol, loss, size, t);

                            RunResult r;
                            try {
                                if (protocol.equals("leap")) {
                                    r = runLeap(loss, dataFile, lossMode);
                                } else {
                                    r = runTcp(loss, dataFile, lossMode);
                                }
                            } catch (Exception e) {
                                System.err.println("[error] " + e.getMessage());
                                r = RunResult.failed();
                            }

                            csv.write(String.format(
                                    "%s,%s,%s,%d,%d,%d,%d,%d,%.2f,%d%n",
                                    protocol, lossMode, loss, sizeBytes, t,
                                    r.timeMs, r.bytesPerSec, r.retransmits,
                                    r.efficiencyPct, r.integrityOk ? 1 : 0));
                            csv.flush();

                            System.out.printf("       time=%d ms throughput=%s eff=%.1f%% integrity=%s%n",
                                    r.timeMs, humanRate(r.bytesPerSec),
                                    r.efficiencyPct, r.integrityOk);
                        }
                    }
                }
            }
        }

        System.out.println();
        System.out.println("[bench] wrote " + csvPath);
    }

    private static RunResult runLeap(double loss, File dataFile, String lossMode) throws Exception {
        int clientPort = LEAP_SERVER_PORT;
        double serverAppLoss = 0;
        Process proxy = null;
        Process server = null;
        StreamWatcher serverWatcher = null;

        File outDir = Files.createTempDirectory("leap_bench_").toFile();
        File outFile = new File(outDir, "received_1.dat");

        try {
            if (lossMode.equals("app")) {
                serverAppLoss = loss;
            } else if (lossMode.equals("proxy") && loss > 0) {
                proxy = spawnJava("com.leap.benchmark.Proxy",
                        "--protocol", "udp",
                        "--listen", String.valueOf(LEAP_PROXY_PORT),
                        "--target-host", "localhost",
                        "--target-port", String.valueOf(LEAP_SERVER_PORT),
                        "--loss", String.valueOf(loss),
                        "--quiet");
                clientPort = LEAP_PROXY_PORT;
                Thread.sleep(200);
            }
            // kernel mode: loss is applied by pfctl outside; both ports 4040 traffic hit it.

            server = spawnJava("com.leap.server.Server",
                    "--port", String.valueOf(LEAP_SERVER_PORT),
                    "--output", outDir.getAbsolutePath(),
                    "--loss", String.valueOf(serverAppLoss));
            // Watch server stdout so we can wait for the session-complete line
            // before tearing the server down (otherwise we race the final flush).
            serverWatcher = new StreamWatcher(server.getInputStream(),
                    "integrity verified|integrity mismatch|session closed");
            serverWatcher.start();
            Thread.sleep(300);

            Process client = spawnJava("com.leap.client.Client",
                    "--file", dataFile.getAbsolutePath(),
                    "--host", "localhost",
                    "--port", String.valueOf(clientPort),
                    "--window", "20",
                    "--chunk", "1024");

            long timeoutMs = timeoutFor(dataFile.length());
            String clientOut = drainWithTimeout(client, timeoutMs);
            if (!client.waitFor(5, TimeUnit.SECONDS)) client.destroyForcibly();
            // Wait for server to print "integrity verified" (or equivalent) so
            // its FileAssembler has finalized the on-disk output.
            serverWatcher.waitForMatch(10_000);
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
            if (proxy != null) { proxy.destroy(); if (!proxy.waitFor(5, TimeUnit.SECONDS)) proxy.destroyForcibly(); }

            long timeMs = parseLong(clientOut, "Transfer time\\s*:\\s*(\\d+) ms");
            long totalPackets = parseLong(clientOut, "Total packets\\s*:\\s*(\\d+)");
            long packetsSent = parseLong(clientOut, "Packets sent\\s*:\\s*(\\d+)");
            long fastRetx = parseLong(clientOut, "Fast retransmits:\\s*(\\d+)");
            double eff = parseDouble(clientOut, "Efficiency\\s*:\\s*([0-9.]+)%");

            long bps = timeMs > 0 ? (dataFile.length() * 1000L / timeMs) : 0;
            long retransmits = Math.max(0, totalPackets - packetsSent);
            boolean integrity = outFile.exists()
                    && outFile.length() == dataFile.length()
                    && filesEqualBySha256(dataFile, outFile);

            return new RunResult(timeMs, bps, retransmits, eff, integrity);
        } finally {
            if (server != null && server.isAlive()) server.destroyForcibly();
            if (proxy != null && proxy.isAlive()) proxy.destroyForcibly();
            deleteRecursively(outDir);
        }
    }

    private static RunResult runTcp(double loss, File dataFile, String lossMode) throws Exception {
        int clientPort = TCP_SERVER_PORT;
        double serverAppLoss = 0;
        Process proxy = null;
        Process server = null;

        File outFile = File.createTempFile("tcp_bench_", ".bin");
        outFile.deleteOnExit();

        try {
            if (lossMode.equals("app")) {
                serverAppLoss = loss;
            } else if (lossMode.equals("proxy") && loss > 0) {
                proxy = spawnJava("com.leap.benchmark.Proxy",
                        "--protocol", "tcp",
                        "--listen", String.valueOf(TCP_PROXY_PORT),
                        "--target-host", "localhost",
                        "--target-port", String.valueOf(TCP_SERVER_PORT),
                        "--loss", String.valueOf(loss),
                        "--quiet");
                clientPort = TCP_PROXY_PORT;
                Thread.sleep(200);
            }

            server = spawnJava("com.leap.benchmark.TcpServer",
                    "--port", String.valueOf(TCP_SERVER_PORT),
                    "--output", outFile.getAbsolutePath(),
                    "--loss", String.valueOf(serverAppLoss),
                    "--quiet");
            Thread.sleep(300);

            long t0 = System.currentTimeMillis();
            Process client = spawnJava("com.leap.benchmark.TcpClient",
                    "--file", dataFile.getAbsolutePath(),
                    "--host", "localhost",
                    "--port", String.valueOf(clientPort),
                    "--quiet");

            long timeoutMs = timeoutFor(dataFile.length());
            String clientOut = drainWithTimeout(client, timeoutMs);
            if (!client.waitFor(5, TimeUnit.SECONDS)) client.destroyForcibly();
            String serverOut = drainWithTimeout(server, 5_000);
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
            if (proxy != null) { proxy.destroy(); if (!proxy.waitFor(5, TimeUnit.SECONDS)) proxy.destroyForcibly(); }
            long t1 = System.currentTimeMillis();

            long elapsed = parseLong(serverOut, "elapsed_ms=(\\d+)");
            if (elapsed == 0) elapsed = t1 - t0;
            long bps = dataFile.length() * 1000L / Math.max(elapsed, 1);

            boolean integrity;
            if (lossMode.equals("app") && serverAppLoss > 0) {
                // App-level TCP loss drops bytes at the server before write,
                // so an integrity match is impossible by construction.
                integrity = false;
            } else {
                integrity = outFile.length() == dataFile.length()
                        && filesEqualBySha256(dataFile, outFile);
            }

            return new RunResult(elapsed, bps, 0, 0.0, integrity);
        } finally {
            if (server != null && server.isAlive()) server.destroyForcibly();
            if (proxy != null && proxy.isAlive()) proxy.destroyForcibly();
            outFile.delete();
        }
    }

    private static Process spawnJava(String mainClass, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(jar);
        cmd.add(mainClass);
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static String drain(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Drain stdout of a process. If it doesn't exit within timeoutMs, kill
     * it and return whatever was captured so far. Prevents one stuck run
     * from hanging the whole benchmark.
     */
    private static String drainWithTimeout(Process p, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (sb) { sb.append(line).append('\n'); }
                }
            } catch (Exception ignored) {}
        }, "bench-drain");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            System.err.println("[timeout] run exceeded " + timeoutMs + " ms, killing");
            p.destroyForcibly();
        }
        reader.join(2000);
        synchronized (sb) { return sb.toString(); }
    }

    /**
     * Consumes an InputStream in a background thread and latches on the
     * first line matching a regex. Callers use waitForMatch(timeout) to
     * synchronize teardown with "session done" signals from subprocesses.
     */
    static class StreamWatcher {
        private final java.io.InputStream stream;
        private final Pattern pattern;
        private final StringBuilder buffer = new StringBuilder();
        private final Object gate = new Object();
        private volatile boolean matched = false;
        private Thread thread;

        StreamWatcher(java.io.InputStream stream, String regex) {
            this.stream = stream;
            this.pattern = Pattern.compile(regex);
        }

        void start() {
            thread = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(stream))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (buffer) { buffer.append(line).append('\n'); }
                        if (!matched && pattern.matcher(line).find()) {
                            synchronized (gate) {
                                matched = true;
                                gate.notifyAll();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }, "bench-stream-watcher");
            thread.setDaemon(true);
            thread.start();
        }

        boolean waitForMatch(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (gate) {
                while (!matched) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) return false;
                    gate.wait(remaining);
                }
            }
            return true;
        }

        String buffered() {
            synchronized (buffer) { return buffer.toString(); }
        }
    }

    private static long timeoutFor(long fileSizeBytes) {
        // ~30s per MiB, clamped to [PER_RUN_TIMEOUT_MS_BASE, 10 min]
        long scaled = PER_RUN_TIMEOUT_MS_BASE + (fileSizeBytes / (1024 * 1024)) * 30_000L;
        return Math.min(scaled, 10 * 60_000L);
    }

    private static long parseLong(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) return Long.parseLong(m.group(1));
        return 0;
    }

    private static double parseDouble(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (m.find()) return Double.parseDouble(m.group(1));
        return 0;
    }

    private static List<Double> parseLossRates(String s) {
        List<Double> out = new ArrayList<>();
        for (String p : s.split(",")) {
            out.add(Double.parseDouble(p.trim()));
        }
        return out;
    }

    private static String locateJar() {
        String candidate = "target/leap.jar";
        if (new File(candidate).exists()) return new File(candidate).getAbsolutePath();
        try {
            return new File(Benchmark.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (Exception e) {
            return candidate;
        }
    }

    private static String humanRate(long bps) {
        if (bps >= 1024 * 1024) return String.format("%.1f MB/s", bps / 1048576.0);
        if (bps >= 1024) return String.format("%.1f KB/s", bps / 1024.0);
        return bps + " B/s";
    }

    private static boolean filesEqualBySha256(File a, File b) {
        try {
            byte[] da = com.leap.utils.ChecksumUtils.sha256Bytes(a.getAbsolutePath());
            byte[] db = com.leap.utils.ChecksumUtils.sha256Bytes(b.getAbsolutePath());
            return Arrays.equals(da, db);
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) { m.put("help", "true"); continue; }
            if (a.startsWith("--") && i + 1 < args.length) {
                m.put(a.substring(2), args[++i]);
            }
        }
        return m;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  leap benchmark [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --loss-mode <mode>    app | proxy | kernel         (default: proxy)");
        System.out.println("  --loss-rates <list>   comma-separated              (default: 0,0.01,0.05,0.1,0.2,0.3)");
        System.out.println("  --sizes <list>        1m,10m,100m                  (default: 1m,10m,100m)");
        System.out.println("  --trials <n>          trials per cell              (default: 3)");
        System.out.println("  --protocols <list>    leap,tcp                     (default: leap,tcp)");
        System.out.println("  --out <dir>           output dir for CSV           (default: docs)");
        System.out.println("  --data <dir>          dir with test_*.bin files    (default: bench_data)");
        System.out.println();
        System.out.println("Kernel mode expects you to run scripts/loss_up.sh manually.");
        System.out.println("Proxy mode is self-contained. App mode is reference-only for TCP.");
    }

    static class RunResult {
        final long timeMs;
        final long bytesPerSec;
        final long retransmits;
        final double efficiencyPct;
        final boolean integrityOk;
        RunResult(long t, long b, long r, double e, boolean i) {
            timeMs = t; bytesPerSec = b; retransmits = r; efficiencyPct = e; integrityOk = i;
        }
        static RunResult failed() { return new RunResult(0, 0, 0, 0, false); }
    }
}
