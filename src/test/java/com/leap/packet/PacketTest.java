package com.leap.packet;

import java.util.Arrays;

import org.junit.Test;

import com.leap.utils.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PacketTest {

    @Test
    public void roundTripPreservesFields() {
        byte[] payload = "hello-leap".getBytes();
        Packet original = new Packet(Config.PROTOCOL_VERSION, Config.TYPE_DATA, 7, payload);
        byte[] wire = original.toBytes();

        assertEquals(Config.HEADER_SIZE + payload.length, wire.length);

        Packet parsed = Packet.fromBytes(wire, wire.length);
        assertNotNull(parsed);
        assertEquals(Config.PROTOCOL_VERSION, parsed.getVersion());
        assertEquals(Config.TYPE_DATA, parsed.getType());
        assertEquals(7, parsed.getSequenceNumber());
        assertEquals(payload.length, parsed.getPayloadlength());
        assertArrayEquals(payload, parsed.getPayload());
    }

    @Test
    public void ackWithEmptyPayloadRoundTrips() {
        Packet ack = new Packet(Config.PROTOCOL_VERSION, Config.TYPE_ACK, 3, new byte[0]);
        byte[] wire = ack.toBytes();
        assertEquals(Config.HEADER_SIZE, wire.length);

        Packet parsed = Packet.fromBytes(wire, wire.length);
        assertNotNull(parsed);
        assertEquals(Config.TYPE_ACK, parsed.getType());
        assertEquals(3, parsed.getSequenceNumber());
        assertEquals(0, parsed.getPayloadlength());
    }

    @Test
    public void truncatedHeaderIsRejected() {
        assertNull(Packet.fromBytes(new byte[8], 8));
    }

    @Test
    public void corruptCrcIsRejected() {
        Packet original = new Packet(Config.PROTOCOL_VERSION, Config.TYPE_DATA, 1, new byte[] {1, 2, 3});
        byte[] wire = original.toBytes();
        wire[wire.length - 1] ^= 0x01;
        assertNull(Packet.fromBytes(wire, wire.length));
    }

    @Test
    public void payloadLengthPastBufferIsRejected() {
        Packet original = new Packet(Config.PROTOCOL_VERSION, Config.TYPE_DATA, 0, new byte[] {9});
        byte[] wire = original.toBytes();
        byte[] shortCopy = Arrays.copyOf(wire, Config.HEADER_SIZE);
        assertNull(Packet.fromBytes(shortCopy, shortCopy.length));
    }
}
