/*
 * @(#)OfNxCookieNxmEntry.java        1.6 12/1/25
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfNxCookieNxmEntry implements NxmEntry {

    private long cookie;

    public OfNxCookieNxmEntry(long cookie) {
        this.cookie = cookie;
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfNxCookieNxmEntry() {
    }

    public long getCookie() {
        return cookie;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        byte[] value = ByteBuffer.allocate(8).putLong(cookie).array();
        return new NxmRawEntry(getNxmType(), value);
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                    getClass() + " from a raw entry of type " +
                    rawEntry.getType());
        ByteBuffer buff = ByteBuffer.wrap(rawEntry.getValue());
        cookie = buff.getLong();
        return this;
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_NX_COOKIE;
    }

    @Override
    public String toString() {
        return "NxCookieNxMatch: cookie=" + cookie;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OfNxCookieNxmEntry that = (OfNxCookieNxmEntry) o;

        if (cookie != that.cookie) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return (int) (cookie ^ (cookie >>> 32));
    }
}
