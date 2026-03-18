package com.rftp.file;

import java.io.FileOutputStream;
import java.io.IOException;

public class FileAssembler {
    private FileOutputStream fos;

    public FileAssembler(String filePath) throws IOException {
        fos = new FileOutputStream(filePath);
    }

    public void writeChunk(byte[] chunk) throws Exception {
        fos.write(chunk);
    }

    public void close() throws IOException {
        fos.close();
    }
}
