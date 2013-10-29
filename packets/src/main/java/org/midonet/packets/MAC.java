// Copyright 2011, 2013 Midokura Inc.

// MAC.java - utility class for a Ethernet-type Media Access Control address
//            (a/k/a "Hardware" or "data link" or "link layer" address)

package org.midonet.packets;

import java.util.Arrays;
import java.util.Random;

import org.midonet.util.collection.WeakObjectPool;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;

public class MAC implements Cloneable {
    private static WeakObjectPool<MAC> INSTANCE_POOL = new WeakObjectPool<MAC>();

    private byte[] address;
    static Random rand = new Random();

    /* Default constructor for deserialization. */
    public MAC() {
    }

    public MAC(byte[] rhs) {
        assert rhs.length == 6;
        address = rhs.clone();
    }

    @Override
    public MAC clone() {
        return this.intern();
    }

    public byte[] getAddress() {
        return address.clone();
    }

    @JsonCreator
    public static MAC fromString(String str) {
        return new MAC(Ethernet.toMACAddress(str)).intern();
    }

    public static MAC fromAddress(byte[] rhs) {
        return new MAC(rhs).intern();
    }

    public MAC intern() {
        return INSTANCE_POOL.sharedRef(this);
    }

    public static MAC random() {
        byte[] addr = new byte[6];
        rand.nextBytes(addr);
        addr[0] &= ~0x01;
        return new MAC(addr);
    }

    public boolean unicast() {
        return 0 == (address[0] & 0x1);
    }

    @JsonValue
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
