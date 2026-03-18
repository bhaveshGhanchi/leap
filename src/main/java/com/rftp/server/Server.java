package com.rftp.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

import com.rftp.file.FileAssembler;
import com.rftp.packet.Packet;
import com.rftp.utils.Config;

public class Server {
    public static void main(String[] args) {

        try {
            int port = Config.PORT;
            DatagramSocket s = new DatagramSocket(port);
            System.out.println("Server started at: " + port);
            FileAssembler assembeler = new FileAssembler(Config.OUTPUT_FILE);

            byte[] receiveBuffer = new byte[65535];
            byte[][] recvBuffer = new byte[10000][];
            boolean[] received = new boolean[10000];
            int expectedSeq = 0;

            while (true) {
                DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                s.receive(packet);
                Packet parsed = Packet.fromBytes(packet.getData(), packet.getLength());
                if (parsed.getType() == Config.TYPE_FIN) {
                    System.out.println("FIN received");

                    // send ACK for FIN
                    Packet ack = new Packet(
                            (byte) 1,
                            Config.TYPE_ACK,
                            expectedSeq,
                            new byte[0]);

                    byte[] ackBytes = ack.toBytes();

                    DatagramPacket ackPacket = new DatagramPacket(ackBytes, ackBytes.length,
                            packet.getAddress(), packet.getPort());

                    s.send(ackPacket);

                    break;
                }

                int receivedSequenceNumber = parsed.getSequenceNumber();
                if (receivedSequenceNumber >= recvBuffer.length) {
                    System.out.println("Sequence out of range: " + receivedSequenceNumber);
                    continue;
                }

                double lossProbability = Config.LOSS_PROBABILITY;

                if (Math.random() < lossProbability) {
                    System.out.println("Dropping packet " + receivedSequenceNumber);
                    continue;
                }

                if (!received[receivedSequenceNumber]) {
                    recvBuffer[receivedSequenceNumber] = parsed.getPayload();
                    received[receivedSequenceNumber] = true;
                }
                while (expectedSeq < received.length && received[expectedSeq]) {
                    assembeler.writeChunk(recvBuffer[expectedSeq]);

                    recvBuffer[expectedSeq] = null;
                    received[expectedSeq] = false;

                    expectedSeq++;
                }
                Packet ack = new Packet(
                        (byte) 1,
                        Config.TYPE_ACK,
                        expectedSeq,
                        new byte[0]);
                byte[] ackBytes = ack.toBytes();

                DatagramPacket ackPacket = new DatagramPacket(
                        ackBytes,
                        ackBytes.length,
                        packet.getAddress(),
                        packet.getPort());
                System.out.println("Sending ACK for seq: " + (expectedSeq));

                s.send(ackPacket);

                // System.out.println("Recieved: "+ new String(parsed.getPayload()));
                // System.out.println("From: "+ packet.getAddress()+" "+packet.getPort() +"
                // "+packet.getLength()+" "+parsed.getSequenceNumber());
                System.out.println("---------------------");
            }
            assembeler.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}