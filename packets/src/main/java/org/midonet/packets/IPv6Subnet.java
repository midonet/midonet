// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public class IPv6Subnet implements IPSubnet {
    private IPv6Addr addr;
    private int prefixLen;

    public IPv6Subnet(IPv6Addr addr_, int prefixLen_) {
        addr = addr_;
        prefixLen = prefixLen_;
    }

    @Override
    public IPv6Addr getAddress() {
        return addr;
    }

    @Override
    public int getPrefixLen() {
        return prefixLen;
    }

    @Override
    public boolean containsAddress(IPAddr other) {
        if (!(other instanceof IPv6Addr))
            return false;

        IPv6Addr otherV6 = (IPv6Addr) other;
        if (prefixLen == 0)
            return true;

        int maskSize = 128-prefixLen;
        long upperMask, lowerMask;
        if (maskSize >= 64) {
            upperMask = ~0L << (maskSize-64);
            lowerMask = 0;
        } else { /* maskSize < 64 */
            upperMask = ~0L;
            lowerMask = ~0L << maskSize;
        }
        return (addr.getUpperWord() & upperMask) ==
               (otherV6.getUpperWord() & upperMask) &&
               (addr.getLowerWord() & lowerMask) ==
               (otherV6.getLowerWord() & lowerMask);
    }

    @Override
    public String toString() {
        return addr.toString() + "/" + prefixLen;
    }

    @Override
    public String toZkString() {
        return addr.toString() + "_" + prefixLen;
    }
}
