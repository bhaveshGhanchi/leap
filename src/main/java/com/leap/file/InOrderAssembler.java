package com.leap.file;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Buffers out-of-order payloads and writes them in sequence number order.
 * Late retransmits of already-delivered seqs are ignored.
 */
public class InOrderAssembler {
    private final FileAssembler dest;
    private final Map<Integer, byte[]> buffer = new HashMap<>();
    private final Set<Integer> received = new HashSet<>();
    private int expectedSeq;

    public InOrderAssembler(FileAssembler dest) {
        this.dest = dest;
    }

    /**
     * @return true if {@link #expectedSeq()} advanced
     */
    public boolean offer(int seq, byte[] payload) throws Exception {
        if (seq < expectedSeq) {
            return false;
        }
        if (!received.contains(seq)) {
            buffer.put(seq, payload);
            received.add(seq);
        }
        boolean advanced = false;
        while (received.contains(expectedSeq)) {
            dest.writeChunk(buffer.remove(expectedSeq));
            received.remove(expectedSeq);
            expectedSeq++;
            advanced = true;
        }
        return advanced;
    }

    public int expectedSeq() {
        return expectedSeq;
    }

    public void close() throws IOException {
        dest.close();
    }
}
