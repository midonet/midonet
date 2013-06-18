// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public final class IPv4Subnet implements IPSubnet<IPv4Addr> {

    private IPv4Addr addr;
    private int prefixLen;

    /* Default constructor for deserialization. */
    public IPv4Subnet() {
    }

    public IPv4Subnet(IPv4Addr addr_, int prefixLen_) {
        addr = addr_;
        prefixLen = prefixLen_;
    }

    @Override
    public IPv4Addr getAddress() {
        return addr;
    }

    @Override
    public void setAddress(IPv4Addr address) {
        this.addr = IPv4Addr.fromIPv4(address);
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
        int bcast = addr.toInt() | mask;
        return IPv4Addr.fromInt(bcast);
    }

    @Override
    public boolean containsAddress(IPAddr other) {
        if (! (other instanceof IPv4Addr))
            return false;

        IPv4Addr that =  (IPv4Addr) other;
        if (prefixLen == 0)
            return true;

        int maskSize = 32-prefixLen;
        int mask = ~0 << maskSize;
        return (addr.toInt() & mask) == (that.toInt() & mask);
    }

    @Override
    public String toString() {
        return addr.toString() + "/" + prefixLen;
    }

    @Override
    public String toZkString() {
        return addr.toString() + "_" + prefixLen;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IPv4Subnet)) return false;

        IPv4Subnet that = (IPv4Subnet) o;
        if (prefixLen != that.prefixLen) return false;
        if (addr != null ? !addr.equals(that.addr) : that.addr != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = addr != null ? addr.hashCode() : 0;
        result = 31 * result + prefixLen;
        return result;
    }
}
