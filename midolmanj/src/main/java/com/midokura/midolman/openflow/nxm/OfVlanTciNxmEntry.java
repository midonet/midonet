/*
 * @(#)OfVlanTciNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfVlanTciNxmEntry implements NxmEntry {

    private short tci = 0;
    private boolean hasMask = false;
    private short mask;

    // Default constructor for class.newInstance used by NxmType.
    public OfVlanTciNxmEntry() {
    }

    public OfVlanTciNxmEntry(short tci) {
        this.tci = tci;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        byte[] valueBytes = new byte[2];
        ByteBuffer buff = ByteBuffer.wrap(valueBytes);
        buff.putShort(tci);
        byte[] maskBytes = null;
        if (hasMask) {
            maskBytes = new byte[2];
            buff = ByteBuffer.wrap(maskBytes);
            buff.putShort(mask);
        }
        return new NxmRawEntry(getNxmType(), valueBytes, hasMask,
                maskBytes);
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                    getClass() + " from a raw entry of type " +
                    rawEntry.getType());
        ByteBuffer buff = ByteBuffer.wrap(rawEntry.getValue());
        tci = buff.getShort();
        hasMask = rawEntry.hasMask();
        if (hasMask) {
            buff = ByteBuffer.wrap(rawEntry.getMask());
            mask = buff.getShort();
        }
        return this;
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_VLAN_TCI;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OfVlanTciNxmEntry that = (OfVlanTciNxmEntry) o;

        if (hasMask != that.hasMask) return false;
        if (mask != that.mask) return false;
        if (tci != that.tci) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) tci;
        result = 31 * result + (hasMask ? 1 : 0);
        result = 31 * result + (int) mask;
        return result;
    }
}
