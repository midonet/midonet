/*
 * @(#)OfIpNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.util.Net;

public abstract class OfIpNxmEntry implements NxmEntry {

    protected InetAddress address;
    protected int maskLen;

    public OfIpNxmEntry(InetAddress address, int maskLen) {
        if (address == null) {
            throw new IllegalArgumentException("address is null");
        }
        this.address = address;
        if (maskLen < 0  || maskLen > 32)
            throw new IllegalArgumentException("Mask length cannot be less " +
                    "than 0 or greater than 32.");
        this.maskLen = maskLen;
    }

    // Default constructor for class.newInstance used by NxmType.
    protected OfIpNxmEntry() {
    }

    /**
     * @return the address
     */
    public InetAddress getAddress() {
        return address;
    }

    public int getIntAddress() {
        return Net.convertInetAddressToInt(address);
    }

    public int getMaskLen() {
        return this.maskLen;        
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        if (maskLen < 32) {
            int mask = ((1 << maskLen) - 1) << (32 - maskLen);
            return new NxmRawEntry(getNxmType(), address.getAddress(),
                    true, IPv4.toIPv4AddressBytes(mask));
        }
        else
            return new NxmRawEntry(getNxmType(), address.getAddress());
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                    getClass() + " from a raw entry of type " +
                    rawEntry.getType());
        try {
            address = InetAddress.getByAddress(rawEntry.getValue());
        } catch (UnknownHostException e) {
            // TODO: not sure what to do.
            e.printStackTrace();
        }
        maskLen = 32;
        if (rawEntry.hasMask()) {
            maskLen -= Integer.numberOfTrailingZeros(
                    IPv4.toIPv4Address(rawEntry.getMask()));
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OfIpNxmEntry that = (OfIpNxmEntry) o;

        if (maskLen != that.maskLen) return false;
        if (address != null ? !address.equals(that.address) : that.address != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = address != null ? address.hashCode() : 0;
        result = 31 * result + maskLen;
        return result;
    }
}
