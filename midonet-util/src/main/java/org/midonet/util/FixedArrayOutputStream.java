/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

