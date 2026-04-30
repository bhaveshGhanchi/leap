package com.leap.benchmark;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Userspace proxy that drops packets/chunks between a client and a server.
 *
 * UDP mode:
 *   - Listen on (listenPort).
 *   - For each datagram from a client C, with probability p drop it;
 *     otherwise forward to (targetHost:targetPort), remembering C.
 *   - Reply packets from the server are returned to the last C that sent.
 *     (Single-client proxy - enough for benchmarking.)
 *   - Bidirectional drops: reverse-direction datagrams are also dropped at p.
 *
 * TCP mode:
 *   - Accept one connection, dial the target, and pump bytes both ways.
 *   - LOSS IS NOT APPLIED. Dropping bytes at an app-layer proxy leaves the
 *     TCP connection wedged (the kernel already ACK'd them), so app-layer
 *     loss cannot faithfully simulate network packet loss for TCP. This
 *     proxy is therefore a straight pass-through for TCP - the only
 *     scientifically valid way to get TCP-under-loss numbers is Mode C
 *     (OS-level pfctl) or a real lossy network.
 *
 *   TCP loss in the benchmark is surfaced as "loss_mode=proxy, loss>0"
 *   rows where TCP numbers == TCP at 0% loss. This is documented in the
 *   README methodology section rather than faked.
 *
 * Not production code. Single session at a time, no reconnection handling.
 */
public class Proxy {

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String protocol = opts.getOrDefault("protocol", "udp");
        int listen = Integer.parseInt(opts.getOrDefault("listen", "0"));
        String targetHost = opts.getOrDefault("target-host", "localhost");
        int targetPort = Integer.parseInt(opts.getOrDefault("target-port", "0"));
        double loss = Double.parseDouble(opts.getOrDefault("loss", "0"));
        long seed = Long.parseLong(opts.getOrDefault("seed", "1"));
        boolean quiet = opts.containsKey("quiet");

        if (listen == 0 || targetPort == 0) {
            System.err.println("Proxy: --listen and --target-port are required");
            System.exit(2);
        }

        if (!quiet) {
            System.out.println("Proxy " + protocol + " :" + listen + " -> "
                    + targetHost + ":" + targetPort + " loss=" + loss);
        }

        if (protocol.equals("udp")) {
            runUdp(listen, targetHost, targetPort, loss, seed, quiet);
        } else if (protocol.equals("tcp")) {
            runTcp(listen, targetHost, targetPort, loss, seed, quiet);
        } else {
            System.err.println("Unknown --protocol: " + protocol);
            System.exit(2);
        }
    }

    private static void runUdp(int listenPort, String targetHost, int targetPort,
                               double loss, long seed, boolean quiet) throws Exception {
        Random rng = new Random(seed);
        InetAddress target = InetAddress.getByName(targetHost);

        try (DatagramSocket listenSock = new DatagramSocket(listenPort);
             DatagramSocket targetSock = new DatagramSocket()) {

            byte[] buf = new byte[65535];
            Map<Integer, SocketAddress> backRoute = new ConcurrentHashMap<>();

            Thread reverse = new Thread(() -> {
                byte[] rbuf = new byte[65535];
                DatagramPacket rp = new DatagramPacket(rbuf, rbuf.length);
                try {
                    while (true) {
                        targetSock.receive(rp);
                        if (rng.nextDouble() < loss) continue;
                        SocketAddress back = backRoute.get(targetSock.getLocalPort());
                        if (back == null) continue;
                        DatagramPacket out = new DatagramPacket(
                                rp.getData(), rp.getLength(), back);
                        listenSock.send(out);
                    }
                } catch (Exception e) {
                    if (!quiet) System.err.println("proxy reverse ended: " + e.getMessage());
                }
            }, "proxy-udp-reverse");
            reverse.setDaemon(true);
            reverse.start();

            DatagramPacket in = new DatagramPacket(buf, buf.length);
            while (true) {
                listenSock.receive(in);
                SocketAddress clientAddr = in.getSocketAddress();
                backRoute.put(targetSock.getLocalPort(), clientAddr);
                if (rng.nextDouble() < loss) continue;
                DatagramPacket fwd = new DatagramPacket(
                        in.getData(), in.getLength(), target, targetPort);
                targetSock.send(fwd);
            }
        }
    }

    private static void runTcp(int listenPort, String targetHost, int targetPort,
                               double loss, long seed, boolean quiet) throws Exception {
        // Straight pass-through; see class javadoc for why loss is ignored here.
        if (!quiet && loss > 0) {
            System.err.println("[proxy-tcp] loss=" + loss
                    + " requested but IGNORED (app-level TCP loss is not valid).");
        }
        try (ServerSocket srv = new ServerSocket(listenPort)) {
            srv.setReuseAddress(true);
            Socket client = srv.accept();
            Socket upstream = new Socket(targetHost, targetPort);
            client.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);

            Thread a = pump("c->s", client.getInputStream(), upstream.getOutputStream(), quiet);
            Thread b = pump("s->c", upstream.getInputStream(), client.getOutputStream(), quiet);
            a.join();
            b.join();
            try { client.close(); } catch (Exception ignored) {}
            try { upstream.close(); } catch (Exception ignored) {}
        }
    }

    private static Thread pump(String tag, InputStream in, OutputStream out,
                               boolean quiet) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            try {
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception e) {
                if (!quiet) System.err.println("proxy " + tag + " ended: " + e.getMessage());
            }
        }, "proxy-" + tag);
        t.setDaemon(true);
        t.start();
        return t;
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
