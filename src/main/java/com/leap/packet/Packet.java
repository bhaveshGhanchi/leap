package com.leap.packet;


import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

import com.leap.utils.Config;

public class Packet {


    private byte version;
    private byte type;
    private int sequenceNumber;
    private int payloadlength;
    private byte[] payload;

    public Packet(byte version, byte type, int sequenceNumber, byte[] payload) {
        this.version = version;
        this.type = type;
        this.sequenceNumber = sequenceNumber;
        this.payloadlength = payload.length;
        this.payload = payload;
    }

    public byte[] toBytes(){
        CRC32 crc = new CRC32();
        crc.update(payload);
        int checksum = (int) crc.getValue();

        ByteBuffer buffer = ByteBuffer.allocate(Config.HEADER_SIZE + payload.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(version);
        buffer.put(type);
        buffer.putInt(sequenceNumber);
        buffer.putInt(payloadlength);
        buffer.putInt(checksum);
        buffer.put(payload);
        return buffer.array();
    }

    public static Packet fromBytes(byte[] data, int length) {
        if (length < Config.HEADER_SIZE) return null;

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        byte version = buffer.get();
        byte type = buffer.get();
        int sequenceNumber = buffer.getInt();
        int payloadlength = buffer.getInt();
        int storedChecksum = buffer.getInt();

        if (payloadlength < 0 || payloadlength > length - Config.HEADER_SIZE) return null;

        byte[] payload = new byte[payloadlength];
        buffer.get(payload);

        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != storedChecksum) {
            System.err.println("[WARN] CRC mismatch for seq=" + sequenceNumber + " — dropping corrupt packet");
            return null;
        }

        return new Packet(version, type, sequenceNumber, payload);
    }



    public int getPayloadlength() {
        return payloadlength;
    }
    public byte getVersion() {
        return version;
    }
    public byte getType() {
        return type;
    }
    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public byte[] getPayload() {
        return payload;
    }
}
