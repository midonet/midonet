// Copyright 2012 Midokura Inc.

package org.midonet.packets;


public class IPv6Subnet implements IPSubnet<IPv6Addr> {

    private IPv6Addr addr;
    private int prefixLen;

    /* Default constructor for deserialization. */
    public IPv6Subnet() {
    }

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
    public void setAddress(IPv6Addr address) {
        this.addr = IPv6Addr.fromIPv6(address);
    }

    @Override
    public void setPrefixLen(int prefixLen) {
        this.prefixLen = prefixLen;
    }

    public boolean containsAddress(IPv6Addr other) {
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
        return (addr.upperWord() & upperMask) ==
               (other.upperWord() & upperMask) &&
               (addr.lowerWord() & lowerMask) ==
               (other.lowerWord() & lowerMask);
    }

    @Override
    public String toString() {
        return addr.toString() + "/" + prefixLen;
    }

    @Override
    public String toZkString() {
        return addr.toString() + "_" + prefixLen;
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
