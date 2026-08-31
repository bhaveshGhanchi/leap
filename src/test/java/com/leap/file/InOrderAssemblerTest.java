package com.leap.file;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InOrderAssemblerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void outOfOrderChunksWriteInSequence() throws Exception {
        Path dst = tmp.newFile("out.bin").toPath();
        InOrderAssembler assembler = new InOrderAssembler(new FileAssembler(dst.toString()));

        assertFalse(assembler.offer(2, "C".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0, assembler.expectedSeq());
        assertFalse(assembler.offer(1, "B".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0, assembler.expectedSeq());
        assertTrue(assembler.offer(0, "A".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(3, assembler.expectedSeq());
        assembler.close();

        assertArrayEquals("ABC".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(dst));
    }

    @Test
    public void lateRetransmitDoesNotRewrite() throws Exception {
        Path dst = tmp.newFile("dup.bin").toPath();
        InOrderAssembler assembler = new InOrderAssembler(new FileAssembler(dst.toString()));
        assembler.offer(0, "X".getBytes(StandardCharsets.US_ASCII));
        assertFalse(assembler.offer(0, "Y".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(1, assembler.expectedSeq());
        assembler.close();
        assertArrayEquals("X".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(dst));
    }

    @Test
    public void duplicateBufferedSeqDoesNotAdvanceTwice() throws Exception {
        Path dst = tmp.newFile("buf.bin").toPath();
        InOrderAssembler assembler = new InOrderAssembler(new FileAssembler(dst.toString()));
        assembler.offer(1, "B".getBytes(StandardCharsets.US_ASCII));
        assertFalse(assembler.offer(1, "Z".getBytes(StandardCharsets.US_ASCII)));
        assembler.offer(0, "A".getBytes(StandardCharsets.US_ASCII));
        assembler.close();
        assertArrayEquals("AB".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(dst));
    }
}
