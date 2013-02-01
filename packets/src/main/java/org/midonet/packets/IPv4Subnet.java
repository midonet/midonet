// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public class IPv4Subnet implements IPSubnet {
    private IPv4Addr addr;
    private int prefixLen;

    public IPv4Subnet(IPv4Addr addr_, int prefixLen_) {
        addr = addr_;
        prefixLen = prefixLen_;
    }

    @Override
    public IPv4Addr getAddress() {
        return addr;
    }

    @Override
    public int getPrefixLen() {
        return prefixLen;
    }

    @Override
    public boolean containsAddress(IPAddr other) {
        if (!(other instanceof IPv4Addr))
            return false;

        IPv4Addr otherV4 = (IPv4Addr) other;
        if (prefixLen == 0)
            return true;

        int maskSize = 32-prefixLen;
        int mask = ~0 << maskSize;
        return (addr.getIntAddress() & mask) ==
               (otherV4.getIntAddress() & mask);
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
