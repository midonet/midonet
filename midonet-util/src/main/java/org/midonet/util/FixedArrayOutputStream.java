/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util;

import java.io.OutputStream;
import java.util.Arrays;

public class FixedArrayOutputStream extends OutputStream {
    public final byte buf[];

    private int n;

    public FixedArrayOutputStream(byte buf[]) {
        this.buf = buf;
        this.reset();
    }

    private void checkForOverflow(int capacity) {
        if (capacity > buf.length)
            throw new IndexOutOfBoundsException();
    }

    @Override
    public void write(int b) {
        checkForOverflow(n+1);
        buf[n] = (byte) b;
        n += 1;
    }

    @Override
    public void write(byte b[], int off, int len) {
        checkForOverflow(n + len);
        if ((off < 0) || (len < 0) || ((off + len) > b.length))
            throw new IndexOutOfBoundsException();

        System.arraycopy(b, off, buf, n, len);
        n += len;
    }

    public void reset() {
        Arrays.fill(buf, (byte) 0);
        n = 0;
    }

    @Override
    public void close() { }
    @Override
    public void flush() { }

}

