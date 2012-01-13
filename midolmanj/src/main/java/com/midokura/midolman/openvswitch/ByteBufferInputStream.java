/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An InputStream that reads data from a ByteBuffer.
 */
public class ByteBufferInputStream extends InputStream {

    /**
     * The buffer from which bytes are read.
     */
    ByteBuffer buffer;

    /**
     * Creates a new input stream that reads data from the given buffer.
     * @param buffer the buffer from which bytes are read
     */
    public ByteBufferInputStream(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int readlimit) {
        buffer.mark();
    }

    @Override
    public void reset() throws IOException {
        buffer.reset();
    }

    @Override
    public int available() throws IOException {
        return buffer.remaining();
    }

    @Override
    public int read() throws IOException {
        if (buffer.hasRemaining()) {
            return ((int)buffer.get()) & 0xff;
        } else {
            return -1;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("b is null");
        }
        if (off < 0) {
            throw new IndexOutOfBoundsException("off is <0");
        }
        if (len < 0) {
            throw new IndexOutOfBoundsException("len is <0");
        }
        if (len > (b.length - off)) {
            throw new IndexOutOfBoundsException("len is >b.length-off");
        }
        if (len == 0) {
            return 0;
        }
        if (!buffer.hasRemaining()) {
            return -1;
        }
        int bytesRead = Math.min(len, buffer.remaining());
        buffer.get(b, off, bytesRead);
        return bytesRead;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n < 0) {
            return 0;
        }
        int bytesSkipped = Math.min((int)n, buffer.remaining());
        if (bytesSkipped > 0) {
            buffer.position(buffer.position() + bytesSkipped);
        }
        return bytesSkipped;
    }
}
