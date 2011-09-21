// Copyright 2011 Midokura Inc.

// MAC.java - utility class for a Ethernet-type Media Access Control address
//            (a/k/a "Hardware" or "data link" or "link layer" address)

package com.midokura.midolman.util;

import java.util.Arrays;


class MAC implements Cloneable {
    public byte[] address;

    public MAC(MAC rhs) { address = rhs.address.clone(); }
    public MAC(byte[] rhs) { 
        assert rhs.length == 6;
        address = rhs.clone();
    }

    public MAC clone() { return new MAC(this); }

    public boolean equals(Object rhs) {
        if (this == rhs)
            return true;
        if (!(rhs instanceof MAC))
            return false;
        return Arrays.equals(address, ((MAC)rhs).address);
    }

    public int hashCode() {
        return (((address[0] ^ address[1])&0xff) << 24) |
               ((address[2]&0xff) << 16) |
               ((address[3]&0xff) << 8) |
               ((address[4] ^ address[5])&0xff);
    }
}
