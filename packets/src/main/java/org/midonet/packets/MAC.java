// Copyright 2011, 2013 Midokura Inc.

// MAC.java - utility class for a Ethernet-type Media Access Control address
//            (a/k/a "Hardware" or "data link" or "link layer" address)

package org.midonet.packets;

import java.util.Arrays;
import java.util.Random;

import org.midonet.util.collection.WeakObjectPool;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;

/** Class reprensentation of a mac address.
 *
 *  Conversion functions taking or returning long values assume the following
 *  encoding rules:
 *      1) the highest two bytes of the long value are ignored or set to 0;
 *      2) the ordering of bytes in the long from MSB to LSB follows the mac
 *          address representation as a string reading from left to right.
 */
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

    private static IllegalArgumentException illegalMacString(String str) {
        return new IllegalArgumentException(
            "Mac address string must be 6 words of 1 or 2 hex digits " +
                "joined with 5 ':' but was " + str);
    }

    public static long stringToLong(String str)
            throws IllegalArgumentException {
        if (str == null)
            throw illegalMacString(str);
        String[] macBytes = str.split(":");
        if (macBytes.length != 6)
            throw illegalMacString(str);
        long addr = 0;
        try {
            for (String s : macBytes) {
                if (s.length() > 2)
                    throw illegalMacString(str);
                addr = (addr << 8) + Integer.parseInt(s, 16);
            }
        } catch(NumberFormatException ex) {
            throw illegalMacString(str);
        }
        return addr;
    }

    public static String longToString(long addr) {
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            (addr & 0xff0000000000L) >> 40,
            (addr & 0x00ff00000000L) >> 32,
            (addr & 0x0000ff000000L) >> 24,
            (addr & 0x000000ff0000L) >> 16,
            (addr & 0x00000000ff00L) >> 8,
            (addr & 0x0000000000ffL)
        );
    }

    private static IllegalArgumentException illegalMacBytes =
        new IllegalArgumentException(
            "byte array representing a MAC address must have length 6 exactly");

    public static long bytesToLong(byte[] bytesAddr)
            throws IllegalArgumentException {
        if (bytesAddr == null || bytesAddr.length != 6) throw illegalMacBytes;
        long addr = 0;
        for (int i = 0; i < 6; i++) {
            addr = (addr << 8) + (bytesAddr[i] & 0xffL);
        }
        return addr;
    }

    public static byte[] longToBytes(long addr) {
        byte[] bytesAddr = new byte[6];
        for (int i = 5; i >= 0; i--) {
            bytesAddr[i] = (byte)(addr & 0xffL);
            addr = addr >> 8;
        }
        return bytesAddr;
    }
}
