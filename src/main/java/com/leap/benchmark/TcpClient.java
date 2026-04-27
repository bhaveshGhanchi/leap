package com.leap.benchmark;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.File;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import com.leap.utils.ChecksumUtils;

/**
 * TCP counterpart to Client for benchmarking.
 *
 * Protocol: send 8 bytes (big-endian file size), then the raw bytes.
 * Prints one [TCP_CLIENT_RESULT] summary line for the orchestrator.
 */
public class TcpClient {
    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String host = opts.getOrDefault("host", "localhost");
        int port = Integer.parseInt(opts.getOrDefault("port", "5050"));
        String path = opts.get("file");
        boolean quiet = opts.containsKey("quiet");
        if (path == null) {
            System.err.println("TcpClient: --file is required");
            System.exit(2);
        }

        File f = new File(path);
        long fileSize = f.length();
        byte[] hash = ChecksumUtils.sha256Bytes(path);

        if (!quiet) {
            System.out.println("TCP sending " + fileSize + " B from "
                    + path + " to " + host + ":" + port);
        }

        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket(host, port);
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());
             FileInputStream fin = new FileInputStream(f)) {

            s.setTcpNoDelay(true);

            byte[] sizeBuf = new byte[8];
            for (int i = 7; i >= 0; i--) {
                sizeBuf[i] = (byte) (fileSize & 0xFF);
                fileSize >>= 8;
            }
            out.write(sizeBuf);

            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fin.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        long t1 = System.currentTimeMillis();

        long elapsed = Math.max(t1 - t0, 1);
        long bps = (f.length() * 1000L) / elapsed;

        System.out.println("[TCP_CLIENT_RESULT]"
                + " bytes=" + f.length()
                + " elapsed_ms=" + elapsed
                + " bytes_per_sec=" + bps
                + " sha256=" + ChecksumUtils.toHex(hash));
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
