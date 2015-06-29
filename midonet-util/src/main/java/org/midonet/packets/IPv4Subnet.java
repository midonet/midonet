/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.packets;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

public final class IPv4Subnet extends IPSubnet<IPv4Addr> {

    /* Default constructor for deserialization. */
    public IPv4Subnet() {
    }

    public IPv4Subnet(IPv4Addr addr, int prefixLen) {
        super(addr, prefixLen);
    }

    public IPv4Subnet(int addr, int prefixLen) {
        super(new IPv4Addr(addr), prefixLen);
    }

    public IPv4Subnet(String addr, int prefixLen) {
        super(IPv4Addr.fromString(addr), prefixLen);
    }

    public IPv4Subnet(byte[] addr, int prefixLen) {
        super(IPv4Addr.apply(addr), prefixLen);
    }

    public IPv4Subnet(String zkCidr) {
        String[] parts = zkCidr.split("_");
        this.address = IPv4Addr.fromString(parts[0]);
        this.prefixLen = Integer.parseInt(parts[1]);
    }

    @Override
    public void setAddress(IPv4Addr addr) {
        this.address = addr;
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
    public static IPv4Subnet fromCidr(String cidr_) {
        if (!isValidIpv4Cidr(cidr_))
            throw new IllegalArgumentException(cidr_ + " is not a valid cidr");

        return fromString(cidr_, "/");
    }

    public static IPv4Subnet fromZkString(String zkCidr) {
        return fromString(zkCidr, "_");
    }

    public static IPv4Subnet fromString(String cidr, String delim) {
        String[] parts = cidr.split(delim);
        int prefixLen = parts.length == 1 ? 32 : Integer.parseInt(parts[1]);
        return new IPv4Subnet(IPv4Addr.fromString(parts[0]), prefixLen);
    }

    public int getIntAddress() {
        return address.addr();
    }

    @Override
    @JsonIgnore
    public short ethertype() {
        return IPv4.ETHERTYPE;
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

    /**
     * Calculate the reversed bits. In case of `mask=23`, the original bit
     * array is like the following:
     *
     *   0 0 0 0 0 0 0 0 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1
     *
     * And `Math.pow(2, 32 - mask) - 1` will be the array below:
     *
     *   0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 1 1 1 1 1 1 1
     *
     * Then inverse it and get the bits of `~(Math.pow(2, 32 - mask) - 1)`
     * which is reversed from the original bits  as follow:
     *
     *   1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 0 0 0 0 0 0 0 0
     *
     * @param prefixLen Subnet prefix length in integer
     * @return An array of four bytes represents the subnet mask
     */
    public static byte[] prefixLenToBytes(int prefixLen) {
        int intMask = ~((int) Math.pow(2, 32 - prefixLen) - 1);
        return new byte[]{
                (byte) ((intMask & 0xff000000) >>> 24),
                (byte) ((intMask & 0x00ff0000) >>> 16),
                (byte) ((intMask & 0x0000ff00) >>> 8),
                (byte) (intMask & 0x000000ff)
        };
    }
}
