/*
 * @(#)OfNxTunIdNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfNxTunIdNxmEntry implements NxmEntry {

    private long tunnelId;

    public OfNxTunIdNxmEntry(long tunnelId) {
        this.tunnelId = tunnelId;
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfNxTunIdNxmEntry() {
    }

    /**
     * @return the tunnelId
     */
    public long getTunnelId() {
        return tunnelId;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        byte[] value = ByteBuffer.allocate(8).putLong(tunnelId).array();
        return new NxmRawEntry(getNxmType(), value);
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                    getClass() + " from a raw entry of type " +
                    rawEntry.getType());
        ByteBuffer buff = ByteBuffer.wrap(rawEntry.getValue());
        tunnelId = buff.getLong();
        return this;
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_NX_TUN_ID;
    }

    @Override
    public String toString() {
        return "NxTunIdNxMatch: tunneldId=" + tunnelId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OfNxTunIdNxmEntry that = (OfNxTunIdNxmEntry) o;

        if (tunnelId != that.tunnelId) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return (int) (tunnelId ^ (tunnelId >>> 32));
    }
}
