// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public final class IPv4Subnet implements IPSubnet<IPv4Addr> {

    private IPv4Addr address;
    private int prefixLen;

    /* Default constructor for deserialization. */
    public IPv4Subnet() {
    }

    public IPv4Subnet(IPv4Addr addr_, int prefixLen_) {
        address = addr_;
        prefixLen = prefixLen_;
    }

    public IPv4Subnet(int addr_, int prefixLen_) {
        this(new IPv4Addr(addr_), prefixLen_);
    }

    public IPv4Subnet(String addr_, int prefixLen_) {
        this(IPv4Addr.fromString(addr_), prefixLen_);
    }

    @Override
    public IPv4Addr getAddress() {
        return address;
    }

    public int getIntAddress() {
        return address.addr();
    }

    @Override
    public void setAddress(IPv4Addr address) {
        this.address = IPv4Addr.fromIPv4(address);
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
        int bcast = address.toInt() | mask;
        return IPv4Addr.fromInt(bcast);
    }

    public IPv4Addr toNetworkAddress() {
        if (prefixLen == 0)
            return new IPv4Addr(0);
        int mask = 0xFFFFFFFF << (32 - prefixLen);
        return new IPv4Addr(address.addr() & mask);
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
        return (address.toInt() & mask) == (that.toInt() & mask);
    }

    public String toUnicastString() {
        return getAddress().toString();
    }

    @Override
    public String toString() {
        return address.toString() + "/" + prefixLen;
    }

    @Override
    public String toZkString() {
        return address.toString() + "_" + prefixLen;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IPv4Subnet)) return false;

        IPv4Subnet that = (IPv4Subnet) o;
        if (prefixLen != that.prefixLen) return false;
        if (address != null ? !address.equals(that.address)
            : that.address != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = address != null ? address.hashCode() : 0;
        result = 31 * result + prefixLen;
        return result;
    }
}
