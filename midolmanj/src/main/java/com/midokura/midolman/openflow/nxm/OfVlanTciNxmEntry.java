/*
 * @(#)OfVlanTciNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfVlanTciNxmEntry implements NxmEntry {

    private final int VLAN_MASK = 0xfff;

    private short vid = 0;
    private boolean hasVid = false;
    private byte pcp = 0;
    private boolean hasPcp = false;

    // Default constructor for class.newInstance used by NxmType.
    public OfVlanTciNxmEntry() {
    }

    public boolean hasExactPcp() {
        return hasPcp;
    }

    public byte getPcp() {
        return pcp;
    }

    public void setPcp(byte pcp) {
        this.pcp = (byte)(pcp & 0x7);
        hasPcp = true;
    }

    public boolean hasExactVid() {
        return hasVid;
    }

    public short getVid() {
        return (short)(vid & VLAN_MASK);
    }

    public void setVid(short vid) {
        this.vid = (short)(vid & VLAN_MASK);
        hasVid = true;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        short tci = 0x1000; // set
        short mask = 0;
        boolean hasMask = !(hasPcp & hasVid);
        if (hasPcp) {
            tci |= (pcp << 13) & 0xe000;
            mask |= 0xe000;
        }
        if (hasVid) {
            tci |= vid & VLAN_MASK;
            mask |= VLAN_MASK;
        }
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
        short tci = buff.getShort();
        short mask = 0;
        boolean hasMask = rawEntry.hasMask();
        if (hasMask) {
            buff = ByteBuffer.wrap(rawEntry.getMask());
            mask = buff.getShort();
        }
        vid = 0;
        pcp = 0;
        hasVid = false;
        hasPcp = false;
        if (!hasMask || (mask & 0xe000) == 0xe000) {
            hasPcp = true;
            pcp = (byte)((tci & 0xe000) >> 13);
        }
        if (!hasMask || (mask & VLAN_MASK) == VLAN_MASK) {
            hasVid = true;
            vid = (short)(tci & VLAN_MASK);
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

        if (hasPcp != that.hasPcp) return false;
        if (hasVid != that.hasVid) return false;
        if (pcp != that.pcp) return false;
        if (vid != that.vid) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) vid;
        result = 31 * result + (hasVid ? 1 : 0);
        result = 31 * result + (int) pcp;
        result = 31 * result + (hasPcp ? 1 : 0);
        return result;
    }
}
