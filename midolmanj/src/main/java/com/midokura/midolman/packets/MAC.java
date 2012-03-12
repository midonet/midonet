// Copyright 2011 Midokura Inc.

// MAC.java - utility class for a Ethernet-type Media Access Control address
//            (a/k/a "Hardware" or "data link" or "link layer" address)

package com.midokura.midolman.packets;

import java.util.Arrays;

import com.midokura.midolman.util.Net;

public class MAC implements Cloneable {

    // TODO(pino): make this private. Can't be final because of deserialization.
    byte[] address;

    /* Default constructor for deserialization. */
    public MAC() {
    }

    public MAC(byte[] rhs) {
        assert rhs.length == 6;
        address = rhs.clone();
    }

    private MAC(MAC rhs) {
        address = rhs.address.clone();
    }

    public MAC clone() {
        return new MAC(this);
    }

    public byte[] getAddress() {
        return address;
    }

    public static MAC fromString(String str) {
        return new MAC(Ethernet.toMACAddress(str));
    }

    @Override
    public String toString() {
        return Net.convertByteMacToString(address);
    }

    @Override
    public boolean equals(Object rhs) {
        if (this == rhs)
            return true;
        if (!(rhs instanceof MAC))
            return false;
        return Arrays.equals(address, ((MAC)rhs).address);
    }

    @Override
    public int hashCode() {
        return (((address[0] ^ address[1])&0xff) << 24) |
               ((address[2]&0xff) << 16) |
               ((address[3]&0xff) << 8) |
               ((address[4] ^ address[5])&0xff);
    }

}
