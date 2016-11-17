/*
 * Copyright 2015 Midokura SARL
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

import org.apache.commons.lang.StringUtils;

public final class IPv4Subnet extends IPSubnet<IPv4Addr> {

    public IPv4Subnet(IPv4Addr address, int prefixLen) {
        super(address, prefixLen);
    }

    public IPv4Subnet(int address, int prefixLen) {
        super(new IPv4Addr(address), prefixLen);
    }

    public IPv4Subnet(String address, int prefixLen) {
        super(IPv4Addr.fromString(address), prefixLen);
    }

    public IPv4Subnet(byte[] address, int prefixLen) {
        super(IPv4Addr.apply(address), prefixLen);
    }

    /**
     * Construct an IPv4Subnet object from a CIDR notation string - e.g.
     * "192.168.0.1/16". The mask length is optional; if omitted, a value of
     * 32 is assumed.
     *
     * IllegalArgumentException is thrown if the CIDR notation string is
     * invalid.
     *
     * @param cidr CIDR notation string
     */
    public static IPv4Subnet fromCidr(String cidr) {
        if (!isValidIpv4Cidr(cidr))
            throw new IllegalArgumentException(cidr + " is not a valid CIDR");
        return fromCidr(cidr, '/');
    }

    public static IPv4Subnet fromUriCidr(String cidr) {
        return fromCidr(cidr, '_');
    }

    private static IPv4Subnet fromCidr(String cidr, char delimiter) {
        String[] parts = StringUtils.split(cidr.trim(), delimiter);
        int prefixLength = parts.length == 1 ? 32 : Integer.parseInt(parts[1]);
        return new IPv4Subnet(IPv4Addr.fromString(parts[0]), prefixLength);
    }

    public int getIntAddress() {
        return address.addr();
    }

    @Override
    public short ethertype() {
        return IPv4.ETHERTYPE;
    }

    @Override
    public IPv4Addr toNetworkAddress() {
        if (prefixLength == 0)
            return IPv4Addr$.MODULE$.AnyAddress();
        int mask = 0xFFFFFFFF << (32 - prefixLength);
        if (address.addr() == (address.addr() & mask))
            return address;
        return new IPv4Addr(address.addr() & mask);
    }

    @Override
    public IPv4Addr toBroadcastAddress() {
        if (prefixLength == 32)
            return address;
        return IPv4Addr.fromInt(address.toInt() | (0xFFFFFFFF >>> prefixLength));
    }

    @Override
    public boolean containsAddress(IPAddr other) {
        if (! (other instanceof IPv4Addr))
            return false;

        IPv4Addr that = (IPv4Addr) other;
        return addrMatch(address.toInt(), that.toInt(), prefixLength);
    }

    /**
     * Regex pattern representing IPv4 CIDR
     */
    private static final String IPV4_CIDR_PATTERN =
        "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
        "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])" +
        "(\\/(\\d|[1-2]\\d|3[0-2]))?$";

    private static Pattern ipv4CidrPattern = Pattern.compile(IPV4_CIDR_PATTERN);

    /**
     * Checks whether CIDR is in the correct format. The expected format is
     * n.n.n.n(/m) where n.n.n.n is a valid quad-dotted IPv4 address and the
     * optional m is a prefix length value in [0, 32]. Returns false if cidr is
     * null.
     *
     * @param cidr CIDR to validate
     * @return True if CIDR is valid
     */
    static boolean isValidIpv4Cidr(String cidr) {
        return cidr != null && ipv4CidrPattern.matcher(cidr.trim()).matches();
    }

    public static boolean addrMatch(int ip1, int ip2, int prefixLen) {
        if (prefixLen == 0)
            return true;
        int maskSize = 32 - prefixLen;
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
