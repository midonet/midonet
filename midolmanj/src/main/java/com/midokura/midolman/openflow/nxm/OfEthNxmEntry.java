/*
 * @(#)OfEthNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;
import java.util.Arrays;

public abstract class OfEthNxmEntry implements NxmEntry {

    protected static int MAC_ADDR_BYTE_SIZE = 6;
    public final static byte[] BCAST_MASK = new byte[] {1, 0, 0, 0, 0, 0};
    public final static byte[] UNICAST_MASK =
            new byte[] {(byte)0xfe, -1, -1, -1, -1, -1 };
    protected byte[] address;
    protected byte[] mask;

    public OfEthNxmEntry(byte[] address) {
        checkLength(address);
        this.address = address;
    }

    public OfEthNxmEntry(byte[] address, byte[] mask) {
        checkLength(address);
        this.address = address;
        checkLength(mask);
        this.mask = mask;
    }

    // Default constructor for class.newInstance used by NxmType.
    protected OfEthNxmEntry() {
    }

    private void checkLength(byte[] address) {
        if (address == null) {
            throw new IllegalArgumentException("MAC is null");
        }
        if (address.length != MAC_ADDR_BYTE_SIZE) {
            throw new IllegalArgumentException("Invalid MAC length");
        }
    }

    /**
     * @return the address
     */
    public byte[] getAddress() {
        return address;
    }

    /**
     * @return true if mask is not NULL.
     */
    public boolean hasMask() {
        return mask != null;
    }

    /**
     * @return the mask
     */
    public byte[] getMask() {
        return mask;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        if (null == mask)
            return new NxmRawEntry(getNxmType(), address);
        else
            return new NxmRawEntry(getNxmType(), address, true, mask);
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                    getClass() + " from a raw entry of type " +
                    rawEntry.getType());
        // TODO: do we have to copy the arrays?
        address = rawEntry.getValue();
        if (rawEntry.hasMask())
            mask = rawEntry.getMask();
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OfEthNxmEntry that = (OfEthNxmEntry) o;

        if (!Arrays.equals(address, that.address)) return false;
        if (!Arrays.equals(mask, that.mask)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = address != null ? Arrays.hashCode(address) : 0;
        result = 31 * result + (mask != null ? Arrays.hashCode(mask) : 0);
        return result;
    }
}
