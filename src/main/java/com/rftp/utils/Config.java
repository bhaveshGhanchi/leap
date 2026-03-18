package com.rftp.utils;

public class Config {

    /* ================= NETWORK SETTINGS ================= */

    public static final int PORT = 4040;

    // Probability of simulated packet loss on server
    public static final double LOSS_PROBABILITY = 0.3;

    // Socket timeout in milliseconds
    public static final int TIMEOUT_MS = 200;

    // Sliding window size
    public static final int WINDOW_SIZE = 5;

    //Congestion control settings
    public static final int INITIAL_CWND = 1; // Initial congestion window size (in packets)
    public static final int SSTHRESH = 16; // Slow start threshold (in packets)

    /* ================= PACKET TYPES ================= */

    public static final byte TYPE_DATA = 1;
    public static final byte TYPE_ACK = 2;
    public static final byte TYPE_FIN = 3;

    /* ================= PROTOCOL STRUCTURE ================= */

    public static final int HEADER_SIZE = 10;

    /* ================= PACKET SETTINGS ================= */

    // Maximum size of payload in each packet
    public static final int CHUNK_SIZE = 1024;

    // Max UDP packet buffer size
    public static final int BUFFER_SIZE = 2048;

    // Protocol version
    public static final byte PROTOCOL_VERSION = 1;

    /* ================= RETRANSMISSION SETTINGS ================= */

    // Maximum attempts for retransmission before giving up
    public static final int MAX_RETRIES = 5;

    // Number of duplicate ACKs required to trigger fast retransmit
    public static final int DUP_ACK_THRESHOLD = 3;

    /* ================= FILE TRANSFER SETTINGS ================= */

    // File path for sending
    public static final String INPUT_FILE = "input.txt";

    // File path where server writes received data
    public static final String OUTPUT_FILE = "output.txt";

    /* ================= LOGGING ================= */

    // Enable / disable debug logs
    public static final boolean DEBUG = true;

}