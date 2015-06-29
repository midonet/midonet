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

import com.fasterxml.jackson.annotation.JsonIgnore;

public final class IPv6Subnet extends IPSubnet<IPv6Addr> {

    /* Default constructor for deserialization. */
    public IPv6Subnet() {
    }

    public IPv6Subnet(IPv6Addr addr, int prefixLen) {
        super(addr, prefixLen);
    }

    public IPv6Subnet(String addr, int prefixLen) {
        super(IPv6Addr.fromString(addr), prefixLen);
    }

    public IPv6Subnet(byte[] addr, int prefixLen) {
        this(IPv6Addr.fromBytes(addr), prefixLen);
    }

    @Override
    public void setAddress(IPv6Addr addr) {
        this.address = addr;
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
