package com.leap.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.leap.file.FileAssembler;
import com.leap.packet.Packet;
import com.leap.utils.ChecksumUtils;
import com.leap.utils.Config;

public class Server {

    private static DatagramSocket sharedSocket;
    private static final Map<SocketAddress, ClientSession> sessions = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final AtomicInteger sessionCounter = new AtomicInteger(0);

    private static String outputDir;
    private static double lossProbability;

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  leap receive --port <n> [--output <dir>] [--loss <0.0-1.0>]");
        System.out.println();
        System.out.println("Flags:");
        System.out.println("  --port <n>        UDP port to listen on  (default: " + Config.PORT + ")");
        System.out.println("  --output <dir>    directory for received files (default: .)");
        System.out.println("  --loss <0.0-1.0>  simulated drop probability (default: 0.0)");
        System.out.println("  --help            show this message");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  leap receive --port 4040 --output received/");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help")) {
            printUsage();
            return;
        }

        int port       = Integer.parseInt(opts.getOrDefault("port",   String.valueOf(Config.PORT)));
        outputDir      = opts.getOrDefault("output", ".");
        lossProbability = Double.parseDouble(opts.getOrDefault("loss", String.valueOf(Config.LOSS_PROBABILITY)));

        java.io.File outDir = new java.io.File(outputDir);
        if (!outDir.exists()) outDir.mkdirs();

        sharedSocket = new DatagramSocket(port);
        System.out.println("Server started at: " + port + "  (loss=" + (int)(lossProbability * 100) + "%)");
        System.out.println("Output directory: " + outDir.getAbsolutePath());
        System.out.println("Waiting for clients...\n");

        byte[] buf = new byte[65535];

        while (true) {
            DatagramPacket raw = new DatagramPacket(buf, buf.length);
            sharedSocket.receive(raw);

            byte[] data = Arrays.copyOf(raw.getData(), raw.getLength());
            SocketAddress clientAddr = new InetSocketAddress(raw.getAddress(), raw.getPort());

            ClientSession session = sessions.get(clientAddr);
            if (session == null) {
                String outFile = outputDir + "/received_"
                        + sessionCounter.incrementAndGet() + ".dat";
                session = new ClientSession(
                        raw.getAddress(), raw.getPort(), outFile, lossProbability);
                sessions.put(clientAddr, session);
                executor.submit(session);
                System.out.println("[NEW] Client " + raw.getAddress().getHostAddress()
                        + ":" + raw.getPort() + " → " + outFile);
            }
            session.enqueue(data);
        }
    }

    static void removeSession(SocketAddress addr) {
        sessions.remove(addr);
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                result.put(args[i].substring(2), args[i + 1]);
                i++;
            }
        }
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) result.put("help", "true");
        }
        return result;
    }

    static class ClientSession implements Runnable {

        private final InetAddress clientAddr;
        private final int clientPort;
        private final String outputFile;
        private final double lossProbability;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

        // Dedicated send socket: avoids blocking the shared receive socket,
        // since DatagramSocket.send() and .receive() are both synchronized on
        // the same object - sharing them would deadlock ACK delivery.
        private DatagramSocket sendSocket;

        ClientSession(InetAddress addr, int port,
                      String outputFile, double lossProbability) {
            this.clientAddr      = addr;
            this.clientPort      = port;
            this.outputFile      = outputFile;
            this.lossProbability = lossProbability;
        }

        void enqueue(byte[] data) {
            queue.offer(data);
        }

        @Override
        public void run() {
            String tag = clientAddr.getHostAddress() + ":" + clientPort;
            SocketAddress addr = new InetSocketAddress(clientAddr, clientPort);

            try {
                sendSocket = new DatagramSocket();
                FileAssembler assembler = new FileAssembler(outputFile);

                Map<Integer, byte[]> recvBuffer = new HashMap<>();
                Set<Integer> received = new HashSet<>();
                int expectedSeq = 0;
                byte[] expectedHash = null;

                long startTime = System.currentTimeMillis();
                int totalPacketsReceived = 0;
                int droppedPackets = 0;

                while (true) {
                    byte[] data = queue.poll(30, TimeUnit.SECONDS);
                    if (data == null) {
                        System.out.println("[TIMEOUT] " + tag + " - no packets for 30s, closing session");
                        break;
                    }

                    Packet parsed = Packet.fromBytes(data, data.length);
                    if (parsed == null) {
                        System.err.println("[WARN] " + tag + " - corrupt packet, skipping");
                        continue;
                    }

                    totalPacketsReceived++;

                    if (parsed.getType() == Config.TYPE_FIN) {
                        if (parsed.getPayloadlength() == Config.HASH_LENGTH) {
                            expectedHash = parsed.getPayload();
                        } else if (parsed.getPayloadlength() > 0) {
                            System.err.println("[WARN] " + tag + " - unexpected FIN checksum length="
                                    + parsed.getPayloadlength());
                        }
                        System.out.println("[FIN] " + tag + " - sending final ACK=" + expectedSeq);
                        sendAck(expectedSeq);
                        break;
                    }

                    int seq = parsed.getSequenceNumber();

                    if (Math.random() < lossProbability) {
                        System.out.println("  [DROP] " + tag + " seq=" + seq
                                + " (simulated loss, p=" + lossProbability + ")");
                        droppedPackets++;
                        continue;
                    }

                    if (!received.contains(seq)) {
                        recvBuffer.put(seq, parsed.getPayload());
                        received.add(seq);
                    } else {
                        System.out.println("  [DUP]  " + tag + " seq=" + seq + " already buffered, ignoring");
                    }

                    int prevExpected = expectedSeq;
                    while (received.contains(expectedSeq)) {
                        assembler.writeChunk(recvBuffer.get(expectedSeq));
                        recvBuffer.remove(expectedSeq);
                        received.remove(expectedSeq);
                        expectedSeq++;
                    }

                    if (Config.DEBUG) {
                        if (expectedSeq > prevExpected) {
                            System.out.println("  [ACK] " + tag
                                    + " seq=" + seq + " → expectedSeq " + prevExpected + "→" + expectedSeq);
                        } else {
                            System.out.println("  [ACK] " + tag
                                    + " seq=" + seq + " out-of-order, buffered. Sending ACK=" + expectedSeq);
                        }
                    }
                    sendAck(expectedSeq);
                }

                assembler.close();
                if (expectedHash != null) {
                    byte[] actualHash = ChecksumUtils.sha256Bytes(outputFile);
                    if (Arrays.equals(expectedHash, actualHash)) {
                        System.out.println("[OK]  " + tag + " - integrity verified (sha256="
                                + ChecksumUtils.toHex(actualHash) + ")");
                    } else {
                        System.err.println("[FAIL] " + tag + " - checksum mismatch");
                        System.err.println("       expected=" + ChecksumUtils.toHex(expectedHash));
                        System.err.println("       actual  =" + ChecksumUtils.toHex(actualHash));
                    }
                } else {
                    System.err.println("[WARN] " + tag + " - FIN did not include SHA-256 checksum");
                }

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("\n===== Stats for " + tag + " =====");
                System.out.println("Transfer time       : " + elapsed + " ms");
                System.out.println("Chunks delivered    : " + expectedSeq);
                System.out.println("Total packets recv  : " + totalPacketsReceived);
                System.out.println("Packets dropped     : " + droppedPackets);
                System.out.println("Output file         : " + outputFile);
                System.out.println("==============================\n");

            } catch (Exception e) {
                System.err.println("[ERROR] " + tag + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                if (sendSocket != null) sendSocket.close();
                Server.removeSession(addr);
            }
        }

        private void sendAck(int seq) throws Exception {
            Packet ack = new Packet((byte) 1, Config.TYPE_ACK, seq, new byte[0]);
            byte[] bytes = ack.toBytes();
            DatagramPacket dp = new DatagramPacket(bytes, bytes.length, clientAddr, clientPort);
            sendSocket.send(dp);
        }
    }
}
