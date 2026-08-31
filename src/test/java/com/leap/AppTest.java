package com.leap;

import org.junit.Test;

import com.leap.utils.Config;

import static org.junit.Assert.assertEquals;

public class AppTest {

    @Test
    public void headerSizeMatchesDocumentedLayout() {
        // version(1) + type(1) + seq(4) + payloadLen(4) + crc32(4)
        assertEquals(14, Config.HEADER_SIZE);
    }
}
