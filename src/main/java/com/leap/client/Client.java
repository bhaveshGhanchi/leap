package com.leap.client;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.leap.packet.Packet;
import com.leap.utils.ChecksumUtils;
import com.leap.utils.Config;
import com.leap.file.FileChunker;

public class Client {
    private static final int PROGRESS_BAR_WIDTH = 12;

    // Printed only when --debug flag is passed or Config.DEBUG is true
    private static boolean debug = Config.DEBUG;

    private static class ProgressState {
        volatile int base;
        volatile int cwnd;
    }

    private static void dbg(String msg) {
        if (debug) System.out.println("[DBG] " + msg);
    }

    private static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond >= 1024L * 1024L) {
            return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
        }
        if (bytesPerSecond >= 1024L) {
            return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        }
        return bytesPerSecond + " B/s";
    }

    private static String buildProgressLine(String fileName, String host, int port, long fileSize,
                                            int totalChunks, int chunkSize, ProgressState progress,
                                            long startTime) {
        int deliveredChunks = Math.min(progress.base, Math.max(totalChunks, 0));
        int percent = totalChunks == 0 ? 100 : (int) ((deliveredChunks * 100L) / totalChunks);
        int filled = (percent * PROGRESS_BAR_WIDTH) / 100;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < PROGRESS_BAR_WIDTH; i++) {
            bar.append(i < filled ? '=' : ' ');
        }

        long deliveredBytes = Math.min((long) deliveredChunks * chunkSize, fileSize);
        long elapsedMs = Math.max(System.currentTimeMillis() - startTime, 1L);
        long bytesPerSecond = (deliveredBytes * 1000L) / elapsedMs;

        return String.format("\rSending %s -> %s:%d  [%s] %3d%%  %d/%d pkts  %s  cwnd=%d",
                fileName, host, port, bar, percent, deliveredChunks, totalChunks,
                formatRate(bytesPerSecond), progress.cwnd);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  leap send <file> --to <host:port> [--window <n>] [--chunk <bytes>] [--debug]");
        System.out.println();
        System.out.println("Flags:");
        System.out.println("  --to <host:port>  destination address (shorthand for --host and --port)");
        System.out.println("  --host <h>        destination host       (default: localhost)");
        System.out.println("  --port <n>        destination UDP port   (default: " + Config.PORT + ")");
        System.out.println("  --file <path>     file to send           (or pass as positional arg)");
        System.out.println("  --window <n>      max in-flight packets  (default: " + Config.WINDOW_SIZE + ")");
        System.out.println("  --chunk <bytes>   payload size per pkt   (default: " + Config.CHUNK_SIZE + ")");
        System.out.println("  --debug           verbose transport trace (disables progress bar)");
        System.out.println("  --help            show this message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  leap send input.txt --to 192.168.1.10:4040");
        System.out.println("  leap send bigfile.iso --to host.example:9000 --window 32 --chunk 1400");
    }

    public static void main(String[] args) {
        Map<String, String> opts = parseArgs(args);
        if (opts.containsKey("help")) {
            printUsage();
            return;
        }
        String host    = opts.getOrDefault("host",   "localhost");
        int port       = Integer.parseInt(opts.getOrDefault("port",   String.valueOf(Config.PORT)));
        String file    = opts.getOrDefault("file",   Config.INPUT_FILE);
        int windowSize = Integer.parseInt(opts.getOrDefault("window", String.valueOf(Config.WINDOW_SIZE)));
        int chunkSize  = Integer.parseInt(opts.getOrDefault("chunk",  String.valueOf(Config.CHUNK_SIZE)));
        if (opts.containsKey("debug")) debug = true;

        System.out.println("Sending '" + file + "' to " + host + ":" + port
                + "  window=" + windowSize + " chunk=" + chunkSize);

        DatagramSocket s = null;
        FileChunker chunker = null;
        FileWriter logWriter = null;
        Thread progressThread = null;
        AtomicBoolean progressRunning = new AtomicBoolean(false);

        try {
            File inputFile = new File(file);
            long fileSize = inputFile.length();
            int totalChunks = fileSize == 0 ? 0 : (int) ((fileSize + chunkSize - 1) / chunkSize);
            String fileName = inputFile.getName();
            byte[] fileHash = ChecksumUtils.sha256Bytes(file);
            dbg("File SHA-256=" + ChecksumUtils.toHex(fileHash));

            double estimatedRTT = 100;
            double devRTT = 50;
            double alpha = 0.125;
            double beta = 0.25;

            Map<Integer, Long> sendTime = new HashMap<>();
            Set<Integer> retransmitted = new HashSet<>();

            s = new DatagramSocket();
            s.setSoTimeout(Config.TIMEOUT_MS);
            Set<Integer> acked = new HashSet<>();

            InetAddress add = InetAddress.getByName(host);
            int cwnd = Config.INITIAL_CWND;
            int ssthresh = Config.SSTHRESH;

            chunker = new FileChunker(file);

            int base = 0;
            int nextSeq = 0;
            int retryCount = 0;
            int tot_packet = 0;
            int fastRetransmits = 0;

            int lastAck = -1;
            int lastFastRetransmitBase = -1;
            int duplicateAckCount = 0;
            boolean inLossRecovery = false;
            int recoveryBase = 0;

            boolean fileFinished = false;
            long start = System.currentTimeMillis();
            ProgressState progress = new ProgressState();
            progress.base = base;
            progress.cwnd = cwnd;

            Map<Integer, Packet> window = new HashMap<>();
            boolean finAckReceived = false;
            int finRetries = 0;

            byte[] receiveBuffer = new byte[Config.BUFFER_SIZE];
            DatagramPacket ackPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            logWriter = new FileWriter("leap_log.csv");
            logWriter.write("time,cwnd,ssthresh,base,nextSeq,event\n");

            dbg("Starting transfer | RTO=" + Config.TIMEOUT_MS + "ms cwnd=" + cwnd + " ssthresh=" + ssthresh);
            if (!debug) {
                progressRunning.set(true);
                progressThread = new Thread(() -> {
                    while (progressRunning.get()) {
                        System.out.print(buildProgressLine(fileName, host, port, fileSize, totalChunks,
                                chunkSize, progress, start));
                        System.out.flush();
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }, "leap-progress");
                progressThread.setDaemon(true);
                progressThread.start();
            }

            while (!fileFinished || base < nextSeq) {

                if (retryCount >= Config.MAX_RETRIES) {
                    System.err.println("[ABORT] Max retries (" + Config.MAX_RETRIES + ") reached at base=" + base + " - giving up.");
                    break;
                }

                // Send window
                while (nextSeq < base + Math.min(cwnd, windowSize) && !fileFinished) {
                    byte[] chunk = chunker.nextChunk(chunkSize);
                    if (chunk == null) {
                        fileFinished = true;
                        dbg("File fully read. Last seq=" + (nextSeq - 1) + " Waiting for ACKs up to seq=" + (nextSeq - 1));
                        break;
                    }

                    Packet packet = new Packet((byte) 1, Config.TYPE_DATA, nextSeq, chunk);
                    byte[] sendBuffer = packet.toBytes();
                    window.put(nextSeq, packet);
                    DatagramPacket dp = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);
                    sendTime.put(nextSeq, System.currentTimeMillis());
                    s.send(dp);

                    dbg("SEND seq=" + nextSeq + " | window=[" + base + "," + (base + Math.min(cwnd, windowSize) - 1) + "]"
                            + " cwnd=" + cwnd + " ssthresh=" + ssthresh);

                    logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + "," + base
                            + "," + nextSeq + ",SEND\n");

                    nextSeq++;
                    tot_packet++;
                }

                try {
                    ackPacket.setLength(receiveBuffer.length);
                    s.receive(ackPacket);
                    Packet ack = Packet.fromBytes(receiveBuffer, ackPacket.getLength());
                    if (ack == null) {
                        dbg("Received corrupt/null packet - skipping");
                        continue;
                    }
                    if (ack.getType() == Config.TYPE_ACK) {
                        int ackSeq = ack.getSequenceNumber();

                        if (ackSeq < base) {
                            dbg("Stale ACK ackSeq=" + ackSeq + " < base=" + base + " - ignored");
                            continue;
                        }

                        if (ackSeq > lastAck) {
                            retryCount = 0;
                        }

                        int oldBase = base;
                        Long ackSendTimeVal = sendTime.get(oldBase);
                        boolean oldBaseWasRetransmitted = retransmitted.contains(oldBase);

                        if (ackSeq == lastAck) {
                            duplicateAckCount++;
                            dbg("DUP ACK ackSeq=" + ackSeq + " count=" + duplicateAckCount
                                    + "/" + Config.DUP_ACK_THRESHOLD);
                        } else {
                            duplicateAckCount = 0;
                        }

                        lastAck = ackSeq;

                        if (duplicateAckCount >= Config.DUP_ACK_THRESHOLD && base != lastFastRetransmitBase) {
                            Packet pkt = window.get(base);
                            if (pkt != null) {
                                byte[] sendBuffer = pkt.toBytes();
                                DatagramPacket dp = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);
                                s.send(dp);
                                fastRetransmits++;
                                tot_packet++;
                                retransmitted.add(base);
                                lastFastRetransmitBase = base;
                                dbg("FAST RETX seq=" + base + " | dupAcks=" + duplicateAckCount
                                        + " cwnd=" + cwnd + "→" + (inLossRecovery ? cwnd : Math.max(cwnd / 2, 1))
                                        + " ssthresh=" + ssthresh);
                            }

                            if (!inLossRecovery) {
                                ssthresh = Math.max(cwnd / 2, 4);
                                cwnd = ssthresh;
                                progress.cwnd = cwnd;
                                inLossRecovery = true;
                                recoveryBase = base;
                                dbg("LOSS RECOVERY entered | ssthresh=" + ssthresh + " cwnd=" + cwnd);
                            }

                            logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + ","
                                    + base + "," + nextSeq + ",FAST_RETX\n");
                            duplicateAckCount = 0;
                        }

                        for (int i = base; i < ackSeq; i++) {
                            acked.add(i);
                        }

                        while (base < nextSeq && acked.contains(base)) {
                            acked.remove(base);
                            window.remove(base);
                            sendTime.remove(base);
                            retransmitted.remove(base);
                            base++;
                        }
                        progress.base = base;
                        if (base > lastFastRetransmitBase) {
                            lastFastRetransmitBase = -1;
                        }

                        if (base > oldBase) {
                            if (inLossRecovery && base > recoveryBase) {
                                inLossRecovery = false;
                                int recoveryTimeout = (int) (estimatedRTT + 4 * devRTT);
                                recoveryTimeout = Math.max(recoveryTimeout, 100);
                                recoveryTimeout = Math.min(recoveryTimeout, 2000);
                                s.setSoTimeout(recoveryTimeout);
                                dbg("LOSS RECOVERY exited at base=" + base + " | RTO reset to "
                                        + recoveryTimeout + "ms");
                            }
                            if (ackSendTimeVal != null && !oldBaseWasRetransmitted) {
                                long sampleRTT = System.currentTimeMillis() - ackSendTimeVal;
                                estimatedRTT = (1 - alpha) * estimatedRTT + alpha * sampleRTT;
                                devRTT = (1 - beta) * devRTT + beta * Math.abs(sampleRTT - estimatedRTT);

                                int timeout = (int) (estimatedRTT + 4 * devRTT);
                                timeout = Math.max(timeout, 100);
                                timeout = Math.min(timeout, 2000);
                                s.setSoTimeout(timeout);
                                dbg("RTT sample=" + sampleRTT + "ms | estimatedRTT="
                                        + String.format("%.1f", estimatedRTT) + "ms devRTT="
                                        + String.format("%.1f", devRTT) + "ms → RTO=" + timeout + "ms");
                            }

                            int oldCwnd = cwnd;
                            if (cwnd < ssthresh) {
                                cwnd = Math.min(cwnd * 2, windowSize);
                            } else {
                                cwnd = Math.min(cwnd + 1, windowSize);
                            }
                            progress.cwnd = cwnd;
                            dbg("ACK base " + oldBase + "→" + base
                                    + " | cwnd=" + oldCwnd + "→" + cwnd + " ssthresh=" + ssthresh
                                    + (cwnd < ssthresh ? " [slow-start]" : " [cong-avoid]"));

                            logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + ","
                                    + base + "," + nextSeq + ",CWND\n");
                        } else {
                            dbg("ACK ackSeq=" + ackSeq + " base unchanged=" + base
                                    + " | dupCount=" + duplicateAckCount);
                        }
                        logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + "," + base
                                + "," + nextSeq + ",ACK\n");
                        logWriter.flush();
                    }
                } catch (SocketTimeoutException e) {
                    int oldCwnd = cwnd;
                    int oldTimeout = s.getSoTimeout();

                    if (!inLossRecovery) {
                        ssthresh = Math.max(cwnd / 2, 4);
                    }
                    cwnd = 1;

                    retryCount++;

                    int newTimeout = Math.min(oldTimeout * 2, 2000);
                    s.setSoTimeout(newTimeout);

                    inLossRecovery = true;
                    recoveryBase = base;
                    duplicateAckCount = 0;
                    lastAck = -1;
                    progress.cwnd = cwnd;

                    if (!debug) {
                        System.out.print("\n");
                    }
                    System.out.println("[TIMEOUT] base=" + base + " nextSeq=" + nextSeq
                            + " | cwnd=" + oldCwnd + "→1 ssthresh=" + ssthresh
                            + " RTO=" + oldTimeout + "→" + newTimeout + "ms"
                            + " retries=" + retryCount + "/" + Config.MAX_RETRIES);

                    Packet pkt = window.get(base);
                    if (pkt != null) {
                        byte[] sendBuffer = pkt.toBytes();
                        DatagramPacket dp = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);
                        s.send(dp);
                        tot_packet++;
                        retransmitted.add(base);
                        dbg("RETX seq=" + base + " (timeout recovery)");
                    } else {
                        dbg("TIMEOUT but no packet in window for base=" + base + " - nothing to retransmit");
                    }

                    logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + "," + base
                            + "," + nextSeq + ",TIMEOUT\n");
                    logWriter.flush();
                }
            }

            dbg("Data loop done. base=" + base + " nextSeq=" + nextSeq + " Sending FIN...");

            while (!finAckReceived && finRetries < Config.MAX_RETRIES) {
                try {
                    Packet fin = new Packet((byte) 1, Config.TYPE_FIN, nextSeq, fileHash);
                    byte[] sendBuffer = fin.toBytes();
                    DatagramPacket finPacket = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);
                    s.send(finPacket);
                    dbg("FIN sent (attempt " + (finRetries + 1) + "/" + Config.MAX_RETRIES + ")");

                    ackPacket.setLength(receiveBuffer.length);
                    s.receive(ackPacket);

                    Packet ack = Packet.fromBytes(receiveBuffer, ackPacket.getLength());
                    if (ack != null && ack.getType() == Config.TYPE_ACK) {
                        finAckReceived = true;
                        dbg("FIN ACKed");
                    }
                } catch (SocketTimeoutException e) {
                    finRetries++;
                    dbg("FIN timeout (attempt " + finRetries + ")");
                }
            }

            if (!finAckReceived) {
                System.err.println("[WARN] FIN not acknowledged after " + Config.MAX_RETRIES + " attempts");
            }

            progress.base = base;
            progress.cwnd = cwnd;
            long end = System.currentTimeMillis();
            System.out.println("\n===== Transfer Complete =====");
            System.out.println("Transfer time  : " + (end - start) + " ms");
            System.out.println("Packets sent   : " + nextSeq);
            System.out.println("Retry count    : " + retryCount);
            System.out.println("Fast retransmits: " + fastRetransmits);
            System.out.println("Total packets  : " + tot_packet);

            long deliveredBytes = Math.min((long) base * chunkSize, fileSize);
            long bytesPerSecond = deliveredBytes * 1000L / Math.max(end - start, 1L);
            System.out.println("Throughput     : " + formatRate(bytesPerSecond));

            double efficiency = ((double) nextSeq / tot_packet) * 100;
            System.out.printf("Efficiency     : %.1f%%%n", efficiency);
            System.out.println("=============================");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            progressRunning.set(false);
            if (progressThread != null) {
                progressThread.interrupt();
                try {
                    progressThread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.print("\r");
            }
            if (chunker != null) try { chunker.close(); } catch (Exception ignored) {}
            if (logWriter != null) try { logWriter.close(); } catch (Exception ignored) {}
            if (s != null) s.close();
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--debug")) { result.put("debug", "true"); continue; }
            if (a.equals("--help") || a.equals("-h")) { result.put("help", "true"); continue; }
            if (a.startsWith("--") && i + 1 < args.length) {
                String key = a.substring(2);
                String val = args[++i];
                if (key.equals("to")) {
                    int colon = val.lastIndexOf(':');
                    if (colon <= 0 || colon == val.length() - 1) {
                        System.err.println("[WARN] --to expects host:port, got '" + val + "'");
                    } else {
                        result.put("host", val.substring(0, colon));
                        result.put("port", val.substring(colon + 1));
                    }
                } else {
                    result.put(key, val);
                }
                continue;
            }
            // First non-flag token is treated as the file path
            if (!a.startsWith("--") && !result.containsKey("file")) {
                result.put("file", a);
            }
        }
        return result;
    }
}
