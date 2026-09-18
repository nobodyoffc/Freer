package com.fc.fc_ajdk.fudp.message;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * InputStream over a bounded [offset, offset+length) region of a file.
 *
 * Shared by the file-backed message types ({@link ResponseMessage},
 * {@link NotifyMessage}), whose payloads live inside a reassembly spill file
 * rather than in memory.
 */
final class BoundedRegionInputStream extends InputStream {
    private final RandomAccessFile raf;
    private long remaining;

    BoundedRegionInputStream(File file, long offset, long length) throws IOException {
        this.raf = new RandomAccessFile(file, "r");
        this.raf.seek(offset);
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) return -1;
        int b = raf.read();
        if (b >= 0) remaining--;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) return -1;
        int toRead = (int) Math.min(len, remaining);
        int n = raf.read(b, off, toRead);
        if (n > 0) remaining -= n;
        return n;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
