/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * An OutputStream that puts data into a ByteBuffer.
 */
public class ByteBufferOutputStream extends OutputStream {

    /**
     * The buffer into which bytes are written.
     */
    ByteBuffer buffer;

    /**
     * Creates a new output stream that outputs data into the given buffer.
     * @param buffer the buffer into which bytes are written
     */
    public ByteBufferOutputStream(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void write(byte[] b) throws IOException {
        buffer.put(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        buffer.put(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
        buffer.put((byte)b);
    }
}
