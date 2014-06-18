/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.packets;

import org.codehaus.jackson.annotate.JsonIgnore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Construct an IPv4Subnet object from a CIDR notation string - e.g.
     * "192.168.0.1/16".
     *
     * IllegalArgumentException is thrown if the CIDR notation string is
     * invalid.
     *
     * @param cidr_ CIDR notation string
     */
    public IPv4Subnet(String cidr_) {
        if (!isValidIpv4Cidr(cidr_))
            throw new IllegalArgumentException("cidr is not valid");

        String[] parts = cidr_.split("/");
        assert parts.length == 2;     // Sanity check

        address = IPv4Addr.fromString(parts[0]);
        prefixLen = Integer.parseInt(parts[1]);
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
    @JsonIgnore
    public short ethertype() {
        return IPv4.ETHERTYPE;
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
        return addrMatch(address.toInt(), that.toInt(), prefixLen);
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

    /**
     * Regex pattern representing IPv4 CIDR
     */
    public static String IPV4_CIDR_PATTERN =
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
                    "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])" +
                    "(\\/(\\d|[1-2]\\d|3[0-2]))$";

    private static Pattern ipv4CidrPattern = Pattern.compile(IPV4_CIDR_PATTERN);

    /**
     * Checks whether CIDR is in the correct format.  The expected format
     * is n.n.n.n/m where n.n.n.n is a valid quad-dotted IPv4 address and m is
     * a prefix length value in [0, 32].  False is returned if cidr is null.
     *
     * @param cidr CIDR to validate
     * @return True if CIDR is valid
     */
    public static boolean isValidIpv4Cidr(String cidr) {
        return cidr != null && ipv4CidrPattern.matcher(cidr).matches();
    }

    public static boolean addrMatch(int ip1, int ip2, int prefixLen) {
        if (prefixLen == 0)
            return true;
        int maskSize = 32-prefixLen;
        int mask = ~0 << maskSize;
        return (ip1 & mask) == (ip2 & mask);
    }
}
