package com.leap.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ChecksumUtilsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sha256MatchesKnownVector() throws Exception {
        Path file = tmp.newFile("hello.txt").toPath();
        Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));

        byte[] digest = ChecksumUtils.sha256Bytes(file.toString());
        assertEquals(32, digest.length);
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ChecksumUtils.toHex(digest));
    }

    @Test
    public void toHexPadsBytes() {
        assertEquals("0001ff", ChecksumUtils.toHex(new byte[] {0, 1, (byte) 0xff}));
    }

    @Test
    public void sameBytesSameDigest() throws Exception {
        Path a = tmp.newFile("a.bin").toPath();
        Path b = tmp.newFile("b.bin").toPath();
        byte[] payload = new byte[] {4, 5, 6, 7};
        Files.write(a, payload);
        Files.write(b, payload);
        assertArrayEquals(
                ChecksumUtils.sha256Bytes(a.toString()),
                ChecksumUtils.sha256Bytes(b.toString()));
    }
}
