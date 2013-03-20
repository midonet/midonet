// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public class IPv4Subnet implements IPSubnet {
    private IPv4Addr addr;
    private int prefixLen;

    /* Default constructor for deserialization. */
    public IPv4Subnet() {
    }

    public IPv4Subnet(IPv4Addr addr_, int prefixLen_) {
        addr = addr_;
        prefixLen = prefixLen_;
    }

    public IPv4Subnet(IntIPv4 ii) {
        // This is supposed to work as just "IPAddr.fromIntIPv4" (see
        // http://lampwww.epfl.ch/~michelou/scala/using-scala-from-java.html )
        // but doesn't.  This will become moot when IPAddr.scala is
        // translated into Java or when IntIPv4 goes away.
        addr = IPAddr$.MODULE$.fromIntIPv4(ii);
        prefixLen = ii.prefixLen();
    }

    @Override
    public IntIPv4 toIntIPv4() {
        return new IntIPv4(addr.getIntAddress(), prefixLen);
    }

    @Override
    public IPv4Addr getAddress() {
        return addr;
    }

    @Override
    public void setAddress(IPAddr address) {
        if (address instanceof IPv4Addr)
            this.addr = ((IPv4Addr)address).clone();
        else
            throw new IllegalArgumentException("IPv4Subnet requires IPv4Addr");
    }

    @Override
    public void setPrefixLen(int prefixLen) {
        this.prefixLen = prefixLen;
    }

    @Override
    public int getPrefixLen() {
        return prefixLen;
    }

    public IPv4Addr toBroadcastAddress() {
        int mask = 0xFFFFFFFF >>> prefixLen;
        int bcast = addr.getIntAddress() | mask;
        return new IPv4Addr().setIntAddress(bcast);
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
