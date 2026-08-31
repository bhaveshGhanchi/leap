package com.leap.file;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.leap.utils.ChecksumUtils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FileChunkerAssemblerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void chunkerThenAssemblerRestoresFile() throws Exception {
        byte[] original = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        Path src = tmp.newFile("src.bin").toPath();
        Path dst = tmp.newFile("dst.bin").toPath();
        Files.write(src, original);

        FileAssembler assembler = new FileAssembler(dst.toString());
        FileChunker chunker = new FileChunker(src.toString());
        byte[] chunk;
        while ((chunk = chunker.nextChunk(8)) != null) {
            assembler.writeChunk(chunk);
        }
        chunker.close();
        assembler.close();

        assertArrayEquals(original, Files.readAllBytes(dst));
        assertArrayEquals(
                ChecksumUtils.sha256Bytes(src.toString()),
                ChecksumUtils.sha256Bytes(dst.toString()));
    }

    @Test
    public void nextChunkReturnsNullAtEof() throws Exception {
        Path src = tmp.newFile("emptyish.bin").toPath();
        Files.write(src, new byte[] {1});
        FileChunker chunker = new FileChunker(src.toString());
        assertEquals(1, chunker.nextChunk(16).length);
        assertNull(chunker.nextChunk(16));
        chunker.close();
    }
}
