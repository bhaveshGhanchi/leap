package com.leap.benchmark;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import com.leap.utils.ChecksumUtils;

/**
 * Minimal TCP file receiver used as the LEAP benchmark baseline.
 *
 * Wire format on a connection:
 *   [8 bytes, big-endian] file size in bytes
 *   [N bytes]             file payload
 *
 * After receiving N bytes, server computes SHA-256, writes the file,
 * and prints one summary line then exits. One file per invocation keeps
 * the orchestrator simple.
 *
 * Loss mode "app": drops incoming bytes at the application layer at the
 * configured probability per read. NOT a realistic TCP loss model - the
 * kernel ACKs those bytes anyway, so congestion control never reacts.
 * Included only for the methodology-contrast section of the writeup.
 */
public class TcpServer {
    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        int port = Integer.parseInt(opts.getOrDefault("port", "5050"));
        String outputPath = opts.getOrDefault("output", "tcp_received.bin");
        double appLoss = Double.parseDouble(opts.getOrDefault("loss", "0"));
        boolean quiet = opts.containsKey("quiet");

        if (!quiet) {
            System.out.println("TCP server listening on " + port
                    + " app-loss=" + appLoss + " -> " + outputPath);
        }

        try (ServerSocket server = new ServerSocket(port)) {
            server.setReuseAddress(true);
            Socket sock = server.accept();
            long t0 = System.currentTimeMillis();

            try (BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
                 FileOutputStream out = new FileOutputStream(outputPath)) {

                byte[] sizeBuf = new byte[8];
                int read = 0;
                while (read < 8) {
                    int n = in.read(sizeBuf, read, 8 - read);
                    if (n < 0) throw new RuntimeException("short read on size header");
                    read += n;
                }
                long fileSize = 0;
                for (int i = 0; i < 8; i++) {
                    fileSize = (fileSize << 8) | (sizeBuf[i] & 0xFFL);
                }

                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[64 * 1024];
                long received = 0;
                long droppedBytes = 0;
                java.util.Random rng = new java.util.Random(42);

                while (received < fileSize) {
                    int toRead = (int) Math.min(buf.length, fileSize - received);
                    int n = in.read(buf, 0, toRead);
                    if (n < 0) break;

                    if (appLoss > 0 && rng.nextDouble() < appLoss) {
                        droppedBytes += n;
                    } else {
                        out.write(buf, 0, n);
                        sha.update(buf, 0, n);
                    }
                    received += n;
                }

                long t1 = System.currentTimeMillis();
                byte[] digest = sha.digest();
                long elapsed = Math.max(t1 - t0, 1);
                long bps = (received * 1000L) / elapsed;

                System.out.println("[TCP_SERVER_RESULT]"
                        + " bytes=" + received
                        + " elapsed_ms=" + elapsed
                        + " bytes_per_sec=" + bps
                        + " dropped_bytes=" + droppedBytes
                        + " sha256=" + ChecksumUtils.toHex(digest));
            }
            sock.close();
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--quiet")) { m.put("quiet", "true"); continue; }
            if (a.startsWith("--") && i + 1 < args.length) {
                m.put(a.substring(2), args[++i]);
            }
        }
        return m;
    }
}
