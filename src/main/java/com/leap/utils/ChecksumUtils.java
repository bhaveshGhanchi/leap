package com.leap.utils;

import java.io.FileInputStream;
import java.security.MessageDigest;

public final class ChecksumUtils {
    private ChecksumUtils() {}

    public static byte[] sha256Bytes(String filePath) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                sha.update(buffer, 0, read);
            }
        }
        return sha.digest();
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
