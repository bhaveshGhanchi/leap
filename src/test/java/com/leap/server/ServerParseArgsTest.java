package com.leap.server;

import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServerParseArgsTest {

    @Test
    public void parsesPortAndOutput() {
        Map<String, String> opts = Server.parseArgs(
                new String[] {"receive", "--port", "4040", "--output", "received/"});
        assertEquals("4040", opts.get("port"));
        assertEquals("received/", opts.get("output"));
    }

    @Test
    public void helpFlagIsRecognized() {
        Map<String, String> opts = Server.parseArgs(new String[] {"--help"});
        assertTrue(opts.containsKey("help"));
    }
}
