package com.rftp.client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.io.FileWriter;
import java.io.IOException;

import com.rftp.packet.Packet;
import com.rftp.utils.Config;
import com.rftp.file.FileChunker;

public class Client {
    public static void main(String[] args) {
        try {

            double estimatedRTT = 100; // initial guess (ms)
            double devRTT = 50;
            double alpha = 0.125;
            double beta = 0.25;

            long[] sendTime = new long[100000]; // track send time per packet

            DatagramSocket s = new DatagramSocket();
            s.setSoTimeout(Config.TIMEOUT_MS);
            boolean[] acked = new boolean[100000]; // large enough

            InetAddress add = InetAddress.getByName("localhost");
            int port = Config.PORT;
            int windowSize = Config.WINDOW_SIZE;
            int cwnd = Config.INITIAL_CWND;
            int ssthresh = Config.SSTHRESH;

            FileChunker chunker = new FileChunker(Config.INPUT_FILE);

            int base = 0;
            int nextSeq = 0;
            int retryCount = 0;
            int tot_packet = 0;
            int fastRetransmits = 0;

            int lastAck = -1;
            int duplicateAckCount = 0;

            boolean fileFinished = false;
            long start = System.currentTimeMillis();

            Packet[] window = new Packet[windowSize];
            // Packet[] window = new Packet[100000]; // large enough
            boolean finAckReceived = false;
            int finRetries = 0;

            byte[] receiveBuffer = new byte[Config.BUFFER_SIZE];
            DatagramPacket ackPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            FileWriter logWriter = new FileWriter("rftp_log.csv");
            logWriter.write("time,cwnd,ssthresh,base,nextSeq,event\n");

            while (!fileFinished || base < nextSeq) {

                while (nextSeq < base + Math.min(cwnd, windowSize) && !fileFinished) {
                    byte[] chunk = chunker.nextChunk(Config.CHUNK_SIZE);
                    if (chunk == null) {
                        fileFinished = true;
                        break;
                    }

                    Packet packet = new Packet((byte) 1, Config.TYPE_DATA, nextSeq, chunk);
                    byte[] sendBuffer = packet.toBytes();
                    window[nextSeq % windowSize] = packet; // large enough
                    DatagramPacket dp = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);
                    sendTime[nextSeq] = System.currentTimeMillis();
                    s.send(dp);

                    logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + "," + base
                            + "," + nextSeq + ",SEND\n");

                    nextSeq++;
                    tot_packet++;

                }

                try {
                    ackPacket.setLength(receiveBuffer.length);
                    s.receive(ackPacket);
                    Packet ack = Packet.fromBytes(receiveBuffer, ackPacket.getLength());
                    if (ack.getType() == Config.TYPE_ACK) {
                        int ackSeq = ack.getSequenceNumber();

                        int oldBase = base;

                        if (ackSeq == lastAck) {
                            duplicateAckCount++;
                        } else {
                            duplicateAckCount = 0;
                        }

                        lastAck = ackSeq;

                        if (duplicateAckCount >= Config.DUP_ACK_THRESHOLD) {
                            int missing_seq = ackSeq;
                            if (missing_seq < nextSeq) {

                                Packet pkt = window[missing_seq % windowSize];
                                if (pkt != null) {
                                    byte[] sendBuffer = pkt.toBytes();

                                    DatagramPacket dp = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);

                                    s.send(dp);
                                    fastRetransmits++;
                                    tot_packet++;
                                }
                            }

                            ssthresh = Math.max(cwnd / 2, 1);
                            cwnd = ssthresh;

                            if (oldBase < ackSeq && ackSeq > 0 && ackSeq - 1 < sendTime.length) {

                                long now = System.currentTimeMillis();
                                long sampleRTT = now - sendTime[ackSeq - 1];

                                estimatedRTT = (1 - alpha) * estimatedRTT + alpha * sampleRTT;
                                devRTT = (1 - beta) * devRTT + beta * Math.abs(sampleRTT - estimatedRTT);

                                int timeout = (int) (estimatedRTT + 4 * devRTT);

                                timeout = Math.max(timeout, 100); // lower bound
                                timeout = Math.min(timeout, 2000); // upper bound (IMPORTANT)

                                s.setSoTimeout(timeout);

                            }

                            logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + ","
                                    + base + "," + nextSeq + ",FAST_RETX\n");
                            duplicateAckCount = 0;
                        }

                        for (int i = base; i < ackSeq; i++) {
                            if (i >= 0 && i < acked.length)
                                acked[i] = true;
                        }

                        while (base < nextSeq && acked[base]) {
                            base++;
                        }
                        if (base > oldBase) {
                            if (cwnd < ssthresh) {
                                cwnd = Math.min(cwnd * 2, windowSize);
                            } else {
                                cwnd = cwnd + 1;
                            }
                            cwnd = Math.min(cwnd, 50);
                            logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + ","
                                    + base + "," + nextSeq + ",CWND\n");
                        }
                        logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + "," + base
                                + "," + nextSeq + ",ACK\n");

                    }
                } catch (SocketTimeoutException e) {

                    ssthresh = Math.max(cwnd / 2, 1);
                    cwnd = ssthresh;
                    retryCount++;

                    for (int i = base; i < nextSeq; i++) {
                        if (!acked[i]) {
                            Packet pkt = window[i % windowSize];

                            if (pkt != null) {
                                byte[] sendBuffer = pkt.toBytes();

                                DatagramPacket dp = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);
                                s.send(dp);
                                tot_packet++;
                            }
                        }
                    }
                    logWriter.write((System.currentTimeMillis() - start) + "," + cwnd + "," + ssthresh + "," + base
                            + "," + nextSeq + ",TIMEOUT\n");

                }

            }

            while (!finAckReceived && finRetries < Config.MAX_RETRIES) {
                try {

                    Packet fin = new Packet((byte) 1, Config.TYPE_FIN, nextSeq, new byte[0]);
                    byte[] sendBuffer = fin.toBytes();

                    DatagramPacket finPacket = new DatagramPacket(sendBuffer, sendBuffer.length, add, port);
                    s.send(finPacket);

                    ackPacket.setLength(receiveBuffer.length);
                    s.receive(ackPacket);

                    Packet ack = Packet.fromBytes(receiveBuffer, ackPacket.getLength());

                    if (ack.getType() == Config.TYPE_ACK) {
                        finAckReceived = true;
                    }

                } catch (SocketTimeoutException e) {
                    finRetries++;
                }
            }
            chunker.close();
            logWriter.close();

            long end = System.currentTimeMillis();
            System.out.println("Transfer time: " + (end - start) + " ms");
            System.out.println("Packets sent: " + nextSeq);
            System.out.println("Retry count: " + retryCount);
            System.out.println("Fast retransmits: " + fastRetransmits);
            System.out.println("Total packets: " + tot_packet);

            double seconds = (end - start) / 1000.0;
            System.out.println("Throughput: " + (nextSeq / seconds) + " packets/sec");

            double efficiency = ((double) nextSeq / tot_packet) * 100;
            System.out.println("Efficiency: " + efficiency + "%");

            s.close();
        } catch (

        Exception e) {
            e.printStackTrace();
        }
    }
}
