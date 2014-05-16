// Copyright 2012 Midokura Inc.

package org.midonet.packets;


import org.codehaus.jackson.annotate.JsonIgnore;

public final class IPv6Subnet implements IPSubnet<IPv6Addr> {

    private IPv6Addr address;
    private int prefixLen;

    /* Default constructor for deserialization. */
    public IPv6Subnet() {
    }

    public IPv6Subnet(IPv6Addr addr_, int prefixLen_) {
        address = addr_;
        prefixLen = prefixLen_;
    }

    @Override
    public IPv6Addr getAddress() {
        return address;
    }

    @Override
    public int getPrefixLen() {
        return prefixLen;
    }

    @Override
    public void setAddress(IPv6Addr address) {
        this.address = IPv6Addr.fromIPv6(address);
    }

    @Override
    public void setPrefixLen(int prefixLen) {
        this.prefixLen = prefixLen;
    }

    @Override
    @JsonIgnore
    public short ethertype() {
        return IPv6.ETHERTYPE;
    }

    @Override
    public boolean containsAddress(IPAddr other) {
        if (!(other instanceof IPv6Addr))
            return false;
        IPv6Addr that = (IPv6Addr) other;
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
        return (address.upperWord() & upperMask) ==
               (that.upperWord() & upperMask) &&
               (address.lowerWord() & lowerMask) ==
               (that.lowerWord() & lowerMask);
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
        if (!(o instanceof IPv6Subnet)) return false;

        IPv6Subnet that = (IPv6Subnet) o;

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

    public static IPv6Subnet fromString(String subnetStr) {
        String[] parts = subnetStr.split("/", 2);
        if (parts.length == 1) {
            parts = subnetStr.split("_", 2);
        }
        // Because of String.split's contract, parts.length can only be 1 or 2
        return new IPv6Subnet(IPv6Addr.fromString(parts[0]),
                parts.length == 1 ? 128 : Integer.parseInt(parts[1]));
    }
}
