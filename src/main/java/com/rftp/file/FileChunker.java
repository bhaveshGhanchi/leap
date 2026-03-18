package com.rftp.file;

import java.io.FileInputStream;
import java.io.IOException;

public class FileChunker {
    private FileInputStream fis;

    public FileChunker(String filePath) throws IOException {
        fis = new FileInputStream(filePath);
    }


    public byte[] nextChunk(int chunkSize) throws IOException {
        byte[] buffer = new byte[chunkSize];
        int bytesRead = fis.read(buffer);
        if (bytesRead == -1) {
            return null; // End of file
        }
        if (bytesRead < chunkSize) {
            byte[] lastBuffer = new byte[bytesRead];
            System.arraycopy(buffer, 0, lastBuffer, 0, bytesRead);
            return lastBuffer;
        }
        return buffer;
    }


    public void close() throws IOException {
        fis.close();
    }
}
