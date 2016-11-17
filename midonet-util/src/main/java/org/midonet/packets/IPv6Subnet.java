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

import org.apache.commons.lang.StringUtils;

public final class IPv6Subnet extends IPSubnet<IPv6Addr> {

    public IPv6Subnet(IPv6Addr address, int prefixLen) {
        super(address, prefixLen);
    }

    public IPv6Subnet(String address, int prefixLen) {
        super(IPv6Addr.fromString(address), prefixLen);
    }

    public IPv6Subnet(byte[] address, int prefixLen) {
        this(IPv6Addr.fromBytes(address), prefixLen);
    }

    @Override
    public short ethertype() {
        return IPv6.ETHERTYPE;
    }

    @Override
    public IPv6Addr toNetworkAddress() {
        if (prefixLength == 0)
            return IPv6Addr$.MODULE$.AnyAddress();

        int maskSize = 128 - prefixLength;
        long upperMask, lowerMask;
        if (maskSize >= 64) {
            upperMask = ~0L << (maskSize - 64);
            lowerMask = 0;
        } else {
            upperMask = ~0L;
            lowerMask = ~0L << maskSize;
        }

        if (address.upperWord() == (address.upperWord() & upperMask) &&
            address.lowerWord() == (address.lowerWord() & lowerMask))
            return address;
        return new IPv6Addr(address.upperWord() & upperMask,
                            address.lowerWord() & lowerMask);
    }

    @Override
    public IPv6Addr toBroadcastAddress() {
        if (prefixLength == 128)
            return address;

        long upperMask, lowerMask;
        if (prefixLength < 64) {
            upperMask = ~0L >>> prefixLength;
            lowerMask = ~0L;
        } else {
            upperMask = 0;
            lowerMask = ~0L >>> (prefixLength - 64);
        }

        return new IPv6Addr(address.upperWord() | upperMask,
                            address.lowerWord() | lowerMask);
    }

    @Override
    public boolean containsAddress(IPAddr other) {
        if (!(other instanceof IPv6Addr))
            return false;
        IPv6Addr that = (IPv6Addr) other;
        if (prefixLength == 0)
            return true;

        int maskSize = 128 - prefixLength;
        long upperMask, lowerMask;
        if (maskSize >= 64) {
            upperMask = ~0L << (maskSize - 64);
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

    public static IPv6Subnet fromCidr(String cidr) {
        return fromCidr(cidr, '/');
    }

    public static IPv6Subnet fromUriCidr(String cidr) {
        return fromCidr(cidr, '_');
    }

    private static IPv6Subnet fromCidr(String cidr, char delimiter) {
        String[] parts = StringUtils.split(cidr.trim(), delimiter);
        int prefixLength = parts.length == 1 ? 128 : Integer.parseInt(parts[1]);
        return new IPv6Subnet(IPv6Addr.fromString(parts[0]), prefixLength);
    }
}
